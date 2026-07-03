package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Background cache-warmer settings. The warmer keeps the newest {@code count} PRs pre-analysed,
 * using the token of an active logged-in user (there is no shared server token), refreshing every
 * {@code intervalMinutes}.
 */
@ConfigurationProperties(prefix = "warm")
public record WarmProperties(Boolean enabled, Integer count, Integer intervalMinutes) {
    public WarmProperties {
        if (enabled == null)
            enabled = true;
        if (count == null || count < 0)
            count = 50;
        if (intervalMinutes == null || intervalMinutes < 1)
            intervalMinutes = 10;
    }
}
