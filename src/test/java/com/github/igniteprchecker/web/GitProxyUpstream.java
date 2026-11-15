package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.springframework.util.FileSystemUtils;

/** Gives Git clients six independent local sources and records the HTTP requests the proxy sends to them. */
final class GitProxyUpstream implements AutoCloseable {
    static final List<String> SLUGS = List.of("ignite", "ignite-python-thin-client", "ignite-php-thin-client",
        "ignite-nodejs-thin-client", "ignite-release", "ignite-extensions");
    final Path root;
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor;
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile Map<String, List<String>> lastHeaders = Map.of();
    volatile boolean failing;
    volatile boolean redirect;
    volatile CountDownLatch finishStream;

    GitProxyUpstream() {
        try {
            root = Files.createTempDirectory("git-proxy-upstream-");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "git-proxy-test-source");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::serve);
            server.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Path repository(String slug) {
        return root.resolve(slug + ".git");
    }

    /** Creates a public commit with the supplied file contents, without changing any branch. */
    String commit(String slug, String contents) throws Exception {
        Path repo = repository(slug);
        String blob = git(repo, contents, "hash-object", "-w", "--stdin").output().strip();
        String tree = git(repo, "100644 blob " + blob + "\tfile.txt\n", "mktree").output().strip();
        return git(repo, "", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit-tree", tree, "-m", contents.strip()).output().strip();
    }

    void ref(String slug, String ref, String sha) throws Exception {
        assertThat(git(repository(slug), "", "update-ref", ref, sha).exit()).isZero();
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(exchange.getRequestHeaders());
        lastHeaders = headers;
        try (exchange) {
            if (path.equals("/redirect-target")) {
                reply(exchange, 200, "redirect followed");
                return;
            }
            if (redirect) {
                exchange.getResponseHeaders().set("Location", url() + "/redirect-target");
                exchange.getResponseHeaders().set("Set-Cookie", "source-cookie=not-for-the-client");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (failing) {
                reply(exchange, 503, "source unavailable");
                return;
            }
            CountDownLatch stream = finishStream;
            if (stream != null) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[32 * 1024]);
                exchange.getResponseBody().flush();
                try {
                    assertThat(stream.await(30, TimeUnit.SECONDS)).as("client releases the source stream").isTrue();
                    exchange.getResponseBody().write(1);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding")))
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gzip.readAllBytes();
                }
            ProcessBuilder builder = new ProcessBuilder("git", "-c", "http.receivepack=true", "-c",
                "http.getanyfile=true", "-c", "core.hooksPath=/dev/null", "http-backend");
            String commandPath = builder.environment().get("PATH");
            builder.environment().clear();
            if (commandPath != null)
                builder.environment().put("PATH", commandPath);
            builder.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_PROJECT_ROOT", root.toString(), "GIT_HTTP_EXPORT_ALL", "1",
                "REQUEST_METHOD", exchange.getRequestMethod(), "PATH_INFO", path.substring("/apache".length()),
                "QUERY_STRING", exchange.getRequestURI().getRawQuery() == null ? ""
                    : exchange.getRequestURI().getRawQuery(), "CONTENT_LENGTH", Integer.toString(body.length)));
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type != null)
                builder.environment().put("CONTENT_TYPE", type);
            String protocol = exchange.getRequestHeaders().getFirst("Git-Protocol");
            if (protocol != null)
                builder.environment().put("GIT_PROTOCOL", protocol);
            Process process = builder.start();
            try {
                process.getOutputStream().write(body);
                process.getOutputStream().close();
                byte[] result = process.getInputStream().readAllBytes();
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("source Git HTTP completes").isTrue();
                int split = -1;
                for (int i = 0; i + 3 < result.length; i++)
                    if (result[i] == '\r' && result[i + 1] == '\n'
                        && result[i + 2] == '\r' && result[i + 3] == '\n') {
                        split = i;
                        break;
                    }
                assertThat(split).as("native CGI headers").isNotNegative();
                int status = 200;
                for (String line : new String(result, 0, split, StandardCharsets.US_ASCII).split("\r\n")) {
                    int colon = line.indexOf(':');
                    String name = line.substring(0, colon);
                    String value = line.substring(colon + 1).strip();
                    if (name.equalsIgnoreCase("Status"))
                        status = Integer.parseInt(value.split(" ", 2)[0]);
                    else
                        exchange.getResponseHeaders().add(name, value);
                }
                exchange.sendResponseHeaders(status, result.length - split - 4);
                exchange.getResponseBody().write(result, split + 4, result.length - split - 4);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                process.destroyForcibly();
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static Result git(Path directory, String input, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "credential.helper=", "-c",
            "core.hooksPath=/dev/null", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        String path = builder.environment().get("PATH");
        builder.environment().clear();
        if (path != null)
            builder.environment().put("PATH", path);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_TERMINAL_PROMPT", "0"));
        Process process = builder.start();
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Git command completes").isTrue();
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
        }
        finally {
            process.destroyForcibly();
        }
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        executor.shutdownNow();
        FileSystemUtils.deleteRecursively(root);
    }

    record Result(int exit, String output) { }
}
