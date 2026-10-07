package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Deterministic, offline test of the blocker/noise classification on synthetic master history. */
class BlockerAnalyzerTest {
    private static final String TOK = "tok";

    /** apache/ignite#13335: a C++ test that runs in three suites of one chain (RunAll 9389046). */
    private static final long RECONNECT = 5272433775095107011L;
    private static final String WIN = "IgniteTests24Java8_PlatformCCMakeWinX64Release";
    private static final String LINUX = "IgniteTests24Java8_PlatformCPPCMakeLinux";
    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

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
        when(tc.getBaseBranchHistory(TOK, 1, "SuiteA")).thenReturn(repeat("SUCCESS", 50));
        when(tc.prBranchRuns(TOK, 42, 1, "SuiteA")).thenReturn(repeat("FAILURE", 1));
        // 2) a single failure in master history -> not PR-specific -> filtered.
        when(tc.getBaseBranchHistory(TOK, 2, "SuiteB")).thenReturn(concat(repeat("SUCCESS", 49), repeat("FAILURE", 1)));
        // 3) fails often in master -> pre-existing -> filtered.
        when(tc.getBaseBranchHistory(TOK, 3, "SuiteC")).thenReturn(concat(repeat("FAILURE", 10), repeat("SUCCESS", 40)));
        // 4) no master history at all -> can't prove pre-existing -> blocker.
        when(tc.getBaseBranchHistory(TOK, 4, "SuiteD")).thenReturn(List.of());
        when(tc.prBranchRuns(TOK, 42, 4, "SuiteD")).thenReturn(repeat("FAILURE", 1));
        // 5) clean on master but its last finished run passed (a re-run) -> not reproducible -> filtered.
        when(tc.getBaseBranchHistory(TOK, 5, "SuiteE")).thenReturn(repeat("SUCCESS", 50));
        when(tc.prBranchRuns(TOK, 42, 5, "SuiteE")).thenReturn(repeat("SUCCESS", 1));

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
            when(tc.getBaseBranchHistory(TOK, id, "S")).thenReturn(repeat("SUCCESS", 50)); // all clean on master
        when(tc.prBranchRuns(TOK, 42, 1, "S")).thenReturn(repeat("FAILURE", 3));                    // FFF -> steady block
        when(tc.prBranchRuns(TOK, 42, 2, "S")).thenReturn(concat(repeat("SUCCESS", 4), repeat("FAILURE", 1))); // PPPPF -> flap
        when(tc.prBranchRuns(TOK, 42, 3, "S")).thenReturn(concat(repeat("SUCCESS", 3), repeat("FAILURE", 2))); // PPPFF -> watch

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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
            run("FAILURE", 9243554L, "7e61596b0e1"),
            run("FAILURE", 9244697L, "3fdf4460a9c")));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::testId).containsExactly(1L);
    }

    /** TeamCity may not inline revisions into a test occurrence — then ask for the build itself. */
    @Test
    void revisionsAreFetchedPerBuildWhenTheOccurrenceDoesNotCarryThem() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
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
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(concat(repeat("SUCCESS", 3), repeat("FAILURE", 1)));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(only(r.filtered()).reason()).isEqualTo("flaky on branch: failed only the latest of 4 runs "
            + "(passed just before, but TeamCity gave no revisions to prove that was the same code)");
    }

    /**
     * TeamCity reports an ignored test as status UNKNOWN. Nothing passed in such a run, so it must not
     * clear the failure before it as "passed on re-run".
     */
    @Test
    void anIgnoredLatestRunDoesNotClearTheFailure() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
            run("FAILURE", 9244697L, "3fdf4460a9c"),
            run("UNKNOWN", 9244800L, "3fdf4460a9c")));

        TestVerdict v = verdict(analyzer.analyze(TOK, 42).orElseThrow(), 1L);

        assertThat(v.reason()).doesNotContain("passed on re-run");
        assertThat(v.blocker()).isTrue();
        assertThat(v.branchRuns()).isEqualTo("F");
        assertThat(v.suiteBuildId()).as("anchored on the run that failed, not on the ignored one").isEqualTo(9244697L);
    }

    /** An ignored run is no bar on the strip either: it neither passed nor failed. */
    @Test
    void anIgnoredRunIsLeftOutOfTheStrip() {
        singleFailure(1L);
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(
            run("SUCCESS", 9243554L, "3fdf4460a9c"),
            run("UNKNOWN", 9244697L, "3fdf4460a9c"),
            run("FAILURE", 9244800L, "3fdf4460a9c")));

        TestVerdict v = verdict(analyzer.analyze(TOK, 42).orElseThrow(), 1L);

        assertThat(v.branchRuns()).isEqualTo("PF");
        assertThat(v.reason()).isEqualTo("flaky on branch: failed only the latest of 2 runs on revision 3fdf446 "
            + "(an earlier run on the same code passed)");
    }

    /**
     * Master history lists ignored runs (UNKNOWN) as well. The test did not run there, so they are no
     * master runs of it: counted, three real runs read "not seen failing in 100 master run(s)".
     */
    @Test
    void ignoredMasterRunsAreNotCountedAsMasterRuns() {
        singleFailure(1L);
        when(tc.getBaseBranchHistory(TOK, 1L, "CalciteSql2"))
            .thenReturn(concat(repeat("UNKNOWN", 97), repeat("SUCCESS", 3)));
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(run("FAILURE", 9244697L, "3fdf4460a9c")));

        TestVerdict v = verdict(analyzer.analyze(TOK, 42).orElseThrow(), 1L);

        assertThat(v.reason()).isEqualTo("not seen failing in 3 master run(s); failed the only run on this branch");
        assertThat(v.blocker()).isTrue();
    }

    /** A test ignored in every master run of the window has no master history to prove it pre-existing by. */
    @Test
    void aTestIgnoredInEveryMasterRunHasNoMasterHistory() {
        singleFailure(1L);
        when(tc.getBaseBranchHistory(TOK, 1L, "CalciteSql2")).thenReturn(repeat("UNKNOWN", 100));
        when(tc.prBranchRuns(TOK, 42, 1L, "CalciteSql2")).thenReturn(List.of(run("FAILURE", 9244697L, "3fdf4460a9c")));

        TestVerdict v = verdict(analyzer.analyze(TOK, 42).orElseThrow(), 1L);

        assertThat(v.reason())
            .isEqualTo("no master history (can't prove pre-existing); failed the only run on this branch");
        assertThat(v.blocker()).isTrue();
    }

    /** One real master failure among ignored runs still makes the test pre-existing, out of its real runs. */
    @Test
    void aMasterFailureAmongIgnoredRunsStillMakesItPreExisting() {
        singleFailure(1L);
        when(tc.getBaseBranchHistory(TOK, 1L, "CalciteSql2"))
            .thenReturn(concat(repeat("UNKNOWN", 96), repeat("FAILURE", 1), repeat("SUCCESS", 3)));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("pre-existing: fails 1/4 on master");
    }

    /**
     * The runs of a test in sibling suites are not re-runs of it. With the failing Clang build holding
     * the lowest id of the three, the Windows pass read as "passed on re-run", dropped the failure and
     * anchored the row on the Windows build.
     */
    @Test
    void aPassInASiblingSuiteIsNotAReRun() {
        chainFailing(13335, reconnectIn(CLANG, 9388970L));
        masterHistory(RECONNECT, new SuiteHistory(CLANG, 33, 0), new SuiteHistory(LINUX, 34, 0),
            new SuiteHistory(WIN, 33, 0));
        branchRuns(13335, RECONNECT,
            run("FAILURE", 9388970L, CLANG, "ec2c458"),
            run("SUCCESS", 9388971L, LINUX, "ec2c458"),
            run("SUCCESS", 9388972L, WIN, "ec2c458"));

        TestVerdict v = verdict(analyzer.analyze(TOK, 13335).orElseThrow(), RECONNECT);

        assertThat(v.reason()).doesNotContain("passed on re-run");
        assertThat(v.blocker()).isTrue();
        assertThat(v.branchRuns()).isEqualTo("F");
        assertThat(v.suite()).isEqualTo(CLANG);
        assertThat(v.suiteBuildId()).isEqualTo(9388970L);
    }

    /**
     * Master history is per suite too: a break on Linux, where master is clean, was filtered as
     * pre-existing because Windows fails the same test now and then.
     */
    @Test
    void masterFailuresInASiblingSuiteDoNotMakeAFailurePreExisting() {
        chainFailing(13335, reconnectIn(LINUX, 9388971L));
        masterHistory(RECONNECT, new SuiteHistory(CLANG, 33, 0), new SuiteHistory(LINUX, 34, 0),
            new SuiteHistory(WIN, 33, 3));
        branchRuns(13335, RECONNECT,
            run("SUCCESS", 9388970L, WIN, "ec2c458"),
            run("FAILURE", 9388971L, LINUX, "ec2c458"),
            run("SUCCESS", 9388972L, CLANG, "ec2c458"));

        TestVerdict v = verdict(analyzer.analyze(TOK, 13335).orElseThrow(), RECONNECT);

        assertThat(v.reason()).isEqualTo("not seen failing in 34 master run(s); failed the only run on this branch");
        assertThat(v.blocker()).isTrue();
    }

    /** The master-history cache is per suite as well, or the first suite asked answers for all of them. */
    @Test
    void masterHistoryIsCachedPerSuite() {
        masterHistory(RECONNECT, new SuiteHistory(CLANG, 33, 7), new SuiteHistory(LINUX, 34, 0),
            new SuiteHistory(WIN, 33, 0));
        chainFailing(13335, reconnectIn(CLANG, 9388972L));
        branchRuns(13335, RECONNECT, run("FAILURE", 9388972L, CLANG, "ec2c458"));
        chainFailing(13336, reconnectIn(LINUX, 9390001L));
        branchRuns(13336, RECONNECT, run("FAILURE", 9390001L, LINUX, "1a2b3c4"));

        TestVerdict clang = verdict(analyzer.analyze(TOK, 13335).orElseThrow(), RECONNECT);
        TestVerdict linux = verdict(analyzer.analyze(TOK, 13336).orElseThrow(), RECONNECT);

        assertThat(clang.reason()).isEqualTo("pre-existing: fails 7/33 on master");
        assertThat(linux.reason()).isEqualTo("not seen failing in 34 master run(s); failed the only run on this branch");
    }

    @Test
    void emptyWhenNoRunAllBuild() {
        when(chains.findBuildId(TOK, 7)).thenReturn(Optional.empty());

        assertThat(analyzer.analyze(TOK, 7)).isEmpty();
    }

    /**
     * PR 13440: a green re-run of the blocker suite finished at 03:03 and the page still showed the
     * 20 blockers hours later — the chain build hadn't changed, so the warm cycle kept skipping it.
     * The watermark is what makes "same chain" stop meaning "same answer".
     */
    @Test
    void warmRecomputesWhenTheBranchFinishedBuildsTheVerdictNeverSaw() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(tc.branchFinishedAfter(eq(TOK), eq(42), anyLong())).thenReturn(false);
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999,
            "pull/42/head", List.of(), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));

        assertThat(analyzer.warm(TOK, 42)).as("first warm computes").isTrue();
        assertThat(analyzer.warm(TOK, 42)).as("nothing moved on the branch: the cached verdict stands").isFalse();

        when(tc.branchFinishedAfter(eq(TOK), eq(42), anyLong())).thenReturn(true); // a suite re-ran
        assertThat(analyzer.warm(TOK, 42)).as("a build the verdict never saw must trigger a recompute").isTrue();
    }

    /** The watermark is the analysis start, less the skew margin — not the moment the result is stored. */
    @Test
    void theWatermarkIsTakenBeforeTheAnalysisReadsAnything() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        long[] readAt = new long[1];
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenAnswer(inv -> {
            readAt[0] = System.currentTimeMillis() / 1000;
            Thread.sleep(1100); // a slow analysis: stamping at the end would land a second later
            return new ChainCollector.Chain(999, "pull/42/head", List.of(), List.of(), List.of(), 0, 0, false, 0,
                false, 0, 0, 0, 0);
        });

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.branchWatermarkAt()).isBetween(readAt[0] - 62, readAt[0] - 60);
    }

    /** A blip asking TeamCity must not make every warm cycle recompute every PR. */
    @Test
    void warmKeepsTheVerdictWhenTeamCityCannotSayWhetherTheBranchMoved() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999,
            "pull/42/head", List.of(), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        analyzer.warm(TOK, 42);
        when(tc.branchFinishedAfter(eq(TOK), eq(42), anyLong())).thenThrow(new IllegalStateException("ci2 timeout"));

        assertThat(analyzer.warm(TOK, 42)).isFalse();
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
        return concat(r.blockers(), r.watch(), r.filtered()).stream()
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
        when(tc.getBaseBranchHistory(TOK, testId, "CalciteSql2")).thenReturn(repeat("SUCCESS", 100));

        return t;
    }

    /** Wires up a PR whose chain failed exactly these tests. */
    private void chainFailing(int pr, FailedTest... tests) {
        long chain = 9389046L + (pr - 13335); // RunAll 9389046 of apache/ignite#13335, a neighbour id for any other PR
        when(chains.findBuildId(TOK, pr)).thenReturn(Optional.of(chain));
        when(chains.collectForBuild(eq(TOK), eq(pr), eq(chain), any())).thenReturn(new ChainCollector.Chain(chain,
            "pull/" + pr + "/head", List.of(tests), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
    }

    private static FailedTest reconnectIn(String suite, long suiteBuildId) {
        return new FailedTest(RECONNECT, "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientReconnect", suite,
            suiteBuildId, suite, "o-" + suiteBuildId);
    }

    /** How a test did on master in one suite: {@code fails} failures in its last {@code runs} runs. */
    private record SuiteHistory(String suite, int runs, int fails) {
    }

    /** Stubs the test's master history the way TeamCity answers it: one suite at a time. */
    private void masterHistory(long testId, SuiteHistory... suites) {
        for (SuiteHistory s : suites) {
            when(tc.getBaseBranchHistory(TOK, testId, s.suite()))
                .thenReturn(concat(repeat("FAILURE", s.fails()), repeat("SUCCESS", s.runs() - s.fails())));
        }
    }

    /**
     * Stubs the test's finished runs on the PR branch (oldest → newest) the way TeamCity answers them:
     * asked for one suite, it gives that suite's runs only.
     */
    private void branchRuns(int pr, long testId, TcModel.TestOccurrence... runs) {
        for (TcModel.TestOccurrence r : runs) {
            String suite = r.build().buildTypeId();
            when(tc.prBranchRuns(TOK, pr, testId, suite))
                .thenReturn(Arrays.stream(runs).filter(o -> suite.equals(o.build().buildTypeId())).toList());
        }
    }

    /** One finished branch run: its status, the suite build it ran in, and that build's revision. */
    private static TcModel.TestOccurrence run(String status, long buildId, String revision) {
        return run(status, buildId, "CalciteSql2", revision);
    }

    /** Same, in a given suite; an ignored test (UNKNOWN) or a pass leave the build itself green. */
    private static TcModel.TestOccurrence run(String status, long buildId, String suite, String revision) {
        TcModel.Revisions revs = revision == null ? null
            : new TcModel.Revisions(List.of(new TcModel.Revision(revision)));
        String buildStatus = "FAILURE".equals(status) ? "FAILURE" : "SUCCESS";

        return new TcModel.TestOccurrence(null, null, status, null,
            new TcModel.BuildRef(buildId, null, "finished", buildStatus, suite, null, revs, null), null);
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
