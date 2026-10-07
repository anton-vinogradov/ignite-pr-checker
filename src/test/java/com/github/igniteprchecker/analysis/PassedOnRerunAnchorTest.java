package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * A test that failed in the RunAll and passed on its re-run is filtered as "passed on re-run", and its
 * "why?" opened the passing run: a log with no failure in it. The verdict keeps pointing at the newest
 * failure, the one someone asking why it failed wants to read.
 */
class PassedOnRerunAnchorTest {
    private static final String TOK = "tok";

    private static final String SUITE = "IgniteTests24Java8_Cache12";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(4), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @Test
    void whyOfATestThatPassedOnReRunShowsItsLastFailure() {
        FailedTest t = new FailedTest(5L, "Cache12: EvictionTest.testGroupReservation", SUITE, 9379938L, "Cache 12",
            "build:(id:9379938),id:2000000230");
        when(chains.findBuildId(TOK, 42)).thenReturn(Optional.of(999L));
        when(chains.collectForBuild(eq(TOK), eq(42), eq(999L), any())).thenReturn(new ChainCollector.Chain(999,
            "pull/42/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, 5L, SUITE)).thenReturn(successes(50));
        when(tc.prBranchRuns(TOK, 42, 5L, SUITE)).thenReturn(List.of(
            occurrence("build:(id:9379938),id:2000000230", "FAILURE", 9379938L),
            occurrence("build:(id:9388909),id:2000000230", "FAILURE", 9388909L),
            occurrence("build:(id:9389500),id:2000000230", "SUCCESS", 9389500L)));

        AnalysisResult r = analyzer.analyze(TOK, 42).orElseThrow();

        TestVerdict v = r.filtered().get(0);
        assertThat(v.reason()).contains("passed on re-run");
        assertThat(v.occurrenceId()).isEqualTo("build:(id:9388909),id:2000000230");
        assertThat(v.suiteBuildId()).isEqualTo(9388909L);
        assertThat(v.branchRuns()).isEqualTo("FFP");
    }

    private static TcModel.TestOccurrence occurrence(String id, String status, long buildId) {
        return new TcModel.TestOccurrence(id, null, status, null,
            new TcModel.BuildRef(buildId, null, "finished", status, SUITE, null, null, null), null);
    }

    private static List<TcModel.TestOccurrence> successes(int n) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null));

        return out;
    }
}
