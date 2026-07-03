package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.config.AnalysisProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bounded pool that fans out the per-test / per-suite TeamCity calls of a single analysis. */
@Configuration
public class AnalysisConfig {
    @Bean(destroyMethod = "shutdown")
    ExecutorService analysisExecutor(AnalysisProperties props) {
        AtomicInteger counter = new AtomicInteger();

        return Executors.newFixedThreadPool(props.concurrency(), r -> {
            Thread t = new Thread(r, "analysis-" + counter.incrementAndGet());
            t.setDaemon(true);

            return t;
        });
    }
}
