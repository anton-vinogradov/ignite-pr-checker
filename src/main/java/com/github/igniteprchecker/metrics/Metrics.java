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
 * In-memory service metrics for the status page. The breakdowns (per-category counts, latency, the
 * per-minute series) are over a rolling <b>last hour</b> — that's what's actionable — while a plain
 * lifetime total ("since start") is kept per target and persisted. Thread-safe and cheap; the
 * rolling windows are in-memory (rebuild within an hour after a restart), the lifetime totals survive.
 */
@Component
public class Metrics implements SnapshotCache {
    static final int WINDOW = 60; // minutes in the rolling window

    private final ObjectMapper mapper;
    private final long startedAt = System.currentTimeMillis();

    private final Map<String, Ring> tc = new ConcurrentHashMap<>();
    private final Map<String, Ring> github = new ConcurrentHashMap<>();
    private final Map<String, Ring> http = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> tcByStatus = new ConcurrentHashMap<>();

    private final AtomicLong tcSinceStart = new AtomicLong();
    private final AtomicLong githubSinceStart = new AtomicLong();
    private final AtomicLong httpSinceStart = new AtomicLong();

    public Metrics(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Record one TeamCity call. {@code status} is the HTTP code, or 0 for a network/other error. */
    public void recordTc(String category, boolean ok, int status, long latencyMs) {
        tc.computeIfAbsent(category, c -> new Ring()).record(ok, latencyMs);
        tcSinceStart.incrementAndGet();
        if (!ok)
            tcByStatus.computeIfAbsent(status, s -> new AtomicLong()).incrementAndGet();
    }

    /** Record one GitHub call (categories: prs / star / release). */
    public void recordGithub(String category, boolean ok, long latencyMs) {
        github.computeIfAbsent(category, c -> new Ring()).record(ok, latencyMs);
        githubSinceStart.incrementAndGet();
    }

    /** Record one served HTTP request. {@code endpoint} is the request path (e.g. {@code /api/analyze}). */
    public void recordHttp(String endpoint, boolean ok, long latencyMs) {
        http.computeIfAbsent(endpoint, c -> new Ring()).record(ok, latencyMs);
        httpSinceStart.incrementAndGet();
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }

    public Group teamcity() {
        return new Group(aggregate(tc), tcSinceStart.get(), byCategory(tc), byStatusView(),
            perMinute(tc), byCategoryPerMinute(tc));
    }

    public Group github() {
        return new Group(aggregate(github), githubSinceStart.get(), byCategory(github), Map.of(),
            perMinute(github), byCategoryPerMinute(github));
    }

    /** Latency/volume of the app's own served requests (per endpoint). "since start" resets on restart. */
    public Group http() {
        return new Group(aggregate(http), httpSinceStart.get(), byCategory(http), Map.of(),
            perMinute(http), byCategoryPerMinute(http));
    }

    /** Per-category call counts for each of the last {@code WINDOW} minutes (oldest first), biggest category first. */
    private static Map<String, long[]> byCategoryPerMinute(Map<String, Ring> rings) {
        Map<String, long[]> out = new LinkedHashMap<>();
        rings.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().lastHour().calls(), a.getValue().lastHour().calls()))
            .forEach(e -> out.put(e.getKey(), e.getValue().perMinuteCalls()));

        return out;
    }

    private static Stats aggregate(Map<String, Ring> rings) {
        Raw total = new Raw(0, 0, 0, 0);
        for (Ring r : rings.values())
            total = total.plus(r.lastHour());

        return total.stats();
    }

    private static Map<String, Stats> byCategory(Map<String, Ring> rings) {
        Map<String, Raw> raw = new LinkedHashMap<>();
        rings.forEach((k, r) -> raw.put(k, r.lastHour()));

        Map<String, Stats> out = new LinkedHashMap<>();
        raw.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().calls(), a.getValue().calls()))
            .forEach(e -> out.put(e.getKey(), e.getValue().stats()));

        return out;
    }

    private Map<Integer, Long> byStatusView() {
        Map<Integer, Long> out = new TreeMap<>();
        tcByStatus.forEach((k, v) -> out.put(k, v.get()));

        return out;
    }

    /** The last {@code WINDOW} minutes (oldest first), ok/fail summed across all categories. */
    private static List<Minute> perMinute(Map<String, Ring> rings) {
        long nowMin = System.currentTimeMillis() / 60_000L;
        long[] ok = new long[WINDOW];
        long[] fail = new long[WINDOW];

        for (Ring r : rings.values())
            r.addTo(nowMin, ok, fail);

        List<Minute> out = new ArrayList<>(WINDOW);
        for (int i = 0; i < WINDOW; i++)
            out.add(new Minute((nowMin - (WINDOW - 1) + i) * 60_000L, ok[i], fail[i]));

        return out;
    }

    // --- persistence: only the durable lifetime totals + failures-by-status (rings are ephemeral) ---

    @Override
    public String fileName() {
        return "metrics.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, new Persisted(tcSinceStart.get(), githubSinceStart.get(), byStatusView()));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        tcSinceStart.set(p.tcSinceStart());
        githubSinceStart.set(p.githubSinceStart());
        if (p.byStatus() != null)
            p.byStatus().forEach((k, v) -> tcByStatus.put(k, new AtomicLong(v)));
    }

    /** A per-category rolling window of one-minute buckets. */
    private static final class Ring {
        private final long[] minute = new long[WINDOW];
        private final long[] calls = new long[WINDOW];
        private final long[] fails = new long[WINDOW];
        private final long[] latSum = new long[WINDOW];
        private final long[] latMax = new long[WINDOW];

        synchronized void record(boolean ok, long ms) {
            long m = System.currentTimeMillis() / 60_000L;
            int i = (int) Math.floorMod(m, WINDOW);
            if (minute[i] != m) {
                minute[i] = m;
                calls[i] = 0;
                fails[i] = 0;
                latSum[i] = 0;
                latMax[i] = 0;
            }
            calls[i]++;
            if (!ok)
                fails[i]++;
            latSum[i] += ms;
            latMax[i] = Math.max(latMax[i], ms);
        }

        synchronized Raw lastHour() {
            long nowMin = System.currentTimeMillis() / 60_000L;
            long c = 0;
            long f = 0;
            long ls = 0;
            long lm = 0;
            for (int i = 0; i < WINDOW; i++) {
                if (minute[i] > nowMin - WINDOW && minute[i] <= nowMin) {
                    c += calls[i];
                    f += fails[i];
                    ls += latSum[i];
                    lm = Math.max(lm, latMax[i]);
                }
            }

            return new Raw(c, f, ls, lm);
        }

        synchronized void addTo(long nowMin, long[] ok, long[] fail) {
            for (int i = 0; i < WINDOW; i++) {
                long m = minute[i];
                if (m > nowMin - WINDOW && m <= nowMin) {
                    int slot = (int) (m - (nowMin - (WINDOW - 1)));
                    if (slot >= 0 && slot < WINDOW) {
                        ok[slot] += calls[i] - fails[i];
                        fail[slot] += fails[i];
                    }
                }
            }
        }

        synchronized long[] perMinuteCalls() {
            long nowMin = System.currentTimeMillis() / 60_000L;
            long[] out = new long[WINDOW];
            for (int i = 0; i < WINDOW; i++) {
                long m = minute[i];
                if (m > nowMin - WINDOW && m <= nowMin) {
                    int slot = (int) (m - (nowMin - (WINDOW - 1)));
                    if (slot >= 0 && slot < WINDOW)
                        out[slot] = calls[i];
                }
            }

            return out;
        }
    }

    private record Raw(long calls, long fails, long latSum, long latMax) {
        Raw plus(Raw o) {
            return new Raw(calls + o.calls, fails + o.fails, latSum + o.latSum, Math.max(latMax, o.latMax));
        }

        Stats stats() {
            return new Stats(calls, calls - fails, fails, calls > 0 ? latSum / calls : 0, latMax);
        }
    }

    /** A target's metrics: last-hour aggregate + per-category breakdown + per-minute series, plus the lifetime total. */
    public record Group(Stats lastHour, long sinceStart, Map<String, Stats> byCategory,
        Map<Integer, Long> byStatus, List<Minute> perMinute, Map<String, long[]> perMinuteByCategory) {
    }

    public record Stats(long total, long ok, long fail, long avgLatencyMs, long maxLatencyMs) {
    }

    public record Minute(long t, long ok, long fail) {
    }

    private record Persisted(long tcSinceStart, long githubSinceStart, Map<Integer, Long> byStatus) {
    }
}
