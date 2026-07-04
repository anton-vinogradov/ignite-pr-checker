package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-app self-update settings. The app checks the project's GitHub releases and, on request, downloads
 * the latest jar over {@code jarPath} and restarts itself (the running user must own {@code jarPath}
 * and systemd must relaunch on exit — both true for the standard install).
 */
@ConfigurationProperties(prefix = "update")
public record UpdateProperties(Boolean enabled, String jarPath, String repo) {
    public UpdateProperties {
        if (enabled == null)
            enabled = true;
        if (jarPath == null || jarPath.isBlank())
            jarPath = "/opt/ignite-pr-checker/app.jar";
        if (repo == null || repo.isBlank())
            repo = "anton-vinogradov/ignite-pr-checker";
    }
}
