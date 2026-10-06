package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
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

        List<Callable<Hit>> tasks = sample.stream()
            .<Callable<Hit>>map(v -> () -> {
                String details;
                try {
                    details = v.occurrenceId() == null ? null : tc.testDetails(token, v.occurrenceId());
                }
                catch (RuntimeException e) {
                    details = null; // one missing message must not sink the clustering
                }
                return new Hit(signature(details), new Member(v.testId(), v.suite()));
            })
            .toList();

        Map<String, List<Member>> bySignature = new LinkedHashMap<>();
        for (Hit h : Parallel.run(pool, tasks))
            bySignature.computeIfAbsent(h.signature(), k -> new ArrayList<>()).add(h.member());

        List<Cluster> clusters = bySignature.entrySet().stream()
            .map(e -> new Cluster(e.getKey(), e.getValue().size(), List.copyOf(e.getValue())))
            .sorted(Comparator.comparingInt(Cluster::count).reversed())
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

    /** One root cause: the shared failure signature and the blockers that hit it. */
    public record Cluster(String signature, int count, List<Member> tests) {
    }

    /**
     * A blocker in a cluster: its test and the suite it failed in. One test can be a blocker in several
     * suites, each failing its own way, so the test id alone can't say which of them hit this cause.
     */
    public record Member(@JsonFormat(shape = JsonFormat.Shape.STRING) long testId, String suite) {
    }

    /** One sampled blocker's failure signature. */
    private record Hit(String signature, Member member) {
    }

    /** Clustering outcome: total blockers, how many were sampled for messages, and all clusters by size. */
    public record Result(int total, int sampled, List<Cluster> clusters) {
    }
}
