package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A revoked TeamCity token used to look like an outage: the analysis answered a bare 500, the page said
 * "service is busy or restarting" six times over and kept sending the dead token. A 403 from ci2's
 * firewall, which also hits valid requests, came back as a raw "TeamCity rejected the request (403
 * FORBIDDEN)", and "Cancel all" refused on every build reported "cancelled 0 runs".
 */
class TeamCityRefusalTest {
    private static final String TOKEN = "tok";

    /** Status TeamCity answers per "METHOD /path" prefix; anything unlisted gets an empty 200. */
    private final Map<String, Integer> answers = new ConcurrentHashMap<>();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

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

        mvc = MockMvcBuilders.standaloneSetup(
                new TriggerController(tc, analyzer, mock(RerunTracker.class), mock(GithubClient.class),
                    mock(SuiteBaseline.class), cfg),
                new AnalyzeController(analyzer, mock(RunDeltaStore.class), mock(PendingCommits.class)))
            .setControllerAdvice(new ApiExceptionHandler(tc))
            .build();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void revokedTokenSendsTheUserBackToTheLoginForm() throws Exception {
        answers.put("GET /", 401);
        answers.put("POST /", 401);

        mvc.perform(get("/api/runs").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.tokenRejected").value(true))
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.TOKEN_REJECTED));

        mvc.perform(post("/api/trigger").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.tokenRejected").value(true));
    }

    @Test
    void firewallRefusalExplainsItselfAndKeepsTheSession() throws Exception {
        answers.put("POST /app/rest/buildQueue", 403);

        mvc.perform(post("/api/trigger").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.tokenRejected").doesNotExist())
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.FORBIDDEN));
    }

    @Test
    void anotherTokensRejectionDoesNotLogTheViewerOut() throws Exception {
        when(analyzer.analyze(anyString(), anyInt())).thenThrow(new TcResponseException(
            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null)));

        mvc.perform(get("/api/analyze").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.tokenRejected").doesNotExist());
    }

    @Test
    void githubOrJiraRejectionIsNotTakenForTheTeamCityToken() {
        when(analyzer.analyze(anyString(), anyInt())).thenThrow(
            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> mvc.perform(get("/api/analyze").param("pr", "13575")
            .requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN)))
            .hasRootCauseInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void cancelRefusedOnEveryBuildIsReportedNotCountedAsZero() throws Exception {
        answers.put("POST /app/rest/builds/id:", 403);

        mvc.perform(post("/api/cancel-all").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.FORBIDDEN));
    }

    @Test
    void cancelCountsTheBuildsItCouldStop() throws Exception {
        answers.put("POST /app/rest/builds/id:101", 403);

        mvc.perform(post("/api/cancel-all").param("pr", "13575").requestAttr(AuthInterceptor.TOKEN_ATTR, TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelled").value(1));
    }

    @Test
    void onlyTeamCitysOwn401CountsAsARejectedToken() {
        answers.put("GET /app/rest/users/current", 401);
        assertThat(tc.tokenRejected(TOKEN)).isTrue();

        answers.put("GET /app/rest/users/current", 403);
        assertThat(tc.tokenRejected(TOKEN)).isFalse();

        answers.remove("GET /app/rest/users/current");
        assertThat(tc.tokenRejected(TOKEN)).isFalse();
    }

    private void answer(HttpExchange ex) throws IOException {
        String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        int code = answers.entrySet().stream()
            .filter(e -> call.startsWith(e.getKey()))
            .max(Map.Entry.comparingByKey((a, b) -> Integer.compare(a.length(), b.length())))
            .map(Map.Entry::getValue)
            .orElse(200);

        byte[] body = (code == 200 ? okBody(ex) : "{}").getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Two running builds on the PR branch when asked for them; a queued build for a trigger. */
    private static String okBody(HttpExchange ex) {
        String query = ex.getRequestURI().getQuery();
        if (ex.getRequestURI().getPath().startsWith("/app/rest/users/current"))
            return "{\"username\":\"someone\"}";
        if ("GET".equals(ex.getRequestMethod()) && query != null && query.contains("state:running"))
            return "{\"build\":[{\"id\":101,\"state\":\"running\"},{\"id\":102,\"state\":\"running\"}]}";
        if ("GET".equals(ex.getRequestMethod()) && ex.getRequestURI().getPath().startsWith("/app/rest/builds"))
            return "{\"build\":[]}";

        return "{\"id\":1,\"state\":\"queued\"}";
    }
}
