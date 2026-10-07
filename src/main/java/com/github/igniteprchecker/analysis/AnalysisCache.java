package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caches the expensive parts of an analysis, shared across users and PRs (the data is not
 * user-specific): a compact base-branch history per test and suite, and the whole result per build
 * id. Entries expire after {@code analysis.cacheTtlMinutes}; results are also refreshed by the warmer,
 * and snapshotted to disk (with their expiry) so a restart doesn't start cold.
 */
@Component
public class AnalysisCache implements SnapshotCache {
    private final TtlCache<HistoryKey, RunHistory> history;
    private final TtlCache<Long, AnalysisResult> results;
    private final TtlCache<Long, String> revisions;
    private final ObjectMapper mapper;

    public AnalysisCache(AnalysisProperties cfg, ObjectMapper mapper) {
        long ttlMs = Duration.ofMinutes(cfg.cacheTtlMinutes()).toMillis();
        this.history = new TtlCache<>(ttlMs);
        this.results = new TtlCache<>(ttlMs);
        this.revisions = new TtlCache<>(ttlMs);
        this.mapper = mapper;
    }

    RunHistory history(long testId, String buildTypeId, Supplier<RunHistory> loader) {
        return history.get(new HistoryKey(testId, buildTypeId), loader);
    }

    /**
     * The revision a build ran on, {@code ""} when it has none. Only used where TeamCity didn't inline
     * revisions into the test occurrences; a build's revision never changes, and one suite build backs
     * the runs of hundreds of tests, so this collapses that fallback to one request per build. Not
     * snapshotted — it is cheap to re-learn and worthless once the builds age out.
     */
    String revision(long buildId, Supplier<String> loader) {
        return revisions.get(buildId, loader);
    }

    /** The cached result for a build, if fresh; never recomputes. */
    public Optional<AnalysisResult> peekResult(long buildId) {
        return results.peek(buildId);
    }

    /** Restarts a cached result's TTL — per-build results are immutable, so this is always safe. */
    public void touchResult(long buildId) {
        results.touch(buildId);
    }

    public void putResult(long buildId, AnalysisResult result) {
        results.put(buildId, result);
    }

    /** Number of cached analysis results (per build id). */
    public int resultCount() {
        return results.size();
    }

    /** Number of cached master-history entries (one per test and suite). */
    public int historyCount() {
        return history.size();
    }

    /** The currently-fresh cached analysis results (for the flaky-stats harvester). */
    public List<AnalysisResult> freshResults() {
        return results.freshValues();
    }

    /**
     * A test's cached master history in a suite over all its runs, if still fresh (gives the fail-rate
     * without a TeamCity call).
     */
    public Optional<HistoryStats> historyOf(long testId, String buildTypeId) {
        return history.peek(new HistoryKey(testId, buildTypeId)).map(RunHistory::all);
    }

    /** Sweeps out expired entries so long uptimes don't accumulate dead results/history in memory
     * (and the status page's cached-counts stay honest). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        history.evictExpired();
        results.evictExpired();
        revisions.evictExpired();
    }

    /** Drops all cached results and per-test history; the next analysis recomputes from scratch. */
    public Cleared clear() {
        revisions.clear(); // an input to the results, not a result — nothing to report separately

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
        Snapshots.writeAtomic(mapper, file,
            new Persisted(history.export(), results.export(), TestVerdict.RULES));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        // History doesn't depend on the rules, but it is master's latest runs as of its fetch, so it
        // must not outlive its TTL the way the immutable results below may. A snapshot from before
        // history kept its runs in order with their conditions has no masterHistory at all: that
        // history is simply re-fetched.
        if (p.masterHistory() != null)
            history.importUnexpired(p.masterHistory());
        // Results carry verdicts, and a cached result for an unchanged build is never recomputed (the
        // warmer keeps touching it), so verdicts from superseded rules would otherwise outlive the
        // deploy that fixed them.
        if (p.rules() != null && p.rules() == TestVerdict.RULES)
            results.importAll(p.results());
    }

    /**
     * Master history is per suite: one test id runs in several suites of a chain (the C++ tests run on
     * Windows, Linux and Clang), and each suite has its own failure rate on master.
     */
    record HistoryKey(long testId, String buildTypeId) {
    }

    /**
     * Older snapshots carry master history this release cannot use, so it is not read: {@code history},
     * keyed by test id alone, mixes every suite of the test; {@code suiteHistory} keeps per suite only how
     * many runs failed, without their order or the JDK each ran on.
     */
    @JsonIgnoreProperties({"history", "suiteHistory"})
    private record Persisted(
        List<TtlCache.Snapshot<HistoryKey, RunHistory>> masterHistory,
        List<TtlCache.Snapshot<Long, AnalysisResult>> results,
        Integer rules
    ) {
    }
}
