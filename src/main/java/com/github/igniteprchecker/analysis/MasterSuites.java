package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ProblemKind;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The master runs of each suite of the RunAll chain, kept to tell the suites whose runs end without a test verdict:
 * the "Problem suites" of the master board. A problem run is what a PR's verdict calls a broken suite, by the same
 * rule ({@link ChainCollector#broken}) and of the same kinds ({@link ProblemKind}), so a PR's run of such a suite can
 * end the same way.
 *
 * <p>Nothing in the service learns that a master chain finished: the build watcher follows PRs' builds, and the suite
 * baseline reads the newest chain once in six hours. So a timer asks TeamCity for the ids of the newest chains and
 * reads those it has not read, the newest first, a few per cycle. After the first start that fills the history
 * within an hour; later each new chain is read once. TeamCity reuses a suite's build across chains when nothing
 * changed, so a suite's history is its distinct builds. A suite's new run tells the board's tests of the suite that
 * their own histories may have changed ({@link FlakyStats#masterRan}).
 */
@Component
public class MasterSuites implements SnapshotCache {
    /** How many chains a cycle reads at most: an answer holds the runs of all ~150 suites. */
    static final int CHAINS_PER_CYCLE = 5;

    private static final Comparator<Run> RUN_ORDER = Comparator
        .comparing(Run::finishedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingLong(Run::buildId);

    private final AnalysisCache cache;
    private final ObjectMapper mapper;
    private final TcClient tc;
    private final Warmer warmer;
    private final SuiteBaseline baseline;
    private final FlakyStats flaky;
    private final int depth;

    /** The chains read, of those TeamCity listed last: an older one is not listed again. */
    private final TreeSet<Long> chains = new TreeSet<>();

    private final Map<String, Suite> bySuite = new HashMap<>();

    /** Suite -> PR number -> when a run of the PR last left the suite broken. */
    private final Map<String, Map<Integer, Long>> prSeen = new HashMap<>();

    public MasterSuites(AnalysisCache cache, ObjectMapper mapper, TcClient tc, Warmer warmer, SuiteBaseline baseline,
        FlakyStats flaky, AnalysisProperties analysis) {
        this.cache = cache;
        this.mapper = mapper;
        this.tc = tc;
        this.warmer = warmer;
        this.baseline = baseline;
        this.flaky = flaky;
        this.depth = analysis.historyDepth();
    }

    /**
     * Notes the PRs whose cached verdicts have broken suites, and reads the master chains not read yet, with a pooled
     * token. A chain TeamCity failed to give is asked for again in a later cycle; when the list of chains fails, or
     * two chains do, the rest wait for that cycle too.
     */
    @Scheduled(fixedDelay = 180_000, initialDelay = 40_000)
    void scan() {
        recordPrs();

        String token = warmer.borrowToken();
        if (token == null)
            return;

        List<Long> unread;
        try {
            List<Long> listed = tc.masterChains(token);
            if (listed.isEmpty())
                return;
            synchronized (this) {
                chains.retainAll(listed);
                unread = listed.stream().filter(id -> !chains.contains(id)).limit(CHAINS_PER_CYCLE).toList();
            }
        }
        catch (RuntimeException e) {
            return;
        }
        if (unread.isEmpty())
            return;

        Map<String, Integer> masterTests = baseline.counts(token);
        int strikes = 0;
        for (long chain : unread) {
            try {
                add(chain, tc.chainSuiteRuns(token, chain), masterTests);
            }
            catch (RuntimeException e) {
                if (++strikes >= 2)
                    return;
            }
        }
    }

    /** A suite a failed run kept from running is that run's victim in the PR, not a suite broken its own way. */
    private synchronized void recordPrs() {
        long now = System.currentTimeMillis();
        for (AnalysisResult r : cache.freshResults()) {
            long failedAt = FlakyStats.failedAt(r, now);
            if (now - failedAt > FlakyStats.RETAIN)
                continue;
            for (BrokenSuite b : r.brokenSuites()) {
                if (!b.keptFromRunning())
                    prSeen.computeIfAbsent(b.suite(), suite -> new HashMap<>()).merge(r.prNumber(), failedAt, Math::max);
            }
        }
    }

    private synchronized void add(long chain, List<TcModel.Build> runs, Map<String, Integer> masterTests) {
        boolean newest = chains.isEmpty() || chain > chains.last();
        for (TcModel.Build run : runs) {
            if (!newest && !bySuite.containsKey(run.buildTypeId()))
                continue;
            Character kind = run.buildTypeId() == null ? null : kindOf(run, masterTests.get(run.buildTypeId()));
            if (kind == null)
                continue;
            long finished = TcDates.epochSeconds(run.finishDate());
            Suite suite = bySuite.computeIfAbsent(run.buildTypeId(), id -> new Suite());
            boolean known = !suite.runs.isEmpty();
            // What a suite's runs did before the first one read is not known: any of its tests may have changed.
            if (suite.add(new Run(run.id(), finished > 0 ? finished : null, kind),
                run.buildType() == null ? null : run.buildType().name(), depth))
                flaky.masterRan(run.buildTypeId(), known && "SUCCESS".equals(run.status()));
        }
        // A suite RunAll runs no more would stand with its last runs for good.
        if (newest && !runs.isEmpty()) {
            var present = runs.stream().map(TcModel.Build::buildTypeId).filter(id -> id != null)
                .collect(Collectors.toSet());
            bySuite.keySet().retainAll(present);
            // A cancelled run still belongs to this chain; its older history may be read next.
            present.forEach(id -> bySuite.computeIfAbsent(id, key -> new Suite()));
        }
        chains.add(chain);
    }

    /**
     * The letter a master run of a suite is kept as: P when it reported its tests, whether or not some failed, else
     * the letter of its problem's kind. Null when it is no run of the suite: cancelled, or kept from running by a
     * failed run it needed, as {@link BrokenSuite#keptFromRunning} tells it of a PR's suite.
     */
    private static Character kindOf(TcModel.Build run, Integer masterTests) {
        if (!"finished".equalsIgnoreCase(run.state()))
            return null;
        if ("SUCCESS".equals(run.status()))
            return 'P';
        if (!"FAILURE".equals(run.status()))
            return null;

        TcModel.TestOccurrences tests = run.testOccurrences();
        List<ProblemKind> kinds = ChainCollector.problemsOf(run).stream().map(p -> ProblemKind.of(p.type())).sorted()
            .toList();
        if ((tests == null || tests.count() == 0) && kinds.contains(ProblemKind.DEPENDENCY))
            return null;
        if (!ChainCollector.broken(run, masterTests, tests == null || tests.failed() == null ? 0 : tests.failed()))
            return 'P';

        return (kinds.isEmpty() ? ProblemKind.OTHER : kinds.get(0)).letter();
    }

    /**
     * The suites with a problem run among their master runs, each with those runs and the PRs it was a broken suite
     * in during the last {@link FlakyStats#RETAIN_DAYS} days.
     */
    public synchronized List<ProblemSuite> problemSuites() {
        long now = System.currentTimeMillis();
        prSeen.values().forEach(seen -> seen.values().removeIf(at -> now - at > FlakyStats.RETAIN));
        prSeen.values().removeIf(Map::isEmpty);

        List<ProblemSuite> out = new ArrayList<>();
        new TreeMap<>(bySuite).forEach((id, s) -> {
            if (s.runs.stream().allMatch(run -> run.kind() == 'P'))
                return;
            List<Integer> prs = prSeen.getOrDefault(id, Map.of()).keySet().stream().sorted().toList();
            out.add(new ProblemSuite(id, s.name == null ? id : s.name, Runs.of(s.runs), prs.size(), prs));
        });

        return out;
    }

    /**
     * How a suite that a PR's run left broken, with problems of TeamCity's {@code types}, does on master: the same
     * when its newest master run ended with a problem of the same known kind, fine when every master run read reported its
     * tests. Not known otherwise: no run of it is read yet, the verdict names no types, or master's runs end another
     * way or only at times.
     */
    public synchronized OnMaster onMaster(String suite, List<String> types) {
        Suite s = bySuite.get(suite);
        if (s == null || s.runs.isEmpty() || types.isEmpty())
            return OnMaster.UNKNOWN;
        if (s.runs.stream().allMatch(run -> run.kind() == 'P'))
            return OnMaster.FINE;

        ProblemKind kind = types.stream().map(ProblemKind::of).min(ProblemKind::compareTo).orElseThrow();

        return kind != ProblemKind.OTHER && s.runs.get(s.runs.size() - 1).kind() == kind.letter()
            ? OnMaster.SAME : OnMaster.UNKNOWN;
    }

    /** A suite broken its own way, rather than kept from running, with the same problem kind last read on master. */
    public boolean brokenLikeMaster(BrokenGroup group, BrokenSuite suite) {
        return group.kind() == BrokenGroup.Kind.PROBLEM && onMaster(suite.suite(), suite.problemTypes()) == OnMaster.SAME;
    }

    /** See {@link #onMaster}. */
    public enum OnMaster {
        SAME, FINE, UNKNOWN
    }

    /** How many master chains the histories were read from; 0 until the first one is read. */
    public synchronized int chainsRead() {
        return chains.size();
    }

    /** The letter of each kind of problem a run's history names, with what a broken suite's problems call the kind. */
    public static Map<String, String> kinds() {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (ProblemKind kind : ProblemKind.values())
            kinds.put(String.valueOf(kind.letter()), kind.said());

        return kinds;
    }

    @Override
    public String fileName() {
        return "master-suites.json";
    }

    @Override
    public synchronized void saveTo(Path file) throws IOException {
        Map<String, PersistedSuite> suites = new TreeMap<>();
        bySuite.forEach((id, s) -> suites.put(id, new PersistedSuite(s.name, Runs.of(s.runs))));
        Snapshots.writeAtomic(mapper, file, new Persisted(List.copyOf(chains), suites, new TreeMap<>(prSeen)));
    }

    @Override
    public synchronized void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        chains.addAll(p.chains());
        p.suites().forEach((id, kept) -> {
            Suite s = new Suite();
            s.name = kept.name();
            s.runs = kept.runs().toRuns();
            bySuite.put(id, s);
        });
        p.prSeen().forEach((suite, seen) -> prSeen.put(suite, new HashMap<>(seen)));
    }

    private static final class Suite {
        String name;

        /** Its distinct builds, oldest first, the newest {@code depth} of them. */
        List<Run> runs = List.of();

        /**
         * The name is that of the newest run: the chains are read newest first, and a suite gets renamed. True when
         * the run is new to the suite and its newest: not a build TeamCity reused, nor an older run read later.
         */
        boolean add(Run run, String runName, int depth) {
            if (runs.stream().anyMatch(had -> had.buildId() == run.buildId()))
                return false;
            List<Run> all = new ArrayList<>(runs);
            all.add(run);
            all.sort(RUN_ORDER);
            runs = List.copyOf(all.subList(Math.max(0, all.size() - depth), all.size()));
            boolean newest = runs.get(runs.size() - 1).equals(run);
            if (runName != null && (name == null || newest))
                name = runName;

            return newest;
        }
    }

    /** A finished master run of a suite; a null date means TeamCity gave no readable finish date. */
    private record Run(long buildId, Long finishedAt, char kind) {
    }

    private record Persisted(List<Long> chains, Map<String, PersistedSuite> suites,
        Map<String, Map<Integer, Long>> prSeen) {
    }

    private record PersistedSuite(String name, Runs runs) {
    }

    /**
     * The master runs of a suite, oldest first, as the page polls them and the snapshot keeps them: one object, not
     * one per run. {@code status} holds a letter per run, P for a run that reported its tests and the letter of a
     * {@link ProblemKind} for a problem run; {@code builds} and {@code finished}, epoch seconds or null, go run by run.
     */
    public record Runs(String status, List<Long> builds, List<Long> finished) {
        static Runs of(List<Run> runs) {
            return new Runs(runs.stream().map(run -> String.valueOf(run.kind())).collect(Collectors.joining()),
                runs.stream().map(Run::buildId).toList(), runs.stream().map(Run::finishedAt).toList());
        }

        private List<Run> toRuns() {
            List<Run> runs = new ArrayList<>();
            for (int i = 0; i < builds.size(); i++)
                runs.add(new Run(builds.get(i), finished.get(i), status.charAt(i)));

            return List.copyOf(runs);
        }
    }

    /** A suite with a problem run on master: its runs, and how many and which PRs it was a broken suite in. */
    public record ProblemSuite(String suite, String suiteName, Runs history, int prCount, List<Integer> prs) {
    }
}
