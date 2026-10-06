package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A durable, accumulating record of tests that fail on master (the "fix master" queue). Unlike the
 * 15-minute analysis cache — which decays to nothing when nobody is using the app, so a live scan
 * would falsely report "master looks clean" — this store harvests each cached analysis into a
 * persisted tally that survives idle periods and restarts. Entries not re-observed within
 * {@link #RETAIN} are pruned (a test that got fixed drops off), so the list stays current without
 * vanishing between warm cycles.
 *
 * <p>The tally is kept per test and suite: one test id runs in several suites of a chain (the C++
 * thin-client tests run on Windows, Linux and Clang), each with its own master fail rate and its own
 * failed master runs. One row per test would show one platform's fail rate next to links to another
 * platform's failures.
 */
@Component
public class FlakyStats implements SnapshotCache {
    private static final long RETAIN = Duration.ofDays(14).toMillis();

    /** Master-failure anchors refresh this often; the page is a queue, not a live dashboard. */
    private static final long ANCHOR_TTL = Duration.ofHours(24).toMillis();

    private final AnalysisCache cache;
    private final ObjectMapper mapper;
    private final TcClient tc;
    private final Warmer warmer;
    private final ConcurrentMap<TestInSuite, Entry> byTestInSuite = new ConcurrentHashMap<>();

    public FlakyStats(AnalysisCache cache, ObjectMapper mapper, TcClient tc, Warmer warmer) {
        this.cache = cache;
        this.mapper = mapper;
        this.tc = tc;
        this.warmer = warmer;
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
                HistoryStats h = cache.historyOf(f.testId(), f.suite()).orElse(null);
                if (h != null && h.fails() > 0) // fails on master (not merely a branch re-run pass)
                    record(f, h.fails(), h.runs(), r.prNumber());
            }
        }
        anchorToMaster();
    }

    /**
     * The page is about master, so the test link must open a MASTER failure, not the PR-branch
     * occurrence the entry was harvested from. Backfills/refreshes a few anchors per cycle with a
     * pooled token — gentle on TeamCity, converges over a few cycles.
     */
    private void anchorToMaster() {
        String token = warmer.borrowToken();
        if (token == null)
            return;

        int budget = 10;
        int strikes = 0;
        for (Map.Entry<TestInSuite, Entry> me : byTestInSuite.entrySet()) {
            if (budget == 0)
                return;
            Entry e = me.getValue();
            synchronized (e) {
                if (System.currentTimeMillis() - e.masterAnchorAt < ANCHOR_TTL)
                    continue;
            }
            budget--;
            try {
                List<MasterRef> refs = tc.masterFailures(token, me.getKey().testId(), me.getKey().suite()).stream()
                    .map(o -> new MasterRef(o.build().id(), o.build().buildTypeId(), o.id()))
                    .toList();
                synchronized (e) {
                    if (!refs.isEmpty())
                        e.masterFailures = refs;
                    e.masterAnchorAt = System.currentTimeMillis();
                }
            }
            catch (RuntimeException ex) {
                if (++strikes >= 3)
                    return; // token or network trouble: give up until the next cycle
            }
        }
    }

    private void record(TestVerdict f, int masterFails, int masterRuns, int pr) {
        Entry e = byTestInSuite.computeIfAbsent(new TestInSuite(f.testId(), f.suite()), k -> new Entry());
        synchronized (e) {
            e.name = f.name();
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

    /**
     * Recently-seen flaky/broken-on-master tests, a row per suite, worst master fail-rate first. Prunes
     * stale entries.
     */
    public List<TopFlaky> top(int limit) {
        long now = System.currentTimeMillis();
        byTestInSuite.values().removeIf(e -> now - e.lastSeen > RETAIN);

        List<TopFlaky> out = new ArrayList<>();
        byTestInSuite.forEach((k, e) -> {
            synchronized (e) {
                if (e.masterFails > 0)
                    out.add(new TopFlaky(k.testId(), e.name, k.suite(), e.suiteName, e.suiteBuildId, e.occurrenceId,
                        e.branchRuns, e.masterFails, e.masterRuns, e.prs.size(), e.prs.stream().sorted().toList(),
                        e.masterFailures));
            }
        });

        out.sort(Comparator
            .comparingDouble((TopFlaky f) -> f.masterRuns() == 0 ? 0 : (double) f.masterFails() / f.masterRuns())
            .reversed()
            .thenComparing(Comparator.comparingInt(TopFlaky::prCount).reversed()));

        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** How many tests are currently tracked, once per suite (to tell "no data yet" from "clean"). */
    public int trackedCount() {
        return byTestInSuite.size();
    }

    @Override
    public String fileName() {
        return "flaky.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        byTestInSuite.forEach((k, e) -> {
            synchronized (e) {
                snap.add(new Persisted(k.testId(), e.name, k.suite(), e.suiteName, e.suiteBuildId, e.occurrenceId,
                    e.branchRuns, e.masterFails, e.masterRuns, e.lastSeen, e.prs.stream().sorted().toList(),
                    e.masterFailures, e.masterAnchorAt));
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
            e.suiteName = p.suiteName();
            e.suiteBuildId = p.suiteBuildId();
            e.occurrenceId = p.occurrenceId();
            e.branchRuns = p.branchRuns() == null ? "" : p.branchRuns();
            e.masterFails = p.masterFails();
            e.masterRuns = p.masterRuns();
            e.lastSeen = p.lastSeen();
            List<MasterRef> stored = p.masterFailures() == null ? List.of() : p.masterFailures();
            // Snapshots from before the tally was kept per suite hold failures looked up in every suite.
            e.masterFailures = stored.stream().filter(f -> Objects.equals(f.btId(), p.suite())).toList();
            // Nothing to link (a pre-list snapshot) or other suites' failures dropped: re-anchor now.
            e.masterAnchorAt = !e.masterFailures.isEmpty() && e.masterFailures.size() == stored.size()
                ? p.masterAnchorAt() : 0;
            if (p.prs() != null)
                e.prs.addAll(p.prs());
            byTestInSuite.put(new TestInSuite(p.testId(), p.suite()), e);
        }
    }

    private record TestInSuite(long testId, String suite) {
    }

    private static final class Entry {
        String name;
        String suiteName;
        long suiteBuildId;
        String occurrenceId = "";
        String branchRuns = "";
        int masterFails;
        int masterRuns;
        long lastSeen;
        List<MasterRef> masterFailures = List.of();
        long masterAnchorAt;
        final Set<Integer> prs = ConcurrentHashMap.newKeySet();
    }

    private record Persisted(long testId, String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, String branchRuns, int masterFails, int masterRuns, long lastSeen, List<Integer> prs,
        List<MasterRef> masterFailures, long masterAnchorAt) {
    }

    /** One failed master run of the test: enough to deep-link the occurrence in TeamCity. */
    public record MasterRef(long buildId, String btId, String occ) {
    }

    /**
     * A flaky/broken-on-master test in one suite: identity, that suite's master fail-rate
     * ({@code masterFails}/{@code masterRuns}) and failed master runs, how many/which open PRs recently hit
     * it, and its latest occurrence (build/occurrence) so the UI can link to the failure in TeamCity,
     * expand "why", and draw the branch pass/fail strip.
     */
    public record TopFlaky(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, String branchRuns, int masterFails, int masterRuns,
        int prCount, List<Integer> prs,
        List<MasterRef> masterFailures) {
    }
}
