package com.github.igniteprchecker.metrics;

import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the expired heap dumps while the service runs, so a dump goes on time with no restart. */
@Component
public class HeapDumpExpiry {
    @Scheduled(fixedDelay = 10, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    void deleteExpired() {
        HeapDumps.ofThisJvm().deleteExpired(System.currentTimeMillis());
    }
}
