package com.github.igniteprchecker.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.igniteprchecker.web.GitHttpController;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.FileSystemUtils;

@SpringBootTest(classes = GitHttpTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "spring.main.banner-mode=off"})
@DirtiesContext
class GitHttpTest {
    private static final Path ROOT = temporaryDirectory();
    private static final Path SOURCE = ROOT.resolve("source");
    private static String sha;
    @LocalServerPort
    private int port;
    @Autowired
    private GitMirror mirror;

    @BeforeAll
    static void source() throws Exception {
        git(ROOT, "init", "-b", "master", SOURCE.toString());
        Files.writeString(SOURCE.resolve("file.txt"), "public source\n");
        git(SOURCE, "add", "file.txt");
        git(SOURCE, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c",
            "commit.gpgsign=false", "commit", "-qm", "Source");
        sha = git(SOURCE, "rev-parse", "HEAD").output().strip();
        git(SOURCE, "update-ref", "refs/pull/13786/head", sha);
    }

    @BeforeEach
    void sync() {
        mirror.sync();
        assertThat(mirror.status().get(0).ready()).isTrue();
        mirror.ensurePr("ignite", 13786, sha);
    }

    /** A Git client can clone a mirrored PR and fetch its exact SHA through smart HTTP. */
    @Test
    void cloneAndFetchPrWithNativeGit() throws Exception {
        Path clone = ROOT.resolve("clone");
        assertThat(git(ROOT, "clone", "--quiet", url("/git/ignite.git"), clone.toString()).exit()).isZero();
        assertThat(git(clone, "fetch", "--quiet", "origin", "refs/pull/13786/head").exit()).isZero();
        assertThat(git(clone, "rev-parse", "FETCH_HEAD").output().strip()).isEqualTo(sha);
        assertThat(Files.readString(clone.resolve("file.txt"))).isEqualTo("public source\n");
    }

    /** Protocol v0 remains available for clients that do not request Git's v2 protocol. */
    @Test
    void advertisesPrToProtocolV0() throws Exception {
        Result result = git(ROOT, "-c", "protocol.version=0", "ls-remote", url("/git/ignite.git"),
            "refs/pull/13786/head");
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains(sha + "\trefs/pull/13786/head");
    }

    /**
     * A queued commit stays fetchable after a force push and repository cleanup. GC removes unreferenced objects,
     * so the original PR commit needs its keep-ref.
     */
    @Test
    void fetchesRetainedPinAfterPrMoves() throws Exception {
        String tree = git(SOURCE, "rev-parse", "HEAD^{tree}").output().strip();
        String original = git(SOURCE, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit-tree", tree, "-p", sha, "-m", "Original PR head")
            .output().strip();
        git(SOURCE, "update-ref", "refs/pull/13786/head", original);
        mirror.sync();
        mirror.ensurePr("ignite", 13786, original);
        String next = git(SOURCE, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit-tree", tree, "-p", sha, "-m", "New PR head").output().strip();
        git(SOURCE, "update-ref", "refs/pull/13786/head", next);
        try {
            mirror.ensurePr("ignite", 13786, next);
            assertThat(git(mirror.repository("ignite"), "gc", "--prune=now").exit()).isZero();
            assertThat(git(ROOT, "ls-remote", url("/git/ignite.git"), "refs/pull/13786/head").output())
                .contains(next);
            Path client = ROOT.resolve("pinned-client");
            git(ROOT, "init", client.toString());

            assertThat(git(client, "fetch", "--quiet", url("/git/ignite.git"), original).exit()).isZero();
            assertThat(git(client, "rev-parse", "FETCH_HEAD").output().strip()).isEqualTo(original);
        }
        finally {
            git(SOURCE, "update-ref", "refs/pull/13786/head", sha);
            mirror.ensurePr("ignite", 13786, sha);
        }
    }

    /** A public client cannot write to the mirror, including through receive-pack discovery. */
    @Test
    void refusesPush() throws Exception {
        Result push = git(SOURCE, "push", url("/git/ignite.git"), "HEAD:refs/heads/unwanted");
        assertThat(push.exit()).isNotZero();
        assertThat(push.output()).contains("403");
        assertThat(git(mirror.repository("ignite"), "show-ref", "--verify", "refs/heads/unwanted").exit())
            .isNotZero();
    }

    /** HTTP requests cannot read repository files or address an unknown repository. */
    @Test
    void servesOnlyKnownSmartHttpPaths() throws Exception {
        for (String path : List.of("/git/ignite.git/config", "/git/ignite.git/HEAD",
            "/git/unknown.git/info/refs?service=git-upload-pack", "/git/ignite.git/objects/info/packs",
            "/git/%2e%2e/ignite.git/config"))
            assertThat(get(path).statusCode()).as(path).isBetween(400, 499);
        assertThat(get("/git/ignite.git/info/refs?service=git-receive-pack").statusCode()).isEqualTo(403);
    }

    /** A declared oversized body is refused before the client sends it. */
    @Test
    void refusesOversizedBody() throws Exception {
        try (Socket request = pendingUpload(8 * 1024 * 1024 + 1)) {
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(request.getInputStream(),
                java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            assertThat(status).contains("413");
        }
    }

    /** The upload limit also applies to chunked bodies whose length is unknown until the client sends them. */
    @Test
    void refusesOversizedChunkedBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/git/ignite.git/git-upload-pack")))
            .header("Content-Type", "application/x-git-upload-pack-request")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                new java.io.ByteArrayInputStream(new byte[8 * 1024 * 1024 + 1]))).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
            .isEqualTo(413);
    }

    /** Compressed v2 negotiation requests are decoded before being passed to Git. */
    @Test
    void acceptsGzipNegotiation() throws Exception {
        String command = packet("command=ls-refs\n") + "0001" + packet("symrefs\n") + "0000";
        HttpResponse<String> response = gzipRequest(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(sha + " refs/pull/13786/head");
    }

    /** The request limit applies to decompressed bytes, so a small gzip body cannot exhaust the heap. */
    @Test
    void refusesOversizedDecompressedBody() throws Exception {
        assertThat(gzipRequest(new byte[8 * 1024 * 1024 + 1]).statusCode()).isEqualTo(413);
    }

    private HttpResponse<String> gzipRequest(byte[] body) throws Exception {
        var compressed = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write(body);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/git/ignite.git/git-upload-pack")))
            .header("Content-Type", "application/x-git-upload-pack-request")
            .header("Content-Encoding", "gzip").header("Git-Protocol", "version=2")
            .POST(HttpRequest.BodyPublishers.ofByteArray(compressed.toByteArray())).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String packet(String text) {
        return "%04x".formatted(4 + text.length()) + text;
    }

    /**
     * Only two uploads can read request bodies at once. Two incomplete POST requests make a third return 503
     * before it sends its body.
     */
    @Test
    void refusesConcurrentUploadBeforeReadingBody() throws Exception {
        try (Socket first = pendingUpload(); Socket second = pendingUpload()) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(get("/git/ignite.git/info/refs?service=git-upload-pack").statusCode()).isEqualTo(503));
            try (Socket third = pendingUpload()) {
                String status = new java.io.BufferedReader(new java.io.InputStreamReader(third.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                assertThat(status).contains("503");
            }
        }
    }

    private Socket pendingUpload() throws Exception {
        return pendingUpload(1);
    }

    private Socket pendingUpload(int length) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        socket.getOutputStream().write(("POST /git/ignite.git/git-upload-pack HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\nContent-Type: application/x-git-upload-pack-request\r\n"
            + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    /** Status reports readiness without disclosing local paths or native command errors. */
    @Test
    void reportsPublicReadiness() throws Exception {
        HttpResponse<String> response = get("/api/git-mirror/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"enabled\":true", "\"ready\":true", "\"slug\":\"ignite\"",
            "\"lastSyncFailed\":false").doesNotContain(ROOT.toString(), "\"error\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static Result git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "credential.helper=", "-c",
            "core.hooksPath=/dev/null", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_TERMINAL_PROMPT", "0"));
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Git command completes").isTrue();
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        }
        finally {
            process.destroyForcibly();
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("git-http-test-");
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        FileSystemUtils.deleteRecursively(ROOT);
    }

    private record Result(int exit, String output) { }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(GitHttpController.class)
    static class Application {
        @Bean
        GitMirror mirror() {
            return new GitMirror(new GitMirrorProperties(true, ROOT.resolve("mirrors").toString(), 60L, 30L),
                Map.of("ignite", new GitMirror.Sources(SOURCE.toString(), SOURCE.toString())));
        }
    }
}
