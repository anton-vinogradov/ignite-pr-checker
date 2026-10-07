package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * A view of a PR recomputed its verdict whenever it was older than two minutes, and never asked whether
 * anything had changed: 23 views of long-finished PRs cost ci2 695 calls in a minute, about 170 for PR
 * 12584 alone. A view now recomputes only when something finished on the branch since.
 */
class ChangeDrivenRefreshTest {
    private static final String TOK = "t";

    private static final int PR = 12584;

    private static final long CHAIN = 9380000L;

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, 120, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, new ObjectMapper());

    private final ExecutorService refreshPool = Executors.newSingleThreadExecutor();

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
        Executors.newSingleThreadExecutor(), refreshPool, cache, new RunDeltaStore(new ObjectMapper()));

    private final long now = System.currentTimeMillis();

    ChangeDrivenRefreshTest() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/12584/head", List.of(), List.of(), List.of(), 150, 0, false, 0, false, 0, 0, 0, now / 1000 - 86_400));
    }

    @Test
    void aViewOfAnOldVerdictRecomputesNothingWhenNothingFinished() throws Exception {
        cache.putResult(CHAIN, verdict(now - 3 * 3_600_000, 0));

        assertThat(analyzer.analyze(TOK, PR)).isPresent();
        settle();

        verify(tc).branchFinishedAfter(TOK, PR, (now - 3 * 3_600_000) / 1000 - 60);
        verify(chains, never()).collectForBuild(anyString(), anyInt(), anyLong(), any());
    }

    @Test
    void aViewRecomputesOnceSomethingFinishedOnTheBranch() throws Exception {
        cache.putResult(CHAIN, verdict(now - 3 * 3_600_000, 0));
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true);

        analyzer.analyze(TOK, PR);
        settle();

        verify(chains).collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any());
        assertThat(cache.peekResult(CHAIN)).get().extracting(AnalysisResult::computedAt).isNotEqualTo(now - 3 * 3_600_000);
    }

    /** The page polls while it is open: one question to TeamCity every two minutes is enough. */
    @Test
    void viewsInQuickSuccessionAskTeamCityOnce() throws Exception {
        cache.putResult(CHAIN, verdict(now - 3 * 3_600_000, 0));

        for (int i = 0; i < 5; i++) {
            analyzer.analyze(TOK, PR);
            settle();
        }

        verify(tc, times(1)).branchFinishedAfter(anyString(), anyInt(), anyLong());
    }

    /** An incomplete verdict is retried without asking: what it misses is TeamCity's answers, not news. */
    @Test
    void aViewRetriesAnIncompleteVerdictThatIsDue() throws Exception {
        cache.putResult(CHAIN, verdict(now - 5 * 60_000, now - 5 * 60_000));

        analyzer.analyze(TOK, PR);
        settle();

        verify(chains).collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any());
        verify(tc, never()).branchFinishedAfter(anyString(), anyInt(), anyLong());
    }

    @Test
    void theWarmerRetriesAnIncompleteVerdictThatIsDue() {
        cache.putResult(CHAIN, verdict(now - 5 * 60_000, now - 5 * 60_000));

        assertThat(analyzer.warm(TOK, PR)).isTrue();
    }

    /** Waits for the background refresh a view may have started. */
    private void settle() throws ExecutionException, InterruptedException {
        refreshPool.submit(() -> { }).get();
    }

    private AnalysisResult verdict(long computedAt, long incompleteSince) {
        List<TestVerdict> unverified = incompleteSince == 0 ? List.of() : List.of(new TestVerdict(1L,
            "GridCacheTest.testPut", "Cache1", 9380001L, "Cache 1", "o1", false, false,
            "could not verify (TeamCity error: 502 Bad Gateway)", "", 0));

        return new AnalysisResult(PR, CHAIN, "pull/12584/head", computedAt, List.of(), List.of(), List.of(), List.of(),
            List.of(), 150, 0, false, 0, false, 0, 0, 0, now / 1000 - 86_400, computedAt / 1000 - 60, List.of(),
            List.of(), unverified, incompleteSince);
    }
}
