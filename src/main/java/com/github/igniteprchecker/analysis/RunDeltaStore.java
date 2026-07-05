package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Remembers, per PR, the blocker set of the two most recent distinct RunAll builds, so the UI can
 * answer the question that actually drives iteration: "since my last run — what appeared, what got
 * fixed, what persists?". Recomputes of the same build update the latest snapshot in place (they are
 * refreshes, not new runs). Persisted, so the comparison survives restarts.
 */
@Component
public class RunDeltaStore implements SnapshotCache {
    private final ObjectMapper mapper;
    private final ConcurrentMap<Integer, Entry> byPr = new ConcurrentHashMap<>();

    public RunDeltaStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** How many trend points to keep per PR (each is a distinct RunAll build). */
    private static final int HISTORY_CAP = 30;

    /** Record a computed result: a new build id shifts the previous run down, a recompute updates in place. */
    public void onResult(int pr, long buildId, List<TestVerdict> blockers, int brokenSuites) {
        Map<Long, Ref> snapshot = new LinkedHashMap<>();
        for (TestVerdict b : blockers)
            snapshot.put(b.testId(), new Ref(b.name(), b.suite(), b.suiteName()));

        Snap snap = new Snap(buildId, snapshot);
        Point point = new Point(buildId, System.currentTimeMillis(), blockers.size(), brokenSuites);
        Entry e = byPr.computeIfAbsent(pr, k -> new Entry());
        synchronized (e) {
            if (e.latest == null || e.latest.buildId() == buildId) {
                e.latest = snap;
            }
            else {
                e.prev = e.latest;
                e.latest = snap;
            }

            // The blocker-count trend: one point per distinct build, a recompute refreshes its point
            // (a passing re-run of a suite can shrink the count for the same build).
            if (!e.history.isEmpty() && e.history.get(e.history.size() - 1).buildId() == buildId)
                e.history.set(e.history.size() - 1, point);
            else {
                e.history.add(point);
                if (e.history.size() > HISTORY_CAP)
                    e.history.remove(0);
            }
        }
    }

    /** The PR's blocker-count trend, oldest → newest (one point per distinct RunAll build). */
    public List<Point> history(int pr) {
        Entry e = byPr.get(pr);
        if (e == null)
            return List.of();

        synchronized (e) {
            return List.copyOf(e.history);
        }
    }

    /** The blocker changes between a PR's two latest distinct runs, or null when there is nothing to compare. */
    public Delta delta(int pr) {
        Entry e = byPr.get(pr);
        if (e == null)
            return null;

        Snap latest;
        Snap prev;
        synchronized (e) {
            latest = e.latest;
            prev = e.prev;
        }
        if (latest == null || prev == null)
            return null;

        List<ChangedTest> appeared = new ArrayList<>();
        List<ChangedTest> fixed = new ArrayList<>();
        int persisting = 0;

        for (Map.Entry<Long, Ref> b : latest.blockers().entrySet()) {
            if (prev.blockers().containsKey(b.getKey()))
                persisting++;
            else
                appeared.add(changed(b.getValue()));
        }
        for (Map.Entry<Long, Ref> b : prev.blockers().entrySet()) {
            if (!latest.blockers().containsKey(b.getKey()))
                fixed.add(changed(b.getValue()));
        }

        return new Delta(prev.buildId(), latest.buildId(), appeared, fixed, persisting);
    }

    private static ChangedTest changed(Ref r) {
        return new ChangedTest(r.name(), r.suite(), r.suiteName());
    }

    @Override
    public String fileName() {
        return "delta.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        byPr.forEach((pr, e) -> {
            synchronized (e) {
                snap.add(new Persisted(pr, e.latest, e.prev, List.copyOf(e.history)));
            }
        });
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class)) {
            Entry e = new Entry();
            e.latest = p.latest();
            e.prev = p.prev();
            if (p.history() != null)
                e.history.addAll(p.history());
            byPr.put(p.pr(), e);
        }
    }

    private static final class Entry {
        volatile Snap latest;
        volatile Snap prev;
        final List<Point> history = new ArrayList<>();
    }

    /** One run's blocker set: build id + test id -> identity. */
    record Snap(long buildId, Map<Long, Ref> blockers) {
    }

    record Ref(String name, String suite, String suiteName) {
    }

    private record Persisted(int pr, Snap latest, Snap prev, List<Point> history) {
    }

    /** One trend point: a distinct RunAll build and what its analysis found. */
    public record Point(long buildId, long at, int blockers, int brokenSuites) {
    }

    /** A blocker that appeared or got fixed between two runs. */
    public record ChangedTest(String name, String suite, String suiteName) {
    }

    /** Blocker changes between the previous and the latest run of a PR. */
    public record Delta(long prevBuildId, long latestBuildId,
        List<ChangedTest> appeared, List<ChangedTest> fixed, int persisting) {
    }
}
