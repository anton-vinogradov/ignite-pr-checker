package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise. A test is a
 * blocker only if it (1) fails in the PR, (2) never fails in the last {@code analysis.historyDepth}
 * master runs of the suite it failed in that ran on the PR run's JDK, and (3) still fails in the last
 * fully-finished run of that suite on the PR branch (a passing re-run clears it). A master failure means
 * the test is pre-existing or flaky on master, not this PR's fault, unless that failure is rare and old
 * and the test keeps failing on the PR's code, or master only fails it at another test scale factor
 * while other PRs pass it. A test failing on the branches of several other PRs run like this one is flaky,
 * not this PR's blocker. All of it looks at that one suite only: the same test id runs in several suites
 * of a chain (the C++ tests run on Windows, Linux and Clang), and another platform's pass is not a
 * re-run, nor are its master failures this one's. Results are cached per build; a request serves the
 * cached result and, if something finished on the branch since, triggers a background refresh. A result
 * that TeamCity errors left incomplete is retried on its own, and is not acted on while it is retried.
 */
@Component
public class BlockerAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(BlockerAnalyzer.class);

    /**
     * How far before an analysis starts its branch watermark is set. TeamCity stamps finish dates with
     * its own clock, which may run a little ahead of ours: a watermark set too early costs one more
     * recompute, one set too late silently misses a re-run. Well below the warm interval, so it settles.
     */
    private static final long WATERMARK_MARGIN_SECONDS = 60;

    /** Fewer master runs than this on the PR's JDK are called out as thin evidence. */
    private static final int FEW_MASTER_RUNS = 10;

    /** How many of the newest master runs must have passed for an older master failure to be outweighed. */
    private static final int RECENT_MASTER_GREEN = 10;

    /** The largest share of master runs, in percent, a master failure may have failed for it to be outweighed. */
    private static final int RARE_ON_MASTER_PERCENT = 2;

    /**
     * How unlikely by chance a failure streak on the PR's code must be, as one in this many, to outweigh
     * a rate of failures that happen without the PR.
     */
    private static final int OUTWEIGHS_ONE_IN = 10_000;

    /** In how many other PRs a test must have failed to count as flaky under PR conditions. */
    private static final int FLAKY_IN_PRS = 3;

    /** How many runs on other PRs' branches it takes to set master's failures aside. */
    private static final int MIN_OTHER_PR_RUNS = 10;

    /** How often a viewed verdict may ask TeamCity whether its branch moved, at most. */
    private static final long LOOK_EVERY_MS = 120_000;

    /** The shortest and the longest wait before an incomplete result is computed again. */
    private static final long MIN_RETRY_WAIT_MS = 120_000;

    private static final long MAX_RETRY_WAIT_MS = 1_800_000;

    /**
     * How long an incomplete result is held back from the visa, the PR comment and the re-run waves:
     * two passes of the 10-minute sweep, each with a fresh try. Past that it is acted on as it is, its
     * unchecked tests listed apart and not counted as blockers.
     */
    private static final long HOLD_INCOMPLETE_MS = 1_200_000;

    private final TcClient tc;
    private final ChainCollector chains;
    private final AnalysisProperties cfg;
    private final ExecutorService pool;
    private final ExecutorService bgPool;
    private final ExecutorService refreshPool;
    private final AnalysisCache cache;
    private final RunDeltaStore deltas;

    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    /** Builds whose branch was found unmoved lately, so another view does not ask again so soon. */
    private final TtlCache<Long, Boolean> lookedAt = new TtlCache<>(LOOK_EVERY_MS);

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
    /** Whether the run behind that count covered enough for "0 blockers" to mean anything. */
    private final Map<Integer, Boolean> prProven = new ConcurrentHashMap<>();

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
            cache.touchResult(bid); // an immutable per-build result must never expire while in use
            rememberVerdict(prNumber, cached.get());
            // A view recomputes only what can have changed. A timer used to recompute any verdict older
            // than two minutes: 23 views of long-finished PRs cost ci2 up to 695 calls a minute.
            if (retryDue(cached.get()))
                refreshAsync(token, prNumber, bid, false);
            else if (isStale(cached.get()) && lookedAt.peek(bid).isEmpty())
                refreshAsync(token, prNumber, bid, true);

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

    /**
     * Whether that count came from a run that actually covered the PR — null if not analysed. A zero
     * count off an interrupted or broken run must not show as a clean tick in the PR list.
     */
    public Boolean provenClean(int prNumber) {
        return prProven.get(prNumber);
    }

    private void rememberVerdict(int prNumber, AnalysisResult r) {
        prBlockers.put(prNumber, r.blockers().size());
        prProven.put(prNumber, Caveats.proven(r));
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
     * Warms a PR for the cache-warmer: looks up the latest build (cheap) and recomputes it only when
     * the answer can have changed — a different chain build, or new finished builds on the branch
     * since the cached result was computed (a green re-run of a blocker suite clears it without the
     * chain build changing) — or when TeamCity errors left it incomplete and another try is due.
     * Returns true if it recomputed. This is what keeps the warmer from
     * re-hammering TeamCity with the heavy history/latest-run lookups every cycle.
     */
    public boolean warm(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return false;

        Optional<AnalysisResult> cached = cache.peekResult(buildId.get());
        if (cached.isPresent() && !retryDue(cached.get()) && !branchMovedSince(token, prNumber, cached.get(), false)) {
            // The warm cycle (10 min) is shorter than the TTL (15 min), but a skip used to leave the
            // old expiry in place — every other cycle the entry died mid-window and a viewer hit a
            // cold compute. Touching on skip keeps the warmed set permanently hot.
            cache.touchResult(buildId.get());
            rememberVerdict(prNumber, cached.get());
            return false;
        }

        computeAndStore(token, prNumber, buildId.get(), bgPool);
        return true;
    }

    /**
     * Whether the branch has finished a build the cached verdict may never have seen. A suite re-run is
     * exactly that: same chain, later evidence, different answer — and a PR whose blockers were fixed
     * by a re-run kept showing them until someone opened it. Costs one cheap call. {@code onError} is
     * the answer when TeamCity can't be asked: the warm cycle says no, since a blip must not turn it
     * into a full recompute storm; whatever is about to act on the verdict says yes.
     */
    private boolean branchMovedSince(String token, int prNumber, AnalysisResult cached, boolean onError) {
        if (cached.branchWatermarkAt() <= 0)
            return true; // computed before this was recorded: recompute once, then it settles

        try {
            return tc.branchFinishedAfter(token, prNumber, cached.branchWatermarkAt());
        }
        catch (RuntimeException e) {
            return onError;
        }
    }

    /**
     * The verdict to act on: the standing sweep picks the suites to re-run from it and posts it as the
     * visa and the PR comment. {@link #analyze} serves whatever is cached and refreshes it in the
     * background, which suits a page but not an action. On PR 13335 the sweep re-ran the suites of a
     * verdict cached while the chain was still running, so the suite that failed last was left out of
     * the first wave and got one attempt instead of two. The cached result is used only if the chain
     * had finished when it was read ({@code finishedAt} comes from TeamCity itself, so no clocks are
     * compared), nothing finished on the branch since, and it is not an incomplete result due for another
     * try; otherwise the verdict comes from a compute that starts after this call. One already under way
     * is waited out, not shared: it may have read a suite before its re-run finished, and suites of one
     * wave finish close together.
     */
    public Optional<AnalysisResult> analyzeForAction(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return Optional.empty();

        long bid = buildId.get();
        Optional<AnalysisResult> cached = cache.peekResult(bid);
        if (cached.isPresent() && cached.get().finishedAt() > 0 && !retryDue(cached.get())
            && !branchMovedSince(token, prNumber, cached.get(), true)) {
            cache.touchResult(bid);
            rememberVerdict(prNumber, cached.get());

            return cached;
        }

        return Optional.of(computeAfterNow(token, prNumber, bid));
    }

    /**
     * The analysis of a compute that starts after this call — for an action that must not be judged by
     * anything read earlier. One already under way is waited out, not shared: it may have read a suite
     * before the build that prompted the call finished.
     */
    public Optional<AnalysisResult> analyzeAfterNow(String token, int prNumber) {
        return chains.findBuildId(token, prNumber).map(bid -> computeAfterNow(token, prNumber, bid));
    }

    private AnalysisResult computeAfterNow(String token, int prNumber, long buildId) {
        CompletableFuture<AnalysisResult> underWay = inFlight.get(buildId);
        if (underWay != null)
            underWay.handle((r, e) -> null).join();

        return computeAndStore(token, prNumber, buildId, bgPool);
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

    /**
     * Whether an incomplete result is due for another try. The wait grows with how long it has been
     * incomplete, from 2 minutes to 30: a blip is gone within minutes, and an outage costs one
     * recompute per PR every half an hour.
     */
    private static boolean retryDue(AnalysisResult r) {
        if (r.incompleteSince() <= 0)
            return false;

        long wait = Math.min(MAX_RETRY_WAIT_MS, Math.max(MIN_RETRY_WAIT_MS, r.computedAt() - r.incompleteSince()));

        return System.currentTimeMillis() - r.computedAt() >= wait;
    }

    /**
     * Whether a result is too fresh in its incompleteness to act on: TeamCity failed some of its lookups
     * less than 20 minutes ago, and it is being retried. The visa, the PR comment and the re-run waves
     * wait for it; one failed request on ci2 used to post a blocker and re-run its suite.
     */
    public boolean stillRetrying(AnalysisResult r) {
        return r.incompleteSince() > 0 && System.currentTimeMillis() - r.incompleteSince() < HOLD_INCOMPLETE_MS;
    }

    /** Sweeps out expired "branch unmoved" marks (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        lookedAt.evictExpired();
    }

    /**
     * Recomputes in the background; with {@code ifMoved}, only when something finished on the branch since
     * the result was computed (one cheap call), else the view is marked looked at for a while.
     */
    private void refreshAsync(String token, int prNumber, long buildId, boolean ifMoved) {
        if (!refreshing.add(buildId))
            return;

        refreshPool.execute(() -> {
            try {
                Optional<AnalysisResult> cached = cache.peekResult(buildId);
                if (ifMoved && cached.isPresent() && !branchMovedSince(token, prNumber, cached.get(), false)) {
                    lookedAt.put(buildId, true);

                    return;
                }

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
                // TeamCity's answer to the other compute's token, perhaps a 401: rethrown as is, the caller would
                // take it as the verdict on its own token.
                if (e.getCause() instanceof RestClientResponseException answer)
                    throw new IllegalStateException("shared compute of build " + buildId + " failed", answer);
                if (e.getCause() instanceof RuntimeException re)
                    throw re;
                throw e;
            }
        }

        try {
            AnalysisResult result = doCompute(token, prNumber, buildId, taskPool);
            unlist(buildId, mine);
            mine.complete(result);

            return result;
        }
        catch (RuntimeException | Error e) {
            unlist(buildId, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Drops a compute from the in-flight map before it completes: whoever wakes on its completion and
     * asks again must start a new compute, not join this finished one (see {@link #analyzeForAction}).
     */
    private void unlist(long buildId, CompletableFuture<AnalysisResult> mine) {
        progress.remove(buildId);
        inFlight.remove(buildId, mine);
    }

    private AnalysisResult doCompute(String token, int prNumber, long buildId, ExecutorService taskPool) {
        // Taken before anything is read: a build that finishes while this runs may be missing from the
        // verdict, so it must count as unseen and cost one more recompute rather than be claimed.
        long watermarkAt = System.currentTimeMillis() / 1000 - WATERMARK_MARGIN_SECONDS;
        ChainCollector.Chain chain = chains.collectForBuild(token, prNumber, buildId, taskPool);

        Progress prog = new Progress(prNumber, chain.failedTests().size(), new AtomicInteger());
        progress.put(buildId, prog);

        FailedLookups failedLookups = new FailedLookups();
        List<Callable<Classified>> tasks = chain.failedTests().stream()
            .<Callable<Classified>>map(t -> () -> {
                try {
                    return classify(token, prNumber, t, failedLookups);
                }
                finally {
                    prog.done().incrementAndGet();
                }
            })
            .toList();

        List<Classified> classified = Parallel.run(taskPool, tasks);
        List<TestVerdict> verdicts = classified.stream().filter(Classified::verified).map(Classified::verdict).toList();
        List<TestVerdict> unverified = classified.stream().filter(c -> !c.verified()).map(Classified::verdict).toList();
        List<TestVerdict> blockers = verdicts.stream().filter(TestVerdict::blocker).toList();
        List<TestVerdict> watch = verdicts.stream().filter(v -> v.watch() && !v.blocker()).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker() && !v.watch()).toList();

        // A suite that crashed after running nearly all its tests is broken only if the PR may be behind the crash:
        // when every test it failed is pre-existing or flaky, the crash is a note next to a reliable result.
        Set<String> blamed = Stream.of(blockers, watch, unverified).flatMap(List::stream).map(TestVerdict::suite)
            .collect(Collectors.toSet());
        List<BrokenSuite> chainBroken = new ArrayList<>(chain.brokenSuites());
        List<BrokenSuite> unstable = new ArrayList<>();
        for (BrokenSuite s : chain.unstableSuites())
            (blamed.contains(s.suite()) ? chainBroken : unstable).add(s);

        List<ShrunkSuite> shortReruns = new ArrayList<>();
        List<BrokenSuite> broken = withoutHealed(token, prNumber, chainBroken, shortReruns, failedLookups);
        List<CancelledSuite> cancelled = notRunSince(token, prNumber, chain, broken, shortReruns, failedLookups);
        List<ShrunkSuite> shrunk = newestPerSuite(
            withFullRunsDropped(token, prNumber, chain.shrunkSuites(), failedLookups), shortReruns);

        long now = System.currentTimeMillis();
        long incompleteSince = failedLookups.count() == 0 ? 0 : cache.peekResult(buildId)
            .map(AnalysisResult::incompleteSince).filter(since -> since > 0).orElse(now);
        if (failedLookups.count() > 0) {
            log.warn("PR {} (build {}): {} TeamCity lookup(s) failed, {} failed test(s) left unchecked, retrying; "
                + "first: {}", prNumber, buildId, failedLookups.count(), unverified.size(), failedLookups.first());
        }

        AnalysisResult result = new AnalysisResult(prNumber, buildId, chain.branchName(),
            now, blockers, watch, filtered, broken, shrunk,
            chain.suitesRan(), chain.suitesReused(), chain.interrupted() && !cancelled.isEmpty(), cancelled.size(),
            chain.live(), chain.liveBuildId(), chain.queuedAt(), chain.startedAt(), chain.finishedAt(), watermarkAt,
            unstable, cancelled, unverified, incompleteSince);

        cache.putResult(buildId, result);
        rememberVerdict(prNumber, result);
        // A test left unchecked is not "fixed": the run-to-run comparison waits for a complete result.
        if (incompleteSince == 0)
            deltas.onResult(prNumber, buildId, blockers, broken.size());

        return result;
    }

    /**
     * A shrunk suite stops being shrunk once a NEWER finished run of it on the branch got its test
     * count back — same anchoring as broken suites. Without it, one hung run keeps claiming "tests
     * disappeared" long after the re-runs put them back, which reads as a coverage hole that no
     * longer exists.
     */
    private List<ShrunkSuite> withFullRunsDropped(String token, int prNumber, List<ShrunkSuite> shrunk,
        FailedLookups failedLookups) {
        if (shrunk.isEmpty())
            return shrunk;

        List<ShrunkSuite> out = new ArrayList<>();
        for (ShrunkSuite s : shrunk) {
            try {
                Optional<TcModel.Build> last = tc.latestSuiteRun(token, prNumber, s.suite());
                if (last.isPresent() && last.get().id() > s.suiteBuildId() && last.get().testOccurrences() != null
                    && ChainCollector.isFullRun(last.get().testOccurrences().count(), s.baseline()))
                    continue;
            }
            catch (RuntimeException e) {
                // TC hiccup — keep the entry rather than silently claiming the coverage is back
                failedLookups.add("latest run of " + s.suite(), e);
            }
            out.add(s);
        }

        return out;
    }

    /**
     * A broken suite stops being broken once a NEWER finished run of it on the branch passed with all its
     * tests — the same last-finished-run anchoring tests get, without which re-running a broken suite
     * (manually or automatically) could never clear it from the verdict. A pass with far fewer tests than
     * master's settles nothing: it goes to {@code shortReruns}, so the hole in coverage stays in sight.
     * Kept on any doubt. A newer full run that failed tests is settled already: see ChainCollector.
     */
    private List<BrokenSuite> withoutHealed(String token, int prNumber, List<BrokenSuite> broken,
        List<ShrunkSuite> shortReruns, FailedLookups failedLookups) {
        if (broken.isEmpty())
            return broken;

        List<BrokenSuite> out = new ArrayList<>();
        for (BrokenSuite s : broken) {
            try {
                Optional<TcModel.Build> last = tc.latestSuiteRun(token, prNumber, s.suite());
                if (last.isPresent() && last.get().id() > s.suiteBuildId() && "SUCCESS".equals(last.get().status())) {
                    int tests = last.get().testOccurrences() == null ? 0 : last.get().testOccurrences().count();
                    if (!ChainCollector.isFullRun(tests, s.baseline()))
                        shortReruns.add(ChainCollector.shrunk(s.suite(), s.suiteName(), last.get().id(), tests, s.baseline()));

                    continue;
                }
            }
            catch (RuntimeException e) {
                // TC hiccup — better a possibly stale broken-suite entry than a silently healed one
                failedLookups.add("latest run of " + s.suite(), e);
            }
            out.add(s);
        }

        return out;
    }

    /**
     * The chain's cancelled suites that have not run since. Any later finished run of the suite on the
     * branch, a re-run or a newer chain's, closes one: that run's own result counts instead. Without this a
     * re-run never lifted "N suite(s) never ran", and PR 13592 kept it for the four suites TeamCity had
     * cancelled. A closing run that ran far fewer tests than master and did not break goes to
     * {@code shortReruns}, as a short re-run of a broken suite does: a green run of 30 of Cache 1's 300 tests
     * must not read as covering it. One request for all of them, and none when nothing was cancelled. Kept
     * on any doubt.
     */
    private List<CancelledSuite> notRunSince(String token, int prNumber, ChainCollector.Chain chain,
        List<BrokenSuite> broken, List<ShrunkSuite> shortReruns, FailedLookups failedLookups) {
        if (chain.cancelledSuites().isEmpty())
            return chain.cancelledSuites();

        try {
            Map<String, TcModel.Build> newestRuns = new HashMap<>();
            for (TcModel.Build b : tc.finishedBuildsSince(token, prNumber,
                chain.queuedAt() > 0 ? TcDates.format(chain.queuedAt()) : null)) {
                if (b.buildTypeId() != null)
                    newestRuns.merge(b.buildTypeId(), b, (x, y) -> x.id() >= y.id() ? x : y);
            }

            List<CancelledSuite> open = new ArrayList<>();
            for (CancelledSuite c : chain.cancelledSuites()) {
                TcModel.Build run = newestRuns.get(c.suite());
                if (run == null || run.id() < c.suiteBuildId()) {
                    open.add(c);
                    continue;
                }

                int tests = run.testOccurrences() == null ? 0 : run.testOccurrences().count();
                boolean brokeSince = broken.stream().anyMatch(b -> b.suite().equals(c.suite()));
                if (!brokeSince && !ChainCollector.isFullRun(tests, c.baseline()))
                    shortReruns.add(ChainCollector.shrunk(c.suite(), c.suiteName(), run.id(), tests, c.baseline()));
            }

            return open;
        }
        catch (RuntimeException e) {
            failedLookups.add("runs since the chain", e);

            return chain.cancelledSuites();
        }
    }

    /** The shrunk suites of both lists, one per suite: its newest run, the one a re-run would have replaced. */
    private static List<ShrunkSuite> newestPerSuite(List<ShrunkSuite> shrunk, List<ShrunkSuite> more) {
        if (more.isEmpty())
            return shrunk;

        Map<String, ShrunkSuite> bySuite = new LinkedHashMap<>();
        Stream.concat(shrunk.stream(), more.stream())
            .forEach(s -> bySuite.merge(s.suite(), s, (a, b) -> a.suiteBuildId() >= b.suiteBuildId() ? a : b));

        return bySuite.values().stream().sorted(Comparator.comparingInt(ShrunkSuite::dropPct).reversed()).toList();
    }

    private Classified classify(String token, int prNumber, FailedTest t, FailedLookups failedLookups) {
        try {
            return new Classified(classifyVerified(token, prNumber, t, failedLookups), true);
        }
        catch (RuntimeException e) {
            // A transient TeamCity error for one test must not fail the whole analysis (Parallel.run would
            // propagate it and every other verdict would be lost). The test did fail in the PR, so it stays
            // in sight, but apart from the verdicts: as a blocker, one 502 got a visa and a re-run wave.
            failedLookups.add(t.name(), e);

            return new Classified(verdict(t, null, false, false,
                "could not verify (TeamCity error: " + rootMessage(e) + ")", "", 0), false);
        }
    }

    private TestVerdict classifyVerified(String token, int prNumber, FailedTest t, FailedLookups failedLookups) {
        RunHistory master = cache.history(t.testId(), t.suite(),
            () -> RunHistory.ofMaster(tc.getBaseBranchHistory(token, t.testId(), t.suite())));

        // The finished runs of this test in its suite on the PR branch (one request; also drives the history strip).
        List<TcModel.TestOccurrence> runs = withResult(tc.prBranchRuns(token, prNumber, t.testId(), t.suite()));
        String branchRuns = strip(runs);
        TcModel.TestOccurrence lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);

        // Master runs on another JDK are no evidence either way: some nightly master RunAlls run on JDK
        // 21, and a test broken only there read "15/98 on master" for every PR run on JDK 17.
        RunEnv env = RunEnv.of(lastRun == null ? null : lastRun.build());
        HistoryStats h = master.onJdkOf(env);
        String onJdk = env.jdk() != null && master.knowsJdk() ? " on " + env.jdkLabel() : "";

        // A failure only stands if it still stands in the last finished run: if that run passed, it
        // clears the failure. The revision is not compared here — a pass on the same code makes the
        // failure a flake, a pass on newer code means the branch fixed it; either way it no longer stands.
        if (lastRun != null && "SUCCESS".equals(lastRun.status())) {
            return verdict(t, lastRun, false, false, "not failing in the last finished run (passed on re-run)",
                branchRuns, 0);
        }

        // A failure in the PR is a blocker unless the test also fails in master history: a failure there
        // means it isn't specific to this PR (pre-existing or flaky on master). It still gets its
        // branch-runs strip and latest-run anchor, so flaky tests are visualised like blockers. Two
        // exceptions, checked once the same-code runs are known. A rare, old master failure, when the
        // test keeps failing on the PR's code: one in 100 used to hide a parametrization that failed all
        // four runs in PR 13654, while six siblings with the same strip were blockers. And a master
        // failure only at another test scale factor that other PRs, run like this one, do not share.
        String preExisting = "pre-existing: fails " + h.fails() + "/" + h.runs() + " on master" + onJdk;
        if (h.fails() > 0 && !mayOutweigh(h, branchRuns) && !mayBeScaleOnly(master, env, h, branchRuns))
            return verdict(t, lastRun, false, false, preExisting, branchRuns, 0);

        // Merge of the last N branch runs: a real block fails consistently, a test that passed within
        // the window is a within-branch flake. That merge is only sound over runs of the SAME code —
        // a pass on the revision before the buggy commits proves nothing, and calling it "passed on
        // the same code" is how a red RunAll once got a green visa. So the window is the trailing runs
        // on the revision the latest run was made on; anything older is reported, never counted.
        String head = revisionOf(token, lastRun);
        String code = sameCodeStrip(token, runs, head);
        int streak = trailingFailStreak(code);

        String reason;
        HistoryStats scaleOnlyBar = null;
        if (h.fails() == 0) {
            reason = h.runs() == 0
                ? "no master history" + onJdk + " (can't prove pre-existing)"
                : "not seen failing in " + (!onJdk.isEmpty() && h.runs() < FEW_MASTER_RUNS ? "only " : "") + h.runs()
                    + " master run(s)" + onJdk;
        }
        else if (outweighsMaster(h, streak))
            reason = "rare on master: fails " + h.fails() + "/" + h.runs() + onJdk + ", passed the last "
                + h.greenStreak();
        else {
            scaleOnlyBar = scaleOnlyOnMaster(token, prNumber, t, master, env, h, failedLookups);
            if (scaleOnlyBar == null)
                return verdict(t, lastRun, false, false, preExisting, branchRuns, 0);

            reason = "fails " + h.fails() + "/" + h.runs() + " on master at " + TcModel.TEST_SCALE_FACTOR + "="
                + masterScaleOfFailures(master, env, h) + ", but " + scaleOnlyBar.fails() + "/" + scaleOnlyBar.runs()
                + " on other PR branches at " + env.scale();
        }

        TestVerdict v = onBranch(t, lastRun, reason, h.fails() > 0, branchRuns, code, head);

        // Other PRs set the bar once master's failures are set aside as down to the scale factor. Short
        // of outweighing their rate the test is watched, so the auto re-run gives it the runs it takes:
        // nothing re-runs a pre-existing failure, and testRestoreSnapshotProgress would never get there.
        if (scaleOnlyBar != null && v.blocker() && !outweighs(scaleOnlyBar.fails(), scaleOnlyBar.runs(), streak)) {
            return verdict(t, lastRun, false, true,
                v.reason() + " — watch (too few failures on this code yet to outweigh other PRs)", branchRuns,
                code.length());
        }
        if (!v.blocker() && !v.watch())
            return v;

        // Other PRs run like this one are the second signal: a test that fails in several of them is
        // flaky under PR conditions, whatever master says. The nightly master RunAll runs at test scale
        // factor 1.0 and PR chains at 0.1, so a test green in all 101 master runs failed 14 of 131 runs
        // in 13 other PRs, and was a blocker in each.
        HistoryStats others = otherPrs(token, prNumber, t, env, failedLookups);
        if (others == null || others.failingPrs() < FLAKY_IN_PRS)
            return v;

        String onOthers = "fails " + others.fails() + "/" + others.runs() + " in " + others.failingPrs() + " other PRs";
        if (!outweighs(others.fails(), others.runs(), streak)) {
            return verdict(t, lastRun, false, false, "flaky on other PR branches: " + onOthers + "; " + reason,
                branchRuns, code.length());
        }

        return onBranch(t, lastRun, reason + "; " + onOthers, true, branchRuns, code, head);
    }

    /**
     * The verdict by the test's runs on this branch, once master has let it through: {@code reason} says
     * why, and the evidence from the branch runs is appended to it. A blocker always carries it, any other
     * verdict only when {@code setAside}: failures on master or other PRs that were set aside must stay in
     * sight, while a clean master goes without saying.
     */
    private TestVerdict onBranch(FailedTest t, TcModel.TestOccurrence lastRun, String reason, boolean setAside,
        String branchRuns, String code, String head) {
        String because = setAside ? reason + "; " : "";
        int len = code.length();
        int older = branchRuns.length() - len;
        int streak = trailingFailStreak(code);
        int allStreak = trailingFailStreak(branchRuns);
        int allLen = branchRuns.length();

        // Consistent failures over the whole strip block as they always did: widening the window can
        // only ever add evidence. Without this, a test failing on both the old and the new revision
        // would lose its blocker status to the narrower same-code window.
        if (allStreak >= Math.min(cfg.blockerFailStreak(), allLen))
            return verdict(t, lastRun, true, false,
                allLen == 0 ? reason : reason + "; " + failedRuns(allStreak, allLen, " on this branch"), branchRuns, len);

        // Every run of THIS code failed, and more than one ran: reproduced, not chance.
        if (len >= 2 && streak == len)
            return verdict(t, lastRun, true, false, reason + "; " + failedRuns(streak, len, onRevision(head))
                + notes(discounted(older, head)), branchRuns, len);

        // One run of this code, and it failed. The passes are from older code, so this is not a flake
        // — but one run is not proof either. Watch it and let the auto re-run settle it: a green re-run
        // trips the "last finished run" gate above, a red one makes the gate above this one fire.
        if (len == 1 && older > 0)
            return verdict(t, lastRun, false, true, because + "first failure" + onRevision(head) + " — watch"
                + notes("nothing has passed on this code", discounted(older, head)), branchRuns, len);

        if (streak >= 2)
            return verdict(t, lastRun, false, true,
                because + "started failing in the last " + streak + " of " + len + " runs" + onRevision(head)
                    + " — watch" + notes(passedEarlier(head), discounted(older, head)), branchRuns, len);

        return verdict(t, lastRun, false, false, because + "flaky on branch: failed only the latest of " + len
            + " runs" + onRevision(head) + notes(passedEarlier(head), discounted(older, head)), branchRuns, len);
    }

    /**
     * Whether master's failures could be down to the test scale factor alone, for
     * {@link #scaleOnlyOnMaster} to look at other PRs. It reads no revisions and asks TeamCity nothing,
     * but a test it lets through costs one request about other PRs per test and suite while that answer
     * is cached. Master runs only at scale factor 1.0 and PRs at 0.1, so that is every test master fails
     * in at least half its runs on the PR's JDK.
     */
    private static boolean mayBeScaleOnly(RunHistory master, RunEnv env, HistoryStats h, String branchRuns) {
        return masterScaleOfFailures(master, env, h) != null && trailingFailStreak(branchRuns) > 0;
    }

    /**
     * The scale factor master fails the test at, when it fails at least half the time and only ever at
     * a scale factor this PR did not run at; otherwise null.
     */
    private static String masterScaleOfFailures(RunHistory master, RunEnv env, HistoryStats h) {
        return h.fails() * 2 >= h.runs() ? master.failingOnlyAtScaleOtherThan(env) : null;
    }

    /**
     * How other PRs do with the test, when master's failures are to be set aside for them; otherwise
     * null. Only when master fails the test at least half the time, only ever at another scale factor
     * than this PR ran at, and other PRs run at this PR's scale factor ran it often enough and are not
     * flaky with it. On master testRestoreSnapshotProgress fails all 101 runs at 1.0; on other PRs'
     * branches, at 0.1, it passes 94 of 95, so a break of it in a PR could never be caught. Half the
     * time, not any failure: a test master broke last week also passes on older PR branches, and that
     * must stay pre-existing.
     */
    private HistoryStats scaleOnlyOnMaster(String token, int prNumber, FailedTest t, RunHistory master, RunEnv env,
        HistoryStats h, FailedLookups failedLookups) {
        if (masterScaleOfFailures(master, env, h) == null)
            return null;

        HistoryStats others = otherPrs(token, prNumber, t, env, failedLookups);
        if (others == null || others.runs() < MIN_OTHER_PR_RUNS || others.failingPrs() >= FLAKY_IN_PRS)
            return null;

        return others;
    }

    /**
     * How the test does in its suite on other PRs' branches under this PR's run conditions, or null when
     * TeamCity can't say. It only ever overrides master's verdict, so a TeamCity error leaves master's
     * verdict standing rather than turning the test into an unverified one; the result is incomplete
     * all the same, and is retried.
     */
    private HistoryStats otherPrs(String token, int prNumber, FailedTest t, RunEnv env, FailedLookups failedLookups) {
        try {
            RunHistory onPrs = cache.prBranchHistory(t.testId(), t.suite(),
                () -> RunHistory.ofPrBranches(tc.otherBranchRuns(token, t.testId(), t.suite())));

            return onPrs.otherPrsAs(env, prNumber);
        }
        catch (RuntimeException e) {
            failedLookups.add("other PRs' runs of " + t.name(), e);

            return null;
        }
    }

    /**
     * Whether a master failure is rare and old enough, and the branch failing often enough, for
     * {@link #outweighsMaster} to have a chance once the same-code runs are known. Cheap: it reads no
     * revisions, so a plain pre-existing failure costs no more than it did.
     */
    private static boolean mayOutweigh(HistoryStats master, String branchRuns) {
        return outweighsMaster(master, trailingFailStreak(branchRuns));
    }

    /**
     * Whether master's failures are rare, at most 2% of its runs, and old, none among the newest 10, and
     * failing {@code streak} times in a row on the PR's code {@link #outweighs} them.
     */
    private static boolean outweighsMaster(HistoryStats master, int streak) {
        return master.fails() * 100 <= master.runs() * RARE_ON_MASTER_PERCENT
            && master.greenStreak() >= RECENT_MASTER_GREEN && outweighs(master.fails(), master.runs(), streak);
    }

    /**
     * Whether failing {@code streak} times in a row on the PR's code is more than the {@code fails} in
     * {@code runs} that happen without the PR can explain: a chance of 1 in 10,000 at most. At 1% that
     * takes two failures in a row, at 2% three, at 12.5% five. A single failure never outweighs anything.
     */
    private static boolean outweighs(int fails, int runs, int streak) {
        if (streak < 2)
            return false;

        BigInteger byChance = BigInteger.valueOf(fails).pow(streak).multiply(BigInteger.valueOf(OUTWEIGHS_ONE_IN));

        return byChance.compareTo(BigInteger.valueOf(runs).pow(streak)) <= 0;
    }

    /** Joins the non-empty qualifiers of a reason into one trailing {@code " (a; b)"} group. */
    private static String notes(String... qualifiers) {
        StringBuilder b = new StringBuilder();
        for (String q : qualifiers) {
            if (!q.isEmpty())
                b.append(b.isEmpty() ? " (" : "; ").append(q);
        }

        return b.isEmpty() ? "" : b.append(')').toString();
    }

    /** What the earlier pass in the window does — and does not — prove. */
    private static String passedEarlier(String head) {
        return head == null
            ? "passed just before, but TeamCity gave no revisions to prove that was the same code"
            : "an earlier run on the same code passed";
    }

    /** Why the runs outside the window were left out of it. */
    private static String discounted(int older, String head) {
        if (older <= 0)
            return "";

        String runs = "the " + older + " earlier branch run" + (older == 1 ? "" : "s");

        return head == null ? runs + " could not be placed on a revision" : runs + " ran on other code";
    }

    /** Length of the trailing run of consecutive failures in a P/F strip (oldest → newest). */
    private static int trailingFailStreak(String branchRuns) {
        int n = 0;
        for (int i = branchRuns.length() - 1; i >= 0 && branchRuns.charAt(i) == 'F'; i--)
            n++;

        return n;
    }

    /**
     * The trailing runs made on {@code head} — the only ones that are evidence about the code under
     * review — as a P/F strip. A run on another revision ends the window: it ran on a different
     * program. When TeamCity gives no revision for any run at all we fall back to the whole strip
     * (the old, revision-blind merge); when it gives some but not the latest one's, only the latest
     * run counts — an unplaceable run must never be read as "the same code".
     */
    private String sameCodeStrip(String token, List<TcModel.TestOccurrence> runs, String head) {
        if (runs.isEmpty())
            return "";
        if (head == null)
            return runs.stream().anyMatch(r -> revisionOf(token, r) != null)
                ? strip(runs.subList(runs.size() - 1, runs.size())) : strip(runs);

        int i = runs.size();
        while (i > 0 && head.equals(revisionOf(token, runs.get(i - 1))))
            i--;

        return strip(runs.subList(i, runs.size()));
    }

    /**
     * The VCS revision a run was made on: from the occurrence itself when TeamCity inlined it, else one
     * request for the build (cached and shared — a build's revision never changes). Null when TeamCity
     * has none, which is the only case where two runs may not be compared as same-or-different code.
     * A TeamCity error is deliberately not caught: {@link #classify} then leaves the test unchecked, and
     * a verdict with unchecked tests never reads green and is held back while it is retried (see
     * {@link Caveats} and {@link #stillRetrying}). Swallowed here, the error would silently restore the
     * revision-blind window and the green visa this whole path exists to prevent.
     */
    private String revisionOf(String token, TcModel.TestOccurrence run) {
        if (run == null || run.build() == null)
            return null;

        String inline = firstRevision(run.build().revisions());
        if (inline != null)
            return inline;

        long buildId = run.build().id();
        String fetched = cache.revision(buildId, () -> tc.buildRevision(token, buildId).orElse(""));

        return fetched.isEmpty() ? null : fetched; // "" is the cacheable "this build genuinely has none"
    }

    private static String firstRevision(TcModel.Revisions revs) {
        if (revs == null || revs.revision() == null || revs.revision().isEmpty())
            return null;

        String version = revs.revision().get(0).version();

        return version == null || version.isBlank() ? null : version;
    }

    /** A blocker's evidence sentence: how the test did over the runs the rule that fired looked at. */
    private static String failedRuns(int streak, int len, String where) {
        if (len <= 1)
            return "failed the only run" + where;
        if (streak >= len)
            return "failed all " + len + " runs" + where;

        return "failed the last " + streak + " of " + len + " runs" + where;
    }

    /** {@code " on revision 3fdf446"}, or "" when TeamCity gave us no revision to name. */
    private static String onRevision(String head) {
        if (head == null)
            return "";

        return " on revision " + (head.length() > 7 ? head.substring(0, 7) : head);
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

    /** A failed test's verdict, or, when TeamCity failed to answer for it, what is known: it failed. */
    private record Classified(TestVerdict verdict, boolean verified) {
    }

    /** The TeamCity lookups one compute could not make: how many, and the first, for the log. */
    private static final class FailedLookups {
        private final AtomicInteger count = new AtomicInteger();

        private final AtomicReference<String> first = new AtomicReference<>();

        void add(String what, RuntimeException e) {
            count.incrementAndGet();
            first.compareAndSet(null, what + ": " + rootMessage(e));
        }

        int count() {
            return count.get();
        }

        String first() {
            return first.get();
        }
    }

    /** Compact pass/fail history of the branch runs, oldest → newest: 'P' for a pass, 'F' for a failure. */
    private static String strip(List<TcModel.TestOccurrence> runs) {
        StringBuilder sb = new StringBuilder(runs.size());
        for (TcModel.TestOccurrence o : runs)
            sb.append("SUCCESS".equals(o.status()) ? 'P' : 'F');

        return sb.toString();
    }

    /**
     * The runs that have a result. TeamCity lists a run where the test was ignored with status UNKNOWN:
     * the test did not run, so nothing passed or failed there. Such a run is no evidence either way —
     * not for the last-run gate, not for the same-code window, and not as a bar on the strip.
     */
    private static List<TcModel.TestOccurrence> withResult(List<TcModel.TestOccurrence> runs) {
        return runs.stream()
            .filter(o -> "SUCCESS".equals(o.status()) || "FAILURE".equals(o.status()))
            .toList();
    }

    /**
     * Builds the verdict, anchoring its suite/build/occurrence to the <b>last finished run</b> of the
     * test on the branch when we have it — so the link and grouping point at the run the verdict is
     * actually about (a later re-run, if any), not at the RunAll dependency we first discovered it in.
     * Falls back to the RunAll-dependency occurrence (e.g. for pre-existing tests, where no runs are fetched).
     */
    private static TestVerdict verdict(FailedTest t, TcModel.TestOccurrence lastRun, boolean blocker,
        boolean watch, String reason, String branchRuns, int codeRuns) {
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

        return new TestVerdict(t.testId(), t.name(), suite, suiteBuildId, suiteName, occurrenceId, blocker, watch,
            reason, branchRuns, codeRuns);
    }
}
