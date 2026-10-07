package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PersistProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * One pool of two threads ran the warm cycle's PRs, the refresh a view started and the recompute after a re-run
 * suite finished, first come first served, and the warm cycle's calls held the background fan-out pool too: a
 * re-run's result reached the page up to a minute late. A recompute on request now has a thread and a fan-out
 * pool of its own: a busy warm cycle does not hold it up, and it does not hold up a compute a user waits on.
 */
class RecomputeThreadsTest {
    private static final String TOK = "t";

    private static final int PR = 13654;

    private static final long CHAIN = 9391879L;

    private static final int OTHER_PR = 13583;

    private static final long OTHER_CHAIN = 9390000L;

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, 120, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, new ObjectMapper());

    private final ExecutorService foreground = Executors.newFixedThreadPool(2);

    /** The warm cycle's fan-out pool, every thread of it busy with a warm that waits on TeamCity. */
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    /** The warm cycle's PRs, both threads busy. */
    private final ExecutorService warmPrs = Executors.newFixedThreadPool(2);

    private final ExecutorService recompute = Executors.newSingleThreadExecutor();

    private final ExecutorService recomputeFanOut = Executors.newSingleThreadExecutor();

    private final ExecutorService warmerThread = Executors.newSingleThreadExecutor();

    private final CountDownLatch warmCycleWaits = new CountDownLatch(1);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, foreground, background, recompute,
        recomputeFanOut, cache, new RunDeltaStore(new ObjectMapper()));

    private final long stale = System.currentTimeMillis() - 3_600_000;

    RecomputeThreadsTest() throws Exception {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        // A compute fans its calls out on the pool it is given, as collectForBuild does.
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenAnswer(inv -> {
            ExecutorService fanOut = inv.getArgument(3);
            fanOut.submit(() -> { }).get(10, TimeUnit.SECONDS);

            return new ChainCollector.Chain(CHAIN, "pull/13654/head", List.of(), List.of(), List.of(), 150, 0, false,
                0, false, 0, 0, 0, 0);
        });
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true);
        cache.putResult(CHAIN, new AnalysisResult(PR, CHAIN, "pull/13654/head", stale, List.of(), List.of(),
            List.of(), List.of(), List.of(), 150, 0, false, 0, false, 0, 0, 0, 0, stale / 1000 - 60));
        for (ExecutorService busy : List.of(background, warmPrs, warmPrs))
            busy.execute(this::waitForTeamCity);
    }

    @AfterEach
    void stop() {
        warmCycleWaits.countDown();
        for (ExecutorService pool : List.of(foreground, background, warmPrs, recompute, recomputeFanOut, warmerThread))
            pool.shutdownNow();
    }

    /** PR 13654's page asked for its verdict after a suite re-run finished, in the middle of a warm cycle. */
    @Test
    void aViewsRecomputeDoesNotWaitForTheWarmCycle() {
        analyzer.analyze(TOK, PR);

        await().atMost(Duration.ofSeconds(5)).until(this::recomputed);
    }

    @Test
    void theRecomputeAfterAReRunDoesNotWaitForTheWarmCycle() {
        Warmer warmer = warmer();
        warmer.offerToken(TOK);

        warmer.refreshPr(PR);

        await().atMost(Duration.ofSeconds(5)).until(this::recomputed);
    }

    /** RunAll 9391879 finished: the eager warm of its PR does not queue behind the warm cycle either. */
    @Test
    void theWarmOfAFinishedRunAllDoesNotWaitForTheWarmCycle() {
        Warmer warmer = warmer();
        warmer.offerToken(TOK);

        warmer.warmPr(PR);

        await().atMost(Duration.ofSeconds(5)).until(this::recomputed);
    }

    /**
     * RunAll 9391879 of PR 13654 finishes with hundreds of failed tests, the warm that follows fans their calls
     * out, and a user opens PR 13583 cold at that moment. On the pool of the computes users wait on, the user's
     * calls would queue behind all of them: a compute a user waits on does not wait for one nobody waits on.
     */
    @Test
    void aUserDoesNotWaitBehindTheWarmOfAFinishedRunAll() throws Exception {
        Warmer warmer = warmer();
        warmer.offerToken(TOK);

        assertAUserDoesNotWaitBehind(() -> warmer.warmPr(PR));
    }

    @Test
    void aUserDoesNotWaitBehindTheRecomputeAfterAReRun() throws Exception {
        Warmer warmer = warmer();
        warmer.offerToken(TOK);

        assertAUserDoesNotWaitBehind(() -> warmer.refreshPr(PR));
    }

    @Test
    void aUserDoesNotWaitBehindAViewsRecompute() throws Exception {
        assertAUserDoesNotWaitBehind(() -> analyzer.analyze(TOK, PR));
    }

    /**
     * The analysis is wired the way the application wires it: every pool it names is a bean of AnalysisConfig,
     * and every pool stops with the application.
     */
    @Test
    void theAnalysisPoolsAreBeansThatStopWithTheApplication() {
        List<ExecutorService> pools;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(AnalysisProperties.class, () -> cfg);
            context.registerBean(WarmProperties.class, () -> new WarmProperties(false, 50, 10, 60));
            context.registerBean(GithubClient.class, () -> mock(GithubClient.class));
            context.registerBean(TcClient.class, () -> tc);
            context.registerBean(SuiteBaseline.class, () -> mock(SuiteBaseline.class));
            context.registerBean(PersistProperties.class, () -> new PersistProperties(false, null, null));
            context.register(AnalysisConfig.class, AnalysisCache.class, RunDeltaStore.class, ChainCollector.class,
                MergedVerdicts.class, BlockerAnalyzer.class, Warmer.class);
            context.refresh();

            assertThat(context.getBean(Warmer.class)).isNotNull();
            pools = List.copyOf(context.getBeansOfType(ExecutorService.class).values());
            assertThat(context.getBeansOfType(ExecutorService.class)).containsKeys("analysisExecutor",
                "backgroundExecutor", "warmExecutor", "recomputeExecutor", "recomputeFanOutExecutor", "warmerThread",
                "causesExecutor");
        }

        assertThat(pools).allMatch(ExecutorService::isShutdown);
    }

    /**
     * Starts a recompute of PR 13654 whose 500 calls wait on TeamCity, then has a user open PR 13583 cold: its
     * compute must get its one call through.
     */
    private void assertAUserDoesNotWaitBehind(Runnable recomputeOfPr) throws Exception {
        CountDownLatch fannedOut = new CountDownLatch(1);
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenAnswer(inv -> {
            ExecutorService fanOut = inv.getArgument(3);
            for (int i = 0; i < 500; i++)
                fanOut.execute(this::waitForTeamCity);
            fannedOut.countDown();

            return new ChainCollector.Chain(CHAIN, "pull/13654/head", List.of(), List.of(), List.of(), 150, 0, false,
                0, false, 0, 0, 0, 0);
        });
        when(chains.findBuildId(TOK, OTHER_PR)).thenReturn(Optional.of(OTHER_CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(OTHER_PR), eq(OTHER_CHAIN), any())).thenAnswer(inv -> {
            ExecutorService fanOut = inv.getArgument(3);
            fanOut.submit(() -> { }).get(3, TimeUnit.SECONDS);

            return new ChainCollector.Chain(OTHER_CHAIN, "pull/13583/head", List.of(), List.of(), List.of(), 150, 0,
                false, 0, false, 0, 0, 0, 0);
        });

        recomputeOfPr.run();
        assertThat(fannedOut.await(5, TimeUnit.SECONDS)).as("the recompute fanned its calls out").isTrue();

        assertThat(analyzer.analyze(TOK, OTHER_PR)).isPresent();
    }

    private Warmer warmer() {
        GithubClient github = mock(GithubClient.class);
        when(github.openPrs()).thenReturn(List.of());

        return new Warmer(analyzer, github, new WarmProperties(true, 50, 10, 60), warmPrs, recompute, warmerThread);
    }

    private boolean recomputed() {
        return cache.peekResult(CHAIN).map(AnalysisResult::computedAt).orElse(stale) != stale;
    }

    private void waitForTeamCity() {
        try {
            warmCycleWaits.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
