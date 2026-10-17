package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.QueuedBelowTopException;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * On ci2 only some accounts may reorder the build queue, and TeamCity queues a build before it moves it to the top:
 * a refused move answers with an error while the build already waits at the end of the queue. "Run at top" then
 * said the trigger failed, the user clicked again and queued a second chain, and auto re-run, which asks for the top,
 * queued the same suites anew every ten minutes. Here alice, who may not reorder ci2's queue, asks for the top on PR
 * 13575.
 */
class QueueAtTopTest {
    private static final int PR = 13575;

    /** Builds of the PR branch waiting in the queue, as TeamCity lists them. */
    private final List<String> waiting = new CopyOnWriteArrayList<>();

    /** "METHOD /path" of every build queued or cancelled, in order. */
    private final List<String> writes = new CopyOnWriteArrayList<>();

    private volatile boolean mayReorder;

    /** Whether TeamCity queues the build at all; ci2's firewall refuses some requests before they reach it. */
    private volatile boolean queues = true;

    private volatile long nextId = 501;

    /** A build someone else queues for the branch while TeamCity handles alice's request. */
    private volatile String queuedMeanwhile;

    private HttpServer server;

    private TcClient tc;

    private MockMvc mvc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"), cfg, new Metrics(new ObjectMapper()));
        mvc = MockMvcBuilders.standaloneSetup(new TriggerController(tc, mock(BlockerAnalyzer.class),
                mock(RerunTracker.class), mock(PrHeads.class), mock(SuiteBaseline.class), cfg))
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)))
            .build();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void runAtTopTeamCityWouldNotMoveUpIsQueuedAndSaysSo() throws Exception {
        asAlice(post("/api/trigger").param("top", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered[0].buildId").value(501))
            .andExpect(jsonPath("$.triggered[0].belowTop").value(true))
            .andExpect(jsonPath("$.triggered[0].topRefusal").value(403));

        assertThat(writes).containsExactly("POST /app/rest/buildQueue");
    }

    @Test
    void rerunAtTopTeamCityWouldNotMoveUpIsQueuedOnce() throws Exception {
        asAlice(post("/api/rerun-suite").param("suite", "Cache").param("top", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered[0].buildId").value(501))
            .andExpect(jsonPath("$.triggered[0].belowTop").value(true));

        assertThat(writes).containsExactly("POST /app/rest/buildQueue");
    }

    @Test
    void runAtTopTeamCityMovedUpSaysNothingMore() throws Exception {
        mayReorder = true;

        asAlice(post("/api/trigger").param("top", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered[0].buildId").value(501))
            .andExpect(jsonPath("$.triggered[0].belowTop").doesNotExist());
    }

    /** Her earlier chain still waits in the queue: it is not taken for one TeamCity never queued. */
    @Test
    void aRefusalThatQueuedNothingStaysAFailure() throws Exception {
        waiting.add(build(450, "RunAll", "alice"));
        queues = false;

        asAlice(post("/api/trigger").param("top", "true"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.QUEUE_FORBIDDEN));
    }

    @Test
    void someoneElsesBuildIsNotTakenForHers() {
        queues = false;
        queuedMeanwhile = build(601, "RunAll", "bob");

        assertThatThrownBy(() -> tc.triggerRunAll("alice-tc", PR, true)).isInstanceOf(TcResponseException.class);
    }

    @Test
    void autoRerunAtTopIsQueuedOnce() {
        assertThatThrownBy(() -> tc.triggerBuildReplacingQueued("alice-tc", "Cache", PR, true, "Auto re-run #1"))
            .isInstanceOfSatisfying(QueuedBelowTopException.class, e -> {
                assertThat(e.build().id()).isEqualTo(501);
                assertThat(e.status()).isEqualTo(403);
            });
        assertThat(QueuedBelowTopException.queued(
            () -> tc.triggerBuildReplacingQueued("alice-tc", "Query", PR, true, "Auto re-run #1")).id())
            .isEqualTo(502);

        assertThat(writes).containsExactly("POST /app/rest/buildQueue", "POST /app/rest/buildQueue");
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.param("pr", String.valueOf(PR))
            .requestAttr(AuthInterceptor.TOKEN_ATTR, "alice-tc")
            .requestAttr(AuthInterceptor.USER_ATTR, "alice"));
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int code = 200;
        String body = "{}";

        if ("POST".equals(ex.getRequestMethod())) {
            writes.add("POST " + path);
            String sent = new String(ex.getRequestBody().readAllBytes(), UTF_8);
            if (path.equals("/app/rest/buildQueue")) {
                if (queuedMeanwhile != null)
                    waiting.add(queuedMeanwhile);
                String type = sent.replaceFirst("(?s).*\"buildType\":\\{\"id\":\"([^\"]+)\".*", "$1");
                if (queues) {
                    body = build(nextId++, type, "alice");
                    waiting.add(body);
                }
                if (!queues || sent.contains("\"queueAtTop\":true") && !mayReorder) {
                    code = 403;
                    body = "Access denied. Check the user has enough permissions to perform the operation.";
                }
            }
        }
        else if (path.equals("/app/rest/builds") || path.equals("/app/rest/buildQueue"))
            body = "{\"build\":[" + String.join(",", waiting) + "]}";
        else if (path.equals("/app/rest/users/current"))
            body = "{\"username\":\"alice\"}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", code == 200 ? "application/json" : "text/plain");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String build(long id, String buildType, String user) {
        return "{\"id\":" + id + ",\"state\":\"queued\",\"buildTypeId\":\"" + buildType + "\",\"branchName\":\"pull/"
            + PR + "/head\",\"webUrl\":\"https://ci2.example/build/" + id + "\",\"buildType\":{\"name\":\"" + buildType
            + "\"},\"triggered\":{\"type\":\"user\",\"user\":{\"username\":\"" + user + "\"}}}";
    }
}
