package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.AnalysisProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Thread pools for analysis and the scheduling that drives the cache warmer. */
@Configuration
@EnableScheduling
public class AnalysisConfig {
    /** Fans out the per-test / per-suite calls of a user-facing (foreground) analysis. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService analysisExecutor(AnalysisProperties props) {
        return Executors.newFixedThreadPool(props.concurrency(), named("analysis-"));
    }

    /**
     * Pool for background work (warmer + on-request refreshes), separate so it can't starve foreground
     * analyses. Its width is the real ceiling on how fast warm cycles go — every warming PR fans its
     * per-test calls out here — so it is sized close to the foreground pool while staying below it.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService backgroundExecutor() {
        return Executors.newFixedThreadPool(6, named("bg-analysis-"));
    }

    /**
     * Runs the outer on-request refresh tasks. Kept separate from {@code backgroundExecutor} to avoid
     * a deadlock: a refresh task fans its sub-tasks out onto the background pool, so it must not sit on it.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService refreshExecutor() {
        return Executors.newFixedThreadPool(2, named("refresh-"));
    }

    /** Small pool for the on-demand cause clustering, so a click on "Top causes" isn't queued behind
     * a large foreground analysis occupying the shared analysis pool. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService causesExecutor() {
        return Executors.newFixedThreadPool(6, named("causes-"));
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);

            return t;
        };
    }
}
