package com.github.igniteprchecker.web;

import static com.github.igniteprchecker.web.GitProxyUpstream.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.igniteprchecker.config.GitProxyProperties;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = GitProxyTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "spring.main.banner-mode=off"})
@DirtiesContext
class GitProxyTest {
    private static final GitProxyUpstream SOURCE = new GitProxyUpstream();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> MASTER = new java.util.LinkedHashMap<>();
    private static final Map<String, String> PR = new java.util.LinkedHashMap<>();
    @LocalServerPort
    private int port;

    /** Creates six real repositories with different master and PR 42 contents; the proxy receives no seed or sync. */
    @BeforeAll
    static void sources() throws Exception {
        for (String slug : GitProxyUpstream.SLUGS) {
            assertThat(git(SOURCE.root, "", "init", "--bare", "-b", "master",
                SOURCE.repository(slug).toString()).exit()).isZero();
            MASTER.put(slug, SOURCE.commit(slug, slug + " master\n"));
            PR.put(slug, SOURCE.commit(slug, slug + " PR 42\n"));
            SOURCE.ref(slug, "refs/heads/master", MASTER.get(slug));
            SOURCE.ref(slug, "refs/tags/release-probe", MASTER.get(slug));
            SOURCE.ref(slug, "refs/pull/42/head", PR.get(slug));
        }
    }

    /** Waits for the previous client to release its request before resetting the external-source controls. */
    @BeforeEach
    void resetSource() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(activeRequests()).isZero());
        SOURCE.failing = false;
        SOURCE.redirect = false;
        SOURCE.finishStream = null;
        SOURCE.calls.clear();
    }

    /** Given six distinct sources, when Git clones and fetches PR 42, then it receives only the selected repo's code. */
    @ParameterizedTest
    @ValueSource(strings = {"ignite", "ignite-python-thin-client", "ignite-php-thin-client",
        "ignite-nodejs-thin-client", "ignite-release", "ignite-extensions"})
    void routesEachRepositoryWithoutPreloading(String slug) throws Exception {
        Path clone = SOURCE.root.resolve("clone-" + slug);
        assertThat(git(SOURCE.root, "", "clone", "--quiet", url(slug), clone.toString()).exit()).isZero();
        assertThat(Files.readString(clone.resolve("file.txt"))).isEqualTo(slug + " master\n");

        assertThat(git(clone, "", "fetch", "--quiet", "origin", "refs/pull/42/head").exit()).isZero();

        assertThat(git(clone, "", "rev-parse", "FETCH_HEAD").output().strip()).isEqualTo(PR.get(slug));
        assertThat(git(clone, "", "show", "FETCH_HEAD:file.txt").output()).isEqualTo(slug + " PR 42\n");
        assertThat(SOURCE.calls.keySet()).allSatisfy(path -> assertThat(path).startsWith("/apache/" + slug + ".git/"));
    }

    /**
     * Given previously read refs that move upstream, when Git reads them again and fetches the new PR SHA,
     * then it receives current branches, tags and PR code without calling the checker or syncing a repository.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void readsFreshBranchesTagsAndPrs(int protocol) throws Exception {
        String[] refs = {"refs/heads/master", "refs/tags/release-probe", "refs/pull/42/head"};
        assertThat(git(SOURCE.root, "", "-c", "protocol.version=" + protocol, "ls-remote", url("ignite"),
            refs[2]).output()).contains(PR.get("ignite"));
        String next = SOURCE.commit("ignite", "updated for protocol " + protocol + "\n");
        for (String ref : refs)
            SOURCE.ref("ignite", ref, next);
        try {
            GitProxyUpstream.Result listed = git(SOURCE.root, "", "-c", "protocol.version=" + protocol,
                "ls-remote", url("ignite"), refs[0], refs[1], refs[2]);
            assertThat(listed.exit()).isZero();
            assertThat(listed.output()).contains(next + "\t" + refs[0], next + "\t" + refs[1], next + "\t" + refs[2]);
            Path client = SOURCE.root.resolve("fresh-pr-" + protocol);
            git(SOURCE.root, "", "init", "--quiet", client.toString());

            assertThat(git(client, "", "-c", "protocol.version=" + protocol, "fetch", "--quiet", url("ignite"),
                next).exit()).isZero();
            assertThat(git(client, "", "rev-parse", "FETCH_HEAD").output().strip()).isEqualTo(next);
        }
        finally {
            SOURCE.ref("ignite", refs[0], MASTER.get("ignite"));
            SOURCE.ref("ignite", refs[1], MASTER.get("ignite"));
            SOURCE.ref("ignite", refs[2], PR.get("ignite"));
        }
    }

    /** Given a writable source, when a Git client pushes through the proxy, then discovery is refused upstream of it. */
    @Test
    void refusesPushBeforeContactingTheSource() throws Exception {
        GitProxyUpstream.Result pushed = git(SOURCE.repository("ignite"), "", "push", url("ignite"),
            "refs/heads/master:refs/heads/from-proxy");

        assertThat(pushed.exit()).isNotZero();
        assertThat(pushed.output()).contains("403");
        assertThat(SOURCE.calls).isEmpty();
        assertThat(git(SOURCE.repository("ignite"), "", "show-ref", "--verify", "refs/heads/from-proxy").exit())
            .isNotZero();
    }

    /** Given a public proxy, when clients address write commands or files directly, then none reaches the source. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void refusesDirectWrites(String method) throws Exception {
        String command = packet("0".repeat(40) + " " + MASTER.get("ignite")
            + " refs/heads/from-proxy\0report-status\n") + "0000";
        for (String path : List.of("/git/ignite.git/git-receive-pack", "/git/ignite.git/config")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/x-git-receive-pack-request")
                .method(method, HttpRequest.BodyPublishers.ofString(command)).build();
            assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .as(method + " " + path).isBetween(400, 499);
        }
        assertThat(SOURCE.calls).isEmpty();
    }

    /** Given untrusted paths, when a client requests files or another repository, then it cannot select an upstream. */
    @Test
    void servesOnlyKnownReadPaths() throws Exception {
        for (String path : List.of("/git/ignite.git/config", "/git/ignite.git/HEAD",
            "/git/unknown.git/info/refs?service=git-upload-pack", "/git/ignite.git/objects/info/packs",
            "/git/%2e%2e/ignite.git/config", "/git/ignite.git/info/refs?service=git-receive-pack"))
            assertThat(get(path).statusCode()).as(path).isBetween(400, 499);
        assertThat(SOURCE.calls).isEmpty();
    }

    /** Given client credentials, when discovery is forwarded, then only the Git protocol header reaches the source. */
    @Test
    void doesNotForwardClientCredentials() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/git/ignite.git/info/refs?service=git-upload-pack"))
            .header("Git-Protocol", "version=2").header("Authorization", "Bearer client-secret")
            .header("Proxy-Authorization", "Basic client-proxy-secret").header("Cookie", "session=client-cookie")
            .header("X-Github-Token", "client-token").GET().build();

        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        assertThat(SOURCE.lastHeaders.get("Git-Protocol")).containsExactly("version=2");
        assertThat(SOURCE.lastHeaders).doesNotContainKeys("Authorization", "Proxy-Authorization", "Cookie", "X-Github-Token");
    }

    /** Given an upstream redirect, when a Git request is forwarded, then neither server nor client follows it. */
    @Test
    void refusesUpstreamRedirects() throws Exception {
        SOURCE.redirect = true;

        HttpResponse<String> response = get("/git/ignite.git/info/refs?service=git-upload-pack");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(SOURCE.calls).doesNotContainKey("/redirect-target");
    }

    /** Given a source that stops answering successfully, when refs are read again, then no saved refs hide the error. */
    @Test
    void reportsSourceFailureWithoutReturningSavedRefs() throws Exception {
        String path = "/git/ignite.git/info/refs?service=git-upload-pack";
        assertThat(get(path).body()).contains(MASTER.get("ignite"));
        SOURCE.failing = true;

        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain(MASTER.get("ignite"));
        assertThat(get("/api/git-proxy/status").body()).contains("\"slug\":\"ignite\"", "\"lastRequestFailed\":true")
            .doesNotContain(SOURCE.root.toString(), "source unavailable");
    }

    /** Given a gzip Git request, when it is forwarded, then its encoding and negotiation reach the source intact. */
    @Test
    void forwardsGzipNegotiation() throws Exception {
        String command = packet("command=ls-refs\n") + "0001" + packet("symrefs\n") + "0000";
        var compressed = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write(command.getBytes(StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/git/ignite.git/git-upload-pack"))
            .header("Content-Type", "application/x-git-upload-pack-request")
            .header("Content-Encoding", "gzip").header("Git-Protocol", "version=2")
            .POST(HttpRequest.BodyPublishers.ofByteArray(compressed.toByteArray())).build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(PR.get("ignite") + " refs/pull/42/head");
        assertThat(SOURCE.lastHeaders.get("Content-Encoding")).containsExactly("gzip");
    }

    /** Given a declared oversized request, when its headers arrive, then it is refused without reading its body. */
    @Test
    void refusesDeclaredOversizedBody() throws Exception {
        try (Socket socket = pendingUpload(8 * 1024 * 1024 + 1)) {
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                StandardCharsets.US_ASCII)).readLine();
            assertThat(status).contains("413");
        }
        assertThat(SOURCE.calls).isEmpty();
    }

    /** Given an unknown-length oversized request, when its limit is reached, then no body is sent upstream. */
    @Test
    void refusesChunkedOversizedBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/git/ignite.git/git-upload-pack"))
            .header("Content-Type", "application/x-git-upload-pack-request")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                new java.io.ByteArrayInputStream(new byte[8 * 1024 * 1024 + 1]))).build();

        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        assertThat(SOURCE.calls).isEmpty();
    }

    /** Given two incomplete requests, when a third arrives, then it is rejected before reading another body. */
    @Test
    void boundsConcurrentBodiesAndReportsActivity() throws Exception {
        try (Socket first = pendingUpload(1); Socket second = pendingUpload(1)) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(activeRequests()).isEqualTo(2));

            assertThat(get("/git/ignite.git/info/refs?service=git-upload-pack").statusCode()).isEqualTo(503);
            assertThat(SOURCE.calls).isEmpty();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(activeRequests()).isZero());
    }

    /** Given a paused source response, when the client reads, then bytes arrive before the whole response is ready. */
    @Test
    void streamsSourceResponses() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        SOURCE.finishStream = finish;
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base() + "/git/ignite.git/info/refs?service=git-upload-pack"))
                        .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    return body.readNBytes(1024).length;
                }
            }
            catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1024);
        }
        finally {
            finish.countDown();
            SOURCE.finishStream = null;
        }
    }

    /** Given a disabled proxy, when Git endpoints are called, then they return 404 without contacting any source. */
    @Test
    void disabledProxyDoesNotCallUpstream() throws Exception {
        GitHttpController disabled = new GitHttpController(new GitProxyProperties(false), URI.create(SOURCE.url()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/git/ignite.git/info/refs");
        request.setQueryString("service=git-upload-pack");
        MockHttpServletResponse response = new MockHttpServletResponse();

        disabled.refs("ignite", request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(disabled.status().enabled()).isFalse();
        assertThat(SOURCE.calls).isEmpty();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private int activeRequests() throws Exception {
        return JSON.readTree(get("/api/git-proxy/status").body()).get("activeGitRequests").intValue();
    }

    private Socket pendingUpload(int length) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        socket.getOutputStream().write(("POST /git/ignite.git/git-upload-pack HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            + "Content-Type: application/x-git-upload-pack-request\r\nContent-Length: " + length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String url(String slug) {
        return base() + "/git/" + slug + ".git";
    }

    private static String packet(String text) {
        return "%04x".formatted(4 + text.length()) + text;
    }

    @AfterAll
    static void cleanup() throws Exception {
        SOURCE.close();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        GitHttpController controller() {
            return new GitHttpController(new GitProxyProperties(true), URI.create(SOURCE.url()));
        }
    }
}
