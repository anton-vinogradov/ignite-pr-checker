package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The author of PR #69 of apache/ignite-python-thin-client, "IGNITE-29117 Fix test to forcefully use H2 engine", has
 * **Visa my runs in JIRA** on. Their {@code /run-all} ran only the suite Thin client: Python, against Ignite master.
 * The visa goes to IGNITE-29117 as for a RunAll, and says that it is of that one suite: IGNITE tickets get visas of
 * whole RunAlls, and this one must not read as one. The verdict comment in the PR says where the visa went.
 */
class ClientVisaTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int PR = 69;

    private static final long RUN = 9424005L;

    private static final long SECOND_RUN = 9424200L;

    private static final long RERUN = 9424010L;

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final String ISSUE = "IGNITE-29117";

    private static final String TITLE = ISSUE + " Fix test to forcefully use H2 engine";

    private static final String COMMENT_URL = "https://issues.example/browse/IGNITE-29117?focusedCommentId=18000001"
        + "#comment-18000001";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final StandingVisas standing = standing();

    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    private final PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Author"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", COMMENT_URL));
        when(jira.commentUrl(ISSUE, "18000001")).thenReturn(COMMENT_URL);
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author");
        standing.change("author", "tc", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null,
            null));
        when(github.pullState(CLIENT_REPO, PR)).thenReturn(Optional.of(new GithubClient.PullState(TITLE, true,
            false)));
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
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(RUN));
    }

    @Test
    void theVisaSaysItIsOfOneSuiteAgainstIgniteMasterAndListsWhatFailed() {
        runFinished(1L, RUN);

        ArgumentCaptor<String> visa = ArgumentCaptor.forClass(String.class);
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), visa.capture());
        assertThat(visa.getValue())
            .startsWith("Ignite PR Checker verdict for [PR 69 of " + CLIENT_REPO + "|https://github.com/" + CLIENT_REPO
                + "/pull/69] · *one suite only, not a RunAll*\n\n*Thin client: Python* [build 9424005|"
                + "https://ci2.example/build/9424005] tested the PR's head [a1b2c3d|https://github.com/" + CLIENT_REPO
                + "/pull/69/commits/" + CLIENT + "] against Ignite master at [1f2e3d4|https://github.com/apache/ignite/"
                + "commit/" + IGNITE + "]. No other suite ran.\n\n")
            .contains("(x) *1 blocker:*\n- {{test_sql.test_sql_fields_query}} — not seen failing in 100 master runs;"
                + " failed the only run on this branch · [TC|")
            .contains("*1 failing test is not blamed on this PR:*\n- {{test_cache_config.test_get}} — pre-existing:"
                + " fails 3/100 on master · [TC|")
            .doesNotContain("checker.example");
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("\n\n📝 The JIRA visa is in ["
            + ISSUE + "](" + COMMENT_URL + ").\n\n<sub>"));
        assertThat(journal.views("author", "login", "Firefox on Linux")).first().satisfies(v -> {
            assertThat(v.text()).isEqualTo("Posted a visa to IGNITE-29117 for PR #69 of " + CLIENT_REPO + ", build"
                + " 9424005.");
            assertThat(v.linkText()).isEqualTo(ISSUE);
            assertThat(v.url()).isEqualTo(COMMENT_URL);
        });
        assertThat(standing.postedCount()).isEqualTo(1);
    }

    /** A visa goes to the IGNITE ticket the PR title names: without one there is no visa, and the comment says so. */
    @Test
    void aTitleWithoutATicketGetsNoVisa() {
        when(github.pullState(CLIENT_REPO, PR)).thenReturn(Optional.of(new GithubClient.PullState("Fix test to"
            + " forcefully use H2 engine", true, false)));

        runFinished(1L, RUN);

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No JIRA visa: the PR title names"
            + " no IGNITE ticket."));
    }

    /** The PR was merged before the run's verdict: as for a RunAll, its run gets no visa. */
    @Test
    void aMergedPrGetsNoVisa() {
        when(github.pullState(CLIENT_REPO, PR)).thenReturn(Optional.of(new GithubClient.PullState(TITLE, false,
            true)));

        runFinished(1L, RUN);

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No JIRA visa: the PR was merged"
            + " before the run's verdict."));
    }

    /** The author switched the option on after the run finished: it does not act on that run, as for a RunAll. */
    @Test
    void aRunThatFinishedBeforeTheOptionWentOnGetsNoVisa() {
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(RUN,
            Instant.now().minusSeconds(3600).getEpochSecond()));

        runFinished(1L, RUN);

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝"));
    }

    /**
     * A second {@code /run-all} ran the same two commits and came to the same verdict: as for a RunAll, no second visa
     * repeats the first in the ticket, and the comment links the first.
     */
    @Test
    void aSecondRunOfTheSameCommitsWithTheSameVerdictPostsNoSecondVisa() {
        runFinished(1L, RUN);
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenReturn(build(SECOND_RUN, "queued",
            null));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", SECOND_RUN)).thenReturn(verdict(SECOND_RUN));

        runFinished(2L, SECOND_RUN);

        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No new JIRA visa: your last one"
            + " in [" + ISSUE + "](" + COMMENT_URL + ") has the same verdict for the same commits."));
    }

    /**
     * standing-visas.json keeps the visa of the client's run with its repo and commits. It sits next to a visa of
     * Ignite's PR #69 in another ticket, as the version before this one wrote it. After a restart, a second
     * {@code /run-all} on the same commits still posts no second visa.
     */
    @Test
    void theLastVisaIsKeptOverARestart(@TempDir Path dir) throws Exception {
        runFinished(1L, RUN);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode saved = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)saved.get("visas")).putObject("9400000").put("pr", PR).put("by", "author")
            .put("issue", "IGNITE-28867").put("commentId", "17000000");
        Files.writeString(file, mapper.writeValueAsString(saved));
        StandingVisas restarted = standing();
        restarted.loadFrom(file);
        PrCommands afterRestart = commands(restarted);
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenReturn(build(SECOND_RUN, "queued",
            null));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", SECOND_RUN)).thenReturn(verdict(SECOND_RUN));

        runFinished(afterRestart, 2L, SECOND_RUN);

        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No new JIRA visa: your last one"
            + " in [" + ISSUE + "](" + COMMENT_URL + ")"));
    }

    /**
     * JIRA failed the visa once: the verdict waits a minute, and both go out at the next look. The run is judged once:
     * each try of the visa would ask TeamCity again for the same verdict.
     */
    @Test
    void aVisaJiraFailedIsTriedAgainBeforeTheVerdict() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(new JiraClient.PostedComment("18000001", COMMENT_URL));

        runFinished(1L, RUN);

        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());

        clock.addAndGet(60_000);
        commands.updateEtas();

        verify(jira, times(2)).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 The JIRA visa is in"));
        verify(analyzer, times(1)).analyzeSuiteRun("tc", PR, "pull/69", RUN);
    }

    /**
     * While JIRA fails the visa, the verdict is decided and only waits for the visa: the checker's page of the PR reads
     * it final, not as still settling.
     */
    @Test
    void theVerdictHeldForItsVisaIsFinalOnThePage() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        runFinished(1L, RUN);

        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());
        assertThat(commands.clientPhase(PR, RUN).phase()).isEqualTo("final");
    }

    /** JIRA kept failing for 10 minutes: the verdict goes without the visa, and says what failed. */
    @Test
    void aVisaJiraKeepsFailingIsGivenUpAfterTenMinutes() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        runFinished(1L, RUN);
        clock.addAndGet(9 * 60_000);
        commands.updateEtas();

        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());

        clock.addAndGet(60_000);
        commands.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No JIRA visa: JIRA did not take"
            + " it (HTTP 503). The checker tried for 10 minutes."));
    }

    /** JIRA refused the author's token itself: the checker drops it and switches the option off, and says so. */
    @Test
    void aTokenJiraRefusesIsDroppedAndTheCommentSaysSo() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);

        runFinished(1L, RUN);

        assertThat(standing.visaOn("author")).isFalse();
        assertThat(standing.jiraTokenRejected("author")).isTrue();
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No JIRA visa: JIRA refused your"
            + " JIRA token, so the checker dropped it and switched off **Visa my runs in JIRA**."));
    }

    /**
     * With the auto re-run on too, the visa waits for the re-run and goes out once. The re-run passed the test the run
     * failed, so the visa judges both runs and blames nothing.
     */
    @Test
    void theVisaWaitsForTheRerunAndCountsBothRuns() {
        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        runFinished(1L, RUN);

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());

        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "SUCCESS"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN, RERUN)).thenReturn(passedOnRerun(RUN));
        commands.updateEtas();
        commands.updateEtas();

        ArgumentCaptor<String> visa = ArgumentCaptor.forClass(String.class);
        verify(jira, times(1)).addCommentWithId(eq("jira-pat"), eq(ISSUE), visa.capture());
        assertThat(visa.getValue()).contains("(/) *No blockers*").doesNotContain("(x)")
            .contains("- {{test_sql.test_sql_fields_query}} — flaky on branch: passed on the re-run")
            .contains("\n\n♻️ Settled after one auto re-run: [build 9424010|https://ci2.example/build/9424010] ran the"
                + " suite again on the same commits.");
    }

    /**
     * TeamCity failed the request of the re-run, and JIRA failed the visa. While the visa is tried again, the checker
     * does not queue the re-run again: the verdict it holds counts the first run only, and says so.
     */
    @Test
    void aRerunTeamCityDidNotQueueIsNotQueuedWhileTheVisaIsTriedAgain() {
        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        when(tc.queuePinned(eq("tc"), any(), eq(true), startsWith("Ignite PR Checker: auto")))
            .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
            .thenReturn(build(RERUN, "queued", null));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(new JiraClient.PostedComment("18000001", COMMENT_URL));

        runFinished(1L, RUN);
        clock.addAndGet(60_000);
        commands.updateEtas();

        String notQueued = "♻️ The checker could not queue the auto re-run: TeamCity answered HTTP 500. This verdict"
            + " counts the first run only.";
        verify(tc, times(1)).queuePinned(anyString(), any(), anyBoolean(), startsWith("Ignite PR Checker: auto"));
        verify(jira, times(2)).addCommentWithId(eq("jira-pat"), eq(ISSUE), contains(notQueued));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains(notQueued));
    }

    /**
     * The run finished while JIRA was down. The verdict waits for the visa, and the story says so: the run is over,
     * though the story said it was running.
     */
    @Test
    void theStorySaysTheVisaIsTriedAgainWhileTheVerdictWaits() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(comment(1L)));
        commands.poll();
        when(tc.getBuildState("tc", RUN)).thenReturn(build(RUN, "running", null));
        commands.updateEtas();
        when(tc.getBuildState("tc", RUN)).thenReturn(build(RUN, "finished", "FAILURE"));

        commands.updateEtas();
        clock.addAndGet(60_000);
        commands.updateEtas();

        verify(github, times(1)).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), endsWith("\n🏁 _Run finished —"
            + " the checker tries again to post the JIRA visa; the verdict follows in a new comment._"));
        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());
    }

    /**
     * GitHub asked the checker to wait with the verdict comment, and the service restarted. The visa the run got is
     * kept with it, so after the restart the comment links it and no second visa goes to the ticket.
     */
    @Test
    void aVisaPostedBeforeARestartIsNotPostedAgain(@TempDir Path dir) throws Exception {
        when(github.appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("the verdict of")))
            .thenThrow(new GithubClient.WriteDeferred("GitHub asked to wait 60 s"));
        runFinished(1L, RUN);
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        assertThat(mapper.readTree(file.toFile()).get("clientRuns").get(0).get("visa").get("state").asString())
            .isEqualTo("posted");

        when(github.appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("the verdict of")))
            .thenReturn(new GithubClient.PostedComment(89L, null));
        PrCommands restarted = commands();
        restarted.loadFrom(file);
        restarted.updateEtas();

        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        verify(github, times(2)).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 The JIRA visa is in ["
            + ISSUE + "](" + COMMENT_URL + ")."));
    }

    /**
     * The checker's state was restored from a copy without tokens, and the author logged in again: the TeamCity token
     * is back, the JIRA token is not. The comment says why the run got no visa, and what gets the next one.
     */
    @Test
    void aJiraTokenARestoredCopyLacksIsSaid(@TempDir Path dir) throws Exception {
        Path copy = dir.resolve("standing-visas.json");
        standing.saveTo(copy);
        TokenScrub.removeFrom(copy, System.currentTimeMillis());
        StandingVisas restored = standing();
        restored.loadFrom(copy);
        restored.tcTokenAccepted("author", "tc");
        PrCommands afterRestore = commands(restored);
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(comment(1L)));
        afterRestore.poll();
        when(tc.getBuildState("tc", RUN)).thenReturn(build(RUN, "finished", "FAILURE"));

        afterRestore.updateEtas();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("📝 No JIRA visa: the checker's state"
            + " was restored from a backup older than 2 days, which keeps no tokens. Save your JIRA token in ⚙ again,"
            + " and the next `/run-all` gets a visa."));
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class), jira, mock(VisaService.class),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);
    }

    private PrCommands commands() {
        return commands(standing);
    }

    private PrCommands commands(StandingVisas visas) {
        PrCommands c = new PrCommands(mapper, github, visas, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
            new PythonClientRuns(new PythonClientProperties(null, null, null, null), github, tc, analyzer,
                new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
                new TeamcityProperties("https://ci2.example/")));
        c.clock(clock::get);

        return c;
    }

    /** The author's {@code /run-all} in comment {@code id} queued run {@code buildId}, which then finished. */
    private void runFinished(long id, long buildId) {
        runFinished(commands, id, buildId);
    }

    private void runFinished(PrCommands c, long id, long buildId) {
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(comment(id)));
        c.poll();
        when(tc.getBuildState("tc", buildId)).thenReturn(build(buildId, "finished", "FAILURE"));
        c.updateEtas();
    }

    private static GithubClient.IssueComment comment(long id) {
        return new GithubClient.IssueComment(id, "/run-all", "https://github.com/" + CLIENT_REPO
            + "/pull/69#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"));
    }

    private static TcModel.Build build(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/69", SUITE, "https://ci2.example/viewLog.html?buildId=" + id,
            null, null, null, null, null, new TcModel.BuildType(SUITE, "Thin client: Python"), null, null, null, null,
            null, null);
    }

    private static AnalysisResult verdict(long buildId) {
        return verdict(buildId, Instant.now().plusSeconds(60).getEpochSecond());
    }

    /** The same run once its re-run passed the test master never failed: that test is flaky now, not a blocker. */
    private static AnalysisResult passedOnRerun(long buildId) {
        TestVerdict flaky = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, buildId,
            "Thin client: Python", "o2", false, false, "flaky on branch: passed on the re-run", "F", 1);

        return new AnalysisResult(PR, buildId, "pull/69", System.currentTimeMillis(), List.of(), List.of(),
            List.of(flaky), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0,
            Instant.now().plusSeconds(60).getEpochSecond(), 0, List.of(), List.of(), List.of(), 0, IGNITE + "+" + CLIENT,
            0);
    }

    /** A test master never failed, which failed the run, and one master fails too; the run finished at {@code at}. */
    private static AnalysisResult verdict(long buildId, long at) {
        TestVerdict blocker = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, buildId,
            "Thin client: Python", "o2", true, false, "not seen failing in 100 master runs; failed the only run on"
            + " this branch", "F", 1);
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get", SUITE, buildId, "Thin client: Python",
            "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        return new AnalysisResult(PR, buildId, "pull/69", System.currentTimeMillis(), List.of(blocker), List.of(),
            List.of(shared), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, at, 0, List.of(), List.of(),
            List.of(), 0, IGNITE + "+" + CLIENT, 0);
    }
}
