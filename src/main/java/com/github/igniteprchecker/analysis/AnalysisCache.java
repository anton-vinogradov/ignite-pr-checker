package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Caches the expensive parts of an analysis, shared across users and PRs (the data is not
 * user-specific): base-branch history per test id, and the whole result per build id. Entries
 * expire after {@code analysis.cacheTtlMinutes}.
 */
@Component
public class AnalysisCache {
    private final TtlCache<Long, List<TcModel.TestOccurrence>> history;
    private final TtlCache<Long, AnalysisResult> results;

    public AnalysisCache(AnalysisProperties cfg) {
        long ttlMs = Duration.ofMinutes(cfg.cacheTtlMinutes()).toMillis();
        this.history = new TtlCache<>(ttlMs);
        this.results = new TtlCache<>(ttlMs);
    }

    public List<TcModel.TestOccurrence> history(long testId, Supplier<List<TcModel.TestOccurrence>> loader) {
        return history.get(testId, loader);
    }

    public AnalysisResult result(long buildId, Supplier<AnalysisResult> loader) {
        return results.get(buildId, loader);
    }
}
