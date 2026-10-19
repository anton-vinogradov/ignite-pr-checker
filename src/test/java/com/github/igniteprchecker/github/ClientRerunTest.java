package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * The author of PR #69 of apache/ignite-python-thin-client has **Re-run failed suites of my runs** on. Their
 * {@code /run-all} ran Thin client: Python as build 9424005, and a test master never failed failed it. As for a RunAll,
 * the checker runs the suite once more, on the same two commits, and the verdict comes after that re-run and counts
 * both runs. Ignite master moved on meanwhile: the re-run still tests the commit the first run tested.
 */
class ClientRerunTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int PR = 69;

    private static final long RUN = 9424005L;

    private static final long RERUN = 9424010L;

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String NEWER_IGNITE = "9e8d7c6b5a4f30219e8d7c6b5a4f30219e8d7c6b";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final PinnedRun PINNED = new PinnedRun(SUITE, "pull/69", List.of(
        new PinnedRun.Pin("335", "refs/heads/master", IGNITE), new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)),
        Map.of("OVERRIDDEN_BRANCH", CLIENT));

    private static final String AUTO_RERUN = "Ignite PR Checker: auto re-run of build 9424005 for"
        + " https://github.com/apache/ignite-python-thin-client/pull/69, on the same commits";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, mock(BlockerAnalyzer.class),
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author");
        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        when(github.prHeadSha(CLIENT_REPO, PR)).thenReturn(CLIENT);
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(IGNITE);
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335",
            "GitHubApacheIgnitePythonThinClient", "314")));
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenAnswer(inv ->
            inv.getArgument(3, String.class).startsWith("Ignite PR Checker: auto re-run") ? build(RERUN, "queued", null)
                : build(RUN, "queued", null));
        when(github.appComment(eq(CLIENT_REPO), eq(PR), any(), anyString())).thenAnswer(inv ->
            new GithubClient.PostedComment(inv.getArgument(3, String.class).contains("the verdict of") ? 89L : 88L,
                "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
    }

    @Test
    void aFailedRunIsRunOnceMoreOnTheSameCommitsAndTheVerdictCountsBoth() {
        clientComment(1L, "/run-all");
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(NEWER_IGNITE);
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));

        commands.updateEtas();

        verify(tc).queuePinned("tc", PINNED, true, AUTO_RERUN);
        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), endsWith("\n🏁 _Run finished._ ♻️ _Auto"
            + " re-run [build 9424010](https://ci2.example/viewLog.html?buildId=9424010) runs the suite again on the"
            + " same commits — queued, no finish estimate yet._"));
        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());

        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "running", null));
        commands.updateEtas();

        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), endsWith(" runs the suite again on the"
            + " same commits — running, no finish estimate yet._"));

        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN, RERUN)).thenReturn(verdict(true));
        commands.updateEtas();
        commands.updateEtas();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), bodies.capture());
        assertThat(bodies.getAllValues().get(1)).startsWith("@Author the verdict of your `/run-all`: **Thin client:"
                + " Python** [build 9424005](https://ci2.example/build/9424005)")
            .contains("against Ignite master at [`1f2e3d4`](https://github.com/apache/ignite/commit/" + IGNITE + ")")
            .contains("\n\n♻️ Settled after one auto re-run: [build 9424010](https://ci2.example/build/9424010) ran"
                + " the suite again on the same commits.\n\n<sub>");
        verify(github).appDelete(CLIENT_REPO, PR, "author", 88L);
        verify(tc, times(2)).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    /** Nothing in the run is blamed on the PR: there is nothing for a re-run to settle, and the verdict comes at once. */
    @Test
    void aRunWithNothingBlamedIsNotRunAgain() {
        clientComment(1L, "/run-all");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(false));

        commands.updateEtas();

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), startsWith("Ignite PR Checker: auto"));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("the verdict of your"));
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("♻️"));
    }

    /** The author switched the option on after the run finished: as for a RunAll, it does not act on that run. */
    @Test
    void aRunThatFinishedBeforeTheOptionWentOnIsNotRunAgain() {
        clientComment(1L, "/run-all");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true,
            Instant.now().minusSeconds(3600).getEpochSecond(), List.of()));

        commands.updateEtas();

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), startsWith("Ignite PR Checker: auto"));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("❌ **1 blocker:**"));
    }

    /** The suite failed to compile: the same commits fail the same way, so the checker does not run them again. */
    @Test
    void aSuiteThatFailedToCompileIsNotRunAgain() {
        clientComment(1L, "/run-all");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(false, now(), List.of(
            new BrokenSuite(SUITE, RUN, "Thin client: Python", List.of("compilation error"), 0, 400))));

        commands.updateEtas();

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), startsWith("Ignite PR Checker: auto"));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("compilation error"));
    }

    /** Someone cancelled the re-run in TeamCity: the verdict is that of the first run, and it says why. */
    @Test
    void aCancelledRerunLeavesTheVerdictOfTheFirstRun() {
        clientComment(1L, "/run-all");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));
        commands.updateEtas();
        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "UNKNOWN"));

        commands.updateEtas();

        verify(analyzer, never()).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong(), anyLong());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("♻️ The auto re-run [build 9424010]"
            + "(https://ci2.example/build/9424010) was cancelled, so this verdict counts the first run only."));
    }

    /** TeamCity failed the request of the re-run: the verdict of the first run comes at once and says so. */
    @Test
    void aRerunTeamCityDidNotQueueIsSaidInTheVerdict() {
        clientComment(1L, "/run-all");
        when(tc.queuePinned(eq("tc"), any(), eq(true), startsWith("Ignite PR Checker: auto")))
            .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));

        commands.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("♻️ The checker could not queue the"
            + " auto re-run: TeamCity answered HTTP 500. This verdict counts the first run only."));
    }

    /**
     * TeamCity refused the author's token when the checker queued the re-run: nothing is posted, the story waits for a
     * login, and the re-run goes in once the token works again.
     */
    @Test
    void aRerunTheTokenWasRefusedForGoesInAfterALogin() {
        clientComment(1L, "/run-all");
        when(tc.queuePinned(eq("tc"), any(), eq(true), startsWith("Ignite PR Checker: auto")))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8))
            .thenReturn(build(RERUN, "queued", null));
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));

        commands.updateEtas();

        assertThat(standing.tcTokenRejected("author")).isTrue();
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("the verdict of"));

        standing.tcTokenAccepted("author", "tc");
        commands.updateEtas();

        verify(tc, times(2)).queuePinned("tc", PINNED, true, AUTO_RERUN);
        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), contains("♻️ _Auto re-run"));
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("the verdict of"));
    }

    /**
     * The service restarted while the re-run waited: pr-commands.json keeps the re-run with its run, so the story goes
     * on with the re-run, and the verdict counts both runs.
     */
    @Test
    void theRerunIsFollowedAfterARestart(@TempDir Path dir) throws Exception {
        clientComment(1L, "/run-all");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));
        commands.updateEtas();
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        assertThat(mapper.readTree(file.toFile()).get("clientRuns").get(0).get("rerunId").asLong()).isEqualTo(RERUN);

        PrCommands restarted = commands();
        restarted.loadFrom(file);
        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN, RERUN)).thenReturn(verdict(true));
        restarted.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("♻️ Settled after one auto re-run"));
        verify(tc, times(2)).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    /**
     * pr-commands.json as the version before this one wrote it: its client's run has no re-run and no visa. The run is
     * told on, and its re-run tests the commits the file keeps.
     */
    @Test
    void aRunTheVersionBeforeKeptGoesOn(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path file = dir.resolve("pr-commands.json");
        Files.writeString(file, "{\"sinceMs\":" + now + ",\"handled\":{\"1\":" + now + "},\"handledTotal\":1,"
            + "\"clientSinceMs\":" + now + ",\"clientRuns\":[{\"commentId\":1,\"baseBody\":\"@Author 🚀 **Thin client:"
            + " Python queued**\",\"buildId\":" + RUN + ",\"username\":\"author\",\"narrationId\":88,\"app\":true,"
            + "\"pr\":69,\"note\":null,\"ending\":null,\"readyId\":0,\"repo\":\"" + CLIENT_REPO + "\",\"pinned\":"
            + "{\"suite\":\"" + SUITE + "\",\"branch\":\"pull/69\",\"revisions\":[{\"vcsRootInstance\":\"335\","
            + "\"vcsBranch\":\"refs/heads/master\",\"version\":\"" + IGNITE + "\"},{\"vcsRootInstance\":\"314\","
            + "\"vcsBranch\":\"refs/pull/69/head\",\"version\":\"" + CLIENT + "\"}],\"properties\":"
            + "{\"OVERRIDDEN_BRANCH\":\"" + CLIENT + "\"}}}],\"clientOutbox\":[]}");
        commands.loadFrom(file);
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(true));

        commands.updateEtas();

        verify(tc).queuePinned("tc", PINNED, true, AUTO_RERUN);
        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), contains("♻️ _Auto re-run"));
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
            new PythonClientRuns(new PythonClientProperties(null, null, null, null), github, tc, analyzer,
                new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
                new TeamcityProperties("https://ci2.example/")));
    }

    private void clientComment(long id, String body) {
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(
            new GithubClient.IssueComment(id, body, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-" + id,
                Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private void finished(long id, String status) {
        when(tc.getBuildState("tc", id)).thenReturn(build(id, "finished", status));
    }

    private static TcModel.Build build(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/69", SUITE, "https://ci2.example/viewLog.html?buildId=" + id,
            null, null, null, null, null, new TcModel.BuildType(SUITE, "Thin client: Python"), null, null, null, null,
            null, null);
    }

    /** When the run finished: after the option went on. */
    private static long now() {
        return Instant.now().plusSeconds(60).getEpochSecond();
    }

    private static AnalysisResult verdict(boolean blocked) {
        return verdict(blocked, now(), List.of());
    }

    /**
     * A test master never failed, a blocker when {@code blocked}, else flaky, and one master fails too; the run finished
     * at {@code finishedAt}, and {@code broken} are the suite's breaks.
     */
    private static AnalysisResult verdict(boolean blocked, long finishedAt, List<BrokenSuite> broken) {
        TestVerdict sql = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, RUN, "Thin client: Python",
            "o2", blocked, false, blocked ? "not seen failing in 100 master runs; failed the only run on this branch"
            : "flaky on branch: failed only the latest of 2 runs", "F", 1);
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get", SUITE, RUN, "Thin client: Python",
            "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        return new AnalysisResult(PR, RUN, "pull/69", System.currentTimeMillis(), blocked ? List.of(sql) : List.of(),
            List.of(), blocked ? List.of(shared) : List.of(shared, sql), broken, List.of(), 1, 0, false, 0, false, 0,
            0, 0, finishedAt, 0, List.of(), List.of(), List.of(), 0, IGNITE + "+" + CLIENT, 0);
    }
}
