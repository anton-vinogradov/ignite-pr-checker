package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keeps the newest PRs pre-analysed so opening them is instant. There is no server-side token, so
 * the warmer piggybacks on active users: logged-in requests donate their token, and each warm cycle
 * spreads its PRs round-robin across all currently-pooled tokens, so no single user's token bears
 * the whole background load. It does nothing until the first login after a restart.
 */
@Component
public class Warmer {
    private static final Logger log = LoggerFactory.getLogger(Warmer.class);

    private final BlockerAnalyzer analyzer;
    private final GithubClient github;
    private final WarmProperties props;
    private final TokenPool tokens;
    private final ExecutorService warmPool;

    private volatile int lastWarmed;
    private volatile int lastCached;
    private volatile int lastFailed;
    private volatile long lastCycleAt;
    private volatile long lastCycleMs;
    private volatile long firstCycleMs; // duration of the first cycle after startup (the "warm-up"); 0 until it finishes
    private volatile int cyclesCompleted;
    private volatile boolean warming;
    private volatile long cycleStartedAt; // when the current (or most recent) cycle began
    private volatile int cycleTotal;      // PRs the current cycle will visit
    private volatile int cycleDone;       // PRs visited so far in the current cycle

    /** One thread so warm cycles never overlap; daemon so it doesn't block shutdown. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "warmer");
        t.setDaemon(true);
        return t;
    });

    public Warmer(BlockerAnalyzer analyzer, GithubClient github, WarmProperties props,
        @Qualifier("refreshExecutor") ExecutorService warmPool) {
        this.analyzer = analyzer;
        this.github = github;
        this.props = props;
        this.warmPool = warmPool;
        this.tokens = new TokenPool(Duration.ofMinutes(props.tokenTtlMinutes()).toMillis());
    }

    /** An authenticated request offers its token; the first token into an empty pool kicks off warming. */
    public void offerToken(String token) {
        boolean wasEmpty = tokens.offer(token);

        if (wasEmpty && props.enabled())
            worker.execute(this::warmCycle);
    }

    /**
     * Eagerly pre-analyses one PR the moment its run finishes (the rerun tracker calls this), so
     * the first viewer finds the result cached — or at least joins a compute already in flight —
     * instead of paying the whole cold recompute. No-op without a pooled token.
     */
    public void warmPr(int pr) {
        if (!props.enabled())
            return;

        String token = borrowToken();
        if (token == null)
            return;

        warmPool.execute(() -> {
            try {
                if (analyzer.warm(token, pr))
                    log.info("eager-warmed PR {} right after its run finished", pr);
            }
            catch (RuntimeException e) {
                log.info("eager warm of PR {} failed: {}", pr, e.toString());
            }
        });
    }

    /**
     * Forces a recompute of one PR's verdict (the rerun tracker calls this when a re-run suite
     * finishes): the chain build is unchanged, so the cache-aware warm() would skip it, but the
     * new branch run must flow into the verdict — a passed re-run clears its blockers.
     */
    public void refreshPr(int pr) {
        if (!props.enabled())
            return;

        String token = borrowToken();
        if (token == null)
            return;

        warmPool.execute(() -> {
            try {
                analyzer.refresh(token, pr);
                log.info("re-analysed PR {} after its re-run suite finished", pr);
            }
            catch (RuntimeException e) {
                log.info("re-analysis of PR {} failed: {}", pr, e.toString());
            }
        });
    }

    /** Kicks an out-of-band warm cycle on the warmer thread — e.g. right after a manual cache flush,
     * so the newest PRs are re-analysed in the background instead of every visitor hitting a cold recompute. */
    public void triggerWarm() {
        if (props.enabled())
            worker.execute(this::warmCycle);
    }

    @Scheduled(fixedDelayString = "${warm.interval-minutes:10}", initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void scheduled() {
        if (props.enabled())
            worker.execute(this::warmCycle);
    }

    private void warmCycle() {
        warming = true;
        cycleStartedAt = System.currentTimeMillis();
        cycleDone = 0;
        try {
            runCycle();
        }
        finally {
            long finishedAt = System.currentTimeMillis();
            warming = false;
            lastCycleAt = finishedAt;
            lastCycleMs = finishedAt - cycleStartedAt;
            cyclesCompleted++;
            if (firstCycleMs == 0)
                firstCycleMs = lastCycleMs; // record how long the initial post-startup warm-up took
        }
    }

    private void runCycle() {
        List<PrSummary> prs = github.openPrs();
        int count = Math.min(prs.size(), props.count());
        cycleTotal = count;

        AtomicInteger recomputed = new AtomicInteger();
        AtomicInteger cached = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();

        // The per-PR warms fan out on the refresh pool (cold recomputes dominate a cycle, and serially
        // they made the startup warm-up minutes long); each warm's own per-test fan-out stays on the
        // background pool, so the two levels can't deadlock each other.
        List<Callable<Void>> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int pr = prs.get(i).number();
            tasks.add(() -> {
                // A user blocked on a cold analysis gets the whole TeamCity/CPU budget: skip this PR
                // for now (the next cycle, or the user's own compute, will cover it).
                if (analyzer.userWaiting()) {
                    skipped.incrementAndGet();
                    return null;
                }

                String token = tokens.next();
                if (token == null) // pool empty: nobody logged in, or every token got rejected
                    return null;

                try {
                    if (analyzer.warm(token, pr)) // recomputes only if the latest build isn't already cached
                        recomputed.incrementAndGet();
                    else
                        cached.incrementAndGet();
                }
                catch (RestClientResponseException e) {
                    failed.incrementAndGet();
                    if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                        tokens.remove(token); // revoked/expired: drop it; the next cycle covers this PR
                        log.info("warmer token rejected ({}), dropped ({} left in pool)", e.getStatusCode(), tokens.size());
                    }
                }
                catch (RuntimeException e) {
                    failed.incrementAndGet();
                    log.debug("warm of PR {} failed: {}", pr, e.toString());
                }
                finally {
                    cycleDone = done.incrementAndGet();
                }

                return null;
            });
        }

        try {
            warmPool.invokeAll(tasks); // tasks swallow their own errors; invokeAll just awaits them all
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        lastWarmed = recomputed.get();
        lastCached = cached.get();
        lastFailed = failed.get();
        log.info("warm cycle: {} recomputed, {} already cached, {} failed, {} yielded to users, across {} token(s) in {}ms",
            recomputed.get(), cached.get(), failed.get(), skipped.get(), tokens.size(), System.currentTimeMillis() - cycleStartedAt);
    }

    /** Number of PRs warmed in the last cycle (for the status page). */
    public int lastWarmed() {
        return lastWarmed;
    }

    /** Number of PRs found already-cached in the last cycle. */
    public int lastCached() {
        return lastCached;
    }

    /** Number of PRs whose warm failed in the last cycle (a healthy cycle has 0). */
    public int lastFailed() {
        return lastFailed;
    }

    /** Whether a warm cycle is running right now. */
    public boolean warming() {
        return warming;
    }

    /** Epoch-ms the last warm cycle finished, or 0 if none has completed since start. */
    public long lastCycleAt() {
        return lastCycleAt;
    }

    /** Wall-clock duration of the last completed cycle, in ms (0 if none completed). */
    public long lastCycleMs() {
        return lastCycleMs;
    }

    /** Duration of the first cycle after startup — the initial warm-up — in ms (0 until it finishes). */
    public long firstCycleMs() {
        return firstCycleMs;
    }

    /** Number of warm cycles completed since start. */
    public int cyclesCompleted() {
        return cyclesCompleted;
    }

    /** Epoch-ms the current (or most recent) cycle began, or 0 if none has started. */
    public long cycleStartedAt() {
        return cycleStartedAt;
    }

    /** Number of PRs the running cycle plans to visit (0 when idle before the first cycle). */
    public int cycleTotal() {
        return cycleTotal;
    }

    /** Number of PRs the running cycle has visited so far. */
    public int cycleDone() {
        return cycleDone;
    }

    /** Number of donated TeamCity tokens currently in the pool. */
    public int pooledTokens() {
        return tokens.size();
    }

    /** Lends a pooled token (round-robin) for other background reads (e.g. rerun-state polling); null if none. */
    public String borrowToken() {
        return tokens.next();
    }
}
