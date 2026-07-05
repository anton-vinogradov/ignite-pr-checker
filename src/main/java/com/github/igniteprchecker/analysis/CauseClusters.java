package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.TcClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Groups a run's blockers by failure signature (the normalised first line of the failure message),
 * collapsing hundreds of failed tests into a handful of root causes — e.g. one codegen break showing
 * up as the same NoClassDefFoundError in 200 tests. Fetching every failure message is the expensive
 * part, so big runs are sampled (first {@link #SAMPLE_CAP} blockers) and the result is cached per
 * build for the analysis TTL.
 */
@Component
public class CauseClusters {
    private static final int SAMPLE_CAP = 80;
    private static final int TOP = 12;

    private final TcClient tc;
    private final ExecutorService pool;
    private final TtlCache<Long, Result> cache = new TtlCache<>(15 * 60_000L);

    public CauseClusters(TcClient tc,
        @Qualifier("causesExecutor") ExecutorService pool) {
        this.tc = tc;
        this.pool = pool;
    }

    /** Sweeps out expired per-build cluster entries (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        cache.evictExpired();
    }

    /** Clusters for a run's blockers (cached per build id). */
    public Result clusters(String token, AnalysisResult res) {
        return cache.get(res.buildId(), () -> compute(token, res));
    }

    private Result compute(String token, AnalysisResult res) {
        List<TestVerdict> blockers = res.blockers();
        List<TestVerdict> sample = blockers.size() > SAMPLE_CAP ? blockers.subList(0, SAMPLE_CAP) : blockers;

        List<Callable<String[]>> tasks = sample.stream()
            .<Callable<String[]>>map(v -> () -> {
                String details;
                try {
                    details = v.occurrenceId() == null ? null : tc.testDetails(token, v.occurrenceId());
                }
                catch (RuntimeException e) {
                    details = null; // one missing message must not sink the clustering
                }
                return new String[] {signature(details), v.name()};
            })
            .toList();

        Map<String, Agg> bySignature = new LinkedHashMap<>();
        for (String[] r : Parallel.run(pool, tasks)) {
            Agg agg = bySignature.computeIfAbsent(r[0], k -> new Agg());
            agg.count++;
            if (agg.samples.size() < 3)
                agg.samples.add(r[1]);
        }

        List<Cluster> clusters = bySignature.entrySet().stream()
            .map(e -> new Cluster(e.getKey(), e.getValue().count, e.getValue().samples))
            .sorted(Comparator.comparingInt(Cluster::count).reversed())
            .limit(TOP)
            .toList();

        return new Result(blockers.size(), sample.size(), clusters);
    }

    /** The failure's first line with volatile parts (numbers, hashes, uuids) normalised away. */
    static String signature(String details) {
        if (details == null || details.isBlank())
            return "(no failure message)";

        String line = details.strip().lines()
            .map(String::strip)
            .filter(l -> !l.isBlank())
            .filter(l -> !l.matches("[-=\\s]*(Std(out|err):?)?[-=\\s]*")) // skip '------- Stdout: -------' separators
            .findFirst().orElse("");
        line = line
            .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
            .replaceAll("0x[0-9a-fA-F]+", "<hex>")
            .replaceAll("\\d+", "N");

        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private static final class Agg {
        int count;
        final List<String> samples = new ArrayList<>(3);
    }

    /** One root cause: the shared failure signature, how many sampled blockers hit it, sample test names. */
    public record Cluster(String signature, int count, List<String> sampleTests) {
    }

    /** Clustering outcome: total blockers, how many were sampled for messages, and the top clusters. */
    public record Result(int total, int sampled, List<Cluster> clusters) {
    }
}
