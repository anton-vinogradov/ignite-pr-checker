package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blocker-classification settings. A failed test is a blocker if it does not fail at all in the last
 * {@code historyDepth} master runs on the same JDK (a master failure makes it pre-existing/flaky, not a
 * blocker, with the exceptions {@code BlockerAnalyzer} describes).
 */
@ConfigurationProperties(prefix = "analysis")
public record AnalysisProperties(
    String baseBranch,
    String runAllBuildType,
    Integer historyDepth,
    Integer concurrency,
    Integer cacheTtlMinutes,
    Integer refreshAfterSeconds,
    Integer blockerFailStreak
) {
    public AnalysisProperties {
        if (baseBranch == null)
            baseBranch = "refs/heads/master";
        if (historyDepth == null || historyDepth < 1)
            historyDepth = 100;
        if (concurrency == null || concurrency < 1)
            concurrency = 8;
        if (cacheTtlMinutes == null || cacheTtlMinutes < 0)
            cacheTtlMinutes = 120;
        if (refreshAfterSeconds == null || refreshAfterSeconds < 0)
            refreshAfterSeconds = 120;
        if (blockerFailStreak == null || blockerFailStreak < 1)
            blockerFailStreak = 3;
    }
}
