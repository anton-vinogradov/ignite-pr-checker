package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A durable, accumulating record of tests that fail on master (the "fix master" queue). Unlike the
 * 15-minute analysis cache — which decays to nothing when nobody is using the app, so a live scan
 * would falsely report "master looks clean" — this store harvests each cached analysis into a
 * persisted, per-test tally that survives idle periods and restarts. Entries not re-observed within
 * {@link #RETAIN} are pruned (a test that got fixed drops off), so the list stays current without
 * vanishing between warm cycles.
 */
@Component
public class FlakyStats implements SnapshotCache {
    private static final long RETAIN = Duration.ofDays(14).toMillis();

    private final AnalysisCache cache;
    private final ObjectMapper mapper;
    private final ConcurrentMap<Long, Entry> byTest = new ConcurrentHashMap<>();

    public FlakyStats(AnalysisCache cache, ObjectMapper mapper) {
        this.cache = cache;
        this.mapper = mapper;
    }

    /**
     * Fold the currently cached analyses into the persistent tally: for every test filtered out as
     * failing on master, record its latest fail-rate/occurrence and the PR it was seen in. Cheap and
     * idempotent; runs on a timer so the store stays fresh while warm and simply retains what it has
     * while the cache is cold.
     */
    @Scheduled(fixedDelay = 180_000, initialDelay = 20_000)
    void harvest() {
        for (AnalysisResult r : cache.freshResults()) {
            for (TestVerdict f : r.filtered()) {
                HistoryStats h = cache.historyOf(f.testId()).orElse(null);
                if (h != null && h.fails() > 0) // fails on master (not merely a branch re-run pass)
                    record(f, h.fails(), h.runs(), r.prNumber());
            }
        }
    }

    private void record(TestVerdict f, int masterFails, int masterRuns, int pr) {
        Entry e = byTest.computeIfAbsent(f.testId(), k -> new Entry());
        synchronized (e) {
            e.name = f.name();
            e.suite = f.suite();
            e.suiteName = f.suiteName();
            e.suiteBuildId = f.suiteBuildId();
            e.occurrenceId = f.occurrenceId();
            e.branchRuns = f.branchRuns() == null ? "" : f.branchRuns();
            e.masterFails = masterFails;
            e.masterRuns = masterRuns;
            e.prs.add(pr);
            e.lastSeen = System.currentTimeMillis();
        }
    }

    /** Recently-seen flaky/broken-on-master tests, worst master fail-rate first. Prunes stale entries. */
    public List<TopFlaky> top(int limit) {
        long now = System.currentTimeMillis();
        byTest.values().removeIf(e -> now - e.lastSeen > RETAIN);

        List<TopFlaky> out = new ArrayList<>();
        byTest.forEach((id, e) -> {
            synchronized (e) {
                if (e.masterFails > 0)
                    out.add(new TopFlaky(id, e.name, e.suite, e.suiteName, e.suiteBuildId, e.occurrenceId,
                        e.branchRuns, e.masterFails, e.masterRuns, e.prs.size(), e.prs.stream().sorted().toList()));
            }
        });

        out.sort(Comparator
            .comparingDouble((TopFlaky f) -> f.masterRuns() == 0 ? 0 : (double) f.masterFails() / f.masterRuns())
            .reversed()
            .thenComparing(Comparator.comparingInt(TopFlaky::prCount).reversed()));

        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** How many flaky/broken-on-master tests are currently tracked (to tell "no data yet" from "clean"). */
    public int trackedCount() {
        return byTest.size();
    }

    @Override
    public String fileName() {
        return "flaky.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        byTest.forEach((id, e) -> {
            synchronized (e) {
                snap.add(new Persisted(id, e.name, e.suite, e.suiteName, e.suiteBuildId, e.occurrenceId,
                    e.branchRuns, e.masterFails, e.masterRuns, e.lastSeen, e.prs.stream().sorted().toList()));
            }
        });
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        long now = System.currentTimeMillis();
        Persisted[] snap = mapper.readValue(file.toFile(), Persisted[].class);
        for (Persisted p : snap) {
            if (now - p.lastSeen() > RETAIN)
                continue;
            Entry e = new Entry();
            e.name = p.name();
            e.suite = p.suite();
            e.suiteName = p.suiteName();
            e.suiteBuildId = p.suiteBuildId();
            e.occurrenceId = p.occurrenceId();
            e.branchRuns = p.branchRuns() == null ? "" : p.branchRuns();
            e.masterFails = p.masterFails();
            e.masterRuns = p.masterRuns();
            e.lastSeen = p.lastSeen();
            if (p.prs() != null)
                e.prs.addAll(p.prs());
            byTest.put(p.testId(), e);
        }
    }

    private static final class Entry {
        String name;
        String suite;
        String suiteName;
        long suiteBuildId;
        String occurrenceId = "";
        String branchRuns = "";
        int masterFails;
        int masterRuns;
        long lastSeen;
        final Set<Integer> prs = ConcurrentHashMap.newKeySet();
    }

    private record Persisted(long testId, String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, String branchRuns, int masterFails, int masterRuns, long lastSeen, List<Integer> prs) {
    }

    /**
     * A flaky/broken-on-master test: identity, master fail-rate ({@code masterFails}/{@code masterRuns}),
     * how many/which open PRs recently hit it, and its latest occurrence (suite/build/occurrence) so the
     * UI can link to the failure in TeamCity, expand "why", and draw the branch pass/fail strip.
     */
    public record TopFlaky(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, String branchRuns, int masterFails, int masterRuns,
        int prCount, List<Integer> prs) {
    }
}
