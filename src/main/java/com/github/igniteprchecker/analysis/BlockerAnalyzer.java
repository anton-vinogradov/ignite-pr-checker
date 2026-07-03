package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise, using the
 * test's base-branch history. Mirrors tcbot's rule: a failure is a blocker only if the test's
 * base-branch fail rate is below the threshold and it is not flaky (flips pass/fail on builds that
 * carried no code changes). Results are cached per build; a request serves the cached result and,
 * if it is getting stale, triggers a background refresh.
 */
@Component
public class BlockerAnalyzer {
    private final TcClient tc;
    private final ChainCollector chains;
    private final AnalysisProperties cfg;
    private final ExecutorService pool;
    private final ExecutorService bgPool;
    private final ExecutorService refreshPool;
    private final AnalysisCache cache;

    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg,
        @Qualifier("analysisExecutor") ExecutorService pool,
        @Qualifier("backgroundExecutor") ExecutorService bgPool,
        @Qualifier("refreshExecutor") ExecutorService refreshPool,
        AnalysisCache cache) {
        this.tc = tc;
        this.chains = chains;
        this.cfg = cfg;
        this.pool = pool;
        this.bgPool = bgPool;
        this.refreshPool = refreshPool;
        this.cache = cache;
    }

    /** @return the analysis (cached if available), or empty if no RunAll build exists for the PR yet. */
    public Optional<AnalysisResult> analyze(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return Optional.empty();

        long bid = buildId.get();
        Optional<AnalysisResult> cached = cache.peekResult(bid);
        if (cached.isPresent()) {
            if (isStale(cached.get()))
                refreshAsync(token, prNumber, bid);

            return cached;
        }

        return Optional.of(computeAndStore(token, prNumber, bid, pool));
    }

    /** Recomputes and caches the analysis for a PR's latest build. Used by the warmer (background pool). */
    public void refresh(String token, int prNumber) {
        chains.findBuildId(token, prNumber).ifPresent(bid -> computeAndStore(token, prNumber, bid, bgPool));
    }

    private boolean isStale(AnalysisResult r) {
        return System.currentTimeMillis() - r.computedAt() > cfg.refreshAfterSeconds() * 1000L;
    }

    private void refreshAsync(String token, int prNumber, long buildId) {
        if (!refreshing.add(buildId))
            return;

        refreshPool.execute(() -> {
            try {
                computeAndStore(token, prNumber, buildId, bgPool);
            }
            catch (RuntimeException ignore) {
                // best-effort background refresh; the stale cached value stays until it succeeds
            }
            finally {
                refreshing.remove(buildId);
            }
        });
    }

    private AnalysisResult computeAndStore(String token, int prNumber, long buildId, ExecutorService taskPool) {
        ChainCollector.Chain chain = chains.collectForBuild(token, buildId);

        List<Callable<TestVerdict>> tasks = chain.failedTests().stream()
            .<Callable<TestVerdict>>map(t -> () -> classify(token, t))
            .toList();

        List<TestVerdict> verdicts = Parallel.run(taskPool, tasks);
        List<TestVerdict> blockers = verdicts.stream().filter(TestVerdict::blocker).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker()).toList();

        AnalysisResult result = new AnalysisResult(prNumber, buildId, chain.branchName(),
            System.currentTimeMillis(), blockers, filtered);

        cache.putResult(buildId, result);

        return result;
    }

    private TestVerdict classify(String token, FailedTest t) {
        HistoryStats h = cache.history(t.testId(),
            () -> HistoryStats.of(tc.getBaseBranchHistory(token, t.testId())));

        if (h.runs() == 0) {
            // No base-branch history to prove the failure is pre-existing -> treat as a blocker.
            return new TestVerdict(t.testId(), t.name(), t.suite(), true,
                "no base-branch history (can't prove pre-existing)", 0.0, 0, 0);
        }

        double failRatePct = 100.0 * h.fails() / h.runs();
        boolean flaky = h.flips() >= cfg.flakinessStatusChangeBorder();
        boolean lowFailRate = failRatePct < cfg.failRateBlockerThresholdPercents();

        if (lowFailRate && !flaky) {
            return new TestVerdict(t.testId(), t.name(), t.suite(), true,
                String.format(Locale.ROOT, "base fail-rate %.1f%% < %.1f%%, not flaky",
                    failRatePct, cfg.failRateBlockerThresholdPercents()),
                failRatePct, h.flips(), h.runs());
        }

        String reason = flaky
            ? String.format(Locale.ROOT, "flaky: %d status flip(s) without code changes", h.flips())
            : String.format(Locale.ROOT, "pre-existing: base fail-rate %.1f%% >= %.1f%%",
                failRatePct, cfg.failRateBlockerThresholdPercents());

        return new TestVerdict(t.testId(), t.name(), t.suite(), false, reason, failRatePct, h.flips(), h.runs());
    }
}
