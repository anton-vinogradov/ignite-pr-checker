package com.github.igniteprchecker.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory service metrics for the public status page: call counts, success/latency and a rolling
 * per-minute series of outbound TeamCity calls (how many, when, and how many failed). Thread-safe
 * and cheap; nothing is persisted (a restart resets the counters).
 */
@Component
public class Metrics {
    private static final int WINDOW = 60; // minutes kept in the rolling per-minute series

    private final long startedAt = System.currentTimeMillis();

    private final Counter tc = new Counter();
    private final Counter github = new Counter();
    private final Map<Integer, AtomicLong> tcByStatus = new ConcurrentHashMap<>();

    // Rolling ring of one-minute buckets, indexed by (epochMinute mod WINDOW).
    private final long[] minute = new long[WINDOW];
    private final long[] okAt = new long[WINDOW];
    private final long[] failAt = new long[WINDOW];

    /** Record one TeamCity call. {@code status} is the HTTP code, or 0 for a network/other error. */
    public void recordTc(boolean ok, int status, long latencyMs) {
        tc.record(ok, latencyMs);
        if (!ok)
            tcByStatus.computeIfAbsent(status, s -> new AtomicLong()).incrementAndGet();
        bump(ok);
    }

    /** Record one GitHub call (PR list / star / release lookups). */
    public void recordGithub(boolean ok, long latencyMs) {
        github.record(ok, latencyMs);
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

    public Stats teamcity() {
        return tc.stats(byStatusView(), perMinute());
    }

    public Stats github() {
        return github.stats(Map.of(), List.of());
    }

    private Map<Integer, Long> byStatusView() {
        Map<Integer, Long> out = new TreeMap<>();
        tcByStatus.forEach((k, v) -> out.put(k, v.get()));

        return out;
    }

    /** The last {@code WINDOW} minutes (oldest first), each with its ok/fail counts (0 if no calls). */
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

    private static final class Counter {
        final AtomicLong total = new AtomicLong();
        final AtomicLong ok = new AtomicLong();
        final AtomicLong fail = new AtomicLong();
        final AtomicLong latencySum = new AtomicLong();
        final AtomicLong latencyMax = new AtomicLong();

        void record(boolean okFlag, long ms) {
            total.incrementAndGet();
            (okFlag ? ok : fail).incrementAndGet();
            latencySum.addAndGet(ms);
            latencyMax.accumulateAndGet(ms, Math::max);
        }

        Stats stats(Map<Integer, Long> byStatus, List<Minute> perMinute) {
            long t = total.get();
            return new Stats(t, ok.get(), fail.get(),
                t > 0 ? latencySum.get() / t : 0, latencyMax.get(), byStatus, perMinute);
        }
    }

    public record Stats(long total, long ok, long fail, long avgLatencyMs, long maxLatencyMs,
        Map<Integer, Long> byStatus, List<Minute> perMinute) {
    }

    public record Minute(long t, long ok, long fail) {
    }
}
