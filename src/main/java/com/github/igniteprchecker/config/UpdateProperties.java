package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-app self-update settings. The app checks the project's GitHub releases and, on request, asks for the latest one
 * to be installed over {@code jarPath} before the next start and restarts itself (systemd must relaunch on exit and
 * run install.sh's update.sh first, both true for the standard install). The request and the reason an update failed
 * are kept beside {@code jarPath}.
 */
@ConfigurationProperties(prefix = "update")
public record UpdateProperties(Boolean enabled, String jarPath) {
    public UpdateProperties {
        if (enabled == null)
            enabled = true;
        if (jarPath == null || jarPath.isBlank())
            jarPath = "/opt/ignite-pr-checker/app.jar";
    }
}
