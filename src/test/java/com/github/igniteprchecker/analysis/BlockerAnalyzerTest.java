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
    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null);
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
        when(chains.collectForBuild(eq(TOK), eq(999L), any())).thenReturn(new ChainCollector.Chain(999, "pull/42/head",
            List.of(cleanBreak, rareMasterFail, preExisting, brandNew, passedOnRerun), List.of(), 0, 0));

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
    void emptyWhenNoRunAllBuild() {
        when(chains.findBuildId(TOK, 7)).thenReturn(Optional.empty());

        assertThat(analyzer.analyze(TOK, 7)).isEmpty();
    }

    @Test
    void secondAnalyzeOfSameBuildIsServedFromCache() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(999L), any())).thenReturn(new ChainCollector.Chain(999, "pull/42/head", List.of(), List.of(), 0, 0));

        analyzer.analyze(TOK, 42);
        analyzer.analyze(TOK, 42);

        verify(chains, times(1)).collectForBuild(eq(TOK), eq(999L), any()); // second run reused the cached result
    }

    private static TestVerdict verdict(AnalysisResult r, long testId) {
        return concat(r.blockers(), r.filtered()).stream()
            .filter(v -> v.testId() == testId).findFirst().orElseThrow();
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
