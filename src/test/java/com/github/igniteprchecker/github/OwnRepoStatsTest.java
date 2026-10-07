package com.github.igniteprchecker.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * /api/status fetched the stars and the rate limit from GitHub on the request thread. When GitHub stalls, every poll
 * of the status page, and the main page's /api/config, waited until the call gave up, so the page meant to show a
 * problem hung on it.
 */
class OwnRepoStatsTest {
    private static GithubClient github(String apiUrl) {
        return new GithubClient(new GithubProperties("apache/ignite", null, 300, Duration.ofMillis(300), apiUrl),
            new ObjectMapper(), new Metrics(new ObjectMapper()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
    }

    @Test
    void theStatusPageDoesNotWaitForAStalledGitHub() throws Exception {
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed())
                        held.add(silent.accept());
                }
                catch (IOException closed) {
                    // the test is over
                }
            }, "silent-github");
            acceptor.setDaemon(true);
            acceptor.start();
            GithubClient github = github("http://127.0.0.1:" + silent.getLocalPort());

            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 20; i++) {
                    assertThat(github.starCount()).isEqualTo(-1);
                    assertThat(github.rateLimit()).isEmpty();
                }
            });

            Thread.sleep(2_000);
            assertThat(held).as("one fetch of the stars and the rate limit for all those polls").hasSize(2);
        }
        finally {
            for (Socket s : held)
                s.close();
        }
    }

    @Test
    void theFetchedNumbersShowOnTheNextPoll() throws Exception {
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/", ex -> {
            String json = ex.getRequestURI().getPath().equals("/rate_limit")
                ? "{\"resources\":{\"core\":{\"remaining\":4990,\"limit\":5000,\"reset\":1800000000}}}"
                : "{\"stargazers_count\":42}";
            byte[] body = json.getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        api.start();
        try {
            GithubClient github = github("http://127.0.0.1:" + api.getAddress().getPort());
            github.starCount();

            await(() -> github.starCount() == 42 && !github.rateLimit().isEmpty());

            assertThat(github.starCount()).isEqualTo(42);
            assertThat(github.rateLimit()).containsEntry("remaining", 4990).containsEntry("limit", 5000);
        }
        finally {
            api.stop(0);
        }
    }
}
