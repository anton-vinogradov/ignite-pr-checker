package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Deterministic, offline test of the blocker/noise classification on synthetic base-branch history. */
class BlockerAnalyzerTest {
    private static final String TOK = "tok";

    private final TcClient tc = mock(TcClient.class);
    private final ChainCollector chains = mock(ChainCollector.class);
    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null, null);
    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(4), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    void classifiesBlockersVsNoise() {
        FailedTest cleanBreak = new FailedTest(1, "Suite: A.blockerTest", "SuiteA");
        FailedTest flaky = new FailedTest(2, "Suite: B.flakyTest", "SuiteB");
        FailedTest preExisting = new FailedTest(3, "Suite: C.brokenInMaster", "SuiteC");
        FailedTest brandNew = new FailedTest(4, "Suite: D.newTest", "SuiteD");

        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(TOK, 999L)).thenReturn(
            new ChainCollector.Chain(999, "pull/42/head", List.of(cleanBreak, flaky, preExisting, brandNew)));

        // 1) all green in master, never flips -> broke by this PR.
        when(tc.getBaseBranchHistory(TOK, 1)).thenReturn(repeat("SUCCESS", 1, 50));
        // 2) one spontaneous failure (no code change) among greens -> low fail rate but flaky.
        when(tc.getBaseBranchHistory(TOK, 2)).thenReturn(withFlakyFailure());
        // 3) fails often in master -> pre-existing, and flips only happen on builds that had changes.
        when(tc.getBaseBranchHistory(TOK, 3)).thenReturn(concat(repeat("FAILURE", 1, 10), repeat("SUCCESS", 1, 40)));
        // 4) no history at all -> can't prove pre-existing -> blocker.
        when(tc.getBaseBranchHistory(TOK, 4)).thenReturn(List.of());

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(1L, 4L);
        assertThat(r.filtered()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(2L, 3L);

        assertThat(verdict(r, 2).reason()).contains("flaky");
        assertThat(verdict(r, 3).reason()).contains("pre-existing");
        assertThat(verdict(r, 4).reason()).contains("no base-branch history");
        assertThat(verdict(r, 3).baseFailRatePct()).isEqualTo(20.0);
    }

    @Test
    void emptyWhenNoRunAllBuild() {
        when(chains.findBuildId(TOK, 7)).thenReturn(Optional.empty());

        assertThat(analyzer.analyze(TOK, 7)).isEmpty();
    }

    @Test
    void secondAnalyzeOfSameBuildIsServedFromCache() {
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(TOK, 999L)).thenReturn(new ChainCollector.Chain(999, "pull/42/head", List.of()));

        analyzer.analyze(TOK, 42);
        analyzer.analyze(TOK, 42);

        verify(chains, times(1)).collectForBuild(TOK, 999L); // second run reused the cached result
    }

    private static TestVerdict verdict(AnalysisResult r, long testId) {
        return concat(r.blockers(), r.filtered()).stream()
            .filter(v -> v.testId() == testId).findFirst().orElseThrow();
    }

    /** A history entry: a run with the given status on a build that had {@code changesCount} code changes. */
    private static TcModel.TestOccurrence occ(String status, int changesCount) {
        return new TcModel.TestOccurrence(null, null, status, null,
            new TcModel.BuildRef(0, "refs/heads/master", new TcModel.Changes(changesCount)));
    }

    private static List<TcModel.TestOccurrence> repeat(String status, int changesCount, int n) {
        List<TcModel.TestOccurrence> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(occ(status, changesCount));
        return l;
    }

    /** 49 green runs with a single spontaneous (no-code-change) failure spliced in. */
    private static List<TcModel.TestOccurrence> withFlakyFailure() {
        List<TcModel.TestOccurrence> l = new ArrayList<>(repeat("SUCCESS", 1, 24));
        l.add(occ("FAILURE", 0));
        l.addAll(repeat("SUCCESS", 1, 25));
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
