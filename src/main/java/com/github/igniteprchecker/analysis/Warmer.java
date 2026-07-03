package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
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
 * the warmer piggybacks on an active user: logged-in requests donate their token, and the warmer
 * uses the most recent one. It does nothing until the first login after a restart.
 */
@Component
public class Warmer {
    private static final Logger log = LoggerFactory.getLogger(Warmer.class);

    private final BlockerAnalyzer analyzer;
    private final GithubClient github;
    private final WarmProperties props;

    /** One thread so warm cycles never overlap; daemon so it doesn't block shutdown. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "warmer");
        t.setDaemon(true);
        return t;
    });

    private volatile String token;

    public Warmer(BlockerAnalyzer analyzer, GithubClient github, WarmProperties props) {
        this.analyzer = analyzer;
        this.github = github;
        this.props = props;
    }

    /** An authenticated request offers its token; the first one kicks off warming immediately. */
    public void offerToken(String token) {
        boolean firstToken = this.token == null;
        this.token = token;

        if (firstToken && props.enabled())
            worker.execute(this::warmCycle);
    }

    @Scheduled(fixedDelayString = "${warm.interval-minutes:10}", initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void scheduled() {
        if (props.enabled())
            worker.execute(this::warmCycle);
    }

    private void warmCycle() {
        String t = token;
        if (t == null)
            return;

        List<PrSummary> prs = github.openPrs();
        int count = Math.min(prs.size(), props.count());
        int warmed = 0;

        for (int i = 0; i < count; i++) {
            try {
                analyzer.refresh(t, prs.get(i).number());
                warmed++;
            }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    token = null; // donated token no longer valid; wait for a fresh donation
                    log.info("warmer token rejected ({}), pausing until next login", e.getStatusCode());
                    return;
                }
            }
            catch (RuntimeException e) {
                log.debug("warm of PR {} failed: {}", prs.get(i).number(), e.toString());
            }
        }

        log.info("warmed {} PR(s)", warmed);
    }
}
