package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Caches the expensive parts of an analysis, shared across users and PRs (the data is not
 * user-specific): compact base-branch history stats per test id, and the whole result per build id.
 * Entries expire after {@code analysis.cacheTtlMinutes}; results are also refreshed by the warmer.
 */
@Component
public class AnalysisCache {
    private final TtlCache<Long, HistoryStats> history;
    private final TtlCache<Long, AnalysisResult> results;

    public AnalysisCache(AnalysisProperties cfg) {
        long ttlMs = Duration.ofMinutes(cfg.cacheTtlMinutes()).toMillis();
        this.history = new TtlCache<>(ttlMs);
        this.results = new TtlCache<>(ttlMs);
    }

    HistoryStats history(long testId, Supplier<HistoryStats> loader) {
        return history.get(testId, loader);
    }

    /** The cached result for a build, if fresh; never recomputes. */
    public Optional<AnalysisResult> peekResult(long buildId) {
        return results.peek(buildId);
    }

    public void putResult(long buildId, AnalysisResult result) {
        results.put(buildId, result);
    }
}
