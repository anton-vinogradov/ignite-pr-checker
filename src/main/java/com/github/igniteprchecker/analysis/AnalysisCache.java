package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Caches the expensive parts of an analysis, shared across users and PRs (the data is not
 * user-specific): compact base-branch history stats per test id, and the whole result per build id.
 * Entries expire after {@code analysis.cacheTtlMinutes}; results are also refreshed by the warmer,
 * and snapshotted to disk (with their expiry) so a restart doesn't start cold.
 */
@Component
public class AnalysisCache implements SnapshotCache {
    private final TtlCache<Long, HistoryStats> history;
    private final TtlCache<Long, AnalysisResult> results;
    private final ObjectMapper mapper;

    public AnalysisCache(AnalysisProperties cfg, ObjectMapper mapper) {
        long ttlMs = Duration.ofMinutes(cfg.cacheTtlMinutes()).toMillis();
        this.history = new TtlCache<>(ttlMs);
        this.results = new TtlCache<>(ttlMs);
        this.mapper = mapper;
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

    /** Number of cached analysis results (per build id). */
    public int resultCount() {
        return results.size();
    }

    /** Number of cached per-test master-history entries. */
    public int historyCount() {
        return history.size();
    }

    /**
     * The tests that block the most distinct open PRs, across the currently cached analyses — a
     * project-wide view of which failures are gating the most work (chronic flaky/broken tests).
     */
    public List<TopBlocker> topBlockers(int limit) {
        Map<Long, Agg> byTest = new HashMap<>();
        for (AnalysisResult r : results.freshValues()) {
            for (var b : r.blockers())
                byTest.computeIfAbsent(b.testId(), k -> new Agg(b.name(), b.suiteName())).prs.add(r.prNumber());
        }

        return byTest.values().stream()
            .map(a -> new TopBlocker(a.name, a.suite, a.prs.size(), a.prs.stream().sorted().toList()))
            .sorted(Comparator.comparingInt(TopBlocker::prCount).reversed())
            .limit(limit)
            .toList();
    }

    private static final class Agg {
        final String name;
        final String suite;
        final Set<Integer> prs = new HashSet<>();

        Agg(String name, String suite) {
            this.name = name;
            this.suite = suite;
        }
    }

    /** A chronically-blocking test: which suite/test, how many open PRs it blocks, and which. */
    public record TopBlocker(String name, String suite, int prCount, List<Integer> prs) {
    }

    /** Drops all cached results and per-test history; the next analysis recomputes from scratch. */
    public Cleared clear() {
        return new Cleared(results.clear(), history.clear());
    }

    /** How many entries a {@link #clear()} removed. */
    public record Cleared(int results, int history) {
    }

    @Override
    public String fileName() {
        return "analysis.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, new Persisted(history.export(), results.export()));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        history.importAll(p.history());
        results.importAll(p.results());
    }

    private record Persisted(
        List<TtlCache.Snapshot<Long, HistoryStats>> history,
        List<TtlCache.Snapshot<Long, AnalysisResult>> results
    ) {
    }
}
