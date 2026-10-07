package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Walks the RunAll chain build for a PR into its dependency suites and collects the failed tests
 * across all of them, and across the suites re-run on their own since (one candidate per test and
 * suite). The per-suite lookups run in parallel.
 */
@Component
public class ChainCollector {
    /** How long a PR's latest-build lookup stays valid — short, since it only avoids re-querying the same
     * RunAll on rapid repeat views; a brand-new run is still picked up within this window. */
    private static final long BUILD_ID_TTL_MS = 30_000;

    private final TcClient tc;

    private final SuiteBaseline baseline;

    /** Caches the PR -> latest-RunAll-build-id lookup so a warm {@code /api/analyze} (and each warmer/refresh
     * check) doesn't pay a TeamCity round-trip that almost always returns the same build. */
    private final TtlCache<Integer, Long> buildIds = new TtlCache<>(BUILD_ID_TTL_MS);

    /** PR -> the TeamCity user who triggered its latest RunAll (best-effort, from the last build lookup). */
    private final ConcurrentMap<Integer, String> triggeredBy = new ConcurrentHashMap<>();

    public ChainCollector(TcClient tc, SuiteBaseline baseline) {
        this.tc = tc;
        this.baseline = baseline;
    }

    /** Sweeps out expired build-id lookups (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        buildIds.evictExpired();
    }

    /** The latest RunAll build id for a PR branch, if any (cached briefly). */
    public Optional<Long> findBuildId(String token, int prNumber) {
        Optional<Long> cached = buildIds.peek(prNumber);

        return cached.isPresent() ? cached : lookupBuildId(token, prNumber);
    }

    /** Like {@link #findBuildId} but bypasses the cache — for the manual refresh, which must see a just-finished run. */
    public Optional<Long> findBuildIdFresh(String token, int prNumber) {
        return lookupBuildId(token, prNumber);
    }

    private Optional<Long> lookupBuildId(String token, int prNumber) {
        Optional<TcModel.Build> build = tc.findRunAllBuildForAnalysis(token, prNumber);
        build.ifPresent(b -> {
            buildIds.put(prNumber, b.id());
            String who = b.triggered() != null && b.triggered().user() != null ? b.triggered().user().username() : null;
            if (who != null) // keep the last known triggerer; don't wipe it on a rare missing value
                triggeredBy.put(prNumber, who);
        });

        return build.map(TcModel.Build::id);
    }

    /** The TeamCity user who triggered a PR's latest RunAll, if seen during a build lookup; else null. */
    public String triggeredBy(int prNumber) {
        return triggeredBy.get(prNumber);
    }

    /**
     * Expands a RunAll build into its failed tests (across its FAILURE suites). A FAILURE suite with
     * <em>no</em> failed tests broke before/without testing (compilation, timeout, agent, dependency) —
     * those are collected as {@link BrokenSuite}s so they can't silently vanish from the verdict. The
     * {@code pool} is the caller's fan-out pool: foreground analyses pass the analysis pool, background
     * ones (warmer/refresh) pass their own so they don't compete with user-facing requests.
     */
    /** A suite running at least this % fewer tests than on master is worth surfacing. */
    private static final int SHRINK_PCT = 10;

    /** Below this many tests on master the percentages are noise, not signal. */
    private static final int SHRINK_MIN_BASELINE = 20;

    public Chain collectForBuild(String token, int prNumber, long buildId, ExecutorService pool) {
        TcModel.Build build = tc.getBuildWithDeps(token, buildId);

        // The subject itself may still be running (a PR's first chain, hours before it ends): its
        // finished suites are real results, so they are analysed exactly as a finished chain's are.
        boolean subjectRunning = !"finished".equalsIgnoreCase(build.state());
        Map<String, Integer> masterCounts = baseline.counts(token);
        List<Callable<SuiteResult>> tasks = depBuilds(build).stream()
            .filter(dep -> "FAILURE".equals(dep.status()))
            .<Callable<SuiteResult>>map(dep -> () -> suiteResultOf(token, dep, masterCounts))
            .toList();

        List<FailedTest> failed = new ArrayList<>();
        List<BrokenSuite> broken = new ArrayList<>();
        List<BrokenSuite> unstable = new ArrayList<>();
        Set<Candidate> seen = new HashSet<>();
        for (SuiteResult r : Parallel.run(pool, tasks)) {
            if (r.broken() != null)
                broken.add(r.broken());
            if (r.unstable() != null)
                unstable.add(r.unstable());
            for (FailedTest ft : r.tests()) {
                if (seen.add(new Candidate(ft.testId(), ft.suite())))
                    failed.add(ft);
            }
        }

        // Read before the re-runs: one that broke again replaces the chain's entry for its suite, yet
        // the chain's run still broke, and the tests it never reached are that break's doing, not a shrink.
        Set<Long> brokenRuns = broken.stream().map(BrokenSuite::suiteBuildId).collect(Collectors.toSet());

        // Overlay results from any chain newer than the baseline finished build — running, cancelled
        // or interrupted. Even a run that didn't fully complete ran (and failed) some suites, and those
        // finished-FAILURE suites must count. classify() re-anchors each test to its newest finished
        // run, so this only needs to ADD candidates that appear in the newer chain(s). Only a chain
        // still running makes the verdict live: a cancelled one will never finish its other suites, and
        // calling it "still going" held PR 13654 on "a newer run is still going" for good.
        boolean live = subjectRunning;
        long liveBuildId = subjectRunning ? build.id() : 0;
        Set<Long> newerChainSuites = new HashSet<>();
        Map<String, Long> fullRuns = new HashMap<>();
        for (TcModel.Build chain : tc.recentChains(token, prNumber, 3)) {
            if (chain.id() <= buildId || "queued".equalsIgnoreCase(chain.state()))
                continue; // not newer than the baseline, or nothing has run in it yet
            if ("running".equalsIgnoreCase(chain.state())) {
                live = true;
                liveBuildId = Math.max(liveBuildId, chain.id());
            }
            TcModel.Build rBuild = tc.getBuildWithDeps(token, chain.id());
            depBuilds(rBuild).forEach(dep -> newerChainSuites.add(dep.id()));
            List<Callable<SuiteResult>> rTasks = depBuilds(rBuild).stream()
                .filter(dep -> "finished".equalsIgnoreCase(dep.state()) && "FAILURE".equals(dep.status()))
                .<Callable<SuiteResult>>map(dep -> () -> suiteResultOf(token, dep, masterCounts))
                .toList();
            for (SuiteResult r : Parallel.run(pool, rTasks)) {
                if (r.broken() != null && broken.stream().noneMatch(b -> b.suiteBuildId() == r.broken().suiteBuildId()))
                    broken.add(r.broken());
                if (r.unstable() != null && unstable.stream().noneMatch(u -> u.suiteBuildId() == r.unstable().suiteBuildId()))
                    unstable.add(r.unstable());
                noteFullRun(fullRuns, r, masterCounts);
                for (FailedTest ft : r.tests())
                    if (seen.add(new Candidate(ft.testId(), ft.suite())))
                        failed.add(ft);
            }
        }

        List<Callable<SuiteResult>> reruns = singleSuiteReruns(token, prNumber, build, newerChainSuites, masterCounts);
        for (SuiteResult r : Parallel.run(pool, reruns)) {
            if (r.broken() != null)
                supersede(broken, r.broken());
            if (r.unstable() != null)
                supersede(unstable, r.unstable());
            noteFullRun(fullRuns, r, masterCounts);
            for (FailedTest ft : r.tests())
                if (seen.add(new Candidate(ft.testId(), ft.suite())))
                    failed.add(ft);
        }

        // A suite run in full after it broke has a result again, and its failures are candidates like any
        // other suite's: kept broken, a suite re-run with ordinary failures went into every re-run wave.
        // A green run in full settles it too, later (see BlockerAnalyzer.withoutHealed).
        broken.removeIf(b -> fullRuns.getOrDefault(b.suite(), 0L) > b.suiteBuildId());
        unstable.removeIf(u -> fullRuns.getOrDefault(u.suite(), 0L) > u.suiteBuildId());

        // Reuse transparency: a re-triggered chain on unchanged revisions reuses earlier suite builds
        // (TeamCity substitutes suitable results). A dep queued before the chain itself is such a
        // reused build; showing ran-vs-reused up front beats making users suspect staleness. A suite
        // ran only once it finished with a result: PR 13583 read "147 suites ran" over "137 never ran".
        int ran = 0;
        int reused = 0;
        long chainQueued = TcDates.epochSeconds(build.queuedDate());
        if (chainQueued > 0) {
            for (TcModel.Build dep : depBuilds(build)) {
                long depQueued = TcDates.epochSeconds(dep.queuedDate());
                if (depQueued <= 0)
                    continue; // unknown: count in neither bucket
                if (depQueued < chainQueued - 60)
                    reused++;
                else if (finishedWithResult(dep))
                    ran++;
            }
        }

        // A chain that aborted mid-way leaves suites neither passed nor failed (canceled/UNKNOWN):
        // they never ran, so the verdict is partial — surface that instead of implying they were clean.
        List<CancelledSuite> cancelled = depBuilds(build).stream()
            .filter(d -> d.status() != null && !"SUCCESS".equals(d.status()) && !"FAILURE".equals(d.status()))
            .map(ChainCollector::cancelledSuite)
            .toList();
        // The chain itself failed with suites left unrun, OR the whole chain was cancelled (UNKNOWN)
        // — in both cases the suites that did run still counted, but the verdict is partial.
        boolean interrupted = ("FAILURE".equals(build.status()) || "UNKNOWN".equals(build.status()))
            && !cancelled.isEmpty();

        return new Chain(build.id(), build.branchName(), failed, broken,
            shrunkSuites(depBuilds(build), masterCounts, brokenRuns),
            ran, reused, interrupted, cancelled.size(), live, liveBuildId,
            TcDates.epochSeconds(build.queuedDate()), TcDates.epochSeconds(build.startDate()),
            TcDates.epochSeconds(build.finishDate()), unstable, cancelled);
    }

    private static boolean finishedWithResult(TcModel.Build dep) {
        return "finished".equalsIgnoreCase(dep.state()) && ("SUCCESS".equals(dep.status()) || "FAILURE".equals(dep.status()));
    }

    private static CancelledSuite cancelledSuite(TcModel.Build dep) {
        TcModel.CanceledInfo info = dep.canceledInfo();
        String by = info == null || info.user() == null ? null : info.user().username();

        return new CancelledSuite(dep.buildTypeId(), dep.id(),
            dep.buildType() != null && dep.buildType().name() != null ? dep.buildType().name() : dep.buildTypeId(),
            info == null ? null : info.text(), by);
    }

    /**
     * Failed suite builds on the branch that no chain walk reaches: a suite re-run on its own after the
     * chain (by the checker or by hand) can fail a test the chain passed — on a newer revision, a real
     * break that would otherwise never be classified. A build counts only as a re-run of a suite the
     * chain itself ran, and only when newer than the chain's run of it; that leaves out the RunAll. The
     * build step is a dependency of the chain like any suite, so a re-run wave that could not build is
     * a broken suite, as the chain's own failed build would be. The newer chains' suites are already
     * collected. One list call, plus the failed tests of each re-run.
     */
    private List<Callable<SuiteResult>> singleSuiteReruns(String token, int prNumber, TcModel.Build chain,
        Set<Long> newerChainSuites, Map<String, Integer> masterCounts) {
        Map<String, Long> chainRuns = new HashMap<>();
        for (TcModel.Build dep : depBuilds(chain)) {
            if (dep.buildTypeId() != null)
                chainRuns.merge(dep.buildTypeId(), dep.id(), Math::max);
        }
        if (chainRuns.isEmpty())
            return List.of();

        return tc.failedBuildsSince(token, prNumber, chain.queuedDate()).stream()
            .filter(b -> !newerChainSuites.contains(b.id()))
            .filter(b -> b.id() > chainRuns.getOrDefault(b.buildTypeId(), Long.MAX_VALUE))
            .<Callable<SuiteResult>>map(b -> () -> suiteResultOf(token, b, masterCounts))
            .toList();
    }

    /** Records a finished run that is a result for its suite again: it didn't break and ran master's tests in full. */
    private static void noteFullRun(Map<String, Long> fullRuns, SuiteResult r, Map<String, Integer> masterCounts) {
        TcModel.Build run = r.run();
        if (r.broken() != null || !"finished".equalsIgnoreCase(run.state()) || run.buildTypeId() == null)
            return;

        int tests = run.testOccurrences() == null ? 0 : run.testOccurrences().count();
        if (isFullRun(tests, masterCounts.getOrDefault(run.buildTypeId(), 0)))
            fullRuns.merge(run.buildTypeId(), run.id(), Math::max);
    }

    /**
     * Records a re-run's broken (or unstable) result so that a suite keeps one entry, its newest such run:
     * a suite re-run because it broke and broken again is one broken suite (the count reaches the visa),
     * and the newest run is the one its problems and link must describe.
     */
    private static void supersede(List<BrokenSuite> entries, BrokenSuite rerun) {
        entries.removeIf(b -> rerun.suite().equals(b.suite()) && b.suiteBuildId() < rerun.suiteBuildId());
        if (entries.stream().noneMatch(b -> rerun.suite().equals(b.suite())))
            entries.add(rerun);
    }

    /**
     * Suites whose test count fell well below master's. A suite that TeamCity already reports as
     * broken (timeout/crash) explains its own missing tests, so it is left to that card; what this
     * catches is the silent case — a suite that "passed" with half its tests never run.
     */
    /** Whether a run's test count is back within the shrink threshold of master's. */
    public static boolean isFullRun(int tests, int master) {
        return master < SHRINK_MIN_BASELINE || 100.0 * (master - tests) / master < SHRINK_PCT;
    }

    /** A run of {@code tests} of the {@code master} tests its suite runs on master, as a shrunk suite. */
    static ShrunkSuite shrunk(String suite, String suiteName, long runId, int tests, int master) {
        return new ShrunkSuite(suite, suiteName, runId, tests, master, (int) Math.round(100.0 * (master - tests) / master));
    }

    private static List<ShrunkSuite> shrunkSuites(List<TcModel.Build> deps, java.util.Map<String, Integer> baseline,
        Set<Long> brokenRuns) {
        if (baseline.isEmpty())
            return List.of();

        List<ShrunkSuite> out = new ArrayList<>();
        for (TcModel.Build dep : deps) {
            if (!"finished".equalsIgnoreCase(dep.state()))
                continue; // a suite mid-run has only run part of its tests — that is not a shrink

            if (brokenRuns.contains(dep.id()))
                continue; // its cause is already reported, and the missing tests are that cause's doing

            Integer master = baseline.get(dep.buildTypeId());
            if (master == null || master < SHRINK_MIN_BASELINE || dep.testOccurrences() == null)
                continue;

            ShrunkSuite s = shrunk(dep.buildTypeId(), dep.buildType() == null ? dep.buildTypeId() : dep.buildType().name(),
                dep.id(), dep.testOccurrences().count(), master);
            if (s.dropPct() >= SHRINK_PCT)
                out.add(s);
        }
        out.sort(java.util.Comparator.comparingInt(ShrunkSuite::dropPct).reversed());

        return out;
    }

    /** Problems meaning the suite hung or died mid-run, so its per-test failures are unreliable
     * (timeout/hang cascades, tests that never got to run) — surface the suite, not the noise. */
    private static final Set<String> UNSTABLE_PROBLEMS = Set.of("TC_EXECUTION_TIMEOUT", "TC_OOME", "TC_JVM_CRASH");

    /**
     * Whether a run got through master's tests for its suite. Unlike {@link #isFullRun} this needs a count
     * to compare with, however small the suite: it decides whether the failures of a suite that crashed
     * can be trusted, and an unknown baseline proves nothing.
     */
    private static boolean ranInFull(TcModel.Build run, Integer master) {
        return master != null && master > 0 && run.testOccurrences() != null
            && 100.0 * (master - run.testOccurrences().count()) / master < SHRINK_PCT;
    }

    private SuiteResult suiteResultOf(String token, TcModel.Build dep, Map<String, Integer> masterCounts) {
        String suiteName = dep.buildType() != null && dep.buildType().name() != null
            ? dep.buildType().name()
            : dep.buildTypeId();

        List<TcModel.ProblemOccurrence> problems = dep.problemOccurrences() != null
            && dep.problemOccurrences().problemOccurrence() != null
            ? dep.problemOccurrences().problemOccurrence() : List.of();

        // A suite that timed out / ran out of memory / crashed usually didn't finish: its failed tests are
        // likely cascade noise and some tests never ran. Show it as a broken suite, don't mine it for
        // blockers (the same reasoning as an interrupted chain, at suite granularity). One that still ran
        // all of master's tests did finish them, and its failures are as real as any suite's: hidden, a
        // known flake in Snapshots 6 held PR 13644 on "not proven" and in re-run waves. Whether such a
        // suite is broken waits for its tests' verdicts.
        // A suite still running has failures worth showing but no final story: its tests are collected,
        // and it is never called broken — "failed without running tests" would be a lie about a suite
        // that simply hasn't got there yet.
        boolean finished = "finished".equalsIgnoreCase(dep.state());
        boolean unstable = finished && problems.stream().anyMatch(p -> UNSTABLE_PROBLEMS.contains(p.type()));
        if (unstable && !ranInFull(dep, masterCounts.get(dep.buildTypeId())))
            return new SuiteResult(dep, List.of(), brokenSuite(dep, suiteName, problems, masterCounts), null);

        List<FailedTest> tests = tc.getFailedTests(token, dep.id()).stream()
            .filter(occ -> occ.test() != null)
            .map(occ -> new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId(), dep.id(), suiteName, occ.id()))
            .toList();

        // A suite whose only failures are muted is broken too: muted failures don't turn a suite red, so its
        // problems name what did.
        if (tests.isEmpty() && finished)
            return new SuiteResult(dep, List.of(), brokenSuite(dep, suiteName, problems, masterCounts), null);

        return new SuiteResult(dep, tests, null, unstable ? brokenSuite(dep, suiteName, problems, masterCounts) : null);
    }

    private static BrokenSuite brokenSuite(TcModel.Build dep, String suiteName, List<TcModel.ProblemOccurrence> problems,
        Map<String, Integer> masterCounts) {
        List<String> descriptions = problems.stream().map(ChainCollector::describeProblem).distinct().toList();
        int tests = dep.testOccurrences() == null ? 0 : dep.testOccurrences().count();
        Integer master = masterCounts.get(dep.buildTypeId());

        return new BrokenSuite(dep.buildTypeId(), dep.id(), suiteName,
            descriptions.isEmpty() ? List.of("failed without running tests") : descriptions,
            tests, master == null ? 0 : master);
    }

    /** Human wording for a TeamCity problem type, falling back to its details/raw type. */
    private static String describeProblem(TcModel.ProblemOccurrence p) {
        String type = p.type() == null ? "" : p.type();

        return switch (type) {
            case "TC_COMPILATION_ERROR" -> "compilation error";
            case "TC_EXECUTION_TIMEOUT" -> "execution timeout";
            case "TC_EXIT_CODE" -> "non-zero exit code";
            case "TC_OOME", "TC_JVM_CRASH" -> "JVM crash / out of memory";
            case "SNAPSHOT_DEPENDENCY_ERROR", "SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE" -> "failed dependency";
            default -> p.details() != null && !p.details().isBlank()
                ? p.details().strip().split("\n")[0]
                : (type.isBlank() ? "unknown problem" : type);
        };
    }

    /**
     * One FAILURE suite run's outcome: its failed tests, or (when there are none) why it broke. {@code unstable}
     * is the problem of a suite that crashed after running all its tests: broken only if its failures are
     * not all explained without the PR.
     */
    private record SuiteResult(TcModel.Build run, List<FailedTest> tests, BrokenSuite broken, BrokenSuite unstable) {
    }

    /**
     * What makes a failure a candidate of its own: the test, in the suite it failed in. One test id runs
     * in several suites of a chain (the C++ tests run on Windows, Linux and Clang), and each suite's
     * failure is judged by that suite's runs and master history, so another suite's failure of the same
     * test must not stand in for it. A re-run of the same suite failing it again adds no candidate: it is
     * one of the runs that candidate is judged by.
     */
    private record Candidate(long testId, String suite) {
    }

    private static List<TcModel.Build> depBuilds(TcModel.Build build) {
        TcModel.SnapshotDeps deps = build.snapshotDependencies();
        if (deps == null || deps.build() == null)
            return List.of();

        return deps.build();
    }

    /**
     * A chain's collected verdict inputs plus its composition: how many suites actually ran vs were reused.
     * {@code unstableSuites} crashed after running all their tests: their failed tests are among the
     * candidates, and the analysis decides whether each suite counts as broken. {@code cancelledSuites}
     * are the chain's suites that never ran, as the chain left them.
     */
    public record Chain(long buildId, String branchName, List<FailedTest> failedTests, List<BrokenSuite> brokenSuites,
        List<ShrunkSuite> shrunkSuites,
        int suitesRan, int suitesReused, boolean interrupted, int canceledSuites, boolean live, long liveBuildId,
        long queuedAt, long startedAt, long finishedAt, List<BrokenSuite> unstableSuites,
        List<CancelledSuite> cancelledSuites) {
        /** A chain with no unstable or listed cancelled suites, in the shape callers used before they were tracked. */
        public Chain(long buildId, String branchName, List<FailedTest> failedTests, List<BrokenSuite> brokenSuites,
            List<ShrunkSuite> shrunkSuites, int suitesRan, int suitesReused, boolean interrupted, int canceledSuites,
            boolean live, long liveBuildId, long queuedAt, long startedAt, long finishedAt) {
            this(buildId, branchName, failedTests, brokenSuites, shrunkSuites, suitesRan, suitesReused, interrupted,
                canceledSuites, live, liveBuildId, queuedAt, startedAt, finishedAt, List.of(), List.of());
        }
    }
}
