package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The PR comment and the visa came out the moment the first wave of re-runs was queued ("2 blockers… re-run in
 * progress") and the final verdict arrived as an edit, which GitHub tells nobody about: in 11 of 31 edited
 * comments the green result showed up only that way. IGNITE-28867 holds 15 comments, all of them the checker's
 * visas; a visa went out while a newer run of the PR was still going; and a verdict of code pushed over since did
 * not say which revision it was.
 */
class OneVerdictPerRunTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final PendingCommits pending = mock(PendingCommits.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer, jira,
        new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"), tracker,
        mock(Warmer.class), pending);

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        when(tc.buildRevision(eq("tc"), anyLong())).thenReturn(Optional.of("9f1c2e7a"));
        finished(RUN_ALL);
    }

    @Test
    void nothingIsPostedWhileTheWavesGoAndTheSettledVerdictComesOnce() {
        options(true);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL, blocker("Cache1"))));

        standing.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(PR), anyBoolean(), anyString());
        verify(github, never()).addPrComment(anyString(), anyInt(), anyString());
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());

        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL)));
        standing.sweep();
        standing.sweep();

        verify(github, times(1)).addPrComment(eq("gh-pat"), eq(PR),
            contains("Settled after 1 auto re-run wave(s): #1 — 1 blocker suite(s)."));
        verify(jira, times(1)).addCommentWithId(eq("jira-pat"), eq(ISSUE), contains("*No blockers*"));
        verify(github, never()).updatePrComment(anyString(), anyLong(), anyString());
    }

    @Test
    void noVerdictIsPostedWhileANewerRunOfThePrGoes() {
        options(false);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL, blocker("Cache1"))));
        when(tracker.newestChainUnderWay(PR)).thenReturn(RUN_ALL + 500);

        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github, never()).addPrComment(anyString(), anyInt(), anyString());
        assertThat(standing.buildHandled(USER, PR, RUN_ALL)).isFalse();

        when(tracker.newestChainUnderWay(PR)).thenReturn(0L);
        standing.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
    }

    /** The verdict was computed while the newer RunAll ran, and TeamCity says it still does. */
    @Test
    void aNewerRunTheVerdictSawGoingHoldsItBack() {
        options(false);
        AnalysisResult live = new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(),
            List.of(blocker("Cache1")), List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, true,
            RUN_ALL + 500, 0, 0, 1, 0);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(live));
        when(tc.getBuildState("tc", RUN_ALL + 500)).thenReturn(chain(RUN_ALL + 500, "running", null));

        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
    }

    @Test
    void theSameVerdictOfTheSameRevisionIsNotPostedAgain() {
        options(false);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL, blocker("Cache1"))));
        standing.sweep();

        long again = RUN_ALL + 900;
        finished(again);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(again, blocker("Cache1"))));
        standing.sweep();

        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(standing.visaCover(USER, PR, again, ISSUE)).isEqualTo(StandingVisas.VisaCover.POSTED);

        long fixed = RUN_ALL + 1800;
        finished(fixed);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(fixed)));
        standing.sweep();

        verify(jira, times(2)).addCommentWithId(anyString(), anyString(), anyString());
    }

    @Test
    void aVerdictOfOlderCodeNamesBothRevisions() {
        options(false);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL)));
        when(pending.since("tc", PR, RUN_ALL)).thenReturn(new PendingCommits.Ahead(2, "9f1c2e7", "41b0d3a", "9f1c2e7a3d5b8c0e1f2a4b6c8d0e2f4a6b8c0d2e", false));

        standing.sweep();

        ArgumentCaptor<String> visa = ArgumentCaptor.forClass(String.class);
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), visa.capture());
        assertThat(visa.getValue()).contains("2 commit(s) pushed since this run")
            .contains("_Tested revision {{9f1c2e7}}; the PR head is now {{41b0d3a}}._");
        verify(github).addPrComment(eq("gh-pat"), eq(PR),
            contains("_Tested revision `9f1c2e7`; the PR head is now `41b0d3a`._"));
    }

    private void options(boolean rerun) {
        standing.change(USER, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, rerun, true, false, null));
    }

    private void finished(long buildId) {
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(buildId, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
    }

    private static TcModel.Build chain(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/13335/head", "IgniteTests24Java8_RunAll", null, null, null,
            null, null, null, null, null, null, null, null, null, null);
    }

    private static TestVerdict blocker(String suite) {
        return new TestVerdict(9389011L, suite + "Test.test", suite, 9389011L, suite, "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(long buildId, TestVerdict... blockers) {
        return new AnalysisResult(PR, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
