package com.github.igniteprchecker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * GitHub settings for listing open PRs. {@code token} is optional (raises the API rate limit from
 * 60 to 5000/hour); it is a public-repo read token, not a user credential. {@code readTimeout} bounds
 * how long a call may wait for GitHub to send anything. {@code apiUrl} is where the GitHub API lives.
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
