package com.github.igniteprchecker.mirror;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "git-mirror")
public record GitMirrorProperties(Boolean enabled, String directory, Long syncIntervalSeconds, Long timeoutSeconds) {
    public GitMirrorProperties {
        enabled = Boolean.TRUE.equals(enabled);
        directory = directory == null || directory.isBlank() ? "/opt/ignite-pr-checker/git-mirrors" : directory.strip();
        syncIntervalSeconds = syncIntervalSeconds == null ? 60L : syncIntervalSeconds;
        timeoutSeconds = timeoutSeconds == null ? 600L : timeoutSeconds;
        if (syncIntervalSeconds < 1 || timeoutSeconds < 1 || timeoutSeconds > 3600)
            throw new IllegalArgumentException("Git mirror intervals must be positive; command timeout is at most 3600s");
    }
}
