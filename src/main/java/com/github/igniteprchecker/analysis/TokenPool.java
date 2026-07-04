package com.github.igniteprchecker.analysis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The set of TeamCity tokens currently donated by logged-in users, so the warmer can spread its
 * background load across all of them (round-robin) rather than hammering ci2 under a single user's
 * token. Tokens are re-offered on every authenticated request; one not seen within the TTL is
 * evicted (that user has gone). In-memory only — tokens are secrets and are never persisted.
 */
final class TokenPool {
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private final long ttlMs;

    TokenPool(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** Record or refresh a donated token. Returns true if the pool had been empty (nothing to warm with yet). */
    boolean offer(String token) {
        if (token == null || token.isBlank())
            return false;

        boolean wasEmpty = tokens().isEmpty();
        lastSeen.put(token, System.currentTimeMillis());

        return wasEmpty;
    }

    /** Drop a token TeamCity rejected (revoked/expired) so it isn't tried again. */
    void remove(String token) {
        lastSeen.remove(token);
    }

    /** The next token in round-robin order, or null if the pool is (now) empty. */
    String next() {
        List<String> live = tokens();
        if (live.isEmpty())
            return null;

        return live.get(Math.floorMod(cursor.getAndIncrement(), live.size()));
    }

    int size() {
        return tokens().size();
    }

    /** Non-stale tokens, evicting any not re-offered within the TTL. */
    private List<String> tokens() {
        long now = System.currentTimeMillis();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > ttlMs);

        return List.copyOf(lastSeen.keySet());
    }
}
