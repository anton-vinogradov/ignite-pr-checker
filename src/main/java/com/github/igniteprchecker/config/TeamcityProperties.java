package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TeamCity connection settings. Auth is per-user (token supplied at login), so no token lives here.
 */
@ConfigurationProperties(prefix = "teamcity")
public record TeamcityProperties(String baseUrl) {
}
