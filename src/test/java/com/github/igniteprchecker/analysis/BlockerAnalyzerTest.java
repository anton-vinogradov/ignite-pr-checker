package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Deterministic, offline test of the blocker/noise classification on synthetic master history. */
class BlockerAnalyzerTest {
    private static final String TOK = "tok";

    private final TcClient tc = mock(TcClient.class);
    private final ChainCollector chains = mock(ChainCollector.class);
    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(4), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new com.fasterxml.jackson.databind.ObjectMapper()),
        new RunDeltaStore(new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    void blockerIsAPrFailureCleanOnMasterAndStillFailingInLastRun() {
        FailedTest cleanBreak = new FailedTest(1, "Suite: A.blockerTest", "SuiteA", 101L, "Suite A", "1001");
        FailedTest rareMasterFail = new FailedTest(2, "Suite: B.rareMasterFail", "SuiteB", 102L, "Suite B", "1002");
        FailedTest preExisting = new FailedTest(3, "Suite: C.brokenInMaster", "SuiteC", 103L, "Suite C", "1003");
        FailedTest brandNew = new FailedTest(4, "Suite: D.newTest", "SuiteD", 104L, "Suite D", "1004");
        FailedTest passedOnRerun = new FailedTest(5, "Suite: E.reranAndPassed", "SuiteE", 105L, "Suite E", "1005");

        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999, "pull/42/head",
            List.of(cleanBreak, rareMasterFail, preExisting, brandNew, passedOnRerun), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));

        // 1) clean on master and still failing in its last finished run -> blocker.
        when(tc.getBaseBranchHistory(TOK, 1)).thenReturn(repeat("SUCCESS", 50));
        when(tc.prBranchRuns(TOK, 42, 1)).thenReturn(repeat("FAILURE", 1));
        // 2) a single failure in master history -> not PR-specific -> filtered.
        when(tc.getBaseBranchHistory(TOK, 2)).thenReturn(concat(repeat("SUCCESS", 49), repeat("FAILURE", 1)));
        // 3) fails often in master -> pre-existing -> filtered.
        when(tc.getBaseBranchHistory(TOK, 3)).thenReturn(concat(repeat("FAILURE", 10), repeat("SUCCESS", 40)));
        // 4) no master history at all -> can't prove pre-existing -> blocker.
        when(tc.getBaseBranchHistory(TOK, 4)).thenReturn(List.of());
        when(tc.prBranchRuns(TOK, 42, 4)).thenReturn(repeat("FAILURE", 1));
        // 5) clean on master but its last finished run passed (a re-run) -> not reproducible -> filtered.
        when(tc.getBaseBranchHistory(TOK, 5)).thenReturn(repeat("SUCCESS", 50));
        when(tc.prBranchRuns(TOK, 42, 5)).thenReturn(repeat("SUCCESS", 1));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(1L, 4L);
        assertThat(r.filtered()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(2L, 3L, 5L);

        assertThat(verdict(r, 1).reason()).contains("50 master run");
        assertThat(verdict(r, 2).reason()).contains("1/50");
        assertThat(verdict(r, 3).reason()).contains("pre-existing");
        assertThat(verdict(r, 4).reason()).contains("no master history");
        assertThat(verdict(r, 5).reason()).contains("last finished run");
    }

    @Test
    void mergeOfLastRunsSeparatesSteadyFromFlapAndWatch() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        FailedTest steady = new FailedTest(1L, "Steady", "S", 10L, "S", "o1");
        FailedTest flap = new FailedTest(2L, "Flap", "S", 10L, "S", "o2");
        FailedTest watch = new FailedTest(3L, "Watch", "S", 10L, "S", "o3");
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999,
            "pull/42/head", List.of(steady, flap, watch), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));

        for (long id : new long[] {1, 2, 3})
            when(tc.getBaseBranchHistory(TOK, id)).thenReturn(repeat("SUCCESS", 50)); // all clean on master
        when(tc.prBranchRuns(TOK, 42, 1)).thenReturn(repeat("FAILURE", 3));                    // FFF -> steady block
        when(tc.prBranchRuns(TOK, 42, 2)).thenReturn(concat(repeat("SUCCESS", 4), repeat("FAILURE", 1))); // PPPPF -> flap
        when(tc.prBranchRuns(TOK, 42, 3)).thenReturn(concat(repeat("SUCCESS", 3), repeat("FAILURE", 2))); // PPPFF -> watch

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::testId).containsExactly(1L);
        assertThat(r.watch()).extracting(TestVerdict::testId).containsExactly(3L);
        assertThat(r.filtered()).extracting(TestVerdict::testId).containsExactly(2L);
    }

    /**
     * The apache/ignite PR #13421 false negative: 38 deterministic failures whose only green branch run
     * was on the revision BEFORE the buggy commits. The revision-blind merge called that "passed on the
     * same code" and filtered them, so the bot posted a green visa on a red RunAll and never re-ran.
     */
    @Test
    void aPassOnAnEarlierRevisionNeverClearsAFailureOnTheCurrentOne() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("SUCCESS", 9243554L, "7e61596b0e1"),   // suite #1607 — before the buggy commits
            run("FAILURE", 9244697L, "3fdf4460a9c"))); // suite #1609 — the PR head

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.filtered()).isEmpty();
        assertThat(only(r.watch()).reason()).isEqualTo("first failure on revision 3fdf446 — watch (nothing has "
            + "passed on this code; the 1 earlier branch run ran on other code)");
        assertThat(only(r.watch()).codeRuns()).isEqualTo(1); // only the last bar is evidence
    }

    /** The re-run that a watch item triggers: failing again on the SAME revision proves the break. */
    @Test
    void aConfirmingRerunOnTheSameRevisionTurnsAWatchIntoABlocker() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("SUCCESS", 9243554L, "7e61596b0e1"),
            run("FAILURE", 9244697L, "3fdf4460a9c"),
            run("FAILURE", 9244800L, "3fdf4460a9c"))); // the auto re-run failed again

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.watch()).isEmpty();
        assertThat(only(r.blockers()).reason()).contains("failed all 2 runs on revision 3fdf446");
    }

    /** A pass on the very same revision is real evidence of a flake, and still filters the test out. */
    @Test
    void aPassOnTheSameRevisionStillFiltersItAsAFlapOnThisCode() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("SUCCESS", 9243554L, "7e61596b0e1"),   // other code — counts for nothing either way
            run("SUCCESS", 9244697L, "3fdf4460a9c"),   // passed on THIS code…
            run("FAILURE", 9244800L, "3fdf4460a9c"))); // …and then failed on it

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(r.watch()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("flaky on branch: failed only the latest of 2 runs on "
            + "revision 3fdf446 (an earlier run on the same code passed; the 1 earlier branch run ran on other code)");
    }

    /** Failing on the old revision AND on the new one is a steady break; the narrower window must not lose it. */
    @Test
    void failingOnBothRevisionsStaysABlocker() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("FAILURE", 9243554L, "7e61596b0e1"),
            run("FAILURE", 9244697L, "3fdf4460a9c")));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::testId).containsExactly(1L);
    }

    /** TeamCity may not inline revisions into a test occurrence — then ask for the build itself. */
    @Test
    void revisionsAreFetchedPerBuildWhenTheOccurrenceDoesNotCarryThem() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("SUCCESS", 9243554L, null), run("FAILURE", 9244697L, null)));
        when(tc.buildRevision(TOK, 9243554L)).thenReturn(Optional.of("7e61596b0e1"));
        when(tc.buildRevision(TOK, 9244697L)).thenReturn(Optional.of("3fdf4460a9c"));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(only(r.watch()).reason()).contains("first failure on revision 3fdf446");
    }

    /**
     * A revision we cannot read must never widen the window back over runs we cannot place — that is
     * the revision-blind merge again, and it lands on the green side, which is the bug being fixed.
     */
    @Test
    void anUnreadableRevisionForTheLatestRunDoesNotRestoreTheGreenVerdict() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(List.of(
            run("SUCCESS", 9243554L, "7e61596b0e1"), run("FAILURE", 9244697L, null)));
        when(tc.buildRevision(TOK, 9244697L)).thenReturn(Optional.empty()); // TeamCity has none for it

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.filtered()).isEmpty();
        assertThat(only(r.watch()).reason()).doesNotContain("passed on the same code");
    }

    /** No revisions anywhere: keep the old whole-strip merge, but never claim runs were on the same code. */
    @Test
    void noRevisionsAnywhereKeepsTheOldWindowWithoutClaimingSameCode() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L)).thenReturn(concat(repeat("SUCCESS", 3), repeat("FAILURE", 1)));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(only(r.filtered()).reason()).isEqualTo("flaky on branch: failed only the latest of 4 runs "
            + "(passed just before, but TeamCity gave no revisions to prove that was the same code)");
    }

    @Test
    void emptyWhenNoRunAllBuild() {
        when(chains.findBuildId(TOK, 7)).thenReturn(Optional.empty());

        assertThat(analyzer.analyze(TOK, 7)).isEmpty();
    }

    @Test
    void secondAnalyzeOfSameBuildIsServedFromCache() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999, "pull/42/head", List.of(), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));

        analyzer.analyze(TOK, 42);
        analyzer.analyze(TOK, 42);

        verify(chains, times(1)).collectForBuild(eq(TOK), eq(42), eq(999L), any()); // second run reused the cached result
    }

    private static TestVerdict verdict(AnalysisResult r, long testId) {
        return concat(r.blockers(), r.filtered()).stream()
            .filter(v -> v.testId() == testId).findFirst().orElseThrow();
    }

    private static TestVerdict only(List<TestVerdict> verdicts) {
        assertThat(verdicts).hasSize(1);

        return verdicts.get(0);
    }

    /** Wires up a PR whose chain has exactly one failed test, clean on master. */
    private FailedTest singleFailure(long testId) {
        FailedTest t = new FailedTest(testId, "CalciteSql2: T.test", "CalciteSql2", 9244697L, "Calcite SQL 2", "o1");
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999,
            "pull/42/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, testId)).thenReturn(repeat("SUCCESS", 100));

        return t;
    }

    /** One finished branch run: its status, the suite build it ran in, and that build's revision. */
    private static TcModel.TestOccurrence run(String status, long buildId, String revision) {
        TcModel.Revisions revs = revision == null ? null
            : new TcModel.Revisions(List.of(new TcModel.Revision(revision)));

        return new TcModel.TestOccurrence(null, null, status, null,
            new TcModel.BuildRef(buildId, "pull/42/head", "finished", status, "CalciteSql2", null, revs), null);
    }

    private static List<TcModel.TestOccurrence> repeat(String status, int n) {
        List<TcModel.TestOccurrence> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(new TcModel.TestOccurrence(null, null, status, null, null, null));
        return l;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> l : lists)
            out.addAll(l);
        return out;
    }
}
