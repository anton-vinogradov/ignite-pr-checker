package com.github.igniteprchecker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * GitHub settings. {@code token} belongs to the checker's own GitHub account, the "app account": the PR list
 * and other reads go under it (5000 requests an hour instead of 60), and every comment, edit and reaction of the
 * checker in a PR comes from that account, for every user alike. A classic token with the {@code public_repo} scope
 * of a separate account without write access to the repo; empty, the checker only reads, and the options that write
 * to GitHub are off. {@code readTimeout} bounds how long a call may wait for GitHub to send anything. {@code apiUrl}
 * is where the GitHub API lives, {@code webUrl} where users sign in to GitHub. {@code oauthClientId} and
 * {@code oauthClientSecret} are of the checker's OAuth App, with which users link their GitHub accounts; null when not
 * set, and then nobody can link an account that way.
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties(String repo, String token, Integer cacheSeconds, Duration readTimeout, String apiUrl,
    String webUrl, String oauthClientId, String oauthClientSecret) {
    @ConstructorBinding
    public GithubProperties {
        if (repo == null || repo.isBlank())
            repo = "apache/ignite";
        if (cacheSeconds == null || cacheSeconds < 1)
            cacheSeconds = 300;
        if (readTimeout == null)
            readTimeout = OutboundHttp.READ_TIMEOUT;
        apiUrl = baseUrl(apiUrl, "https://api.github.com");
        webUrl = baseUrl(webUrl, "https://github.com");
        oauthClientId = oauthClientId == null || oauthClientId.isBlank() ? null : oauthClientId.strip();
        oauthClientSecret = oauthClientSecret == null || oauthClientSecret.isBlank() ? null : oauthClientSecret.strip();
    }

    public GithubProperties(String repo, String token, Integer cacheSeconds, Duration readTimeout, String apiUrl) {
        this(repo, token, cacheSeconds, readTimeout, apiUrl, null, null, null);
    }

    public GithubProperties(String repo, String token, Integer cacheSeconds) {
        this(repo, token, cacheSeconds, null, null);
    }

    @Override
    public String toString() {
        return "GithubProperties[repo=" + repo + ", token=" + Secrets.masked(token) + ", cacheSeconds=" + cacheSeconds
            + ", readTimeout=" + readTimeout + ", apiUrl=" + apiUrl + ", webUrl=" + webUrl + ", oauthClientId="
            + oauthClientId + ", oauthClientSecret=" + Secrets.masked(oauthClientSecret) + "]";
    }

    private static String baseUrl(String url, String otherwise) {
        if (url == null || url.isBlank())
            return otherwise;

        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
