package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session/cookie settings. Sessions are stateless: the (encrypted) token lives in the cookie, so
 * {@code secret} must be a stable value in production, otherwise sessions won't survive a restart.
 * There is no session timeout on the server: a login lasts until logout, or until its cookie goes unused for the
 * 400 days a browser keeps it. {@code cookieSecure} must be true behind HTTPS/Caddy so the cookie is only ever sent
 * over TLS.
 */
@ConfigurationProperties(prefix = "session")
public record SessionProperties(Boolean cookieSecure, String secret) {
    public SessionProperties {
        if (cookieSecure == null)
            cookieSecure = false;
    }

    @Override
    public String toString() {
        return "SessionProperties[cookieSecure=" + cookieSecure + ", secret=" + Secrets.masked(secret) + "]";
    }
}
