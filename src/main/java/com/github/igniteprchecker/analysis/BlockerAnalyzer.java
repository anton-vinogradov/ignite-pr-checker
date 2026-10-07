package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
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
import org.springframework.web.client.RestClientResponseException;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise. A test is a
 * blocker only if it (1) fails in the PR, (2) never fails in the last {@code analysis.historyDepth}
 * master runs of the suite it failed in that ran on the PR run's JDK (any such master failure means it
 * is pre-existing or flaky on master, not this PR's fault), and (3) still fails in the last
 * fully-finished run of that suite on the PR branch (a passing re-run clears it). Both look at that one
 * suite only: the same test id runs in several suites of a chain (the C++ tests run on Windows, Linux
 * and Clang), and another platform's pass is not a re-run, nor are its master failures this one's.
 * Results are cached per build; a request serves the cached result and, if it is getting stale,
 * triggers a background refresh.
 */
@Component
public class BlockerAnalyzer {
    /**
     * How far before an analysis starts its branch watermark is set. TeamCity stamps finish dates with
     * its own clock, which may run a little ahead of ours: a watermark set too early costs one more
     * recompute, one set too late silently misses a re-run. Well below the warm interval, so it settles.
     */
    private static final long WATERMARK_MARGIN_SECONDS = 60;

    /** Fewer master runs than this on the PR's JDK are called out as thin evidence. */
    private static final int FEW_MASTER_RUNS = 10;

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
     * chain build changing). Returns true if it recomputed. This is what keeps the warmer from
     * re-hammering TeamCity with the heavy history/latest-run lookups every cycle.
     */
    public boolean warm(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return false;

        Optional<AnalysisResult> cached = cache.peekResult(buildId.get());
        if (cached.isPresent() && !branchMovedSince(token, prNumber, cached.get(), false)) {
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
     * compared) and nothing finished on the branch since; otherwise the verdict comes from a compute
     * that starts after this call. One already under way is waited out, not shared: it may have read
     * a suite before its re-run finished, and suites of one wave finish close together.
     */
    public Optional<AnalysisResult> analyzeForAction(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return Optional.empty();

        long bid = buildId.get();
        Optional<AnalysisResult> cached = cache.peekResult(bid);
        if (cached.isPresent() && cached.get().finishedAt() > 0
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
        List<TestVerdict> watch = verdicts.stream().filter(v -> v.watch() && !v.blocker()).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker() && !v.watch()).toList();
        List<BrokenSuite> broken = withoutHealed(token, prNumber, chain.brokenSuites());
        List<ShrunkSuite> shrunk = withFullRunsDropped(token, prNumber, chain.shrunkSuites());

        AnalysisResult result = new AnalysisResult(prNumber, buildId, chain.branchName(),
            System.currentTimeMillis(), blockers, watch, filtered, broken, shrunk,
            chain.suitesRan(), chain.suitesReused(), chain.interrupted(), chain.canceledSuites(), chain.live(), chain.liveBuildId(),
            chain.queuedAt(), chain.startedAt(), chain.finishedAt(), watermarkAt);

        cache.putResult(buildId, result);
        rememberVerdict(prNumber, result);
        deltas.onResult(prNumber, buildId, blockers, broken.size());

        return result;
    }

    /**
     * A shrunk suite stops being shrunk once a NEWER finished run of it on the branch got its test
     * count back — same anchoring as broken suites. Without it, one hung run keeps claiming "tests
     * disappeared" long after the re-runs put them back, which reads as a coverage hole that no
     * longer exists.
     */
    private List<ShrunkSuite> withFullRunsDropped(String token, int prNumber, List<ShrunkSuite> shrunk) {
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
            }
            out.add(s);
        }

        return out;
    }

    /**
     * A broken suite stops being broken once a NEWER finished run of it on the branch passed — the
     * same last-finished-run anchoring tests get, without which re-running a broken suite (manually
     * or automatically) could never clear it from the verdict. Kept on any doubt.
     */
    private List<BrokenSuite> withoutHealed(String token, int prNumber, List<BrokenSuite> broken) {
        if (broken.isEmpty())
            return broken;

        List<BrokenSuite> out = new ArrayList<>();
        for (BrokenSuite s : broken) {
            try {
                Optional<TcModel.Build> last = tc.latestSuiteRun(token, prNumber, s.suite());
                if (last.isPresent() && last.get().id() > s.suiteBuildId() && "SUCCESS".equals(last.get().status()))
                    continue;
            }
            catch (RuntimeException e) {
                // TC hiccup — better a possibly stale broken-suite entry than a silently healed one
            }
            out.add(s);
        }

        return out;
    }

    private TestVerdict classify(String token, int prNumber, FailedTest t) {
        try {
            return classifyVerified(token, prNumber, t);
        }
        catch (RuntimeException e) {
            // A transient TeamCity error for one test must not fail the whole analysis (Parallel.run would
            // propagate it and every other verdict would be lost). Keep the test visible as an unverified
            // blocker — it did fail in the PR — and flag that we couldn't check it.
            return verdict(t, null, true, false, "could not verify (TeamCity error: " + rootMessage(e) + ")", "", 0);
        }
    }

    private TestVerdict classifyVerified(String token, int prNumber, FailedTest t) {
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

        // A failure in the PR is a blocker unless the test also fails in master history: any failure
        // there means it isn't specific to this PR (pre-existing or flaky on master). It still gets
        // its branch-runs strip and latest-run anchor, so flaky tests are visualised like blockers.
        if (h.fails() > 0) {
            return verdict(t, lastRun, false, false,
                "pre-existing: fails " + h.fails() + "/" + h.runs() + " on master" + onJdk, branchRuns, 0);
        }

        // ...and only if the failure still stands in the last finished run: if that run passed, it
        // clears the failure. The revision is not compared here — a pass on the same code makes the
        // failure a flake, a pass on newer code means the branch fixed it; either way it no longer stands.
        if (lastRun != null && "SUCCESS".equals(lastRun.status())) {
            return verdict(t, lastRun, false, false, "not failing in the last finished run (passed on re-run)",
                branchRuns, 0);
        }

        String reason = h.runs() == 0
            ? "no master history" + onJdk + " (can't prove pre-existing)"
            : "not seen failing in " + (!onJdk.isEmpty() && h.runs() < FEW_MASTER_RUNS ? "only " : "") + h.runs()
                + " master run(s)" + onJdk;

        // Merge of the last N branch runs: a real block fails consistently, a test that passed within
        // the window is a within-branch flake. That merge is only sound over runs of the SAME code —
        // a pass on the revision before the buggy commits proves nothing, and calling it "passed on
        // the same code" is how a red RunAll once got a green visa. So the window is the trailing runs
        // on the revision the latest run was made on; anything older is reported, never counted.
        String head = revisionOf(token, lastRun);
        String code = sameCodeStrip(token, runs, head);
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
            return verdict(t, lastRun, false, true, "first failure" + onRevision(head) + " — watch"
                + notes("nothing has passed on this code", discounted(older, head)), branchRuns, len);

        if (streak >= 2)
            return verdict(t, lastRun, false, true,
                "started failing in the last " + streak + " of " + len + " runs" + onRevision(head) + " — watch"
                    + notes(passedEarlier(head), discounted(older, head)), branchRuns, len);

        return verdict(t, lastRun, false, false, "flaky on branch: failed only the latest of " + len + " runs"
            + onRevision(head) + notes(passedEarlier(head), discounted(older, head)), branchRuns, len);
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
     * A TeamCity error is deliberately not caught: {@link #classify} turns it into an unverified
     * blocker, the fail-safe side, whereas swallowing it would silently restore the revision-blind
     * window and the green visa this whole path exists to prevent.
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
