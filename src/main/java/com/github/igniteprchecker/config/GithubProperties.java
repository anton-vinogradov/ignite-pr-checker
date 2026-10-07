package com.github.igniteprchecker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * GitHub settings. {@code token} belongs to the checker's own GitHub account, the "app account": the PR list
 * and other reads go under it (5000 requests an hour instead of 60), and the checker writes as that account —
 * onboarding replies to PR commands, reactions, hints, and the run narration of users who saved no GitHub
 * token of their own. A classic token with the {@code public_repo} scope of a separate account without write
 * access to the repo; empty, the checker only reads, and such users' commands run with no reply. {@code
 * readTimeout} bounds how long a call may wait for GitHub to send anything. {@code apiUrl} is where the GitHub
 * API lives.
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties(String repo, String token, Integer cacheSeconds, Duration readTimeout, String apiUrl) {
    @ConstructorBinding
    public GithubProperties {
        if (repo == null || repo.isBlank())
            repo = "apache/ignite";
        if (cacheSeconds == null || cacheSeconds < 1)
            cacheSeconds = 300;
        if (readTimeout == null)
            readTimeout = OutboundHttp.READ_TIMEOUT;
        if (apiUrl == null || apiUrl.isBlank())
            apiUrl = "https://api.github.com";
        else if (apiUrl.endsWith("/"))
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
    }

    public GithubProperties(String repo, String token, Integer cacheSeconds) {
        this(repo, token, cacheSeconds, null, null);
    }
}
