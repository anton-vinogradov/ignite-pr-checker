package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.analysis.model.TestVerdict.Doubt;
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
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** What joins the commits of several VCS roots into one revision of a run. */
    private static final String REVISIONS_JOINED = "+";

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

    /**
     * How long the branch runs a suite run's verdict read are kept for its next try: longer than the 20 minutes such a
     * verdict is tried for while TeamCity errors leave it incomplete.
     */
    private static final long KEPT_RUNS_MS = 2 * HOLD_INCOMPLETE_MS;

    private final TcClient tc;
    private final ChainCollector chains;
    private final AnalysisProperties cfg;
    private final ExecutorService pool;
    private final ExecutorService bgPool;
    private final ExecutorService recomputePool;
    private final ExecutorService recomputeFanOut;
    private final AnalysisCache cache;
    private final RunDeltaStore deltas;
    private final MergedVerdicts merged;

    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    /** Builds whose branch was found unmoved lately, so another view does not ask again so soon. */
    private final TtlCache<Long, Boolean> lookedAt = new TtlCache<>(LOOK_EVERY_MS);

    /** Per suite run judged on its own (see {@link #analyzeSuiteRun}), the branch runs its last try read. */
    private final TtlCache<Long, KeptRuns> keptRuns = new TtlCache<>(KEPT_RUNS_MS);

    /** In-flight computes per build id, so concurrent requests for the same build share one run
     * instead of piling duplicate full recomputes onto the pool (a page reload during a cold
     * analysis used to start another one from scratch). */
    private final ConcurrentMap<Long, CompletableFuture<AnalysisResult>> inFlight = new ConcurrentHashMap<>();

    /** Live progress of in-flight computes (build id -> pr/done/total), for the "Analyzing…" line. */
    private final ConcurrentMap<Long, Progress> progress = new ConcurrentHashMap<>();

    /** How many user-facing requests are currently waiting on a compute — the warmer yields to them. */
    private final AtomicInteger usersWaiting = new AtomicInteger();

    /** What the PR list badges of each PR's last verdict (best-effort). */
    private final Map<Integer, Caveats.Glance> prGlance = new ConcurrentHashMap<>();

    /** The build of each PR's latest verdict: what the warm cycle checks first. */
    private final Map<Integer, Long> analysedBuild = new ConcurrentHashMap<>();

    @Autowired
    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg,
        @Qualifier("analysisExecutor") ExecutorService pool,
        @Qualifier("backgroundExecutor") ExecutorService bgPool,
        @Qualifier("recomputeExecutor") ExecutorService recomputePool,
        @Qualifier("recomputeFanOutExecutor") ExecutorService recomputeFanOut,
        AnalysisCache cache, RunDeltaStore deltas, MergedVerdicts merged) {
        this.tc = tc;
        this.chains = chains;
        this.cfg = cfg;
        this.pool = pool;
        this.bgPool = bgPool;
        this.recomputePool = recomputePool;
        this.recomputeFanOut = recomputeFanOut;
        this.cache = cache;
        this.deltas = deltas;
        this.merged = merged;
    }

    /** An analyzer that keeps no merged PR's verdict: it analyses every PR live. */
    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg, ExecutorService pool,
        ExecutorService bgPool, ExecutorService recomputePool, ExecutorService recomputeFanOut, AnalysisCache cache,
        RunDeltaStore deltas) {
        this(tc, chains, cfg, pool, bgPool, recomputePool, recomputeFanOut, cache, deltas, MergedVerdicts.none());
    }

    /** An analyzer whose recomputes on request fan out on {@code pool} too. */
    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg, ExecutorService pool,
        ExecutorService bgPool, ExecutorService recomputePool, AnalysisCache cache, RunDeltaStore deltas) {
        this(tc, chains, cfg, pool, bgPool, recomputePool, pool, cache, deltas);
    }

    /**
     * @return the analysis (cached if available), or empty if no RunAll build exists for the PR yet. A merged PR's
     * is the one it had at the merge.
     */
    public Optional<AnalysisResult> analyze(String token, int prNumber) {
        Optional<AnalysisResult> atMerge = merged.verdict(prNumber);
        if (atMerge.isPresent())
            return atMerge;

        long lookedUpAt = System.currentTimeMillis();
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
                refreshAsync(token, prNumber, bid, lookedUpAt, false);
            else if (isStale(cached.get()) && lookedAt.peek(bid).isEmpty())
                refreshAsync(token, prNumber, bid, lookedUpAt, true);

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
        Caveats.Glance g = prGlance.get(prNumber);

        return g == null ? null : g.blockers();
    }

    /** What the PR list badges of a PR's last verdict, or null if it hasn't been analysed. */
    public Caveats.Glance glance(int prNumber) {
        return prGlance.get(prNumber);
    }

    private void rememberVerdict(int prNumber, AnalysisResult r) {
        prGlance.put(prNumber, Caveats.Glance.of(r));
        analysedBuild.put(prNumber, r.buildId());
    }

    /** The TeamCity user who triggered a PR's latest RunAll, if known — backs the "my run" tag in the PR list. */
    public String triggeredBy(int prNumber) {
        return chains.triggeredBy(prNumber);
    }

    /**
     * Recomputes and caches the analysis for a PR's latest build, on request: a re-run suite of it finished. Fans
     * out on a pool of its own, so it neither waits behind a warm cycle's calls nor holds up a compute a user
     * waits on.
     */
    public void refresh(String token, int prNumber) {
        chains.findBuildId(token, prNumber)
            .ifPresent(bid -> computeAndStore(token, prNumber, bid, recomputeFanOut));
    }

    /**
     * Warms a PR for the cache-warmer: recomputes it only when the answer can have changed — a different
     * chain build, or new finished builds on the branch since the cached result was computed (a green re-run
     * of a blocker suite clears it without the chain build changing) — or when TeamCity errors left it
     * incomplete and another try is due. Returns true if it recomputed. This is what keeps the warmer from
     * re-hammering TeamCity with the heavy history/latest-run lookups every cycle.
     *
     * <p>Once a chain of the PR has finished, which chain is analysed changes only when a build finishes on the
     * branch: a newer chain ending clean or cancelled is one. So a PR with a finished chain's verdict costs one
     * call while nothing finished, not two: the chain is looked up only once something did. Before any chain
     * finished, the newest running one is analysed, and one that starts takes over with nothing finished: such a
     * PR is looked up every cycle. This rests on no verdict claiming more than its chain's lookup saw (see
     * {@link #doCompute}).
     */
    public boolean warm(String token, int prNumber) {
        return warm(token, prNumber, bgPool);
    }

    /**
     * Warms a PR on request, the moment its RunAll finished, as {@link #warm} does in a cycle. Fans out on a pool
     * of its own, so it neither waits behind a warm cycle's calls nor holds up a compute a user waits on.
     */
    public boolean warmNow(String token, int prNumber) {
        return warm(token, prNumber, recomputeFanOut);
    }

    private boolean warm(String token, int prNumber, ExecutorService taskPool) {
        Long held = analysedBuild.get(prNumber);
        Optional<AnalysisResult> verdict = held == null ? Optional.empty()
            : cache.peekResult(held).filter(r -> r.finishedAt() > 0);
        if (verdict.isPresent() && keptWarm(token, prNumber, verdict.get()))
            return false;

        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return false;

        if (verdict.isEmpty() || held.longValue() != buildId.get()) {
            Optional<AnalysisResult> cached = cache.peekResult(buildId.get());
            if (cached.isPresent() && keptWarm(token, prNumber, cached.get()))
                return false;
        }

        computeAndStore(token, prNumber, buildId.get(), taskPool);
        return true;
    }

    /**
     * Keeps a cached verdict warm if it still stands: nothing finished on the branch since, and it is not an
     * incomplete one due for another try. Returns whether it did.
     */
    private boolean keptWarm(String token, int prNumber, AnalysisResult cached) {
        if (retryDue(cached) || branchMovedSince(token, prNumber, cached, false))
            return false;

        // The warm cycle (10 min) is shorter than the TTL (15 min), but a skip used to leave the
        // old expiry in place — every other cycle the entry died mid-window and a viewer hit a
        // cold compute. Touching on skip keeps the warmed set permanently hot.
        cache.touchResult(cached.buildId());
        rememberVerdict(prNumber, cached);

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
        long lookedUpAt = System.currentTimeMillis();
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

        return Optional.of(computeAfterNow(token, prNumber, bid, lookedUpAt));
    }

    /**
     * The analysis of a compute that starts after this call — for an action that must not be judged by
     * anything read earlier. One already under way is waited out, not shared: it may have read a suite
     * before the build that prompted the call finished.
     */
    public Optional<AnalysisResult> analyzeAfterNow(String token, int prNumber) {
        long lookedUpAt = System.currentTimeMillis();

        return chains.findBuildId(token, prNumber).map(bid -> computeAfterNow(token, prNumber, bid, lookedUpAt));
    }

    private AnalysisResult computeAfterNow(String token, int prNumber, long buildId, long lookedUpAt) {
        CompletableFuture<AnalysisResult> underWay = inFlight.get(buildId);
        if (underWay != null)
            underWay.handle((r, e) -> null).join();

        return computeAndStore(token, prNumber, buildId, lookedUpAt, bgPool);
    }

    /**
     * Recomputes now (ignoring any cached result) and returns the fresh analysis, or empty if no
     * RunAll build exists yet. Backs the manual "refresh" button. A merged PR keeps the one it had at the merge.
     */
    public Optional<AnalysisResult> forceRefresh(String token, int prNumber) {
        Optional<AnalysisResult> atMerge = merged.verdict(prNumber);
        if (atMerge.isPresent())
            return atMerge;

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

    /** Sweeps out expired "branch unmoved" marks and kept branch runs (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        lookedAt.evictExpired();
        keptRuns.evictExpired();
    }

    /**
     * Recomputes in the background, on the recompute thread; with {@code ifMoved}, only when something finished
     * on the branch since the result was computed (one cheap call), else the view is marked looked at for a
     * while. Fans out on a pool of its own: queued behind a warm cycle, the page waited up to a minute, and on
     * the foreground pool a user's compute queued behind it.
     */
    private void refreshAsync(String token, int prNumber, long buildId, long lookedUpAt, boolean ifMoved) {
        if (!refreshing.add(buildId))
            return;

        recomputePool.execute(() -> {
            try {
                Optional<AnalysisResult> cached = cache.peekResult(buildId);
                if (ifMoved && cached.isPresent() && !branchMovedSince(token, prNumber, cached.get(), false)) {
                    lookedAt.put(buildId, true);

                    return;
                }

                computeAndStore(token, prNumber, buildId, lookedUpAt, recomputeFanOut);
            }
            catch (RuntimeException ignore) {
                // best-effort background refresh; the stale cached value stays until it succeeds
            }
            finally {
                refreshing.remove(buildId);
            }
        });
    }

    /** Computes the analysis of a chain looked up just now. */
    private AnalysisResult computeAndStore(String token, int prNumber, long buildId, ExecutorService taskPool) {
        return computeAndStore(token, prNumber, buildId, System.currentTimeMillis(), taskPool);
    }

    /** Computes the analysis of a chain looked up at {@code lookedUpAt}, epoch ms; shares one under way. */
    private AnalysisResult computeAndStore(String token, int prNumber, long buildId, long lookedUpAt,
        ExecutorService taskPool) {
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
            AnalysisResult result = doCompute(token, prNumber, buildId, lookedUpAt, taskPool);
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

    private AnalysisResult doCompute(String token, int prNumber, long buildId, long lookedUpAt,
        ExecutorService taskPool) {
        // Taken before anything is read: a build that finishes while this runs may be missing from the
        // verdict, so it must count as unseen and cost one more recompute rather than be claimed. Nor later than
        // the chain's lookup: a chain that finished after it may be the one to analyse now, and the warm cycle
        // looks the chain up again only once something finished after the watermark. A view's refresh waits for
        // the recompute thread, an action for a compute under way.
        long watermarkAt = Math.min(System.currentTimeMillis(), lookedUpAt) / 1000 - WATERMARK_MARGIN_SECONDS;
        BuildFacts.Branch branch = cache.facts().branch(prNumber, watermarkAt,
            since -> tc.suitesFinishedAfter(token, prNumber, since));
        ChainCollector.Chain chain = chains.collectForBuild(token, prNumber, buildId, taskPool);

        Progress prog = new Progress(prNumber, chain.failedTests().size(), new AtomicInteger());
        progress.put(buildId, prog);

        Subject subject = Subject.ofPr(prNumber, t -> branch.testRuns(t.testId(), t.suite(),
            () -> tc.prBranchRuns(token, prNumber, t.testId(), t.suite())));
        SuiteRuns suites = new SuiteRuns(suite -> latestRun(token, prNumber, branch, suite),
            since -> tc.finishedBuildsSince(token, prNumber, since));
        Judged judged = judgeChain(token, chain, subject, suites, taskPool, prog.done());

        long now = System.currentTimeMillis();
        long incompleteSince = judged.failedLookups().count() == 0 ? 0 : cache.peekResult(buildId)
            .map(AnalysisResult::incompleteSince).filter(since -> since > 0).orElse(now);
        if (judged.failedLookups().count() > 0) {
            log.warn("PR {} (build {}): {} TeamCity lookup(s) failed, {} failed test(s) left unchecked, retrying; "
                + "first: {}", prNumber, buildId, judged.failedLookups().count(), judged.unverified().size(),
                judged.failedLookups().first());
        }

        AnalysisResult result = judged.result(prNumber, chain, now, watermarkAt, incompleteSince,
            chain.revision() != null ? chain.revision() : chainRevision(token, buildId), 0, List.of());

        cache.putResult(buildId, result);
        rememberVerdict(prNumber, result);
        // A test left unchecked is not "fixed": the run-to-run comparison waits for a complete result.
        if (incompleteSince == 0)
            deltas.onResult(prNumber, buildId, result.blockers(), result.brokenSuites().size());

        return result;
    }

    /**
     * The verdict of a merged PR's RunAll {@code chainId} with the runs queued on its branch before the merge. {@code
     * branchBuilds} are the branch's builds as TeamCity listed them; a run in {@code afterMerge}, queued after the merge,
     * does not count, nor a master run that finished after {@code mergedAt}, epoch seconds: master then holds the PR's
     * own code, and its failures would read as pre-existing. A suite's newest run is its newest in {@code
     * branchBuilds} that counts. {@code runsAtMerge} names the runs that were still going at the merge. Neither cached
     * nor compared with the PR's earlier runs: the open PR's verdicts stay as they were.
     */
    public AnalysisResult verdictAtMerge(String token, int prNumber, long chainId, long mergedAt, Set<Long> afterMerge,
        List<TcModel.Build> branchBuilds, List<RunRef> runsAtMerge) {
        LongPredicate counted = id -> !afterMerge.contains(id);
        long watermarkAt = System.currentTimeMillis() / 1000 - WATERMARK_MARGIN_SECONDS;
        BuildFacts.Branch branch = cache.facts().branch(prNumber, watermarkAt,
            since -> tc.suitesFinishedAfter(token, prNumber, since));
        ChainCollector.Chain chain = chains.collectForBuild(token, prNumber, chainId, bgPool, counted);

        List<TcModel.Build> finished = branchBuilds.stream()
            .filter(b -> "finished".equalsIgnoreCase(b.state()) && b.canceledInfo() == null && counted.test(b.id()))
            .toList();
        Map<String, TcModel.Build> newest = new HashMap<>();
        for (TcModel.Build b : finished) {
            if (b.buildTypeId() != null)
                newest.merge(b.buildTypeId(), b, (x, y) -> x.id() >= y.id() ? x : y);
        }

        Subject subject = new Subject(prNumber, t -> branch.testRuns(t.testId(), t.suite(),
                () -> tc.prBranchRuns(token, prNumber, t.testId(), t.suite())).stream()
            .filter(o -> o.build() != null && counted.test(o.build().id())).toList(), false, mergedAt, new RunsRead());
        SuiteRuns suites = new SuiteRuns(suite -> Optional.ofNullable(newest.get(suite)), since -> finished);
        Judged judged = judgeChain(token, chain, subject, suites, bgPool, new AtomicInteger());

        long now = System.currentTimeMillis();
        if (judged.failedLookups().count() > 0) {
            log.warn("merged PR {} (build {}): {} TeamCity lookup(s) failed, {} failed test(s) left unchecked; "
                + "first: {}", prNumber, chainId, judged.failedLookups().count(), judged.unverified().size(),
                judged.failedLookups().first());
        }

        return judged.result(prNumber, chain, now, watermarkAt, judged.failedLookups().count() == 0 ? 0 : now,
            chain.revision() != null ? chain.revision() : chainRevision(token, chainId), mergedAt, runsAtMerge);
    }

    /**
     * Classifies each failed test of the chain by what {@code subject} reads of the branch, and settles the broken,
     * never-run and shrunk suites by the newer runs {@code suites} finds. Fans out on {@code taskPool}, counting the
     * tests classified in {@code done}.
     */
    private Judged judgeChain(String token, ChainCollector.Chain chain, Subject subject, SuiteRuns suites,
        ExecutorService taskPool, AtomicInteger done) {
        chain.newerRuns().forEach(subject.read()::note);
        FailedLookups failedLookups = new FailedLookups();
        List<Callable<Classified>> tasks = chain.failedTests().stream()
            .<Callable<Classified>>map(t -> () -> {
                try {
                    return classify(token, subject, t, failedLookups);
                }
                finally {
                    done.incrementAndGet();
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
        List<BrokenSuite> broken = withoutHealed(suites, chainBroken, shortReruns, failedLookups, subject.read());
        List<CancelledSuite> cancelled = notRunSince(suites, chain, broken, shortReruns, failedLookups, subject.read());
        List<ShrunkSuite> shrunk = newestPerSuite(
            withFullRunsDropped(suites, chain.shrunkSuites(), failedLookups, subject.read()), shortReruns);

        return new Judged(blockers, watch, filtered, unverified, broken, unstable, cancelled, shrunk, subject.read(),
            failedLookups);
    }

    /**
     * The verdict of one suite run on its own branch, by the rules each suite of a RunAll is judged by. A PR of the
     * Python thin client runs the client's suite on {@code branch}, such as {@code pull/69}, pinned to the PR's head
     * and to Ignite's master. Its failed tests are judged against master's runs of the suite, other Ignite PRs' runs of
     * it and its own runs on that branch. Two of those runs are the same code only when the commit of every VCS root
     * matches, and a run that did not test the client at its own commit does not count (see {@link #clientRuns}).
     * The result is not cached. TeamCity errors that left tests unchecked set {@code incompleteSince}, and the caller
     * computes the verdict again; that try reuses the branch runs the last one read.
     */
    public AnalysisResult analyzeSuiteRun(String token, int prNumber, String branch, long buildId) {
        return analyzeSuiteRun(token, prNumber, branch, buildId, 0);
    }

    /**
     * The same verdict of the run {@code buildId} once its re-run on the same commits, {@code rerunId}, has finished:
     * the failed tests of both runs are judged, and the suite is broken or short as the re-run left it (see {@link
     * ChainCollector#collectForSuiteRun(String, TcModel.Build, TcModel.Build)}). {@code rerunId} 0: the run alone.
     */
    public AnalysisResult analyzeSuiteRun(String token, int prNumber, String branch, long buildId, long rerunId) {
        TcModel.Build run = tc.suiteRun(token, buildId);
        TcModel.Build rerun = rerunId == 0 ? null : tc.suiteRun(token, rerunId);
        if (run == null || rerunId != 0 && rerun == null)
            throw new IllegalStateException("TeamCity sent no build " + (run == null ? buildId : rerunId));

        ChainCollector.SuiteRun suite = rerun == null ? chains.collectForSuiteRun(token, run)
            : chains.collectForSuiteRun(token, run, rerun);
        Subject subject = new Subject(0, suite.failedTests().isEmpty() ? t -> List.of()
            : clientRuns(token, branch, run), true);
        FailedLookups failedLookups = new FailedLookups();
        List<Classified> classified = Parallel.run(bgPool, suite.failedTests().stream()
            .<Callable<Classified>>map(t -> () -> classify(token, subject, t, failedLookups)).toList());
        List<TestVerdict> verdicts = classified.stream().filter(Classified::verified).map(Classified::verdict).toList();
        List<TestVerdict> unverified = classified.stream().filter(c -> !c.verified()).map(Classified::verdict).toList();
        List<TestVerdict> blockers = verdicts.stream().filter(TestVerdict::blocker).toList();
        List<TestVerdict> watch = verdicts.stream().filter(v -> v.watch() && !v.blocker()).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker() && !v.watch()).toList();

        List<BrokenSuite> broken = new ArrayList<>();
        List<BrokenSuite> unstable = new ArrayList<>();
        if (suite.broken() != null)
            broken.add(suite.broken());
        if (suite.unstable() != null) {
            boolean blamed = !blockers.isEmpty() || !watch.isEmpty() || !unverified.isEmpty();
            (blamed ? broken : unstable).add(suite.unstable());
        }

        long now = System.currentTimeMillis();
        if (failedLookups.count() > 0) {
            log.warn("{} (build {}): {} TeamCity lookup(s) failed, {} failed test(s) left unchecked; first: {}", branch,
                buildId, failedLookups.count(), unverified.size(), failedLookups.first());
        }

        return new AnalysisResult(prNumber, buildId, branch, now, blockers, watch, filtered, broken, suite.shrunk(),
            1, 0, false, 0, false, 0, TcDates.epochSeconds(run.queuedDate()), TcDates.epochSeconds(run.startDate()),
            TcDates.epochSeconds(run.finishDate()), 0, unstable, List.of(), unverified,
            failedLookups.count() == 0 ? 0 : now, everyRevision(run.revisions()), 0);
    }

    /**
     * The runs of each failed test of {@code run} on its branch of the Python thin client, oldest first: only those
     * that tested the client at one of their own commits (see {@link #testedItsCommit}). One request lists the
     * suite's builds on the branch. While the list is the one the last try of this run's verdict read, the runs that
     * try read are reused. A verdict TeamCity errors left incomplete is tried again every few minutes while ci2 fails,
     * and asking again for the runs of every failed test would add one request per test each time. When the list
     * fails, every test is left unchecked.
     */
    private Function<FailedTest, List<TcModel.TestOccurrence>> clientRuns(String token, String branch,
        TcModel.Build run) {
        Map<Long, String> checkouts;
        try {
            checkouts = tc.finishedRunsParameter(token, run.buildTypeId(), branch, TcModel.OVERRIDDEN_BRANCH);
        }
        catch (RuntimeException e) {
            return t -> {
                throw e;
            };
        }

        KeptRuns kept = keptRuns.peek(run.id()).filter(k -> k.builds().equals(checkouts.keySet()))
            .orElseGet(() -> new KeptRuns(Set.copyOf(checkouts.keySet()), new ConcurrentHashMap<>()));
        keptRuns.put(run.id(), kept);

        return t -> {
            List<TcModel.TestOccurrence> runs = kept.runs().get(t.testId());
            if (runs == null) {
                runs = tc.branchRuns(token, branch, t.testId(), t.suite()).stream()
                    .filter(o -> testedItsCommit(o.build(), checkouts)).toList();
                kept.runs().put(t.testId(), runs);
            }

            return runs;
        };
    }

    /**
     * Whether a run on a branch of the Python thin client tested the client at one of the run's own commits. The
     * suite's first step checks the client out at OVERRIDDEN_BRANCH, the build's value in {@code checkouts}. It is
     * master unless the run was queued with a commit, so a run someone started on the PR's branch without it tested
     * the client's master, and its pass would clear the PR's failure. A build TeamCity named no OVERRIDDEN_BRANCH of is
     * taken by its commits. A build missing from {@code checkouts} finished after they were listed, or before the
     * newest 100, and does not count.
     */
    private static boolean testedItsCommit(TcModel.BuildRef build, Map<Long, String> checkouts) {
        if (build == null || !checkouts.containsKey(build.id()))
            return false;

        String checkout = checkouts.get(build.id());

        return checkout == null || build.revisions() != null && build.revisions().revision() != null
            && build.revisions().revision().stream().anyMatch(r -> checkout.equals(r.version()));
    }

    /**
     * A shrunk suite stops being shrunk once a NEWER finished run of it on the branch got its test
     * count back — same anchoring as broken suites. Without it, one hung run keeps claiming "tests
     * disappeared" long after the re-runs put them back, which reads as a coverage hole that no
     * longer exists.
     */
    private List<ShrunkSuite> withFullRunsDropped(SuiteRuns suites, List<ShrunkSuite> shrunk,
        FailedLookups failedLookups, RunsRead read) {
        if (shrunk.isEmpty())
            return shrunk;

        List<ShrunkSuite> out = new ArrayList<>();
        for (ShrunkSuite s : shrunk) {
            try {
                Optional<TcModel.Build> last = suites.latest().apply(s.suite());
                if (last.isPresent() && last.get().id() > s.suiteBuildId() && last.get().testOccurrences() != null
                    && ChainCollector.isFullRun(last.get().testOccurrences().count(), s.baseline())) {
                    read.note(s.suite(), s.suiteName(), last.get());
                    continue;
                }
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
    private List<BrokenSuite> withoutHealed(SuiteRuns suites, List<BrokenSuite> broken, List<ShrunkSuite> shortReruns,
        FailedLookups failedLookups, RunsRead read) {
        if (broken.isEmpty())
            return broken;

        List<BrokenSuite> out = new ArrayList<>();
        for (BrokenSuite s : broken) {
            try {
                Optional<TcModel.Build> last = suites.latest().apply(s.suite());
                if (last.isPresent() && last.get().id() > s.suiteBuildId() && "SUCCESS".equals(last.get().status())) {
                    read.note(s.suite(), s.suiteName(), last.get());
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
     * The revision a chain ran on, for a chain read before its revision was asked for with it; null when TeamCity
     * has none or cannot say. The badge then cannot show the verdict clean, which is all it costs.
     */
    private String chainRevision(String token, long buildId) {
        try {
            String fetched = cache.revision(buildId, () -> tc.buildRevision(token, buildId).orElse(""));

            return fetched.isEmpty() ? null : fetched;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** The suite's newest finished run on the branch, fetched only if one may have finished since it was last. */
    private Optional<TcModel.Build> latestRun(String token, int prNumber, BuildFacts.Branch branch, String suite) {
        return Optional.ofNullable(branch.latestRun(suite, () -> tc.latestSuiteRun(token, prNumber, suite)));
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
    private List<CancelledSuite> notRunSince(SuiteRuns suites, ChainCollector.Chain chain, List<BrokenSuite> broken,
        List<ShrunkSuite> shortReruns, FailedLookups failedLookups, RunsRead read) {
        if (chain.cancelledSuites().isEmpty())
            return chain.cancelledSuites();

        try {
            Map<String, TcModel.Build> newestRuns = new HashMap<>();
            for (TcModel.Build b : suites.finishedSince()
                .apply(chain.queuedAt() > 0 ? TcDates.format(chain.queuedAt()) : null)) {
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

                read.note(c.suite(), c.suiteName(), run);
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

    private Classified classify(String token, Subject subject, FailedTest t, FailedLookups failedLookups) {
        try {
            return new Classified(classifyVerified(token, subject, t, failedLookups), true);
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

    /**
     * The test's verdict, a blocker or a test to watch saying what it rests on short of proof: one run of the
     * test on the branch, no master history to compare with, a check TeamCity errors kept from being made. On
     * PRs 13655, 13566 and others all 31 blockers rested on one run, and read like proven ones.
     */
    private TestVerdict classifyVerified(String token, Subject subject, FailedTest t, FailedLookups failedLookups) {
        Footing footing = new Footing();
        TestVerdict v = judge(token, subject, t, failedLookups, footing);

        return v.blocker() || v.watch() ? withDoubts(v, footing.doubtsOf(v)) : v;
    }

    private TestVerdict judge(String token, Subject subject, FailedTest t, FailedLookups failedLookups,
        Footing footing) {
        RunHistory master = subject.masterUntil() > 0
            ? RunHistory.ofMaster(tc.getBaseBranchHistory(token, t.testId(), t.suite(), subject.masterUntil()))
            : cache.history(t.testId(), t.suite(),
                () -> RunHistory.ofMaster(tc.getBaseBranchHistory(token, t.testId(), t.suite())));

        // The finished runs of this test in its suite on the PR branch (one request, unless no run of the suite has
        // finished since the last compute fetched them; also drives the history strip).
        List<TcModel.TestOccurrence> runs = withResult(subject.runs().apply(t));
        String branchRuns = strip(runs);
        TcModel.TestOccurrence lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        subject.read().note(t, lastRun);

        // Master runs on another JDK are no evidence either way: some nightly master RunAlls run on JDK
        // 21, and a test broken only there read "15/98 on master" for every PR run on JDK 17.
        RunEnv env = RunEnv.of(lastRun == null ? null : lastRun.build());
        HistoryStats h = master.onJdkOf(env);
        String onJdk = env.jdk() != null && master.knowsJdk() ? " on " + env.jdkLabel() : "";
        footing.noMasterHistory = h.runs() == 0;

        // A failure only stands if it still stands in the last finished run: if that run passed, it
        // clears the failure. The revision is not compared here — a pass on the same code makes the
        // failure a flake, a pass on newer code means the branch fixed it; either way it no longer stands.
        // It stays anchored to its latest failure: the failure panel of a passing run showed a log with nothing in it.
        if (lastRun != null && "SUCCESS".equals(lastRun.status())) {
            return verdict(t, lastFailure(runs), false, false,
                "not failing in the last finished run (passed on re-run)", branchRuns, 0);
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
        String head = revisionOf(token, subject, lastRun);
        String code = sameCodeStrip(token, subject, runs, head);
        int streak = trailingFailStreak(code);

        String reason;
        HistoryStats scaleOnlyBar = null;
        if (h.fails() == 0) {
            reason = h.runs() == 0
                ? "no master history" + onJdk + " (can't prove pre-existing)"
                : "not seen failing in " + (!onJdk.isEmpty() && h.runs() < FEW_MASTER_RUNS ? "only " : "") + h.runs()
                    + " master run" + (h.runs() == 1 ? "" : "s") + onJdk;
        }
        else if (outweighsMaster(h, streak))
            reason = "rare on master: fails " + h.fails() + "/" + h.runs() + onJdk + ", passed the last "
                + h.greenStreak();
        else {
            scaleOnlyBar = scaleOnlyOnMaster(token, subject.pr(), t, master, env, h, failedLookups, footing);
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
                v.reason() + "; too few failures on this code yet to outweigh other PRs", branchRuns, code.length());
        }
        if (!v.blocker() && !v.watch())
            return v;

        // Other PRs run like this one are the second signal: a test that fails in several of them is
        // flaky under PR conditions, whatever master says. The nightly master RunAll runs at test scale
        // factor 1.0 and PR chains at 0.1, so a test green in all 101 master runs failed 14 of 131 runs
        // in 13 other PRs, and was a blocker in each.
        HistoryStats others = otherPrs(token, subject.pr(), t, env, failedLookups, footing);
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
            return verdict(t, lastRun, true, false, reason + "; " + failedRuns(streak, len, onCommit(head))
                + notes(discounted(older, head)), branchRuns, len);

        // One run of this code, and it failed. The passes are from older code, so this is not a flake
        // — but one run is not proof either. Watch it and let the auto re-run settle it: a green re-run
        // trips the "last finished run" gate above, a red one makes the gate above this one fire.
        if (len == 1 && older > 0)
            return verdict(t, lastRun, false, true, because
                + (head == null ? "failed the latest run" : "failed the only run" + onCommit(head)) + "; "
                + discounted(older, head), branchRuns, len);

        if (streak >= 2)
            return verdict(t, lastRun, false, true, because + failedRuns(streak, len, onCommit(head)) + afterAPass(head)
                + (older > 0 ? "; " + discounted(older, head) : ""), branchRuns, len);

        return verdict(t, lastRun, false, false, because + "flaky on branch: failed only the latest of " + len
            + " runs" + onCommit(head) + notes(passedEarlier(head), discounted(older, head)), branchRuns, len);
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
        HistoryStats h, FailedLookups failedLookups, Footing footing) {
        if (masterScaleOfFailures(master, env, h) == null)
            return null;

        HistoryStats others = otherPrs(token, prNumber, t, env, failedLookups, footing);
        if (others == null || others.runs() < MIN_OTHER_PR_RUNS || others.failingPrs() >= FLAKY_IN_PRS)
            return null;

        return others;
    }

    /**
     * How the test does in its suite on other PRs' branches under this PR's run conditions, or null when
     * TeamCity can't say. It only ever overrides master's verdict, so a TeamCity error leaves master's
     * verdict standing rather than turning the test into an unverified one; the result is incomplete
     * all the same, and is retried, and the verdict says it went unchecked.
     */
    private HistoryStats otherPrs(String token, int prNumber, FailedTest t, RunEnv env, FailedLookups failedLookups,
        Footing footing) {
        try {
            RunHistory onPrs = cache.prBranchHistory(t.testId(), t.suite(),
                () -> RunHistory.ofPrBranches(tc.otherBranchRuns(token, t.testId(), t.suite())));

            return onPrs.otherPrsAs(env, prNumber);
        }
        catch (RuntimeException e) {
            failedLookups.add("other PRs' runs of " + t.name(), e);
            footing.unchecked = true;

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
            ? "passed just before, but TeamCity gave no commits to prove that was the same code"
            : "an earlier run on the same code passed";
    }

    /** The same after a test to watch: {@code ", after a pass on the same commit"}. */
    private static String afterAPass(String head) {
        return head == null
            ? ", after a pass; TeamCity gave no commits to prove that was the same code"
            : ", after a pass on the same commit" + (head.contains(REVISIONS_JOINED) ? "s" : "");
    }

    /**
     * Why the runs outside the window were left out of it. Without a commit of the latest run it follows the words
     * that name that run: "failed the latest run; TeamCity gave no commit for it, so the 2 earlier runs do not count".
     */
    private static String discounted(int older, String head) {
        if (older <= 0)
            return "";

        String runs = "the " + older + " earlier run" + (older == 1 ? "" : "s");

        if (head == null)
            return "TeamCity gave no commit for it, so " + runs + (older == 1 ? " does" : " do") + " not count";

        return runs + (older == 1 ? " was" : " were") + " on other code";
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
    private String sameCodeStrip(String token, Subject subject, List<TcModel.TestOccurrence> runs, String head) {
        if (runs.isEmpty())
            return "";
        if (head == null)
            return runs.stream().anyMatch(r -> revisionOf(token, subject, r) != null)
                ? strip(runs.subList(runs.size() - 1, runs.size())) : strip(runs);

        int i = runs.size();
        while (i > 0 && head.equals(revisionOf(token, subject, runs.get(i - 1))))
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
    private String revisionOf(String token, Subject subject, TcModel.TestOccurrence run) {
        if (run == null || run.build() == null)
            return null;
        if (subject.everyRoot())
            return everyRevision(run.build().revisions());

        String inline = firstRevision(run.build().revisions());
        if (inline != null)
            return inline;

        long buildId = run.build().id();
        String fetched = cache.revision(buildId, () -> tc.buildRevision(token, buildId).orElse(""));

        return fetched.isEmpty() ? null : fetched; // "" is the cacheable "this build genuinely has none"
    }

    /**
     * The commits of every VCS root of the run, joined by "+": a run of the Python thin client's suite takes Ignite and
     * the client from two roots, and two runs are the same code only when both match. Null when TeamCity named none.
     */
    private static String everyRevision(TcModel.Revisions revs) {
        if (revs == null || revs.revision() == null)
            return null;

        List<String> versions = revs.revision().stream().map(TcModel.Revision::version)
            .filter(v -> v != null && !v.isBlank()).toList();

        return versions.isEmpty() ? null : String.join(REVISIONS_JOINED, versions);
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

    /**
     * {@code " on commit 3fdf446"}, {@code " on commits 3fdf446 and 9c1e2a0"} for the commits of several VCS roots, or
     * "" when TeamCity gave us no revision to name.
     */
    private static String onCommit(String head) {
        if (head == null)
            return "";

        List<String> shas = List.of(head.split(Pattern.quote(REVISIONS_JOINED)));
        if (shas.size() > 1)
            return " on commits " + String.join(" and ", shas.stream().map(BlockerAnalyzer::shortSha).toList());

        return " on commit " + shortSha(head);
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
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

    /**
     * What a failed test is judged against besides master: its runs on the branch under review, the Ignite PR whose
     * runs are not "other PRs" (0: none), and whether a run's code is the commit of every VCS root or only the first.
     * {@code masterUntil}, epoch seconds, keeps out the master runs that finished after it; 0 keeps them all. {@code
     * read} notes the newest run of each suite the verdict reads.
     */
    private record Subject(int pr, Function<FailedTest, List<TcModel.TestOccurrence>> runs, boolean everyRoot,
        long masterUntil, RunsRead read) {
        /** A subject judged against every master run, whose reads nobody looks at. */
        Subject(int pr, Function<FailedTest, List<TcModel.TestOccurrence>> runs, boolean everyRoot) {
            this(pr, runs, everyRoot, 0, new RunsRead());
        }

        /** An Ignite PR, whose RunAll takes the PR's code from one VCS root. */
        static Subject ofPr(int pr, Function<FailedTest, List<TcModel.TestOccurrence>> runs) {
            return new Subject(pr, runs, false);
        }
    }

    /**
     * A PR branch's suite runs as one compute reads them: a suite's newest finished run, and the branch's finished
     * builds since a TeamCity time (null: the newest ones).
     */
    private record SuiteRuns(Function<String, Optional<TcModel.Build>> latest,
        Function<String, List<TcModel.Build>> finishedSince) {
    }

    /**
     * A chain's verdict before it is dated: the tests by list, the suites as the newer runs left them, the runs read,
     * and the TeamCity lookups that failed.
     */
    private record Judged(List<TestVerdict> blockers, List<TestVerdict> watch, List<TestVerdict> filtered,
        List<TestVerdict> unverified, List<BrokenSuite> broken, List<BrokenSuite> unstable,
        List<CancelledSuite> cancelled, List<ShrunkSuite> shrunk, RunsRead read, FailedLookups failedLookups) {
        /** The verdict of {@code chain} as computed at {@code now}, epoch ms, of the chain's {@code revision}. */
        AnalysisResult result(int prNumber, ChainCollector.Chain chain, long now, long watermarkAt,
            long incompleteSince, String revision, long mergedAt, List<RunRef> runsAtMerge) {
            return new AnalysisResult(prNumber, chain.buildId(), chain.branchName(), now, blockers, watch, filtered,
                broken, shrunk, chain.suitesRan(), chain.suitesReused(), chain.interrupted() && !cancelled.isEmpty(),
                cancelled.size(), chain.live(), chain.liveBuildId(), chain.queuedAt(), chain.startedAt(),
                chain.finishedAt(), watermarkAt, unstable, cancelled, unverified, incompleteSince, revision, mergedAt,
                read.otherThan(revision), runsAtMerge);
        }
    }

    /**
     * The newest run of each suite a verdict reads: the chain's own, a re-run, or a newer chain's run. Those that tested
     * another commit than the chain make the verdict a mixed run.
     */
    private static final class RunsRead {
        private final Map<String, RunRef> newest = new ConcurrentHashMap<>();

        void note(RunRef run) {
            if (run.suite() != null)
                newest.merge(run.suite(), run, (a, b) -> a.buildId() >= b.buildId() ? a : b);
        }

        void note(String suite, String suiteName, TcModel.Build run) {
            note(new RunRef(suite, suiteName, run.id(), ChainCollector.revisionOf(run)));
        }

        /** The last run of a failed test, in the suite the run names, else the suite the test failed in. */
        void note(FailedTest t, TcModel.TestOccurrence lastRun) {
            if (lastRun == null || lastRun.build() == null)
                return;

            TcModel.BuildRef b = lastRun.build();
            note(new RunRef(b.buildTypeId() != null ? b.buildTypeId() : t.suite(),
                b.buildType() != null && b.buildType().name() != null ? b.buildType().name() : t.suiteName(), b.id(),
                firstRevision(b.revisions())));
        }

        /** The runs read that tested another commit than {@code revision}, by suite name; none when it is unknown. */
        List<RunRef> otherThan(String revision) {
            if (revision == null)
                return List.of();

            return newest.values().stream()
                .filter(r -> r.revision() != null && !r.revision().equalsIgnoreCase(revision))
                .sorted(Comparator.comparing(RunRef::suiteName, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(RunRef::buildId))
                .toList();
        }
    }

    /**
     * The runs of a suite run's failed tests on its branch, by test id, as a try of its verdict read them while the
     * suite's finished builds on the branch were {@code builds}.
     */
    private record KeptRuns(Set<Long> builds, ConcurrentMap<Long, List<TcModel.TestOccurrence>> runs) {
    }

    /** What a test's verdict leans on besides its runs on the branch, noted while the test is judged. */
    private static final class Footing {
        private boolean noMasterHistory;

        private boolean unchecked;

        /**
         * A blocker or watch rests on one run when no more than one failure of the test on the branch backs it. A
         * blocker is made of one failure only when that is the test's only run on the branch ("failed the only run").
         */
        List<Doubt> doubtsOf(TestVerdict v) {
            List<Doubt> out = new ArrayList<>();
            if (trailingFailStreak(v.branchRuns()) <= 1)
                out.add(Doubt.ONE_RUN);
            if (noMasterHistory)
                out.add(Doubt.NO_MASTER_HISTORY);
            if (unchecked)
                out.add(Doubt.UNCHECKED);

            return out;
        }
    }

    private static TestVerdict withDoubts(TestVerdict v, List<Doubt> doubts) {
        return new TestVerdict(v.testId(), v.name(), v.suite(), v.suiteBuildId(), v.suiteName(), v.occurrenceId(),
            v.blocker(), v.watch(), v.reason(), v.branchRuns(), v.codeRuns(), doubts);
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

    /** The newest failed run, or null when none of them failed (the chain's own occurrence is the anchor then). */
    private static TcModel.TestOccurrence lastFailure(List<TcModel.TestOccurrence> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            if ("FAILURE".equals(runs.get(i).status()))
                return runs.get(i);
        }

        return null;
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
