package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Walks the RunAll chain build for a PR into its dependency suites and collects the failed tests
 * across all of them (deduplicated by test id). The per-suite lookups run in parallel.
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
        Set<Long> seen = new HashSet<>();
        for (SuiteResult r : Parallel.run(pool, tasks)) {
            if (r.broken() != null)
                broken.add(r.broken());
            for (FailedTest ft : r.tests()) {
                if (seen.add(ft.testId()))
                    failed.add(ft);
            }
        }

        // Overlay results from any chain newer than the baseline finished build — running, cancelled
        // or interrupted. Even a run that didn't fully complete ran (and failed) some suites, and those
        // finished-FAILURE suites must count. classify() re-anchors each test to its newest finished
        // run, so this only needs to ADD candidates that appear in the newer chain(s).
        boolean live = subjectRunning;
        long liveBuildId = subjectRunning ? build.id() : 0;
        for (TcModel.Build chain : tc.recentChains(token, prNumber, 3)) {
            if (chain.id() <= buildId || "queued".equalsIgnoreCase(chain.state()))
                continue; // not newer than the baseline, or nothing has run in it yet
            live = true;
            liveBuildId = Math.max(liveBuildId, chain.id());
            TcModel.Build rBuild = tc.getBuildWithDeps(token, chain.id());
            List<Callable<SuiteResult>> rTasks = depBuilds(rBuild).stream()
                .filter(dep -> "finished".equalsIgnoreCase(dep.state()) && "FAILURE".equals(dep.status()))
                .<Callable<SuiteResult>>map(dep -> () -> suiteResultOf(token, dep, masterCounts))
                .toList();
            for (SuiteResult r : Parallel.run(pool, rTasks)) {
                if (r.broken() != null && broken.stream().noneMatch(b -> b.suiteBuildId() == r.broken().suiteBuildId()))
                    broken.add(r.broken());
                for (FailedTest ft : r.tests())
                    if (seen.add(ft.testId()))
                        failed.add(ft);
            }
        }

        // Reuse transparency: a re-triggered chain on unchanged revisions reuses earlier suite builds
        // (TeamCity substitutes suitable results). A dep queued before the chain itself is such a
        // reused build; showing ran-vs-reused up front beats making users suspect staleness.
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
                else
                    ran++;
            }
        }

        // A chain that aborted mid-way leaves suites neither passed nor failed (canceled/UNKNOWN):
        // they never ran, so the verdict is partial — surface that instead of implying they were clean.
        int canceled = (int) depBuilds(build).stream()
            .filter(d -> d.status() != null && !"SUCCESS".equals(d.status()) && !"FAILURE".equals(d.status()))
            .count();
        // The chain itself failed with suites left unrun, OR the whole chain was cancelled (UNKNOWN)
        // — in both cases the suites that did run still counted, but the verdict is partial.
        boolean interrupted = ("FAILURE".equals(build.status()) || "UNKNOWN".equals(build.status())) && canceled > 0;

        return new Chain(build.id(), build.branchName(), failed, broken,
            shrunkSuites(depBuilds(build), masterCounts, broken),
            ran, reused, interrupted, canceled, live, liveBuildId,
            TcDates.epochSeconds(build.queuedDate()), TcDates.epochSeconds(build.startDate()),
            TcDates.epochSeconds(build.finishDate()));
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

    private static List<ShrunkSuite> shrunkSuites(List<TcModel.Build> deps, java.util.Map<String, Integer> baseline,
        List<BrokenSuite> broken) {
        if (baseline.isEmpty())
            return List.of();

        Set<Long> brokenBuilds = broken.stream().map(BrokenSuite::suiteBuildId).collect(java.util.stream.Collectors.toSet());
        List<ShrunkSuite> out = new ArrayList<>();
        for (TcModel.Build dep : deps) {
            if (!"finished".equalsIgnoreCase(dep.state()))
                continue; // a suite mid-run has only run part of its tests — that is not a shrink

            if (brokenBuilds.contains(dep.id()))
                continue; // its cause is already reported, and the missing tests are that cause's doing

            Integer master = baseline.get(dep.buildTypeId());
            if (master == null || master < SHRINK_MIN_BASELINE || dep.testOccurrences() == null)
                continue;

            int tests = dep.testOccurrences().count();
            int drop = (int) Math.round(100.0 * (master - tests) / master);
            if (drop >= SHRINK_PCT)
                out.add(new ShrunkSuite(dep.buildTypeId(),
                    dep.buildType() == null ? dep.buildTypeId() : dep.buildType().name(),
                    dep.id(), tests, master, drop));
        }
        out.sort(java.util.Comparator.comparingInt(ShrunkSuite::dropPct).reversed());

        return out;
    }

    /** Problems meaning the suite hung or died mid-run, so its per-test failures are unreliable
     * (timeout/hang cascades, tests that never got to run) — surface the suite, not the noise. */
    private static final Set<String> UNSTABLE_PROBLEMS = Set.of("TC_EXECUTION_TIMEOUT", "TC_OOME", "TC_JVM_CRASH");

    private SuiteResult suiteResultOf(String token, TcModel.Build dep, Map<String, Integer> masterCounts) {
        String suiteName = dep.buildType() != null && dep.buildType().name() != null
            ? dep.buildType().name()
            : dep.buildTypeId();

        List<TcModel.ProblemOccurrence> problems = dep.problemOccurrences() != null
            && dep.problemOccurrences().problemOccurrence() != null
            ? dep.problemOccurrences().problemOccurrence() : List.of();

        // A suite that timed out / ran out of memory / crashed didn't finish reliably: its failed tests
        // are likely cascade noise and some tests never ran. Show it as a broken suite, don't mine it
        // for blockers (the same reasoning as an interrupted chain, at suite granularity).
        // A suite still running has failures worth showing but no final story: its tests are collected,
        // and it is never called broken — "failed without running tests" would be a lie about a suite
        // that simply hasn't got there yet.
        boolean finished = "finished".equalsIgnoreCase(dep.state());
        boolean unstable = finished && problems.stream().anyMatch(p -> UNSTABLE_PROBLEMS.contains(p.type()));
        if (unstable)
            return new SuiteResult(List.of(), brokenSuite(dep, suiteName, problems, masterCounts));

        List<FailedTest> tests = tc.getFailedTests(token, dep.id()).stream()
            .filter(occ -> occ.test() != null)
            .map(occ -> new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId(), dep.id(), suiteName, occ.id()))
            .toList();

        if (!tests.isEmpty() || !finished)
            return new SuiteResult(tests, null);

        return new SuiteResult(List.of(), brokenSuite(dep, suiteName, problems, masterCounts));
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

    /** One FAILURE suite's outcome: its failed tests, or (when there are none) why it broke. */
    private record SuiteResult(List<FailedTest> tests, BrokenSuite broken) {
    }

    private static List<TcModel.Build> depBuilds(TcModel.Build build) {
        TcModel.SnapshotDeps deps = build.snapshotDependencies();
        if (deps == null || deps.build() == null)
            return List.of();

        return deps.build();
    }

    /** A chain's collected verdict inputs plus its composition: how many suites actually ran vs were reused. */
    public record Chain(long buildId, String branchName, List<FailedTest> failedTests, List<BrokenSuite> brokenSuites,
        List<ShrunkSuite> shrunkSuites,
        int suitesRan, int suitesReused, boolean interrupted, int canceledSuites, boolean live, long liveBuildId,
        long queuedAt, long startedAt, long finishedAt) {
    }
}
