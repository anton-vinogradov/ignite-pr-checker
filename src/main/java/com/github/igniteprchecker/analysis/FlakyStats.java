package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A durable, accumulating record of tests that fail on master (the "fix master" queue). Unlike the
 * 15-minute analysis cache — which decays to nothing when nobody is using the app, so a live scan
 * would falsely report "master looks clean" — this store harvests each cached analysis into a
 * persisted tally that survives idle periods and restarts. Entries not re-observed within
 * {@link #RETAIN_DAYS} days are pruned (a test that got fixed drops off), so the list stays current without
 * vanishing between warm cycles.
 *
 * <p>The tally is kept per test and suite: one test id runs in several suites of a chain (the C++
 * thin-client tests run on Windows, Linux and Clang), each with its own master fail rate and its own
 * failed master runs. One row per test would show one platform's fail rate next to links to another
 * platform's failures.
 *
 * <p>A test that fails every recent master run is not flaky: it broke, or fails by design, and the way to it is
 * the commit that broke it. A test TeamCity has muted is left out: someone decided it waits, and its failures fail
 * no build. Both topped the board: 12 of its 40 rows failed 100 of 100 runs, the top eight muted since 2017–2018
 * or failing by design. The PRs a test hit count for {@link #RETAIN_DAYS} days each, not for as long as the test
 * stays on the board.
 */
@Component
public class FlakyStats implements SnapshotCache {
    /** How long a test stays on the board without being seen again, and a PR in its count. */
    public static final int RETAIN_DAYS = 14;

    private static final long RETAIN = Duration.ofDays(RETAIN_DAYS).toMillis();

    /** A test failing this many of its newest master runs in a row, or all of fewer, is broken on master. */
    static final int BROKEN_STREAK = 10;

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
                cache.masterHistoryOf(f.testId(), f.suite())
                    .filter(h -> h.all().fails() > 0) // fails on master (not merely a branch re-run pass)
                    .ifPresent(h -> record(f, h, r));
            }
        }
        anchorToMaster();
    }

    /**
     * The page is about master, so the test link must open a MASTER failure, not the PR-branch
     * occurrence the entry was harvested from. Backfills/refreshes a few anchors per cycle with a
     * pooled token — gentle on TeamCity, converges over a few cycles. Tests not yet checked for a mute
     * go first, so the muted ones a snapshot of an older release holds leave the board within minutes.
     */
    private void anchorToMaster() {
        String token = warmer.borrowToken();
        if (token == null)
            return;

        long now = System.currentTimeMillis();
        List<Map.Entry<TestInSuite, Entry>> due = byTestInSuite.entrySet().stream()
            .filter(me -> me.getValue().anchorDue(now))
            .sorted(Comparator.comparing((Map.Entry<TestInSuite, Entry> me) -> me.getValue().muteChecked()))
            .toList();

        int budget = 10;
        int strikes = 0;
        for (Map.Entry<TestInSuite, Entry> me : due) {
            if (budget == 0)
                return;
            budget--;
            Entry e = me.getValue();
            try {
                TcClient.MasterFailures found = tc.masterFailures(token, me.getKey().testId(), me.getKey().suite());
                List<MasterRef> refs = found.failures().stream()
                    .map(o -> new MasterRef(o.build().id(), o.build().buildTypeId(), o.id()))
                    .toList();
                synchronized (e) {
                    if (!refs.isEmpty())
                        e.masterFailures = refs;
                    e.muted = found.muted();
                    e.masterAnchorAt = System.currentTimeMillis();
                }
            }
            catch (RuntimeException ex) {
                if (++strikes >= 3)
                    return; // token or network trouble: give up until the next cycle
            }
        }
    }

    private void record(TestVerdict f, RunHistory master, AnalysisResult r) {
        HistoryStats all = master.all();
        long now = System.currentTimeMillis();
        long failedAt = failedAt(r, now);
        Entry e = byTestInSuite.computeIfAbsent(new TestInSuite(f.testId(), f.suite()), k -> new Entry());
        synchronized (e) {
            e.name = f.name();
            e.suiteName = f.suiteName();
            e.suiteBuildId = f.suiteBuildId();
            e.occurrenceId = f.occurrenceId();
            e.masterFails = all.fails();
            e.masterRuns = all.runs();
            e.failStreak = master.failStreak();
            e.lastSeen = now;
            if (now - failedAt <= RETAIN)
                e.prSeen.merge(r.prNumber(), failedAt, Math::max);
        }
    }

    /**
     * When the run behind {@code r} failed the PR's tests: when it finished, not when its verdict was harvested, as
     * the warmer recomputes the verdicts of the newest open PRs every few minutes however long ago their RunAll
     * finished. A run still going failed them as of its verdict.
     */
    private static long failedAt(AnalysisResult r, long now) {
        if (r.finishedAt() > 0)
            return r.finishedAt() * 1000;

        return r.computedAt() > 0 ? r.computedAt() : now;
    }

    /**
     * Recently-seen flaky/broken-on-master tests TeamCity has not muted, a row per suite: up to {@code limit} flaky
     * ones, then up to {@code limit} broken on master, each worst master fail-rate first. Each group is cut on its
     * own: cut as one list, more than {@code limit} flaky ones would leave no broken one.
     */
    public List<TopFlaky> top(int limit) {
        List<TopFlaky> board = board();

        return Stream.concat(board.stream().filter(f -> !f.broken()).limit(limit),
            board.stream().filter(TopFlaky::broken).limit(limit)).toList();
    }

    /** How many rows each group of the board has before {@link #top} cuts it. */
    public GroupSizes groupSizes() {
        List<TopFlaky> board = board();
        int broken = (int) board.stream().filter(TopFlaky::broken).count();

        return new GroupSizes(board.size() - broken, broken);
    }

    /** Every row of the board in its order, stale entries and PRs pruned. */
    private List<TopFlaky> board() {
        long now = System.currentTimeMillis();
        byTestInSuite.values().removeIf(e -> now - e.lastSeen > RETAIN);

        List<TopFlaky> out = new ArrayList<>();
        byTestInSuite.forEach((k, e) -> {
            synchronized (e) {
                e.prSeen.values().removeIf(at -> now - at > RETAIN);
                if (e.onTheBoard())
                    out.add(new TopFlaky(k.testId(), e.name, k.suite(), e.suiteName, e.suiteBuildId, e.occurrenceId,
                        e.masterFails, e.masterRuns, e.failStreak, e.broken(), e.prSeen.size(),
                        e.prSeen.keySet().stream().sorted().toList(), e.masterFailures));
            }
        });

        out.sort(Comparator.comparing(TopFlaky::broken)
            .thenComparing(Comparator
                .comparingDouble((TopFlaky f) -> f.masterRuns() == 0 ? 0 : (double) f.masterFails() / f.masterRuns())
                .reversed())
            .thenComparing(Comparator.comparingInt(TopFlaky::prCount).reversed()));

        return out;
    }

    /** How many tests are currently tracked, once per suite (to tell "no data yet" from "clean"). */
    public int trackedCount() {
        return byTestInSuite.size();
    }

    /** How many of the tracked tests the board leaves out because TeamCity has them muted. */
    public int mutedCount() {
        return (int) byTestInSuite.values().stream().filter(Entry::isMuted).count();
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
                    e.masterFails, e.masterRuns, e.failStreak, e.lastSeen, e.prSeen.keySet().stream().sorted().toList(),
                    new HashMap<>(e.prSeen), e.masterFailures, e.masterAnchorAt, e.muted));
            }
        });
        Snapshots.writeAtomic(mapper, file, snap);
    }

    /**
     * Snapshots of older releases name the PRs without saying when each was seen, so those PRs are dropped: kept,
     * a test seen in 252 PRs since it first failed went on counting all of them. Nor do they say whether a test
     * is muted, so each of their tests is checked on the next harvest.
     */
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
            e.masterFails = p.masterFails();
            e.masterRuns = p.masterRuns();
            // A test that failed every run has a streak of all of them: older snapshots did not keep it.
            e.failStreak = p.failStreak() == 0 && p.masterFails() == p.masterRuns() ? p.masterRuns() : p.failStreak();
            e.lastSeen = p.lastSeen();
            e.muted = p.muted();
            List<MasterRef> stored = p.masterFailures() == null ? List.of() : p.masterFailures();
            // Snapshots from before the tally was kept per suite hold failures looked up in every suite.
            e.masterFailures = stored.stream().filter(f -> Objects.equals(f.btId(), p.suite())).toList();
            // Nothing to link (a pre-list snapshot), other suites' failures dropped or no word on a mute:
            // re-anchor now.
            e.masterAnchorAt = !e.masterFailures.isEmpty() && e.masterFailures.size() == stored.size()
                && p.muted() != null ? p.masterAnchorAt() : 0;
            if (p.prSeen() != null)
                e.prSeen.putAll(p.prSeen());
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
        int masterFails;
        int masterRuns;
        int failStreak;
        long lastSeen;
        List<MasterRef> masterFailures = List.of();
        long masterAnchorAt;
        /** Whether TeamCity has the test muted, as of the last anchor; null until it was asked. */
        Boolean muted;
        /** PR number -> when the test was last seen failing there. */
        final Map<Integer, Long> prSeen = new HashMap<>();

        synchronized boolean anchorDue(long now) {
            return now - masterAnchorAt >= ANCHOR_TTL;
        }

        synchronized boolean muteChecked() {
            return muted != null;
        }

        synchronized boolean isMuted() {
            return Boolean.TRUE.equals(muted) && masterFails > 0;
        }

        boolean broken() {
            return masterRuns >= 2 && failStreak >= Math.min(masterRuns, BROKEN_STREAK);
        }

        /**
         * Flaky (failed some master runs, passed others) or broken on master. A test whose only master run failed
         * is neither: it topped the flaky group at "1/1 on master 100%" though nothing showed it passing.
         */
        boolean onTheBoard() {
            return masterFails > 0 && !Boolean.TRUE.equals(muted) && (broken() || masterFails < masterRuns);
        }
    }

    /** {@code prs} is kept next to {@code prSeen} for a release that reads only the PR numbers. */
    @JsonIgnoreProperties("branchRuns")
    private record Persisted(long testId, String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, int masterFails, int masterRuns, int failStreak, long lastSeen, List<Integer> prs,
        Map<Integer, Long> prSeen, List<MasterRef> masterFailures, long masterAnchorAt, Boolean muted) {
    }

    /** How many tests the board holds as flaky on master and as broken on master. */
    public record GroupSizes(int flaky, int broken) {
    }

    /** One failed master run of the test: enough to deep-link the occurrence in TeamCity. */
    public record MasterRef(long buildId, String btId, String occ) {
    }

    /**
     * A test failing on master in one suite: identity, that suite's master fail-rate ({@code masterFails}/
     * {@code masterRuns}), how many of the newest master runs failed in a row and whether that makes it broken
     * on master rather than flaky, how many/which PRs it hit in the last {@link #RETAIN_DAYS} days, its failed
     * master runs, and its latest PR occurrence so the UI can expand "why".
     */
    public record TopFlaky(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, int masterFails, int masterRuns, int failStreak, boolean broken,
        int prCount, List<Integer> prs,
        List<MasterRef> masterFailures) {
    }
}
