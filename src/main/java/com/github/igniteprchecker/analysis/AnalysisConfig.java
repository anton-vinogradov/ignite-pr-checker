package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.AnalysisProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Where analysis work runs, and the scheduling that drives the cache warmer. The box has one CPU, so the
 * pools are narrow, and nothing that recomputes on request queues behind warming:
 * <ul>
 *   <li>A request a user waits on (a PR opened cold, the ↻ button) computes on its own request thread and fans
 *   its per-test calls out on {@code analysisExecutor}.</li>
 *   <li>{@code recomputeExecutor}, one thread, recomputes what changed without anyone waiting for it: a viewed
 *   verdict whose branch finished a build, a PR whose re-run suite finished, a PR whose RunAll just finished.
 *   It fans out on {@code analysisExecutor} too, so it never waits for a warm cycle.</li>
 *   <li>{@code warmerThread} runs the warm cycles, one after another; {@code warmExecutor} warms the PRs of a
 *   cycle two at a time, fanning out on {@code backgroundExecutor}. A PR is skipped while a user waits.</li>
 *   <li>{@code backgroundExecutor} also fans out the computes the standing sweep, early re-runs and the auto
 *   visa act on; the auto visa's first try after a chain finished recomputes as ↻ does.</li>
 *   <li>{@code causesExecutor} clusters the "Top causes".</li>
 *   <li>Spring's scheduler (three threads, {@code spring.task.scheduling.pool.size}) runs the timers, and some
 *   of them call TeamCity, GitHub and JIRA on it: the rerun tracker, the standing sweep, the PR command
 *   poll.</li>
 * </ul>
 * Every pool here is shut down with the application. A few single threads live with the class they serve: the
 * snapshot flusher (CacheStore), early re-runs (StandingVisas), the auto-visa poster (VisaSubscriptions) and the
 * fetch of the project's own GitHub stats (GithubClient).
 */
@Configuration
@EnableScheduling
public class AnalysisConfig {
    /** Fans out the per-test / per-suite calls of a compute a user waits on, and of the on-request recomputes. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService analysisExecutor(AnalysisProperties props) {
        return Executors.newFixedThreadPool(props.concurrency(), named("analysis-"));
    }

    /**
     * Fans out the per-test calls of the warm cycle and of the computes actions act on, separate so they can't
     * starve the computes users wait on. Its width is the real ceiling on how fast warm cycles go, so it
     * is sized close to the foreground pool while staying below it.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService backgroundExecutor() {
        return Executors.newFixedThreadPool(6, named("bg-analysis-"));
    }

    /**
     * Warms the PRs of a warm cycle, two at a time. Kept apart from {@code backgroundExecutor} to avoid a
     * deadlock: a warm fans its sub-tasks out onto the background pool, so it must not sit on it.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService warmExecutor() {
        return Executors.newFixedThreadPool(2, named("warm-"));
    }

    /**
     * Recomputes on request, one PR at a time: after a view found the branch moved, after a re-run suite or a
     * RunAll finished. One thread of its own, so such a recompute starts at once rather than after the PRs a
     * warm cycle queued; it fans out on {@code analysisExecutor}.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService recomputeExecutor() {
        return Executors.newSingleThreadExecutor(named("recompute-"));
    }

    /** Runs the warm cycles; one thread, so cycles never overlap. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService warmerThread() {
        return Executors.newSingleThreadExecutor(named("warmer-"));
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
