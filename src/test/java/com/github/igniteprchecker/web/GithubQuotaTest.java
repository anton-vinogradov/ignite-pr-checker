package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.StubbedGithub;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Any signed-in user spends calls of the checker's own GitHub token through the page, and ci2 lets anyone register.
 * /api/runs asked GitHub for the head of any PR number, with or without a run, and kept each answer, a failed one
 * too; /api/pending asked GitHub on every request, about any build. On prod on 17.10, PR head lookups took 120 of the
 * hour's 308 GitHub calls. With the calls run out for over an hour, the PR commands posted meanwhile are skipped, and
 * visas go out without saying that commits were pushed since.
 */
class GithubQuotaTest {
    private static final int PR = 13335;

    private static final long RUN_ALL = 9398719L;

    private static final String BUILT = "1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e";

    private static final String HEAD = "6573622e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a";

    /** A head pushed over {@link #HEAD}. */
    private static final String PUSHED = "a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9";

    private static final String HEAD_URL = "https://api.github.com/repos/apache/ignite/pulls/" + PR;

    private static final String COMPARE_URL = "https://api.github.com/repos/apache/ignite/compare/" + BUILT + "..."
        + HEAD;

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer github = MockRestServiceServer.bindTo(http).ignoreExpectOrder(true).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient client = StubbedGithub.over(new GithubProperties("apache/ignite", "app-token", 300),
        http.build());

    /** What the page's clock says, epoch milliseconds. */
    private long now = System.currentTimeMillis();

    private final PrHeads heads = new PrHeads(client, () -> now);

    /** The PR's builds on ci2 that are running and queued, as TeamCity lists them. */
    private volatile String running = "";

    private volatile String queued = "";

    /** The branch of the RunAll build the page asks about. */
    private volatile String branch = "pull/" + PR + "/head";

    private HttpServer teamcity;

    private TriggerController runs;

    private AnalyzeController analyze;

    @BeforeEach
    void startTeamCity() throws IOException {
        teamcity = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        teamcity.createContext("/", this::answer);
        teamcity.start();

        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        TcClient tc = new TcClient(new TeamcityProperties("http://" + teamcity.getAddress().getHostString() + ":"
            + teamcity.getAddress().getPort() + "/"), cfg, new Metrics(mapper));
        runs = new TriggerController(tc, mock(BlockerAnalyzer.class), mock(RerunTracker.class), heads,
            mock(SuiteBaseline.class), cfg);
        analyze = new AnalyzeController(mock(BlockerAnalyzer.class), mock(RunDeltaStore.class),
            new PendingCommits(tc, client), heads);
    }

    @AfterEach
    void stopTeamCity() {
        teamcity.stop(0);
    }

    @Test
    void aPrWithNoRunningBuildCostsNoGithubCall() {
        queued = build(204, "queued");

        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).doesNotContainKey("onHead"));
        github.verify();
    }

    @Test
    void aRunningBuildIsComparedWithTheHead() {
        running = build(201, "running");
        github.expect(once(), requestTo(HEAD_URL)).andRespond(head(HEAD));

        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).containsEntry("onHead", false));
        github.verify();
    }

    /** GitHub failed once: the next poll asks again rather than leave the run without its mark for 30 s. */
    @Test
    void aFailedAnswerIsNotKept() {
        running = build(201, "running");
        github.expect(once(), requestTo(HEAD_URL)).andRespond(withServerError());
        github.expect(once(), requestTo(HEAD_URL)).andRespond(head(BUILT));

        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).doesNotContainKey("onHead"));
        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).containsEntry("onHead", true));
        github.verify();
    }

    @Test
    void theHeadIsAskedOnceIn30SecondsAndAPairComparedOnce() {
        github.expect(times(2), requestTo(HEAD_URL)).andRespond(head(HEAD));
        github.expect(once(), requestTo(COMPARE_URL)).andRespond(withSuccess("{\"status\":\"ahead\",\"ahead_by\":33}",
            MediaType.APPLICATION_JSON));

        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("ahead", 33);
        now += PrHeads.KEEP_MS - 1;
        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("ahead", 33);
        now += 1;
        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("ahead", 33);
        github.verify();
    }

    /** The open-PR list, fetched seconds ago, already names the head. */
    @Test
    void aFreshPrListNamesTheHead(@TempDir Path dir) throws IOException {
        listHead(dir, BUILT);

        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("pending", false).containsEntry("checked", true);
        github.verify();
    }

    /**
     * The open-PR list, fetched seconds ago, names a head past the run. In the last fifth of the hour the page gets
     * the comparison GitHub already made, and no new one.
     */
    @Test
    void theLastFifthOfTheHourComparesNoNewPair(@TempDir Path dir) throws IOException {
        github.expect(once(), requestTo(COMPARE_URL)).andRespond(withSuccess("{\"status\":\"ahead\",\"ahead_by\":33}",
            MediaType.APPLICATION_JSON));
        github.expect(once(), requestTo("https://api.github.com/repos/apache/ignite/pulls/13000"))
            .andRespond(head(HEAD).headers(quota(999, now / 1000 + 600)));
        listHead(dir, HEAD);

        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("ahead", 33);
        client.prHeadSha(13000);
        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("ahead", 33);

        listHead(dir, PUSHED);
        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("pending", false).containsEntry("checked", false);
        github.verify();
    }

    /** A build of another PR, or of master, says nothing of this PR: GitHub is not asked about it. */
    @Test
    void aBuildOfAnotherBranchGetsNoAnswer() {
        branch = "pull/13000/head";

        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("pending", false).containsEntry("checked", false);
        github.verify();
    }

    /** A fifth of the hour's calls stays for PR commands, comments and visas, until GitHub's hour resets. */
    @Test
    void theLastFifthOfTheHourIsLeftForTheChecker() {
        running = build(201, "running");
        long reset = now / 1000 + 600;
        github.expect(once(), requestTo("https://api.github.com/repos/apache/ignite/pulls/13000"))
            .andRespond(head(HEAD).headers(quota(999, reset)));

        assertThat(runs(13000)).singleElement().satisfies(b -> assertThat(b).containsKey("onHead"));
        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).doesNotContainKey("onHead"));
        assertThat(analyze.pending(PR, RUN_ALL, "t")).containsEntry("checked", false);
        github.verify();

        github.reset();
        now = reset * 1000;
        github.expect(once(), requestTo(HEAD_URL)).andRespond(head(BUILT).headers(quota(4999, reset + 3600)));

        assertThat(runs(PR)).singleElement().satisfies(b -> assertThat(b).containsEntry("onHead", true));
        github.verify();
    }

    @Test
    void theQuotaComesFromAnswersToTheAppTokenOnly() {
        long reset = now / 1000 + 600;
        github.expect(requestTo("https://api.github.com/user")).andRespond(
            withSuccess("{\"login\":\"alice\",\"id\":7}", MediaType.APPLICATION_JSON).headers(quota(1, reset)));
        github.expect(requestTo(HEAD_URL)).andRespond(head(HEAD).headers(quota(4000, reset)));
        github.expect(requestTo(HEAD_URL)).andRespond(head(HEAD).headers(quota(4100, reset)));

        client.ghIdentity("alices-own-token");
        assertThat(client.appQuota()).as("a user's own token").isNull();
        client.prHeadSha(PR);
        client.prHeadSha(PR);

        assertThat(client.appQuota()).as("an answer that came late")
            .isEqualTo(new GithubClient.Quota(4000, 5000, reset));
    }

    private List<Map<String, Object>> runs(int pr) {
        return runs.runs(pr, "t", "alice");
    }

    /** Loads an open-PR list fetched 5 seconds ago, where the PR's head is {@code sha}. */
    private void listHead(Path dir, String sha) throws IOException {
        Path file = dir.resolve("github.json");
        Files.writeString(file, "{\"prs\":[{\"number\":" + PR + ",\"title\":\"IGNITE-28867 Reload SSL certificates\","
            + "\"url\":\"https://github.com/apache/ignite/pull/" + PR + "\",\"headSha\":\"" + sha + "\"}],"
            + "\"cacheTs\":" + (now - 5_000) + "}");
        client.loadFrom(file);
    }

    private static DefaultResponseCreator head(String sha) {
        return withSuccess("{\"user\":{\"login\":\"alice\"},\"head\":{\"ref\":\"ignite-28867\",\"sha\":\"" + sha + "\","
            + "\"repo\":{\"full_name\":\"alice/ignite\"}}}", MediaType.APPLICATION_JSON);
    }

    private static HttpHeaders quota(int remaining, long reset) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-RateLimit-Limit", "5000");
        h.add("X-RateLimit-Remaining", Integer.toString(remaining));
        h.add("X-RateLimit-Reset", Long.toString(reset));
        h.add("X-RateLimit-Resource", "core");

        return h;
    }

    private static String build(long id, String state) {
        return "{\"id\":" + id + ",\"state\":\"" + state + "\",\"buildTypeId\":\"Cache\",\"buildType\":{\"name\":"
            + "\"Cache\"},\"triggered\":{\"type\":\"user\",\"user\":{\"username\":\"alice\"}}}";
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
        String body;
        if (path.equals("/app/rest/builds") && query.contains("state:running"))
            body = "{\"build\":[" + running + "]}";
        else if (path.equals("/app/rest/builds") && query.contains("state:queued"))
            body = "{\"build\":[" + queued + "]}";
        else if (path.equals("/app/rest/builds/id:" + RUN_ALL))
            body = "{\"branchName\":\"" + branch + "\",\"revisions\":{\"revision\":[{\"version\":\"" + BUILT + "\"}]}}";
        else if (path.equals("/app/rest/builds/id:201"))
            body = "{\"revisions\":{\"revision\":[{\"version\":\"" + BUILT + "\"}]}}";
        else
            body = "{}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
