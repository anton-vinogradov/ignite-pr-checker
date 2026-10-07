package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
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
 * ci2 lets every signed-in user cancel any build, and "Cancel all" stopped every build queued by a person
 * on the PR branch: anyone who opened the PR could take down someone else's RunAll chain of ~150 suites.
 * A suite's Rerun likewise took someone else's queued re-run of that suite off the queue, top place and
 * all. RunAll also queued a second chain next to the user's own running one. On PR 13575 alice runs a
 * RunAll and has a re-run of Cache queued; bob runs his own RunAll and two re-runs of Cache, one still
 * in the queue.
 */
class OwnRunsTest {
    private static final String TOKEN = "tok";

    /** "METHOD /path" of every build cancellation or queueing TeamCity received, in order. */
    private final List<String> writes = new CopyOnWriteArrayList<>();

    private volatile int cancelStatus = 200;

    private volatile int queueStatus = 200;

    private volatile String queuedBody = "{\"id\":1,\"state\":\"queued\",\"buildTypeId\":\"RunAll\"}";

    private HttpServer server;

    private MockMvc mvc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        TcClient tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"), cfg, new Metrics(new ObjectMapper()));

        mvc = MockMvcBuilders.standaloneSetup(new TriggerController(tc, mock(BlockerAnalyzer.class),
                mock(RerunTracker.class), mock(GithubClient.class), mock(SuiteBaseline.class), cfg))
            .setControllerAdvice(new ApiExceptionHandler(tc))
            .build();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void cancelStopsOnlyTheViewersOwnBuilds() throws Exception {
        as("alice", post("/api/cancel-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(2));

        assertThat(writes).containsExactly("POST /app/rest/builds/id:201", "POST /app/rest/buildQueue/id:204");
    }

    @Test
    void cancelStopsOnlyTheRunsTheUserConfirmed() throws Exception {
        as("alice", post("/api/cancel-all").param("ids", "204,202"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(1));

        assertThat(writes).containsExactly("POST /app/rest/buildQueue/id:204");
    }

    @Test
    void runsTellWhoStartedEachAndWhichAreTheViewers() throws Exception {
        as("alice", get("/api/runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].buildId").value(201))
            .andExpect(jsonPath("$[0].by").value("alice"))
            .andExpect(jsonPath("$[0].mine").value(true))
            .andExpect(jsonPath("$[0].runAll").value(true))
            .andExpect(jsonPath("$[1].by").value("bob"))
            .andExpect(jsonPath("$[1].mine").value(false))
            .andExpect(jsonPath("$[1].runAll").value(false))
            .andExpect(jsonPath("$[2].by").value("bob"))
            .andExpect(jsonPath("$[2].mine").value(false))
            .andExpect(jsonPath("$[2].runAll").value(true))
            .andExpect(jsonPath("$[3].mine").value(true));
    }

    @Test
    void replacingRunAllCancelsOnlyTheViewersOwnChain() throws Exception {
        as("alice", post("/api/trigger").param("replace", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replaced").value(1))
            .andExpect(jsonPath("$.triggered[0].buildId").value(1));

        assertThat(writes).containsExactly("POST /app/rest/builds/id:201", "POST /app/rest/buildQueue");
    }

    @Test
    void plainRunAllCancelsNothing() throws Exception {
        as("alice", post("/api/trigger"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replaced").value(0));

        assertThat(writes).containsExactly("POST /app/rest/buildQueue");
    }

    @Test
    void refusedReplacementQueuesNoSecondChain() throws Exception {
        cancelStatus = 403;

        as("alice", post("/api/trigger").param("replace", "true"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.FORBIDDEN));

        assertThat(writes).containsExactly("POST /app/rest/builds/id:201");
    }

    @Test
    void replacementTeamCityDidNotQueueSaysThePreviousRunAllIsGone() throws Exception {
        queueStatus = 403;

        as("alice", post("/api/trigger").param("replace", "true"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.replaced").value(1))
            .andExpect(jsonPath("$.error").value("Your previous RunAll was cancelled, but queuing the new one "
                + "failed: " + ApiExceptionHandler.FORBIDDEN));

        assertThat(writes).containsExactly("POST /app/rest/builds/id:201", "POST /app/rest/buildQueue");
    }

    @Test
    void replacementWithAnUnreadableAnswerSaysThePreviousRunAllIsGone() throws Exception {
        queuedBody = "<html>Access denied</html>";

        as("alice", post("/api/trigger").param("replace", "true"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.replaced").value(1))
            .andExpect(jsonPath("$.error").value("Your previous RunAll was cancelled, but queuing the new one "
                + "failed — try RunAll again"));
    }

    @Test
    void suiteRerunReplacesOnlyTheViewersOwnQueuedRerun() throws Exception {
        as("alice", post("/api/rerun-suite").param("suite", "Cache"))
            .andExpect(status().isOk());

        assertThat(writes).containsExactly("POST /app/rest/buildQueue/id:204", "POST /app/rest/buildQueue");
    }

    @Test
    void sectionRerunReplacesOnlyTheViewersOwnQueuedReruns() throws Exception {
        as("alice", post("/api/rerun-suites").param("suites", "Cache,Query"))
            .andExpect(status().isOk());

        assertThat(writes).containsExactly("POST /app/rest/buildQueue/id:204", "POST /app/rest/buildQueue",
            "POST /app/rest/buildQueue");
    }

    private ResultActions as(String user, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.param("pr", "13575")
            .requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN)
            .requestAttr(AuthInterceptor.USER_ATTR, user));
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
        int code = 200;
        String body;

        if ("POST".equals(ex.getRequestMethod())) {
            writes.add("POST " + path);
            boolean cancel = path.contains("/id:");
            code = cancel ? cancelStatus : queueStatus;
            body = cancel ? "{}" : queuedBody;
        }
        else if (path.equals("/app/rest/buildQueue"))
            body = "{\"build\":[" + queued(204, "Cache", "alice", query) + ","
                + queued(305, "Cache", "bob", query) + "]}";
        else if (path.equals("/app/rest/builds") && query.contains("state:running"))
            body = "{\"build\":[" + build(201, "running", "RunAll", "alice") + ","
                + build(202, "running", "Cache", "bob") + "," + build(203, "running", "RunAll", "bob") + "]}";
        else if (path.equals("/app/rest/builds") && query.contains("state:queued"))
            body = "{\"build\":[" + build(204, "queued", "Cache", "alice") + ","
                + build(305, "queued", "Cache", "bob") + "]}";
        else
            body = "{}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** A suite re-run waiting in the queue; like TeamCity, who queued it only when the fields ask for it. */
    private static String queued(long id, String buildType, String user, String query) {
        return "{\"id\":" + id + ",\"state\":\"queued\",\"buildTypeId\":\"" + buildType
            + "\",\"branchName\":\"pull/13575/head\",\"triggered\":{\"type\":\"user\""
            + (query.contains("user(username)") ? ",\"user\":{\"username\":\"" + user + "\"}" : "") + "}}";
    }

    private static String build(long id, String state, String buildType, String user) {
        return "{\"id\":" + id + ",\"state\":\"" + state + "\",\"buildTypeId\":\"" + buildType
            + "\",\"buildType\":{\"name\":\"" + buildType + "\"},\"triggered\":{\"type\":\"user\",\"user\":"
            + "{\"username\":\"" + user + "\"}}}";
    }
}
