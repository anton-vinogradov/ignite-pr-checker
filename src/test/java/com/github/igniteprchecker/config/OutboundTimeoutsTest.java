package com.github.igniteprchecker.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The TeamCity and JIRA clients had no timeouts at all, and GitHub got httpclient5's defaults only because
 * checkstyle happened to bring the library in. A peer that accepts the connection and never answers held the
 * caller forever: one such call stalls a PR's analysis for good, and three stall every scheduled job.
 */
class OutboundTimeoutsTest {
    private static final Duration SHORT = Duration.ofMillis(300);

    private static final Duration HANG_GUARD = Duration.ofSeconds(10);

    private final List<Socket> held = new CopyOnWriteArrayList<>();

    private final Metrics metrics = new Metrics(new ObjectMapper());

    private ServerSocket silent;

    @BeforeEach
    void startSilentPeer() throws IOException {
        silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try {
                while (!silent.isClosed())
                    held.add(silent.accept());
            }
            catch (IOException closed) {
                // the test is over
            }
        }, "silent-peer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopSilentPeer() throws IOException {
        silent.close();
        for (Socket s : held)
            s.close();
    }

    private String silentUrl() {
        return "http://127.0.0.1:" + silent.getLocalPort() + "/";
    }

    @Test
    void teamCityCallGivesUp() {
        TcClient tc = new TcClient(new TeamcityProperties(silentUrl(), SHORT),
            new AnalysisProperties(null, null, null, null, null, null, null), metrics);

        assertTimeoutPreemptively(HANG_GUARD, () ->
            assertThatThrownBy(() -> tc.currentUsername("tok")).isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void jiraCallGivesUp() {
        JiraClient jira = new JiraClient(silentUrl(), SHORT, metrics);

        assertTimeoutPreemptively(HANG_GUARD, () ->
            assertThatThrownBy(() -> jira.myself("tok")).isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void gitHubFactoryGivesUp() {
        RestClient http = RestClient.builder().requestFactory(OutboundHttp.withPatch(SHORT)).build();

        assertTimeoutPreemptively(HANG_GUARD, () ->
            assertThatThrownBy(() -> http.patch().uri(silentUrl() + "comments/1").body(Map.of("body", "x"))
                .retrieve().toBodilessEntity()).isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void gitHubFactorySendsPatch() throws IOException {
        HttpServer github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> methods = new CopyOnWriteArrayList<>();
        github.createContext("/", ex -> {
            methods.add(ex.getRequestMethod());
            byte[] body = "{}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        github.start();
        try {
            RestClient http = RestClient.builder().requestFactory(OutboundHttp.withPatch(SHORT)).build();

            http.patch().uri("http://127.0.0.1:" + github.getAddress().getPort() + "/comments/1")
                .body(Map.of("body", "x")).retrieve().toBodilessEntity();

            assertThat(methods).containsExactly("PATCH");
        }
        finally {
            github.stop(0);
        }
    }
}
