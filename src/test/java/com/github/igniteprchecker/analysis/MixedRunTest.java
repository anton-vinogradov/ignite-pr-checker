package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Caveats.Glance;
import com.github.igniteprchecker.analysis.Caveats.Standing;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * apache/ignite#13709: RunAll 9425992 tested c577b18 and blamed a test of Control Utility 5. The author fixed the code
 * and re-ran that one suite on the PR's head 9b02561 as build 9426316, and it passed. The verdict then had no blocker,
 * and nothing said that only that suite had run on the head: the page called it a verdict for older code, and a visa
 * posted while GitHub could not name the head read clean. Such a verdict is a mixed run: it names the suites that
 * re-ran on another commit, and it is never clean.
 */
class MixedRunTest {
    private static final String TOK = "tok";

    private static final int PR = 13709;

    private static final long CHAIN = 9425992L;

    private static final long CHAIN_CU5 = 9425950L;

    private static final long RERUN = 9426316L;

    private static final String CU5 = "IgniteTests24Java8_ControlUtility5";

    private static final String RUN_ALL_COMMIT = "c577b18e2f0d4a6b8c1d3e5f7a9b0c2d4e6f8a1b";

    private static final String HEAD = "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a";

    private static final String MIXED = "mixed run — Control Utility 5 re-ran on 9b02561, the rest of the RunAll ran on "
        + "c577b18; only a RunAll of the PR's head can prove it clean";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(4), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, Jackson2Defaults.mapper()), new RunDeltaStore(Jackson2Defaults.mapper()));

    @Test
    void aSuiteReRunOnTheHeadMakesAMixedRunThatIsNotClean() {
        AnalysisResult r = analyzedWithReRunOn(HEAD);

        assertThat(r.blockers()).isEmpty();
        assertThat(r.filtered()).extracting(v -> v.reason())
            .containsExactly("not failing in the last finished run (passed on re-run)");
        assertThat(r.otherCodeRuns()).containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
        assertThat(Caveats.standing(r, null)).as("before the page knows the PR's head").isEqualTo(Standing.UNPROVEN);
        assertThat(Glance.of(r).against(HEAD)).as("the PR list, the head being the re-run's commit")
            .isEqualTo(Standing.UNPROVEN);
        assertThat(Caveats.of(r, 3)).as("in place of the commits pushed since, in the PR comment and the visa")
            .containsExactly(MIXED);
        assertThat(Caveats.onPage(r)).as("the page says it in its line about the code").isEmpty();
    }

    @Test
    void aReRunOnTheRunAllsOwnCommitKeepsTheVerdictClean() {
        AnalysisResult r = analyzedWithReRunOn(RUN_ALL_COMMIT);

        assertThat(r.otherCodeRuns()).isEmpty();
        assertThat(Caveats.standing(r, null)).isEqualTo(Standing.CLEAN);
        assertThat(Caveats.of(r, 3)).containsExactly("3 commits pushed since this run — it tested older code");
    }

    /** A broken suite that passed in full on another commit is judged by that run: the verdict reads it too. */
    @Test
    void aBrokenSuiteThatPassedOnTheHeadMakesAMixedRunToo() {
        BrokenSuite broken = new BrokenSuite(CU5, CHAIN_CU5, "Control Utility 5", List.of("execution timeout"), 10, 200);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13709/head", List.of(), List.of(broken), List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, List.of(),
            List.of(), RUN_ALL_COMMIT));
        when(tc.latestSuiteRun(TOK, PR, CU5)).thenReturn(Optional.of(new TcModel.Build(RERUN, "SUCCESS", "finished",
            null, CU5, null, null, null, null, null, null, null, null, null, null, null, revisions(HEAD),
            new TcModel.TestOccurrences(200, List.of()))));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.brokenSuites()).isEmpty();
        assertThat(r.otherCodeRuns()).containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
        assertThat(Caveats.standing(r, null)).isEqualTo(Standing.UNPROVEN);
    }

    /**
     * Control Utility 5 broke again in its re-run on the head: the chain read that re-run, which is the suite's broken
     * run now. The verdict reads it as the chain did.
     */
    @Test
    void aReRunOnTheHeadThatTheChainReadMakesAMixedRun() {
        BrokenSuite broken = new BrokenSuite(CU5, RERUN, "Control Utility 5", List.of("execution timeout"), 10, 200);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13709/head", List.of(), List.of(broken), List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, List.of(),
            List.of(), RUN_ALL_COMMIT, List.of(new RunRef(CU5, "Control Utility 5", RERUN, HEAD))));
        when(tc.latestSuiteRun(TOK, PR, CU5)).thenReturn(Optional.of(suiteRun(RERUN, "FAILURE", HEAD,
            "TC_EXECUTION_TIMEOUT")));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.brokenSuites()).hasSize(1);
        assertThat(r.otherCodeRuns()).containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
    }

    /** Control Utility 5 ran 20 of its 200 tests in the RunAll; its re-run on the head ran them all. */
    @Test
    void aShrunkSuiteThatRanInFullOnTheHeadMakesAMixedRun() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13709/head", List.of(), List.of(), List.of(new ShrunkSuite(CU5, "Control Utility 5", CHAIN_CU5, 20,
                200, 90)), 148, 1, false, 0, false, 0, 0, 0, 1, List.of(), List.of(), RUN_ALL_COMMIT));
        when(tc.latestSuiteRun(TOK, PR, CU5)).thenReturn(Optional.of(passedRun(HEAD)));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.shrunkSuites()).isEmpty();
        assertThat(r.otherCodeRuns()).containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
    }

    /** TeamCity cancelled Control Utility 5 in the RunAll; the author ran it on the head, in full. */
    @Test
    void aCancelledSuiteThatRanOnTheHeadMakesAMixedRun() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13709/head", List.of(), List.of(), List.of(), 147, 1, true, 1, false, 0, 0, 0, 1, List.of(),
            List.of(new CancelledSuite(CU5, CHAIN_CU5, "Control Utility 5", null, null, true, 200)), RUN_ALL_COMMIT));
        when(tc.finishedBuildsSince(TOK, PR, null)).thenReturn(List.of(passedRun(HEAD)));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.cancelledSuites()).isEmpty();
        assertThat(r.otherCodeRuns()).containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
    }

    /** A re-run that failed again on another commit is read by the chain itself, broken or not. */
    @Test
    void theChainNamesTheReRunsItReadWithTheirCommits() {
        TcClient client = mock(TcClient.class);
        SuiteBaseline baseline = mock(SuiteBaseline.class);
        when(baseline.counts(anyString())).thenReturn(Map.of());
        TcModel.Build chainRun = suiteRun(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT, "TC_EXECUTION_TIMEOUT");
        when(client.getBuildWithDeps(TOK, CHAIN)).thenReturn(new TcModel.Build(CHAIN, "FAILURE", "finished",
            "pull/13709/head", "IgniteTests24Java8_RunAll", null, "20261020T080000+0000", null, null, null, null,
            new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(1, List.of(chainRun)), null, null, revisions(RUN_ALL_COMMIT), null));
        when(client.failedBuildsSince(TOK, PR, "20261020T080000+0000"))
            .thenReturn(List.of(suiteRun(RERUN, "FAILURE", HEAD, "TC_EXECUTION_TIMEOUT")));

        ChainCollector.Chain chain = new ChainCollector(client, baseline)
            .collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(chain.newerRuns()).extracting(RunRef::suite, RunRef::buildId, RunRef::revision)
            .containsExactly(tuple(CU5, RERUN, HEAD));
    }

    /** "Control Utility 5 and Cache 2 re-ran on 9b02561, Queries 1 on 1a2b3c4": each commit once. */
    @Test
    void theCaveatNamesEachCommitOnce() {
        AnalysisResult r = new AnalysisResult(PR, CHAIN, "pull/13709/head", 1, List.of(), List.of(), List.of(),
            List.of(), List.of(), 148, 0, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0,
            RUN_ALL_COMMIT, 0, List.of(new RunRef("Cache2", "Cache 2", 9426400L, HEAD),
                new RunRef(CU5, "Control Utility 5", RERUN, HEAD),
                new RunRef("Queries1", "Queries 1", 9426500L, "1a2b3c4d5e6f")), List.of());

        assertThat(Caveats.of(r, null)).containsExactly("mixed run — Cache 2 and Control Utility 5 re-ran on 9b02561, "
            + "Queries 1 on 1a2b3c4, the rest of the RunAll ran on c577b18; only a RunAll of the PR's head can prove it "
            + "clean");
    }

    /** The test of Control Utility 5 failed in the RunAll and passed in the suite's re-run on {@code commit}. */
    private AnalysisResult analyzedWithReRunOn(String commit) {
        FailedTest t = new FailedTest(1001L, "GridCommandHandlerTest.testCacheIdle", CU5, CHAIN_CU5,
            "Control Utility 5", "build:(id:" + CHAIN_CU5 + "),id:1");
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13709/head", List.of(t), List.of(), List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, List.of(),
            List.of(), RUN_ALL_COMMIT));
        when(tc.getBaseBranchHistory(TOK, 1001L, CU5)).thenReturn(passes(50));
        when(tc.prBranchRuns(TOK, PR, 1001L, CU5)).thenReturn(List.of(
            run(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT), run(RERUN, "SUCCESS", commit)));

        return analyzer.analyze(TOK, PR).orElseThrow();
    }

    private static TcModel.TestOccurrence run(long buildId, String status, String commit) {
        return new TcModel.TestOccurrence("build:(id:" + buildId + "),id:1", null, status, null,
            new TcModel.BuildRef(buildId, null, "finished", status, CU5,
                new TcModel.BuildType(CU5, "Control Utility 5"), revisions(commit), null), null);
    }

    private static TcModel.Build suiteRun(long id, String status, String commit, String problem) {
        return new TcModel.Build(id, status, "finished", "pull/13709/head", CU5, null, null, null, null, null, null,
            new TcModel.BuildType(CU5, "Control Utility 5"), null, null, null,
            new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(problem, null))), revisions(commit),
            new TcModel.TestOccurrences(10, List.of()));
    }

    /** The re-run of Control Utility 5 on {@code commit}, which passed all its 200 tests. */
    private static TcModel.Build passedRun(String commit) {
        return new TcModel.Build(RERUN, "SUCCESS", "finished", "pull/13709/head", CU5, null, null, null, null, null,
            null, new TcModel.BuildType(CU5, "Control Utility 5"), null, null, null, null, revisions(commit),
            new TcModel.TestOccurrences(200, List.of()));
    }

    private static TcModel.Revisions revisions(String commit) {
        return new TcModel.Revisions(List.of(new TcModel.Revision(commit)));
    }

    private static List<TcModel.TestOccurrence> passes(int n) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null));

        return out;
    }
}
