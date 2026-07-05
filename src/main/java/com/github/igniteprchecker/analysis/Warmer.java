package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private volatile int lastWarmed;
    private volatile int lastCached;
    private volatile long lastCycleAt;
    private volatile boolean warming;

    /** One thread so warm cycles never overlap; daemon so it doesn't block shutdown. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "warmer");
        t.setDaemon(true);
        return t;
    });

    public Warmer(BlockerAnalyzer analyzer, GithubClient github, WarmProperties props) {
        this.analyzer = analyzer;
        this.github = github;
        this.props = props;
        this.tokens = new TokenPool(Duration.ofMinutes(props.tokenTtlMinutes()).toMillis());
    }

    /** An authenticated request offers its token; the first token into an empty pool kicks off warming. */
    public void offerToken(String token) {
        boolean wasEmpty = tokens.offer(token);

        if (wasEmpty && props.enabled())
            worker.execute(this::warmCycle);
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
        try {
            runCycle();
        }
        finally {
            warming = false;
            lastCycleAt = System.currentTimeMillis();
        }
    }

    private void runCycle() {
        List<PrSummary> prs = github.openPrs();
        int count = Math.min(prs.size(), props.count());
        int recomputed = 0;
        int cached = 0;

        for (int i = 0; i < count; i++) {
            String token = tokens.next();
            if (token == null) // pool empty: nobody logged in, or every token got rejected
                break;

            int pr = prs.get(i).number();

            try {
                if (analyzer.warm(token, pr)) // recomputes only if the latest build isn't already cached
                    recomputed++;
                else
                    cached++;
            }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    tokens.remove(token); // revoked/expired: drop it and retry this PR with another token
                    log.info("warmer token rejected ({}), dropped ({} left in pool)", e.getStatusCode(), tokens.size());
                    i--;
                }
            }
            catch (RuntimeException e) {
                log.debug("warm of PR {} failed: {}", pr, e.toString());
            }
        }

        lastWarmed = recomputed;
        lastCached = cached;
        log.info("warm cycle: {} recomputed, {} already cached, across {} token(s)", recomputed, cached, tokens.size());
    }

    /** Number of PRs warmed in the last cycle (for the status page). */
    public int lastWarmed() {
        return lastWarmed;
    }

    /** Number of PRs found already-cached in the last cycle. */
    public int lastCached() {
        return lastCached;
    }

    /** Whether a warm cycle is running right now. */
    public boolean warming() {
        return warming;
    }

    /** Epoch-ms the last warm cycle finished, or 0 if none has completed since start. */
    public long lastCycleAt() {
        return lastCycleAt;
    }

    /** Number of donated TeamCity tokens currently in the pool. */
    public int pooledTokens() {
        return tokens.size();
    }
}
