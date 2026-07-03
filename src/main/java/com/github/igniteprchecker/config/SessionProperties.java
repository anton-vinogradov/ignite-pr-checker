package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session/cookie settings. Sessions are stateless: the (encrypted) token lives in the cookie, so
 * {@code secret} must be a stable value in production, otherwise sessions won't survive a restart.
 * {@code cookieSecure} must be true behind HTTPS/Caddy so the cookie is only ever sent over TLS.
 */
@ConfigurationProperties(prefix = "session")
public record SessionProperties(Integer ttlMinutes, Boolean cookieSecure, String secret) {
    public SessionProperties {
        if (ttlMinutes == null)
            ttlMinutes = 480;
        if (cookieSecure == null)
            cookieSecure = false;
    }
}
