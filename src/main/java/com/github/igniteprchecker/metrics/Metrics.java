package com.github.igniteprchecker.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory service metrics for the status page: outbound-call counts, success and latency, broken
 * down by <em>category</em> of call (which TeamCity/GitHub endpoint), plus a rolling per-minute
 * series of TeamCity calls (how many, when, how many failed). Thread-safe and cheap. Snapshotted to
 * disk (via {@link SnapshotCache}) so counts survive restarts/deploys.
 */
@Component
public class Metrics implements SnapshotCache {
    private static final int WINDOW = 60; // minutes kept in the rolling per-minute series

    private final ObjectMapper mapper;
    private final long startedAt = System.currentTimeMillis();

    private final Map<String, Counter> tc = new ConcurrentHashMap<>();
    private final Map<String, Counter> github = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> tcByStatus = new ConcurrentHashMap<>();

    private final long[] minute = new long[WINDOW];
    private final long[] okAt = new long[WINDOW];
    private final long[] failAt = new long[WINDOW];

    public Metrics(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Record one TeamCity call. {@code status} is the HTTP code, or 0 for a network/other error. */
    public void recordTc(String category, boolean ok, int status, long latencyMs) {
        tc.computeIfAbsent(category, c -> new Counter()).record(ok, latencyMs);
        if (!ok)
            tcByStatus.computeIfAbsent(status, s -> new AtomicLong()).incrementAndGet();
        bump(ok);
    }

    /** Record one GitHub call (categories: prs / star / release). */
    public void recordGithub(String category, boolean ok, long latencyMs) {
        github.computeIfAbsent(category, c -> new Counter()).record(ok, latencyMs);
    }

    private synchronized void bump(boolean ok) {
        long m = System.currentTimeMillis() / 60_000L;
        int i = (int) Math.floorMod(m, WINDOW);
        if (minute[i] != m) { // rolled into a new minute: reset this slot
            minute[i] = m;
            okAt[i] = 0;
            failAt[i] = 0;
        }
        if (ok)
            okAt[i]++;
        else
            failAt[i]++;
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }

    public Group teamcity() {
        return new Group(aggregate(tc), byCategory(tc), byStatusView(), perMinute());
    }

    public Group github() {
        return new Group(aggregate(github), byCategory(github), Map.of(), List.of());
    }

    private static Stats aggregate(Map<String, Counter> counters) {
        Counter all = new Counter();
        counters.values().forEach(c -> all.add(c));

        return all.stats();
    }

    private static Map<String, Stats> byCategory(Map<String, Counter> counters) {
        Map<String, Stats> out = new LinkedHashMap<>();
        counters.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().total.get(), a.getValue().total.get()))
            .forEach(e -> out.put(e.getKey(), e.getValue().stats()));

        return out;
    }

    private Map<Integer, Long> byStatusView() {
        Map<Integer, Long> out = new TreeMap<>();
        tcByStatus.forEach((k, v) -> out.put(k, v.get()));

        return out;
    }

    private synchronized List<Minute> perMinute() {
        long nowMin = System.currentTimeMillis() / 60_000L;
        List<Minute> out = new ArrayList<>(WINDOW);

        for (long m = nowMin - (WINDOW - 1); m <= nowMin; m++) {
            int i = (int) Math.floorMod(m, WINDOW);
            boolean live = minute[i] == m;
            out.add(new Minute(m * 60_000L, live ? okAt[i] : 0, live ? failAt[i] : 0));
        }

        return out;
    }

    // --- persistence -------------------------------------------------------------------------

    @Override
    public String fileName() {
        return "metrics.json";
    }

    @Override
    public synchronized void saveTo(Path file) throws IOException {
        List<Minute> ring = new ArrayList<>();
        for (int i = 0; i < WINDOW; i++)
            if (minute[i] > 0)
                ring.add(new Minute(minute[i] * 60_000L, okAt[i], failAt[i]));

        Snapshots.writeAtomic(mapper, file, new Persisted(states(tc), states(github), byStatusView(), ring));
    }

    @Override
    public synchronized void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        p.tc().forEach((k, v) -> tc.put(k, Counter.of(v)));
        p.github().forEach((k, v) -> github.put(k, Counter.of(v)));
        p.byStatus().forEach((k, v) -> tcByStatus.put(k, new AtomicLong(v)));

        long nowMin = System.currentTimeMillis() / 60_000L;
        for (Minute m : p.ring()) {
            long mm = m.t() / 60_000L;
            if (mm > nowMin - WINDOW && mm <= nowMin) { // still inside the window
                int i = (int) Math.floorMod(mm, WINDOW);
                minute[i] = mm;
                okAt[i] = m.ok();
                failAt[i] = m.fail();
            }
        }
    }

    private static Map<String, CounterState> states(Map<String, Counter> counters) {
        Map<String, CounterState> out = new LinkedHashMap<>();
        counters.forEach((k, c) -> out.put(k, c.state()));

        return out;
    }

    private static final class Counter {
        final AtomicLong total = new AtomicLong();
        final AtomicLong ok = new AtomicLong();
        final AtomicLong fail = new AtomicLong();
        final AtomicLong latencySum = new AtomicLong();
        final AtomicLong latencyMax = new AtomicLong();

        static Counter of(CounterState s) {
            Counter c = new Counter();
            c.total.set(s.total());
            c.ok.set(s.ok());
            c.fail.set(s.fail());
            c.latencySum.set(s.latencySum());
            c.latencyMax.set(s.latencyMax());

            return c;
        }

        void record(boolean okFlag, long ms) {
            total.incrementAndGet();
            (okFlag ? ok : fail).incrementAndGet();
            latencySum.addAndGet(ms);
            latencyMax.accumulateAndGet(ms, Math::max);
        }

        void add(Counter o) {
            total.addAndGet(o.total.get());
            ok.addAndGet(o.ok.get());
            fail.addAndGet(o.fail.get());
            latencySum.addAndGet(o.latencySum.get());
            latencyMax.accumulateAndGet(o.latencyMax.get(), Math::max);
        }

        Stats stats() {
            long t = total.get();

            return new Stats(t, ok.get(), fail.get(), t > 0 ? latencySum.get() / t : 0, latencyMax.get());
        }

        CounterState state() {
            return new CounterState(total.get(), ok.get(), fail.get(), latencySum.get(), latencyMax.get());
        }
    }

    /** A category group: aggregate stats, a per-category breakdown, and (for TC) failures-by-status and the per-minute series. */
    public record Group(Stats total, Map<String, Stats> byCategory, Map<Integer, Long> byStatus, List<Minute> perMinute) {
    }

    public record Stats(long total, long ok, long fail, long avgLatencyMs, long maxLatencyMs) {
    }

    public record Minute(long t, long ok, long fail) {
    }

    private record CounterState(long total, long ok, long fail, long latencySum, long latencyMax) {
    }

    private record Persisted(Map<String, CounterState> tc, Map<String, CounterState> github,
        Map<Integer, Long> byStatus, List<Minute> ring) {
    }
}
