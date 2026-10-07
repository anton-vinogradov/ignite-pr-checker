package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Every 10 minutes the sweep looked up the latest finished RunAll of each of the 50 listed PRs, about 300 TeamCity
 * calls an hour to learn, nearly always, that nothing had changed. It now asks once which PRs had a RunAll finish
 * since its last pass, and looks up only those and the PRs still waiting for something: a wave of re-runs, a
 * comment to mark, a settle that stopped short. Once an hour, and whenever TeamCity cannot say, it looks every PR up
 * as before.
 */
class SweepAsksWhatFinishedTest {
    private static final String USER = "avinogradov";

    private static final int OWN = 13335;

    private static final int OTHERS = 13654;

    private static final int NO_RUN = 13800;

    private static final long RUN_ALL = 9389046L;

    /** Before the options were switched on: the sweep only marks such a run handled. */
    private static final String LAST_WEEK = "20260930T120000+0000";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        tracker, mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.openPrs()).thenReturn(List.of(pr(OWN), pr(OTHERS), pr(NO_RUN)));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, LAST_WEEK)));
        when(tc.findRunAllBuildForPr("tc", OTHERS)).thenReturn(Optional.of(chain(RUN_ALL + 1, "someone", LAST_WEEK)));
        when(tc.findRunAllBuildForPr("tc", NO_RUN)).thenReturn(Optional.empty());
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(null, true, true, false, null));
    }

    @Test
    void onlyThePrsWhoseRunAllFinishedAreLookedUpAgain() {
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()),
            Optional.of(Set.of(OTHERS)));

        long first = System.currentTimeMillis();
        standing.sweep();
        standing.sweep();
        standing.sweep();

        verify(tc, times(1)).findRunAllBuildForPr("tc", OWN);
        verify(tc, times(2)).findRunAllBuildForPr("tc", OTHERS);
        verify(tc, times(1)).findRunAllBuildForPr("tc", NO_RUN);

        ArgumentCaptor<Long> since = ArgumentCaptor.forClass(Long.class);
        verify(tc, times(2)).prsWithChainsFinishedAfter(eq("tc"), since.capture());
        assertThat(since.getAllValues().get(0)).as("from 5 minutes before the first sweep")
            .isBetween((first - 300_000) / 1000, (first - 300_000) / 1000 + 5);
    }

    @Test
    void withoutAnAnswerFromTeamCityEveryPrIsLookedUp() {
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.empty())
            .thenThrow(new IllegalStateException("TeamCity answered 502"));

        standing.sweep();
        standing.sweep();
        standing.sweep();

        verify(tc, times(3)).findRunAllBuildForPr("tc", OWN);
        verify(tc, times(3)).findRunAllBuildForPr("tc", OTHERS);
        verify(tc, times(3)).findRunAllBuildForPr("tc", NO_RUN);
    }

    @Test
    void everySixthSweepLooksEveryPrUp() {
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()));

        for (int i = 0; i < 7; i++)
            standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr("tc", OWN);
        verify(tc, times(5)).prsWithChainsFinishedAfter(eq("tc"), anyLong());
    }

    /** Its re-runs settle the run: nothing finished as a RunAll, yet the sweep has to go on looking at the PR. */
    @Test
    void aPrWithAWaveGoingIsLookedUpEverySweep() {
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL, blocker())));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(OWN), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        when(tracker.hasActive(OWN)).thenReturn(false, true);

        standing.sweep();
        standing.sweep();
        standing.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(OWN), anyBoolean(), anyString());
        verify(tc, times(3)).findRunAllBuildForPr("tc", OWN);
        verify(tc, times(1)).findRunAllBuildForPr("tc", OTHERS);
    }

    /**
     * The newer run's verdict went out, but GitHub failed the edit that marks the older verdict comment superseded:
     * the PR stays looked up until the mark is made.
     */
    @Test
    void aCommentStillToBeMarkedKeepsItsPrLookedUp() {
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of(OWN)),
            Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL)));
        when(github.addPrComment(eq("gh-pat"), eq(OWN), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"),
                new GithubClient.PostedComment(556L, "https://github.com/apache/ignite/pull/13335#c556"));
        standing.sweep();

        long newer = RUN_ALL + 900;
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(newer, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(newer)));
        when(github.commentBody(555L)).thenThrow(new IllegalStateException("GitHub answered 502"))
            .thenReturn(Optional.of("verdict of " + RUN_ALL));
        standing.sweep();
        standing.sweep();
        standing.sweep();

        verify(tc, times(3)).findRunAllBuildForPr("tc", OWN);
        verify(github).updatePrComment(eq("gh-pat"), eq(555L), anyString());
    }

    /** TeamCity errors left part of the verdict unchecked: the next sweep tries it again, not the hourly pass. */
    @Test
    void aVerdictLeftUncheckedKeepsItsPrLookedUp() {
        commentsOnly();
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL)));
        when(analyzer.stillRetrying(any())).thenReturn(true, false);
        when(github.addPrComment(eq("gh-pat"), eq(OWN), anyString())).thenReturn(comment());

        standing.sweep();
        standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr("tc", OWN);
        verify(github).addPrComment(eq("gh-pat"), eq(OWN), anyString());
    }

    /** Right after the chain finished, the analysis still named the run before it: the run waits for its own. */
    @Test
    void aVerdictOfTheRunBeforeKeepsItsPrLookedUp() {
        commentsOnly();
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL - 900)),
            Optional.of(verdict(RUN_ALL)));
        when(analyzer.forceRefresh("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL - 900)));
        when(github.addPrComment(eq("gh-pat"), eq(OWN), anyString())).thenReturn(comment());

        standing.sweep();
        standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr("tc", OWN);
        verify(github).addPrComment(eq("gh-pat"), eq(OWN), anyString());
    }

    /** The settle failed on a TeamCity error: the next sweep settles the run, not the hourly pass. */
    @Test
    void aFailedSettleKeepsItsPrLookedUp() {
        commentsOnly();
        when(tc.prsWithChainsFinishedAfter(eq("tc"), anyLong())).thenReturn(Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr("tc", OWN)).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenThrow(new IllegalStateException("TeamCity answered 502"))
            .thenReturn(Optional.of(verdict(RUN_ALL)));
        when(github.addPrComment(eq("gh-pat"), eq(OWN), anyString())).thenReturn(comment());

        standing.sweep();
        standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr("tc", OWN);
        verify(github).addPrComment(eq("gh-pat"), eq(OWN), anyString());
    }

    /**
     * TeamCity refused the token of the run's starter, which pauses their options, and another user's token keeps
     * the sweep going. The sweep after the starter logs in again posts the verdict, not the hourly pass.
     */
    @Test
    void aPausedTokenKeepsItsPrLookedUp() {
        standing.change("zstan", "tc-zstan", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        standing.markTcRejected(USER);
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Set.of()));
        when(tc.findRunAllBuildForPr(anyString(), eq(OWN))).thenReturn(Optional.of(chain(RUN_ALL, USER, null)));
        when(analyzer.analyzeForAction("tc", OWN)).thenReturn(Optional.of(verdict(RUN_ALL)));
        when(github.addPrComment(eq("gh-pat"), eq(OWN), anyString())).thenReturn(comment());

        standing.sweep();
        standing.tcTokenAccepted(USER, "tc");
        standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr(anyString(), eq(OWN));
        verify(github).addPrComment(eq("gh-pat"), eq(OWN), anyString());
    }

    /** Without re-runs no wave or decision keeps the PR waiting: only how its last settle ended does. */
    private void commentsOnly() {
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(null, false, null, null, null));
    }

    private static GithubClient.PostedComment comment() {
        return new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555");
    }

    private static PrSummary pr(int number) {
        return new PrSummary(number, "IGNITE-28867 Hot reload of SSL certificates", null, null, null, null);
    }

    private static TcModel.Build chain(long id, String by, String finishDate) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/" + id + "/head", null, null, null, null, finishDate,
            null, null, null, new TcModel.Triggered("user", new TcModel.User(by)), null, null, null, null, null);
    }

    private static TestVerdict blocker() {
        return new TestVerdict(9389011L, "Cache1Test.test", "Cache1", 9389011L, "Cache1", "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(long buildId, TestVerdict... blockers) {
        return new AnalysisResult(OWN, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
