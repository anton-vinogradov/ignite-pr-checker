package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
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
     * The tests the tool filters out as <em>failing on master</em> (pre-existing/flaky), across the
     * currently cached analyses — the project-wide "fix master" queue. Ranked by master fail-rate
     * (worst first), which is the pure "how broken is master" signal, then by how many open PRs the
     * test is currently noising. Tests filtered only because a branch re-run passed (clean on master)
     * are excluded — those aren't master's fault.
     */
    public List<TopFlaky> topFlaky(int limit) {
        Map<Long, Agg> byTest = new HashMap<>();
        for (AnalysisResult r : results.freshValues()) {
            for (TestVerdict f : r.filtered())
                byTest.computeIfAbsent(f.testId(), k -> new Agg()).add(f, r.prNumber());
        }

        return byTest.entrySet().stream()
            .filter(e -> e.getValue().rep != null)
            .map(e -> {
                Agg a = e.getValue();
                HistoryStats h = history.peek(e.getKey()).orElse(null);
                int fails = h == null ? 0 : h.fails();
                int runs = h == null ? 0 : h.runs();
                return new TopFlaky(a.rep.testId(), a.rep.name(), a.rep.suite(), a.rep.suiteName(),
                    a.rep.suiteBuildId(), a.rep.occurrenceId(), a.rep.branchRuns(),
                    fails, runs, a.prs.size(), a.prs.stream().sorted().toList());
            })
            .filter(f -> f.masterFails() > 0) // only tests that actually fail on master = the "fix master" set
            .sorted(Comparator
                .comparingDouble((TopFlaky f) -> f.masterRuns() == 0 ? 0 : (double) f.masterFails() / f.masterRuns())
                .reversed()
                .thenComparing(Comparator.comparingInt(TopFlaky::prCount).reversed()))
            .limit(limit)
            .toList();
    }

    /** Aggregates one test across PRs: the distinct PRs it touches, plus its newest occurrence as a representative. */
    private static final class Agg {
        final Set<Integer> prs = new HashSet<>();
        private TestVerdict rep;

        void add(TestVerdict v, int pr) {
            prs.add(pr);
            if (rep == null || v.suiteBuildId() > rep.suiteBuildId())
                rep = v; // newest build = freshest failure to link/expand
        }
    }

    /**
     * A flaky/broken-on-master test: its identity, master fail-rate ({@code masterFails}/{@code masterRuns}),
     * how many/which open PRs it currently noises, and its latest occurrence (suite/build/occurrence +
     * branch-run strip) so the UI can link to the failure in TeamCity, expand "why", and show the runs.
     */
    public record TopFlaky(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, String branchRuns, int masterFails, int masterRuns,
        int prCount, List<Integer> prs) {
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
