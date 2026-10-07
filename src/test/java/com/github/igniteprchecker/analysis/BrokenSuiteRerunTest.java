package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Cache (Failover) 5 on PR 13434 timed out after 33 of master's 67 tests (build 9253651). What closes
 * such a broken suite is a re-run that ran its tests in full, not a SUCCESS status: a full re-run that
 * failed an ordinary test left it broken and sent it into the next re-run wave, while a green re-run of
 * half the tests closed it and hid the hole in coverage.
 */
class BrokenSuiteRerunTest {
    private static final String TOK = "t";

    private static final int PR = 13434;

    private static final long CHAIN = 9253366L;

    private static final String CHAIN_QUEUED = "20260920T101500+0000";

    private static final String FAILOVER5 = "IgniteTests24Java8_CacheFailover5";

    private static final long TIMED_OUT = 9253651L;

    private static final long RERUN = 9253900L;

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final ChainCollector collector = new ChainCollector(tc, baseline);

    @Test
    void aFullRerunWithOrdinaryFailuresClosesTheBrokenSuite() {
        chainWithTheTimeout();
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(failover5(RERUN, "FAILURE", 67)));
        failing(RERUN, 77L);

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).as("the re-run ran all 67 tests: it is a result, not a broken suite").isEmpty();
        assertThat(chain.failedTests()).extracting(FailedTest::testId, FailedTest::suiteBuildId)
            .containsExactly(tuple(77L, RERUN));
    }

    @Test
    void aNewerChainsFullRunClosesTheBrokenSuiteToo() {
        chainWithTheTimeout();
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(chainBuild(9260000L, List.of())));
        when(tc.getBuildWithDeps(TOK, 9260000L)).thenReturn(chainBuild(9260000L,
            List.of(failover5(9260050L, "FAILURE", 66))));
        failing(9260050L, 77L);

        assertThat(collect().brokenSuites()).isEmpty();
    }

    @Test
    void aRerunThatAlsoRanShortLeavesTheSuiteBroken() {
        chainWithTheTimeout();
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(failover5(RERUN, "FAILURE", 30)));
        failing(RERUN, 77L);

        assertThat(collect().brokenSuites()).extracting(BrokenSuite::suite, BrokenSuite::suiteBuildId)
            .containsExactly(tuple(FAILOVER5, TIMED_OUT));
    }

    /** A crash after a full run is a note about that run: a newer full run without it drops the note. */
    @Test
    void aFullRerunDropsTheCrashNoteOfAnOlderRun() {
        when(baseline.counts(anyString())).thenReturn(Map.of(FAILOVER5, 67));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(CHAIN,
            List.of(withProblem(failover5(TIMED_OUT, "FAILURE", 67), "TC_JVM_CRASH"))));
        failing(TIMED_OUT, 76L);
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(failover5(RERUN, "FAILURE", 67)));
        failing(RERUN, 77L);

        ChainCollector.Chain chain = collect();

        assertThat(chain.unstableSuites()).isEmpty();
        assertThat(chain.brokenSuites()).isEmpty();
        assertThat(chain.failedTests()).extracting(FailedTest::testId).containsExactlyInAnyOrder(76L, 77L);
    }

    @Test
    void aGreenRerunOfHalfTheTestsLeavesAShrunkSuite() {
        when(tc.latestSuiteRun(TOK, PR, FAILOVER5)).thenReturn(Optional.of(failover5(RERUN, "SUCCESS", 30)));

        AnalysisResult r = analyzeTheTimeout();

        assertThat(r.brokenSuites()).isEmpty();
        assertThat(r.shrunkSuites())
            .extracting(ShrunkSuite::suite, ShrunkSuite::suiteBuildId, ShrunkSuite::tests, ShrunkSuite::baseline,
                ShrunkSuite::dropPct)
            .containsExactly(tuple(FAILOVER5, RERUN, 30, 67, 55));
        assertThat(Caveats.proven(r)).isFalse();
    }

    @Test
    void aGreenRerunInFullHealsTheBrokenSuite() {
        when(tc.latestSuiteRun(TOK, PR, FAILOVER5)).thenReturn(Optional.of(failover5(RERUN, "SUCCESS", 67)));

        AnalysisResult r = analyzeTheTimeout();

        assertThat(r.brokenSuites()).isEmpty();
        assertThat(r.shrunkSuites()).isEmpty();
        assertThat(Caveats.proven(r)).isTrue();
    }

    /** One suite reads once: a green re-run that ran short replaces the chain's own shrunk run of it. */
    @Test
    void aSuiteThatRanShortTwiceIsShrunkOnce() {
        ShrunkSuite chainRun = ChainCollector.shrunk(FAILOVER5, "Cache (Failover) 5", 9253650L, 40, 67);
        when(tc.latestSuiteRun(TOK, PR, FAILOVER5)).thenReturn(Optional.of(failover5(RERUN, "SUCCESS", 30)));

        AnalysisResult r = analyze(new ChainCollector.Chain(CHAIN, "pull/13434/head", List.of(),
            List.of(timedOutEntry()), List.of(chainRun), 140, 0, false, 0, false, 0, 0, 0, 0));

        assertThat(r.shrunkSuites()).extracting(ShrunkSuite::suiteBuildId, ShrunkSuite::tests)
            .containsExactly(tuple(RERUN, 30));
    }

    private AnalysisResult analyzeTheTimeout() {
        return analyze(new ChainCollector.Chain(CHAIN, "pull/13434/head", List.of(), List.of(timedOutEntry()),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, 0));
    }

    private AnalysisResult analyze(ChainCollector.Chain chain) {
        ChainCollector chains = mock(ChainCollector.class);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(chain);
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();

        return new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2), Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor(), new AnalysisCache(cfg, mapper), new RunDeltaStore(mapper))
            .analyze(TOK, PR).orElseThrow();
    }

    private static BrokenSuite timedOutEntry() {
        return new BrokenSuite(FAILOVER5, TIMED_OUT, "Cache (Failover) 5", List.of("execution timeout"), 33, 67);
    }

    private ChainCollector.Chain collect() {
        return collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
    }

    private void chainWithTheTimeout() {
        when(baseline.counts(anyString())).thenReturn(Map.of(FAILOVER5, 67));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(CHAIN,
            List.of(withProblem(failover5(TIMED_OUT, "FAILURE", 33), "TC_EXECUTION_TIMEOUT"))));
    }

    private static TcModel.Build withProblem(TcModel.Build b, String type) {
        return new TcModel.Build(b.id(), b.status(), b.state(), b.branchName(), b.buildTypeId(), null, null, null, null,
            null, null, b.buildType(), null, null, null,
            new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(type, null))), null, b.testOccurrences());
    }

    private void failing(long suiteBuildId, long testId) {
        when(tc.getFailedTests(TOK, suiteBuildId)).thenReturn(List.of(new TcModel.TestOccurrence(
            "id:" + testId + ",build:(id:" + suiteBuildId + ")", "GridCacheFailoverTest.test" + testId, "FAILURE",
            new TcModel.TestRef(testId), null, null)));
    }

    private static TcModel.Build chainBuild(long id, List<TcModel.Build> deps) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/" + PR + "/head", "IgniteTests24Java8_RunAll", null,
            id == CHAIN ? CHAIN_QUEUED : null, null, null, null, null,
            new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }

    private static TcModel.Build failover5(long id, String status, int tests) {
        return new TcModel.Build(id, status, "finished", "pull/" + PR + "/head", FAILOVER5, null, null, null, null, null,
            null, new TcModel.BuildType(FAILOVER5, "Cache (Failover) 5"), null, null, null, null, null,
            new TcModel.TestOccurrences(tests, List.of()));
    }
}
