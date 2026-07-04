package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * On-disk snapshot of the in-memory caches, so a restart/redeploy starts warm instead of cold (and
 * doesn't re-hammer TeamCity to re-warm). {@code dir} must be writable and survive restarts; if it
 * isn't (e.g. a local dev run without the server layout), persistence disables itself and the app
 * runs in-memory only — it is never fatal.
 */
@ConfigurationProperties(prefix = "persist")
public record PersistProperties(Boolean enabled, String dir, Integer intervalMinutes) {
    public PersistProperties {
        if (enabled == null)
            enabled = true;
        if (dir == null || dir.isBlank())
            dir = "/opt/ignite-pr-checker/cache";
        if (intervalMinutes == null || intervalMinutes < 1)
            intervalMinutes = 5;
    }
}
