package com.github.igniteprchecker.session;

import java.time.Instant;

/** A logged-in user's session: their TeamCity token is held here in memory only, never persisted. */
public record UserSession(String id, String username, String token, Instant expiresAt) {
    public boolean expired() {
        return Instant.now().isAfter(expiresAt);
    }
}
