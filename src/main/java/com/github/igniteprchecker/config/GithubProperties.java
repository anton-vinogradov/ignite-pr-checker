package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub settings for listing open PRs. {@code token} is optional (raises the API rate limit from
 * 60 to 5000/hour); it is a public-repo read token, not a user credential.
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties(String repo, String token, Integer cacheSeconds) {
    public GithubProperties {
        if (repo == null || repo.isBlank())
            repo = "apache/ignite";
        if (cacheSeconds == null || cacheSeconds < 1)
            cacheSeconds = 300;
    }
}
