package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "git-proxy")
public record GitProxyProperties(boolean enabled) {
}
