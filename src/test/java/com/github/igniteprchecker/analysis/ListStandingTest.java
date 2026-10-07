package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Caveats.Glance;
import com.github.igniteprchecker.analysis.Caveats.Standing;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The green tick in the PR list stood at PRs 13583, 13577 and 13389, whose pages said "No test blockers" over
 * tests that started failing, and at runs of old code: 7 of the 13 ticks on prod had commits pushed since, up to
 * 38. The tick now stands only where the page says "No blockers", for the code the PR's head has now.
 */
class ListStandingTest {
    private static final String HEAD = "5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4";

    private static final String OLDER = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567";

    private static final String TOK = "tok";

    private static final int PR = 13583;

    private static final long CHAIN = 9391879L;

    @Test
    void testsToWatchAreNoTick() {
        assertThat(new Glance(0, 2, true, HEAD).against(HEAD)).isEqualTo(Standing.WATCH);
    }

    @Test
    void aCleanRunOfTheHeadIsClean() {
        assertThat(new Glance(0, 0, true, HEAD).against(HEAD)).isEqualTo(Standing.CLEAN);
    }

    @Test
    void aCleanRunOfOlderCodeIsOldCode() {
        assertThat(new Glance(0, 0, true, OLDER).against(HEAD)).isEqualTo(Standing.OLD_CODE);
    }

    @Test
    void aCleanRunOfUnknownCodeIsNoTick() {
        assertThat(new Glance(0, 0, true, null).against(HEAD)).isEqualTo(Standing.UNKNOWN_CODE);
        assertThat(new Glance(0, 0, true, HEAD).against(null)).isEqualTo(Standing.UNKNOWN_CODE);
    }

    @Test
    void aRunThatDidNotCoverThePrIsUnproven() {
        assertThat(new Glance(0, 0, false, HEAD).against(HEAD)).isEqualTo(Standing.UNPROVEN);
    }

    @Test
    void blockersComeFirst() {
        assertThat(new Glance(3, 2, false, OLDER).against(HEAD)).isEqualTo(Standing.BLOCKERS);
    }

    @Test
    void theVerdictKeepsTheRevisionItsChainRan() {
        TcClient tc = mock(TcClient.class);
        BlockerAnalyzer analyzer = analyzer(tc, HEAD);

        assertThat(analyzer.analyze(TOK, PR).orElseThrow().revision()).isEqualTo(HEAD);
        assertThat(analyzer.glance(PR)).isEqualTo(new Glance(0, 0, true, HEAD));
        verify(tc, never()).buildRevision(TOK, CHAIN);
    }

    /** A chain kept from before its revision was asked for with it costs one call for the revision. */
    @Test
    void aChainReadWithoutItsRevisionHasItLookedUp() {
        TcClient tc = mock(TcClient.class);
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(OLDER));

        assertThat(analyzer(tc, null).analyze(TOK, PR).orElseThrow().revision()).isEqualTo(OLDER);
    }

    private static BlockerAnalyzer analyzer(TcClient tc, String chainRevision) {
        ChainCollector chains = mock(ChainCollector.class);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/" + PR + "/head", List.of(), List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 1_791_000_000L,
            List.of(), List.of(), chainRevision));
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

        return new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
            Executors.newFixedThreadPool(2), new AnalysisCache(cfg, new ObjectMapper()),
            new RunDeltaStore(new ObjectMapper()));
    }
}
