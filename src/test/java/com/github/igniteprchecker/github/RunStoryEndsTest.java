package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The /run-all stories of 13419, 13421, 13440 and 13462 still say "analysing; the verdict comment follows": a newer
 * run overtook the commanded one, or a second person's /run-all on the same PR took its place, since the stories were
 * kept one per PR. A new /run-all cancelled the commander's earlier chain but not the re-runs of its waves, and a
 * story whose commander switched their options off, or whose build TeamCity dropped, just stopped.
 */
class RunStoryEndsTest {
    private static final int PR = 13440;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        jira, new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc-a", "Author");
        standing.linkGhLogin("reviewer", "tc-r", "Reviewer");
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28999 Fix it", null, null, null, null)));
        when(tc.triggerRunAll("tc-a", PR, false)).thenReturn(chain(CHAIN, "queued", null));
        when(tc.triggerRunAll("tc-r", PR, false)).thenReturn(chain(CHAIN + 1, "queued", null));
        when(github.addPrCommentAsAppWithId(eq(PR), anyString())).thenAnswer(inv -> new GithubClient.PostedComment(
            inv.getArgument(1, String.class).startsWith("@Author") ? 77L : 78L, PR_URL + "#c"));
        when(github.updatePrCommentAsApp(anyLong(), anyString())).thenReturn(true);
    }

    @Test
    void twoPeoplesRunsOnOnePrAreBothToldToTheEnd() {
        command(1L, "Author");
        command(2L, "Reviewer");
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain(CHAIN, "finished", "SUCCESS"));
        when(tc.getBuildState("tc-r", CHAIN + 1)).thenReturn(chain(CHAIN + 1, "finished", "SUCCESS"));

        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("🏁 _Run finished"));
        verify(github).updatePrCommentAsApp(eq(78L), contains("🏁 _Run finished"));
    }

    @Test
    void aRunANewerOneReplacedSaysSo() {
        autoRerun();
        command(1L, "Author");
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain(CHAIN, "finished", "FAILURE"));
        when(tc.findRunAllBuildForPr("tc-a", PR)).thenReturn(Optional.of(finished(CHAIN + 5, "bob")));
        standing.settleRequested(PR);
        verify(tc, timeout(5_000)).findRunAllBuildForPr("tc-a", PR);
        awaitEnd();

        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("🏁 _Run finished — RunAll [" + (CHAIN + 5)
            + "](https://ci2.example/build/" + (CHAIN + 5) + ") replaced it before it settled; the newest verdict is on"
            + " [the checker's page](https://checker.example/?pr=" + PR + ")._"));
    }

    @Test
    void aRunWhosePrWasMergedSaysSo() {
        autoRerun();
        command(1L, "Author");
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain(CHAIN, "finished", "FAILURE"));
        when(github.openPrs()).thenReturn(List.of());
        when(github.pullState(PR)).thenReturn(Optional.of(new GithubClient.PullState("IGNITE-28999 Fix it", false,
            true)));

        commands.updateEtas();
        awaitEnd();
        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("🏁 _Run finished — the PR was merged before the re-runs"
            + " settled; the last known verdict is on [the checker's page](https://checker.example/?pr=" + PR + ")._"));
    }

    @Test
    void aNewRunAllCancelsTheWavesOfTheEarlierRun() {
        aWaveGoesForTheFirstRun();
        when(tc.triggerRunAll("tc-a", PR, false)).thenReturn(chain(CHAIN + 9, "queued", null));
        when(tc.cancelOwnBuilds(eq("tc-a"), eq(PR), eq("author"), any())).thenReturn(1);

        command(3L, "Author");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<TcModel.Build>> cancelled = ArgumentCaptor.forClass(Predicate.class);
        verify(tc).cancelOwnBuilds(eq("tc-a"), eq(PR), eq("author"), cancelled.capture());
        assertThat(cancelled.getValue().test(chain(9400100L, "running", null))).isTrue();
        assertThat(cancelled.getValue().test(chain(9400200L, "running", null))).isFalse();
        assertThat(standing.waveStatus(PR, CHAIN)).isEmpty();
        verify(github).updatePrCommentAsApp(eq(77L), contains("🛑 _Superseded by [a newer /run-all]"));
    }

    /** The earlier run had long finished: only the re-runs of its wave were cancelled, and the ack said the run was. */
    @Test
    void theAckOfANewRunAllSaysOnlyTheReRunsWereCancelled() {
        aWaveGoesForTheFirstRun();
        when(tc.triggerRunAll("tc-a", PR, false)).thenReturn(chain(CHAIN + 9, "queued", null));
        when(tc.cancelOwnBuilds(eq("tc-a"), eq(PR), eq("author"), any())).thenReturn(1);

        command(3L, "Author");

        verify(github).addPrCommentAsAppWithId(eq(PR),
            contains("The re-runs of your previous run were cancelled — this one supersedes it."));
        verify(github, never()).addPrCommentAsAppWithId(eq(PR), contains("Your previous run was cancelled"));
    }

    @Test
    void theStoryOfSomeoneWhoSwitchedEverythingOffGetsALastLine() {
        command(2L, "Reviewer");

        standing.disable("reviewer");
        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(78L), contains("🛑 _No longer followed: the options of the user who"
            + " started it were switched off._"));
    }

    @Test
    void aBuildTeamCityNoLongerHasEndsTheStory() {
        command(1L, "Author");
        when(tc.getBuildState("tc-a", CHAIN)).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("🛑 _TeamCity no longer has build " + CHAIN
            + ", so this story ends here._"));
    }

    @Test
    void bothStoriesOfOnePrSurviveARestart(@TempDir Path dir) throws Exception {
        command(1L, "Author");
        command(2L, "Reviewer");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        PrCommands restarted = commands();
        restarted.loadFrom(file);
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain(CHAIN, "finished", "SUCCESS"));
        when(tc.getBuildState("tc-r", CHAIN + 1)).thenReturn(chain(CHAIN + 1, "finished", "SUCCESS"));

        restarted.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("🏁 _Run finished"));
        verify(github).updatePrCommentAsApp(eq(78L), contains("🏁 _Run finished"));
        assertThat(mapper.readTree(file.toFile()).get("watching").get(String.valueOf(PR)).get("commentId").asLong())
            .isEqualTo(2L);
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }

    /** The author's runs are settled: they have auto re-run on, so their stories wait for it. */
    private void autoRerun() {
        standing.change("author", "tc-a", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
    }

    private void command(long id, String login) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, "/run-all",
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), new GithubClient.GhUser(login))));
        commands.poll();
    }

    /** A /run-all on a PR whose title names no ticket: the run is settled, and the story says why no visa came. */
    @Test
    void aRunOnAPrWithoutATicketSaysWhyNoVisaCame() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "For TC debug", null, null, null, null)));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc-a", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null, null));
        command(1L, "Author");
        when(tc.findRunAllBuildForPr("tc-a", PR)).thenReturn(Optional.of(finished(CHAIN, "author")));
        when(analyzer.analyzeForAction("tc-a", PR)).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13440/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140, 1,
            false, 0, false, 0, 0, 0, 1, 0)));
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain(CHAIN, "finished", "SUCCESS"));
        standing.settleRequested(PR);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!standing.buildHandled("author", PR, CHAIN)) {
            assertThat(System.currentTimeMillis()).as("the run is settled").isLessThan(deadline);
            Thread.onSpinWait();
        }

        commands.updateEtas();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).updatePrCommentAsApp(eq(77L), contains("No JIRA visa: the PR title names no IGNITE ticket."));
    }

    /** The author's /run-all finished red, and the settle the story asked for queued the run's wave. */
    private void aWaveGoesForTheFirstRun() {
        autoRerun();
        command(1L, "Author");
        when(tc.findRunAllBuildForPr("tc-a", PR)).thenReturn(Optional.of(finished(CHAIN, "author")));
        when(analyzer.analyzeForAction("tc-a", PR)).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13440/head", System.currentTimeMillis(), List.of(new TestVerdict(1L, "Cache1Test.test", "Cache1", 2L,
                "Cache 1", "o1", true, false, "not seen failing in 100 master run(s)", "F", 1)), List.of(), List.of(),
            List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0)));
        when(tc.triggerBuildReplacingQueued(eq("tc-a"), eq("Cache1"), eq(PR), anyBoolean(), anyString()))
            .thenReturn(chain(9400100L, "queued", null));
        standing.settleRequested(PR);
        verify(tc, timeout(5_000)).triggerBuildReplacingQueued(eq("tc-a"), eq("Cache1"), eq(PR), anyBoolean(),
            anyString());
        awaitWave();
    }

    /** Waits for the settle the story asked for to queue the run's wave. */
    private void awaitWave() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (standing.waveStatus(PR, CHAIN).isEmpty()) {
            assertThat(System.currentTimeMillis()).as("the wave is recorded").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    /** Waits for the settle the story asked for to learn how the run ended. */
    private void awaitEnd() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (standing.runEnd(PR, CHAIN).isEmpty()) {
            assertThat(System.currentTimeMillis()).as("the run's end is known").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static TcModel.Build finished(long id, String by) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/13440/head", null, null, null, null, null, null,
            null, null, new TcModel.Triggered("user", new TcModel.User(by)), null, null, null, null, null);
    }

    private static TcModel.Build chain(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/13440/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
