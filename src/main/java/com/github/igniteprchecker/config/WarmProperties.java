package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Background cache-warmer settings. The warmer keeps the newest {@code count} PRs pre-analysed,
 * spreading the load across the tokens of active logged-in users (there is no shared server token),
 * refreshing every {@code intervalMinutes}. A pooled token not re-offered within
 * {@code tokenTtlMinutes} is dropped (that user has left).
 */
@ConfigurationProperties(prefix = "warm")
public record WarmProperties(Boolean enabled, Integer count, Integer intervalMinutes, Integer tokenTtlMinutes) {
    public WarmProperties {
        if (enabled == null)
            enabled = true;
        if (count == null || count < 0)
            count = 50;
        if (intervalMinutes == null || intervalMinutes < 1)
            intervalMinutes = 10;
        if (tokenTtlMinutes == null || tokenTtlMinutes < 1)
            tokenTtlMinutes = 60;
    }
}
