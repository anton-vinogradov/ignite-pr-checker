package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "thin-clients")
public record ThinClientProperties(Settings php, Settings nodejs) {
    public record Settings(String repo, String suite, String igniteVcsRoot, String clientVcsRoot) {
    }
}
