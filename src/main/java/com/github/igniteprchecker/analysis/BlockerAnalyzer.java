package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise, using the
 * test's master-branch history: a test that fails in the PR is a blocker unless it also fails at
 * least once in the last {@code analysis.historyDepth} master runs (any master failure means the
 * failure is pre-existing or flaky on master, not caused by this PR). Results are cached per build;
 * a request serves the cached result and, if it is getting stale, triggers a background refresh.
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

        // A failure in the PR is a blocker unless the test also fails in master history: any failure
        // there means it isn't specific to this PR (pre-existing or flaky on master).
        if (h.fails() > 0) {
            return new TestVerdict(t.testId(), t.name(), t.suite(), false,
                "pre-existing: fails " + h.fails() + "/" + h.runs() + " on master");
        }

        String reason = h.runs() == 0
            ? "no master history (can't prove pre-existing)"
            : "not seen failing in " + h.runs() + " master run(s)";

        return new TestVerdict(t.testId(), t.name(), t.suite(), true, reason);
    }
}
