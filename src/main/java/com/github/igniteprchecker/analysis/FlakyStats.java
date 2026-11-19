package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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

    static final long RETAIN = Duration.ofDays(RETAIN_DAYS).toMillis();

    /** A test failing this many of its newest master runs in a row, or all of fewer, is broken on master. */
    static final int BROKEN_STREAK = 10;

    /** A newly broken test failed at least this many of its newest master runs in a row. */
    static final int NEW_STREAK = 2;

    /** How many master runs before that streak say how seldom the test failed. */
    static final int NEW_RUNS_BEFORE = 5;

    /** That streak is a break, not chance, when a test failing as seldom as before it fails so less often than this. */
    static final double NEW_CHANCE = 0.01;

    /** Master history and failure anchors refresh this often when no master run asks for it sooner. */
    private static final long ANCHOR_TTL = Duration.ofHours(24).toMillis();

    /** How many commits of a cause the board names at most: the newest ones. */
    static final int CAUSE_COMMITS = 20;

    private final AnalysisCache cache;
    private final ObjectMapper mapper;
    private final TcClient tc;
    private final Warmer warmer;
    private final GithubClient github;
    private final ConcurrentMap<TestInSuite, Entry> byTestInSuite = new ConcurrentHashMap<>();

    /**
     * What GitHub listed between the two commits of a cause on the board; empty when it has no such list. Two commits
     * have the same commits between them for good, so an answer is asked for once. A restart asks again.
     */
    private final ConcurrentMap<Range, Optional<GithubClient.Commits>> listed = new ConcurrentHashMap<>();

    public FlakyStats(AnalysisCache cache, ObjectMapper mapper, TcClient tc, Warmer warmer, GithubClient github) {
        this.cache = cache;
        this.mapper = mapper;
        this.tc = tc;
        this.warmer = warmer;
        this.github = github;
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
        listCommits();
    }

    /**
     * The page is about master, so the test link must open a MASTER failure, not the PR-branch
     * occurrence the entry was harvested from. Backfills/refreshes a few anchors per cycle with a
     * pooled token — gentle on TeamCity, converges over a few cycles. Tests not yet checked for a mute
     * go first, so the muted ones a snapshot of an older release holds leave the board within minutes;
     * then those a master run asked for ({@link #masterRan}), so a break or a fix shows before the day is out.
     */
    private void anchorToMaster() {
        String token = warmer.borrowToken();
        if (token == null)
            return;

        long now = System.currentTimeMillis();
        // A turn is taken once per entry: a master run may ask for an entry while the others are sorted.
        List<Map.Entry<TestInSuite, Entry>> due = byTestInSuite.entrySet().stream()
            .map(me -> Map.entry(me.getValue().anchorTurn(now), me))
            .filter(turn -> turn.getKey() < Entry.NOT_DUE)
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();

        int budget = 10;
        int strikes = 0;
        for (Map.Entry<TestInSuite, Entry> me : due) {
            if (budget == 0)
                return;
            budget--;
            Entry e = me.getValue();
            long asked;
            synchronized (e) {
                asked = e.rereadVersion;
            }
            try {
                TcClient.MasterHistory found = tc.masterTestHistory(token, me.getKey().testId(), me.getKey().suite());
                List<MasterRun> history = found.runs().stream()
                    .map(o -> {
                        long finished = TcDates.epochSeconds(o.build().finishDate());
                        return new MasterRun(o.build().id(), o.build().buildTypeId(), o.id(),
                            "FAILURE".equals(o.status()) ? "F" : "P", finished > 0 ? finished : null,
                            BlockerAnalyzer.firstRevision(o.build().revisions()));
                    })
                    .sorted(MASTER_ORDER)
                    .toList();
                synchronized (e) {
                    e.masterHistory = history;
                    e.historyWhole = found.whole();
                    e.muted = found.muted();
                    e.masterAnchorAt = System.currentTimeMillis();
                    if (e.rereadVersion == asked)
                        e.rereadAsked = false;
                }
            }
            catch (RuntimeException ex) {
                if (++strikes >= 3)
                    return; // token or network trouble: give up until the next cycle
            }
        }
    }

    /**
     * A master run of {@code suite} newer than those known before has been read. The histories it may have changed
     * are read anew in the coming cycles, ahead of {@link #ANCHOR_TTL}: of every test of the suite on the board when
     * the run did not pass, as any of them may have failed in it or not run; of those ending with a failure when it
     * did, as it ended their streak.
     */
    void masterRan(String suite, boolean passed) {
        byTestInSuite.forEach((k, e) -> {
            if (!k.suite().equals(suite))
                return;
            synchronized (e) {
                List<MasterRun> runs = e.masterHistory;
                if (e.onTheBoard() && (!passed || !runs.isEmpty() && "F".equals(runs.get(runs.size() - 1).status()))) {
                    e.rereadAsked = true;
                    e.rereadVersion++;
                }
            }
        });
    }

    /**
     * Asks GitHub for the commits of the causes it has not listed yet, a few per cycle, and forgets those of the
     * causes that left the board. A call that failed is made again in a later cycle; when GitHub limits the calls, or
     * fails two of them, the rest wait for that cycle too.
     */
    private void listCommits() {
        List<Range> ranges = List.copyOf(ranges().values());
        listed.keySet().retainAll(ranges);

        int budget = 3;
        int strikes = 0;
        for (Range range : ranges) {
            if (range.empty() || listed.containsKey(range))
                continue;
            if (budget == 0)
                return;
            budget--;
            try {
                listed.put(range, github.commitsBetween(range.base(), range.head(), CAUSE_COMMITS));
            }
            catch (RuntimeException e) {
                if (GithubClient.rateLimited(e) || ++strikes >= 2)
                    return;
            }
        }
    }

    /**
     * Where to look for each cause on the board, by the commit its tests first failed on: after the last pass of the
     * test that passed last, as the cause came after the last pass of each of them. A cause has no range when
     * TeamCity did not name the commit of its first failure, or of any of those passes.
     */
    private Map<String, Range> ranges() {
        Map<String, AttentionRun> lastPasses = new LinkedHashMap<>();
        for (TopFlaky f : board()) {
            Attention a = f.attention();
            if (a != null && a.firstFailure().commit() != null && a.lastPass().commit() != null)
                lastPasses.merge(a.firstFailure().commit(), a.lastPass(),
                    (one, other) -> one.finishedAt() >= other.finishedAt() ? one : other);
        }

        Map<String, Range> ranges = new LinkedHashMap<>();
        lastPasses.forEach((head, pass) -> ranges.put(head, new Range(pass.commit(), head)));

        return ranges;
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
    static long failedAt(AnalysisResult r, long now) {
        if (r.finishedAt() > 0)
            return r.finishedAt() * 1000;

        return r.computedAt() > 0 ? r.computedAt() : now;
    }

    /**
     * Unmuted tests, once per suite: first the newly broken ones, the latest break first, then other flaky and broken
     * tests. Each of the last two groups is cut independently, so a large group does not hide the other. The newly
     * broken ones come whole: a cause is told by all its tests, and with 40 of 88 on the page the board said
     * "39 tests" of a cause of 87.
     */
    public List<TopFlaky> top(int limit) {
        List<TopFlaky> board = board();

        return Stream.concat(board.stream().filter(f -> f.attention() != null).sorted(Comparator
                .comparingLong((TopFlaky f) -> f.attention().firstFailure().finishedAt()).reversed()),
            Stream.of(board.stream().filter(f -> f.attention() == null && !f.broken()),
                board.stream().filter(f -> f.attention() == null && f.broken()))
                .flatMap(group -> group.limit(limit))).toList();
    }

    /**
     * The causes of the newly broken tests among {@code tests}, each with its commits as far as GitHub listed them.
     * The range of a cause is that of all its tests on the board, whichever of them {@code tests} holds.
     */
    public List<Cause> causes(List<TopFlaky> tests) {
        Map<String, Range> ranges = ranges();

        return tests.stream().filter(f -> f.attention() != null)
            .map(f -> ranges.get(f.attention().firstFailure().commit())).filter(Objects::nonNull).distinct()
            .map(range -> {
                Optional<GithubClient.Commits> found = range.empty()
                    ? Optional.of(new GithubClient.Commits(0, List.of()))
                    : listed.getOrDefault(range, Optional.empty());

                return new Cause(range.head(), range.base(), found.map(GithubClient.Commits::total).orElse(null),
                    found.map(GithubClient.Commits::commits).orElse(null));
            }).toList();
    }

    /** How many rows each group of the board has before {@link #top} cuts it. */
    public GroupSizes groupSizes() {
        List<TopFlaky> board = board();
        int attention = (int) board.stream().filter(f -> f.attention() != null).count();
        int broken = (int) board.stream().filter(f -> f.attention() == null && f.broken()).count();

        return new GroupSizes(attention, board.size() - attention - broken, broken);
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
                    out.add(new TopFlaky(k.testId(), e.name, k.suite(), e.suiteName, e.occurrenceId,
                        e.masterFails, e.masterRuns, e.failStreak, e.broken(), e.prSeen.size(),
                        e.prSeen.keySet().stream().sorted().toList(), History.of(e.masterHistory, e.historyWhole),
                        e.masterAnchorAt, e.attention()));
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
                    new HashMap<>(e.prSeen), null, e.masterAnchorAt, e.muted, e.masterHistory, e.historyWhole,
                    e.rereadAsked));
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
            List<MasterRun> stored = p.masterHistory() != null ? p.masterHistory()
                : p.masterFailures() == null ? List.of() : p.masterFailures().stream()
                    .map(f -> new MasterRun(f.buildId(), f.btId(), f.occ(), "F", null, null)).toList();
            e.masterHistory = stored.stream().filter(f -> Objects.equals(f.btId(), p.suite()))
                .sorted(MASTER_ORDER).toList();
            // Old failure-only anchors are links, not a dated history. Backfill in the normal bounded cycle.
            e.masterAnchorAt = p.masterHistory() != null && e.masterHistory.size() == stored.size()
                && p.muted() != null ? p.masterAnchorAt() : 0;
            e.historyWhole = p.historyWhole();
            e.rereadAsked = p.rereadAsked();
            if (p.prSeen() != null)
                e.prSeen.putAll(p.prSeen());
            byTestInSuite.put(new TestInSuite(p.testId(), p.suite()), e);
        }
    }

    private record TestInSuite(long testId, String suite) {
    }

    /** The commits after {@code base} up to {@code head}. */
    private record Range(String base, String head) {
        /** No commit to ask GitHub for: the two runs tested the same one. */
        boolean empty() {
            return base.equals(head);
        }
    }

    private static final class Entry {
        /** The turn of an entry whose history is not to be read now. */
        static final int NOT_DUE = 3;

        String name;
        String suiteName;
        long suiteBuildId;
        String occurrenceId = "";
        int masterFails;
        int masterRuns;
        int failStreak;
        long lastSeen;
        List<MasterRun> masterHistory = List.of();
        /** Whether TeamCity has no master run of the test older than {@link #masterHistory}, as of the last anchor. */
        boolean historyWhole;
        long masterAnchorAt;
        /** Whether TeamCity has the test muted, as of the last anchor; null until it was asked. */
        Boolean muted;
        /**
         * Whether a master run read since the last anchor may have changed the history. Kept in the snapshot as the
         * chains read are: a restart that forgot it would leave the run unread here for a day.
         */
        boolean rereadAsked;
        /** A notification during a history request must not be cleared by that request's older answer. */
        private long rereadVersion;
        /** PR number -> when the test was last seen failing there. */
        final Map<Integer, Long> prSeen = new HashMap<>();

        /**
         * When the history is read among those due, the lowest first: 0 before TeamCity was asked about a mute, 1 when
         * a master run asked for it, 2 when it is {@link #ANCHOR_TTL} old; {@link #NOT_DUE} otherwise.
         */
        synchronized int anchorTurn(long now) {
            if (!rereadAsked && now - masterAnchorAt < ANCHOR_TTL)
                return NOT_DUE;

            return muted == null ? 0 : rereadAsked ? 1 : 2;
        }

        synchronized boolean isMuted() {
            return Boolean.TRUE.equals(muted) && masterFails > 0;
        }

        /**
         * The streak is what the PR analyses saw, and a test fixed on master fails in no PR that would count it anew:
         * once the history is checked, a test whose newest run there passed is broken no more.
         */
        boolean broken() {
            return masterRuns >= 2 && failStreak >= Math.min(masterRuns, BROKEN_STREAK)
                && (masterAnchorAt == 0 || masterHistory.isEmpty()
                    || "F".equals(masterHistory.get(masterHistory.size() - 1).status()));
        }

        /**
         * Newly broken: the dated history ends with failures after a pass, too many in a row for how seldom the test
         * failed before them. Any flaky test ends with two failures after a pass now and then: taken for a break,
         * that put 40 of the board's 91 tests here. The chance of one more failure is rated by the runs before the
         * streak with one failure and one pass added, so a history too short to tell says little.
         */
        Attention attention() {
            if (masterAnchorAt == 0 || masterHistory.stream().anyMatch(r -> r.finishedAt() == null))
                return null;
            int first = masterHistory.size();
            while (first > 0 && "F".equals(masterHistory.get(first - 1).status()))
                first--;
            int failed = masterHistory.size() - first;
            if (failed < NEW_STREAK || first < NEW_RUNS_BEFORE)
                return null;
            MasterRun passed = masterHistory.get(first - 1);
            MasterRun failedFirst = masterHistory.get(first);
            int failsBefore = (int) masterHistory.subList(0, first).stream().filter(r -> "F".equals(r.status()))
                .count();
            double failChance = (failsBefore + 1.0) / (first + 2);
            return passed.finishedAt() < failedFirst.finishedAt() && Math.pow(failChance, failed) < NEW_CHANCE
                ? new Attention(new AttentionRun(passed.buildId(), passed.finishedAt(), passed.commit()),
                    new AttentionRun(failedFirst.buildId(), failedFirst.finishedAt(), failedFirst.commit()), failed,
                    failsBefore, first)
                : null;
        }

        /**
         * Flaky (failed some master runs, passed others) or broken on master. A test whose only master run failed
         * is neither: it topped the flaky group at "1/1 on master 100%" though nothing showed it passing.
         */
        boolean onTheBoard() {
            return masterFails > 0 && !Boolean.TRUE.equals(muted)
                && (broken() || masterFails < masterRuns || attention() != null);
        }
    }

    /** {@code prs} is kept next to {@code prSeen} for a release that reads only the PR numbers. */
    @JsonIgnoreProperties("branchRuns")
    private record Persisted(long testId, String name, String suite, String suiteName, long suiteBuildId,
        String occurrenceId, int masterFails, int masterRuns, int failStreak, long lastSeen, List<Integer> prs,
        Map<Integer, Long> prSeen, List<MasterRef> masterFailures, long masterAnchorAt, Boolean muted,
        List<MasterRun> masterHistory, boolean historyWhole, boolean rereadAsked) {
    }

    /** The three disjoint groups before their display limits. */
    public record GroupSizes(int attention, int flaky, int broken) {
    }

    /** One failed master run of the test, as snapshots of older releases keep them. */
    public record MasterRef(long buildId, String btId, String occ) {
    }

    private static final Comparator<MasterRun> MASTER_ORDER = Comparator
        .comparing(MasterRun::finishedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparingLong(MasterRun::buildId);

    /**
     * A completed master test run; null date means TeamCity did not supply a readable finish date, null commit that it
     * named none, as in the snapshots written before the commit was read.
     */
    public record MasterRun(long buildId, String btId, String occ, String status, Long finishedAt, String commit) {
    }

    /**
     * The master runs of a test in its suite, oldest first, as the page polls them: one object, not one per run.
     * {@code status} holds a letter per run, P or F; {@code builds} and {@code finished}, epoch seconds or null, go run
     * by run. {@code occ} are the occurrences of the failed runs, which open the test in its run: of the latest alone
     * when no run passed, as the page then draws no run but links that one. {@code whole}: TeamCity has no older run.
     */
    public record History(String status, List<Long> builds, List<Long> finished, List<String> occ, boolean whole) {
        static History of(List<MasterRun> runs, boolean whole) {
            List<String> failed = runs.stream().filter(r -> "F".equals(r.status())).map(MasterRun::occ).toList();

            return new History(runs.stream().map(MasterRun::status).collect(Collectors.joining()),
                runs.stream().map(MasterRun::buildId).toList(),
                runs.stream().map(MasterRun::finishedAt).toList(),
                failed.size() < runs.size() ? failed : failed.subList(Math.max(0, failed.size() - 1), failed.size()),
                whole);
        }
    }

    /** A run at either end of a break: {@code finishedAt} in epoch seconds, {@code commit} null when not known. */
    public record AttentionRun(long buildId, long finishedAt, String commit) {
    }

    /**
     * The observed pass before the current failure streak and the first run of the streak, not the time or cause of a
     * regression; of the {@code runsBefore} runs before the streak the test failed {@code failsBefore}.
     */
    public record Attention(AttentionRun lastPass, AttentionRun firstFailure, int failedRuns, int failsBefore,
        int runsBefore) {
    }

    /**
     * The commits one of which may have broken the tests that first failed on {@code commit}: those after {@code base},
     * the commit of the last pass before the failures, up to {@code commit}. There are {@code total} of them, and
     * {@code commits} are the newest, the newest first; both are null while GitHub has not listed them, or has no such
     * list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cause(String commit, String base, Integer total, List<GithubClient.Commit> commits) {
    }

    /**
     * A test failing on master in one suite: identity, that suite's master fail-rate ({@code masterFails}/
     * {@code masterRuns}), how many of the newest master runs failed in a row and whether that makes it broken
     * on master rather than flaky, how many/which PRs it hit in the last {@link #RETAIN_DAYS} days, its master
     * runs, and its latest PR occurrence so the UI can expand "why".
     */
    public record TopFlaky(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name, String suite, String suiteName,
        String occurrenceId, int masterFails, int masterRuns, int failStreak, boolean broken,
        int prCount, List<Integer> prs,
        History history, long historyCheckedAt, Attention attention) {
    }
}
