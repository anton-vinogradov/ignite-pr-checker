package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The warmer checks the 50 newest PRs every 10 minutes, and for each it asked TeamCity which chain to analyse
 * before asking whether anything finished on the branch. Once a chain has finished, which chain is analysed
 * changes only when a build finishes there, so a PR with a finished chain's verdict costs that one question until
 * one does.
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

    private final ExecutorService recompute = Executors.newSingleThreadExecutor();

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
        Executors.newSingleThreadExecutor(), recompute, cache, new RunDeltaStore(new ObjectMapper()));

    /** When the analysed chains finished, in epoch seconds. */
    private final long finishedAt = System.currentTimeMillis() / 1000 - 7200;

    WarmOneQuestionTest() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        for (long id : List.of(CHAIN, NEWER_CHAIN))
            when(chains.collectForBuild(eq(TOK), eq(PR), eq(id), any())).thenReturn(chain(id, false));
    }

    @AfterEach
    void stop() {
        recompute.shutdownNow();
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

    /**
     * A view of PR 13654 finds the verdict of chain 9391879 stale and queues its refresh behind another PR's
     * recompute; meanwhile RunAll 9392500 finishes clean. The refresh runs a minute later. Were its verdict to
     * hold from the refresh's start, it would claim nothing finished since, though its chain was looked up before
     * 9392500 finished, and the warm cycle would keep the older chain's blockers in the PR list. A verdict holds
     * from its chain's lookup at the latest.
     */
    @Test
    void aRefreshThatWaitedDoesNotHideAChainThatFinishedMeanwhile() throws Exception {
        long stale = System.currentTimeMillis() - 3_600_000;
        cache.putResult(CHAIN, new AnalysisResult(PR, CHAIN, "pull/13654/head", stale, List.of(), List.of(),
            List.of(), List.of(), List.of(), 150, 0, false, 0, false, 0, 0, 0, finishedAt, stale / 1000 - 60));
        AtomicLong lookedUpAt = new AtomicLong();
        when(chains.findBuildId(TOK, PR)).thenAnswer(inv -> {
            lookedUpAt.set(System.currentTimeMillis() / 1000);

            return Optional.of(CHAIN);
        });
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true);
        CountDownLatch otherPr = new CountDownLatch(1);
        recompute.execute(() -> awaitQuietly(otherPr));

        analyzer.analyze(TOK, PR);
        // 9392500 finished right after that lookup, by a TeamCity clock 59 s behind ours, which the watermark's
        // margin allows: two seconds in the queue then stand for the minute.
        long newerFinishedAt = lookedUpAt.get() + 1 - 60;
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(NEWER_CHAIN));
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong()))
            .thenAnswer(inv -> inv.<Long>getArgument(2) < newerFinishedAt);
        Thread.sleep(2_000);
        otherPr.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> cache.peekResult(CHAIN).orElseThrow().computedAt() != stale);

        assertThat(analyzer.warm(TOK, PR)).as("the warm cycle analyses the newer chain").isTrue();
        assertThat(cache.peekResult(NEWER_CHAIN)).isPresent();
    }

    /**
     * The sweep acts on PR 13654 while a view's cold compute of chain 9391879 is under way, so it waits that
     * compute out and then computes the chain afresh; meanwhile RunAll 9392500 finished. The verdict the sweep
     * gets does not hide 9392500 from the warm cycle either.
     */
    @Test
    void anActionThatWaitedDoesNotHideAChainThatFinishedMeanwhile() throws Exception {
        AtomicLong lookedUpAt = new AtomicLong();
        CountDownLatch lookups = new CountDownLatch(2);
        when(chains.findBuildId(TOK, PR)).thenAnswer(inv -> {
            lookedUpAt.set(System.currentTimeMillis() / 1000);
            lookups.countDown();

            return Optional.of(CHAIN);
        });
        CountDownLatch viewComputes = new CountDownLatch(1);
        CountDownLatch viewDone = new CountDownLatch(1);
        AtomicInteger computes = new AtomicInteger();
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenAnswer(inv -> {
            if (computes.getAndIncrement() == 0)
                viewDone.await();

            return chain(CHAIN, false);
        });
        CompletableFuture<?> view = CompletableFuture.runAsync(() -> analyzer.analyze(TOK, PR));
        CompletableFuture<?> action = CompletableFuture.runAsync(() -> {
            awaitQuietly(viewComputes);
            analyzer.analyzeForAction(TOK, PR);
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> computes.get() == 1);
        viewComputes.countDown();
        assertThat(lookups.await(5, TimeUnit.SECONDS)).isTrue();

        long newerFinishedAt = lookedUpAt.get() + 1 - 60;
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(NEWER_CHAIN));
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong()))
            .thenAnswer(inv -> inv.<Long>getArgument(2) < newerFinishedAt);
        Thread.sleep(2_000);
        viewDone.countDown();
        CompletableFuture.allOf(view, action).get(5, TimeUnit.SECONDS);

        assertThat(computes).as("the action computed after the view's compute").hasValue(2);
        assertThat(analyzer.warm(TOK, PR)).as("the warm cycle analyses the newer chain").isTrue();
        assertThat(cache.peekResult(NEWER_CHAIN)).isPresent();
    }

    /**
     * No RunAll of PR 13654 has finished yet, so the running one is analysed, until a newer one starts: that
     * changes the chain while nothing finished. A running chain's verdict does not spare the lookup.
     */
    @Test
    void aNewerChainStartedWhileNoneHadFinishedIsAnalysed() {
        for (long id : List.of(CHAIN, NEWER_CHAIN))
            when(chains.collectForBuild(eq(TOK), eq(PR), eq(id), any())).thenReturn(chain(id, true));
        analyzer.warm(TOK, PR);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(NEWER_CHAIN));

        assertThat(analyzer.warm(TOK, PR)).isTrue();

        assertThat(cache.peekResult(NEWER_CHAIN)).isPresent();
    }

    private ChainCollector.Chain chain(long id, boolean running) {
        return new ChainCollector.Chain(id, "pull/13654/head", List.of(), List.of(), List.of(), 150, 0, false, 0,
            running, running ? id : 0, 0, 0, running ? 0 : finishedAt);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
