package com.github.igniteprchecker.analysis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The set of TeamCity tokens the background work may run under, so the warmer can spread its load
 * across all of them (round-robin) rather than hammering ci2 under a single user's token. Two
 * sources: every authenticated request donates its token (evicted once not re-offered within the
 * TTL — that user has gone), and users with standing options on re-donate theirs on every sweep, so
 * background work keeps running while nobody is browsing. In-memory only — tokens are secrets and
 * are never persisted here.
 */
final class TokenPool {
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** Tokens TeamCity rejected, with when — re-donating one before its cooldown ends is pointless. */
    private final Map<String, Long> rejected = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private final long ttlMs;

    TokenPool(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Record or refresh a donated token. Returns true if the pool had been empty (nothing to warm
     * with yet). A {@code verified} token has just been accepted by TeamCity, which clears any
     * earlier rejection; an unverified re-donation of a rejected one is ignored until the cooldown
     * ends, so an expired token can't make every cycle hammer ci2 with 401s.
     */
    boolean offer(String token, boolean verified) {
        if (token == null || token.isBlank())
            return false;

        if (verified)
            rejected.remove(token);
        else if (inCooldown(token))
            return false;

        boolean wasEmpty = tokens().isEmpty();
        lastSeen.put(token, System.currentTimeMillis());

        return wasEmpty;
    }

    /** Drop a token TeamCity rejected (revoked/expired) so it isn't tried again while it cools down. */
    void remove(String token) {
        lastSeen.remove(token);
        rejected.put(token, System.currentTimeMillis());
    }

    private boolean inCooldown(String token) {
        Long at = rejected.get(token);
        if (at == null)
            return false;

        if (System.currentTimeMillis() - at <= ttlMs)
            return true;

        rejected.remove(token); // cooled down: worth another try (a revoked one just gets rejected again)

        return false;
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
