package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The warmer checks the 50 newest PRs every 10 minutes, and for each it asked TeamCity which chain to analyse
 * before asking whether anything finished on the branch. Which chain is analysed changes only when a build
 * finishes there, so a PR with a verdict costs that one question until one does.
 */
class WarmOneQuestionTest {
    private static final String TOK = "t";

    private static final int PR = 13654;

    private static final long CHAIN = 9391879L;

    private static final long NEWER_CHAIN = 9392500L;

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, new ObjectMapper());

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), cache,
        new RunDeltaStore(new ObjectMapper()));

    WarmOneQuestionTest() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        for (long id : List.of(CHAIN, NEWER_CHAIN)) {
            when(chains.collectForBuild(eq(TOK), eq(PR), eq(id), any())).thenReturn(new ChainCollector.Chain(id,
                "pull/13654/head", List.of(), List.of(), List.of(), 150, 0, false, 0, false, 0, 0, 0, 0));
        }
    }

    @Test
    void aPrWithAVerdictIsAskedOnlyWhetherSomethingFinished() {
        assertThat(analyzer.warm(TOK, PR)).as("the first warm computes").isTrue();
        assertThat(analyzer.warm(TOK, PR)).isFalse();
        assertThat(analyzer.warm(TOK, PR)).isFalse();

        verify(chains, times(1)).findBuildId(TOK, PR);
        verify(tc, times(2)).branchFinishedAfter(eq(TOK), eq(PR), anyLong());
    }

    /** A newer RunAll that finished is something that finished: the chain is looked up again and analysed. */
    @Test
    void onceABuildFinishedTheChainIsLookedUpAgain() {
        analyzer.warm(TOK, PR);
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(NEWER_CHAIN));

        assertThat(analyzer.warm(TOK, PR)).isTrue();

        assertThat(cache.peekResult(NEWER_CHAIN)).isPresent();
        verify(tc, times(1)).branchFinishedAfter(eq(TOK), eq(PR), anyLong());
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(false);
        assertThat(analyzer.warm(TOK, PR)).as("the newer chain's verdict stands").isFalse();
        verify(chains, times(2)).findBuildId(TOK, PR);
    }

    /** Something finished, but the chain is the same: asked once, recomputed once. */
    @Test
    void aSuiteRerunOnTheSameChainIsAskedAboutOnce() {
        analyzer.warm(TOK, PR);
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true);

        assertThat(analyzer.warm(TOK, PR)).isTrue();

        verify(tc, times(1)).branchFinishedAfter(eq(TOK), eq(PR), anyLong());
        assertThat(cache.peekResult(CHAIN)).get().extracting(AnalysisResult::buildId).isEqualTo(CHAIN);
    }
}
