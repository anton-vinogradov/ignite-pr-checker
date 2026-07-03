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

    /** Smaller pool for background work (warmer + on-request refreshes) so it can't starve foreground analyses. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService backgroundExecutor() {
        return Executors.newFixedThreadPool(4, named("bg-analysis-"));
    }

    /**
     * Runs the outer on-request refresh tasks. Kept separate from {@code backgroundExecutor} to avoid
     * a deadlock: a refresh task fans its sub-tasks out onto the background pool, so it must not sit on it.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService refreshExecutor() {
        return Executors.newFixedThreadPool(2, named("refresh-"));
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
