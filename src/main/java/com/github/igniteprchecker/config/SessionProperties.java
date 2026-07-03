package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session/cookie settings. {@code cookieSecure} must be true in production (behind HTTPS/Caddy) so
 * the session cookie is only ever sent over TLS; it is false for local http development.
 */
@ConfigurationProperties(prefix = "session")
public record SessionProperties(Integer ttlMinutes, Boolean cookieSecure) {
    public SessionProperties {
        if (ttlMinutes == null)
            ttlMinutes = 480;
        if (cookieSecure == null)
            cookieSecure = false;
    }
}
