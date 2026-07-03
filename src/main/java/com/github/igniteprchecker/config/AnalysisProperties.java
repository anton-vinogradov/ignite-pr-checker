package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blocker-classification settings. Defaults mirror the proven values from the original tcbot
 * (TcBotConst): a failed test is a blocker only if its base-branch fail rate is below
 * {@code failRateBlockerThresholdPercents} and it is not flaky, where "flaky" means it flipped
 * pass/fail at least {@code flakinessStatusChangeBorder} times on builds without code changes.
 */
@ConfigurationProperties(prefix = "analysis")
public record AnalysisProperties(
    String baseBranch,
    String runAllBuildType,
    Integer historyDepth,
    Double failRateBlockerThresholdPercents,
    Integer flakinessStatusChangeBorder,
    Integer concurrency,
    Integer cacheTtlMinutes
) {
    public AnalysisProperties {
        if (baseBranch == null)
            baseBranch = "refs/heads/master";
        if (historyDepth == null)
            historyDepth = 100;
        if (failRateBlockerThresholdPercents == null)
            failRateBlockerThresholdPercents = 4.0;
        if (flakinessStatusChangeBorder == null)
            flakinessStatusChangeBorder = 1;
        if (concurrency == null || concurrency < 1)
            concurrency = 12;
        if (cacheTtlMinutes == null || cacheTtlMinutes < 0)
            cacheTtlMinutes = 15;
    }
}
