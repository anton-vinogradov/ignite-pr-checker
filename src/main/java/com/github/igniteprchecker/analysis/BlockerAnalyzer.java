package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise. A test is a
 * blocker only if it (1) fails in the PR, (2) never fails in the last {@code analysis.historyDepth}
 * master runs (any master failure means it is pre-existing or flaky on master, not this PR's fault),
 * and (3) still fails in the last fully-finished run of its suite on the PR branch (a passing re-run
 * clears it). Results are cached per build; a request serves the cached result and, if it is getting
 * stale, triggers a background refresh.
 */
@Component
public class BlockerAnalyzer {
    private final TcClient tc;
    private final ChainCollector chains;
    private final AnalysisProperties cfg;
    private final ExecutorService pool;
    private final ExecutorService bgPool;
    private final ExecutorService refreshPool;
    private final AnalysisCache cache;
    private final RunDeltaStore deltas;

    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    /** In-flight computes per build id, so concurrent requests for the same build share one run
     * instead of piling duplicate full recomputes onto the pool (a page reload during a cold
     * analysis used to start another one from scratch). */
    private final ConcurrentMap<Long, CompletableFuture<AnalysisResult>> inFlight = new ConcurrentHashMap<>();

    /** Live progress of in-flight computes (build id -> pr/done/total), for the "Analyzing…" line. */
    private final ConcurrentMap<Long, Progress> progress = new ConcurrentHashMap<>();

    /** How many user-facing requests are currently waiting on a compute — the warmer yields to them. */
    private final AtomicInteger usersWaiting = new AtomicInteger();

    /** Last known blocker count per PR (best-effort), for the badges in the PR list. */
    private final Map<Integer, Integer> prBlockers = new ConcurrentHashMap<>();

    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg,
        @Qualifier("analysisExecutor") ExecutorService pool,
        @Qualifier("backgroundExecutor") ExecutorService bgPool,
        @Qualifier("refreshExecutor") ExecutorService refreshPool,
        AnalysisCache cache, RunDeltaStore deltas) {
        this.tc = tc;
        this.chains = chains;
        this.cfg = cfg;
        this.pool = pool;
        this.bgPool = bgPool;
        this.refreshPool = refreshPool;
        this.cache = cache;
        this.deltas = deltas;
    }

    /** @return the analysis (cached if available), or empty if no RunAll build exists for the PR yet. */
    public Optional<AnalysisResult> analyze(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return Optional.empty();

        long bid = buildId.get();
        Optional<AnalysisResult> cached = cache.peekResult(bid);
        if (cached.isPresent()) {
            prBlockers.put(prNumber, cached.get().blockers().size());
            if (isStale(cached.get()))
                refreshAsync(token, prNumber, bid);

            return cached;
        }

        usersWaiting.incrementAndGet();
        try {
            return Optional.of(computeAndStore(token, prNumber, bid, pool));
        }
        finally {
            usersWaiting.decrementAndGet();
        }
    }

    /** Best-effort blocker count for a PR from the last analysis, or null if it hasn't been analysed. */
    public Integer blockerCount(int prNumber) {
        return prBlockers.get(prNumber);
    }

    /** The TeamCity user who triggered a PR's latest RunAll, if known — backs the "My?" flag in the PR list. */
    public String triggeredBy(int prNumber) {
        return chains.triggeredBy(prNumber);
    }

    /** Recomputes and caches the analysis for a PR's latest build. Used by the warmer (background pool). */
    public void refresh(String token, int prNumber) {
        chains.findBuildId(token, prNumber).ifPresent(bid -> computeAndStore(token, prNumber, bid, bgPool));
    }

    /**
     * Warms a PR for the cache-warmer: looks up the latest build (cheap) and recomputes it only if
     * that build is not already cached — an unchanged RunAll build yields the same result, so there
     * is nothing to redo. Returns true if it recomputed, false if the cached result was reused (or
     * the PR has no finished RunAll build yet). This is what keeps the warmer from re-hammering
     * TeamCity with the heavy history/latest-run lookups every cycle.
     */
    public boolean warm(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return false;

        Optional<AnalysisResult> cached = cache.peekResult(buildId.get());
        if (cached.isPresent()) {
            prBlockers.put(prNumber, cached.get().blockers().size());
            return false;
        }

        computeAndStore(token, prNumber, buildId.get(), bgPool);
        return true;
    }

    /**
     * Recomputes now (ignoring any cached result) and returns the fresh analysis, or empty if no
     * RunAll build exists yet. Backs the manual "refresh" button.
     */
    public Optional<AnalysisResult> forceRefresh(String token, int prNumber) {
        usersWaiting.incrementAndGet();
        try {
            return chains.findBuildIdFresh(token, prNumber)
                .map(bid -> computeAndStore(token, prNumber, bid, pool));
        }
        finally {
            usersWaiting.decrementAndGet();
        }
    }

    /** True while at least one user request is blocked on a compute — background warming should yield. */
    public boolean userWaiting() {
        return usersWaiting.get() > 0;
    }

    /** Progress of an in-flight compute for this PR ({@code done}/{@code total} failed tests), or null. */
    public Progress progressOf(int prNumber) {
        return progress.values().stream().filter(p -> p.pr() == prNumber).findFirst().orElse(null);
    }

    private boolean isStale(AnalysisResult r) {
        // A live (run-in-progress) result must refresh quickly to pull in suites as they finish.
        long windowMs = r.live() ? 120_000L : cfg.refreshAfterSeconds() * 1000L;
        return System.currentTimeMillis() - r.computedAt() > windowMs;
    }

    private void refreshAsync(String token, int prNumber, long buildId) {
        if (!refreshing.add(buildId))
            return;

        refreshPool.execute(() -> {
            try {
                computeAndStore(token, prNumber, buildId, bgPool);
            }
            catch (RuntimeException ignore) {
                // best-effort background refresh; the stale cached value stays until it succeeds
            }
            finally {
                refreshing.remove(buildId);
            }
        });
    }

    private AnalysisResult computeAndStore(String token, int prNumber, long buildId, ExecutorService taskPool) {
        CompletableFuture<AnalysisResult> mine = new CompletableFuture<>();
        CompletableFuture<AnalysisResult> existing = inFlight.putIfAbsent(buildId, mine);
        if (existing != null) {
            try {
                return existing.join(); // this exact build is already being computed: share that run
            }
            catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException re)
                    throw re;
                throw e;
            }
        }

        try {
            AnalysisResult result = doCompute(token, prNumber, buildId, taskPool);
            mine.complete(result);

            return result;
        }
        catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        }
        finally {
            inFlight.remove(buildId);
            progress.remove(buildId);
        }
    }

    private AnalysisResult doCompute(String token, int prNumber, long buildId, ExecutorService taskPool) {
        ChainCollector.Chain chain = chains.collectForBuild(token, prNumber, buildId, taskPool);

        Progress prog = new Progress(prNumber, chain.failedTests().size(), new AtomicInteger());
        progress.put(buildId, prog);

        List<Callable<TestVerdict>> tasks = chain.failedTests().stream()
            .<Callable<TestVerdict>>map(t -> () -> {
                try {
                    return classify(token, prNumber, t);
                }
                finally {
                    prog.done().incrementAndGet();
                }
            })
            .toList();

        List<TestVerdict> verdicts = Parallel.run(taskPool, tasks);
        List<TestVerdict> blockers = verdicts.stream().filter(TestVerdict::blocker).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker()).toList();

        AnalysisResult result = new AnalysisResult(prNumber, buildId, chain.branchName(),
            System.currentTimeMillis(), blockers, filtered, chain.brokenSuites(),
            chain.suitesRan(), chain.suitesReused(), chain.interrupted(), chain.canceledSuites(), chain.live());

        cache.putResult(buildId, result);
        prBlockers.put(prNumber, blockers.size());
        deltas.onResult(prNumber, buildId, blockers, chain.brokenSuites().size());

        return result;
    }

    private TestVerdict classify(String token, int prNumber, FailedTest t) {
        try {
            return classifyVerified(token, prNumber, t);
        }
        catch (RuntimeException e) {
            // A transient TeamCity error for one test must not fail the whole analysis (Parallel.run would
            // propagate it and every other verdict would be lost). Keep the test visible as an unverified
            // blocker — it did fail in the PR — and flag that we couldn't check it.
            return verdict(t, null, true, "could not verify (TeamCity error: " + rootMessage(e) + ")", "");
        }
    }

    private TestVerdict classifyVerified(String token, int prNumber, FailedTest t) {
        HistoryStats h = cache.history(t.testId(),
            () -> HistoryStats.of(tc.getBaseBranchHistory(token, t.testId())));

        // The finished runs of this test on the PR branch (one request; also drives the history strip).
        List<TcModel.TestOccurrence> runs = tc.prBranchRuns(token, prNumber, t.testId());
        String branchRuns = strip(runs);
        TcModel.TestOccurrence lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);

        // A failure in the PR is a blocker unless the test also fails in master history: any failure
        // there means it isn't specific to this PR (pre-existing or flaky on master). It still gets
        // its branch-runs strip and latest-run anchor, so flaky tests are visualised like blockers.
        if (h.fails() > 0) {
            return verdict(t, lastRun, false, "pre-existing: fails " + h.fails() + "/" + h.runs() + " on master", branchRuns);
        }

        // ...and only if the failure still stands in the last fully-finished run on the branch: a
        // later re-run that passed clears it (the failure wasn't reproducible on the same code).
        String latest = lastRun == null ? null : lastRun.status();
        if (latest != null && !"FAILURE".equals(latest)) {
            return verdict(t, lastRun, false, "not failing in the last finished run (passed on re-run)", branchRuns);
        }

        String reason = h.runs() == 0
            ? "no master history (can't prove pre-existing)"
            : "not seen failing in " + h.runs() + " master run(s)";

        return verdict(t, lastRun, true, reason, branchRuns);
    }

    /** Short root-cause message of a failure, for the "could not verify" reason (bounded length). */
    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();

        String m = c.getMessage();
        if (m == null)
            return c.getClass().getSimpleName();

        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    /** An in-flight compute: which PR, how many failed tests total, how many are classified so far. */
    public record Progress(int pr, int total, AtomicInteger done) {
    }

    /** Compact pass/fail history of the branch runs, oldest → newest: 'P' for a pass, 'F' for a failure. */
    private static String strip(List<TcModel.TestOccurrence> runs) {
        StringBuilder sb = new StringBuilder(runs.size());
        for (TcModel.TestOccurrence o : runs)
            sb.append("FAILURE".equals(o.status()) ? 'F' : 'P');

        return sb.toString();
    }

    /**
     * Builds the verdict, anchoring its suite/build/occurrence to the <b>last finished run</b> of the
     * test on the branch when we have it — so the link and grouping point at the run the verdict is
     * actually about (a later re-run, if any), not at the RunAll dependency we first discovered it in.
     * Falls back to the RunAll-dependency occurrence (e.g. for pre-existing tests, where no runs are fetched).
     */
    private static TestVerdict verdict(FailedTest t, TcModel.TestOccurrence lastRun, boolean blocker,
        String reason, String branchRuns) {
        long suiteBuildId = t.suiteBuildId();
        String suite = t.suite();
        String suiteName = t.suiteName();
        String occurrenceId = t.occurrenceId();

        if (lastRun != null && lastRun.build() != null) {
            TcModel.BuildRef b = lastRun.build();
            suiteBuildId = b.id();
            if (b.buildTypeId() != null)
                suite = b.buildTypeId();
            if (b.buildType() != null && b.buildType().name() != null)
                suiteName = b.buildType().name();
            if (lastRun.id() != null)
                occurrenceId = lastRun.id();
        }

        return new TestVerdict(t.testId(), t.name(), suite, suiteBuildId, suiteName, occurrenceId, blocker, reason, branchRuns);
    }
}
