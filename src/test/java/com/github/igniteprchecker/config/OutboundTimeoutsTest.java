package com.github.igniteprchecker.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.ApiExceptionHandler;
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
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.client.ResourceAccessException;

/**
 * The TeamCity and JIRA clients had no timeouts at all, and GitHub got httpclient5's defaults only because
 * checkstyle happened to bring the library in. A peer that accepts the connection and never answers held the
 * caller forever: one such call stalls a PR's analysis for good, and three stall every scheduled job. Once the
 * calls gave up, the page said "TeamCity is unreachable" whichever service it was.
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

    /** What the page is told when the call gives up. */
    private static String answerTo(Executable call) {
        ResourceAccessException e = assertThrows(ResourceAccessException.class, call);

        return (String)((Map<?, ?>)new ApiExceptionHandler(null).noAnswer(e).getBody()).get("error");
    }

    @Test
    void teamCityCallGivesUp() {
        TcClient tc = new TcClient(new TeamcityProperties(silentUrl(), SHORT),
            new AnalysisProperties(null, null, null, null, null, null, null), metrics);

        assertTimeoutPreemptively(HANG_GUARD, () -> assertThat(answerTo(() -> tc.currentUsername("tok")))
            .isEqualTo("TeamCity did not answer — try again in a moment"));
    }

    @Test
    void jiraCallGivesUp() {
        JiraClient jira = new JiraClient(silentUrl(), SHORT, metrics);

        assertTimeoutPreemptively(HANG_GUARD, () -> assertThat(answerTo(() -> jira.myself("tok")))
            .isEqualTo("JIRA did not answer — try again in a moment"));
    }

    private GithubClient github(String apiUrl) {
        return new GithubClient(new GithubProperties("apache/ignite", null, 300, SHORT, apiUrl), new ObjectMapper(),
            metrics);
    }

    @Test
    void gitHubCallGivesUp() {
        GithubClient github = github(silentUrl());

        assertTimeoutPreemptively(HANG_GUARD, () -> assertThat(answerTo(() -> github.updatePrComment("pat", 1, "x")))
            .isEqualTo("GitHub did not answer — try again in a moment"));
    }

    @Test
    void gitHubCommentEditIsSentAsPatch() throws IOException {
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        api.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            byte[] body = "{}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        api.start();
        try {
            github("http://127.0.0.1:" + api.getAddress().getPort()).updatePrComment("pat", 1, "x");

            assertThat(requests).containsExactly("PATCH /repos/apache/ignite/issues/comments/1");
        }
        finally {
            api.stop(0);
        }
    }
}
