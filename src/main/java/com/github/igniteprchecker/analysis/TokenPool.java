package com.github.igniteprchecker.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The set of TeamCity tokens the background work may run under, so the warmer can spread its load
 * across all of them (round-robin) rather than hammering ci2 under a single user's token. Two
 * sources: every authenticated request donates its token (evicted once not re-offered within the
 * TTL — that user has gone), and users with standing options on re-donate theirs on every sweep, so
 * background work keeps running while nobody is browsing. In-memory only — tokens are secrets and
 * are never persisted here. The tokens TeamCity refused are kept by their SHA-256 only: a heap dump
 * must not hand out the revoked tokens of the past along with the live ones.
 */
final class TokenPool {
    /** How long a revoked token's sessions stay refused here; past it, the first TeamCity call refuses them again. */
    static final Duration REVOKED_MEMORY = Duration.ofDays(30);

    /** Bounds the memory of revoked tokens; past it the oldest is forgotten first. */
    static final int REVOKED_MAX = 10_000;

    /** The pooled tokens: who offered each and when last. */
    private final Map<String, Offer> offers = new ConcurrentHashMap<>();
    /** Hashes of tokens TeamCity rejected, with when — re-donating one before its cooldown ends is pointless. */
    private final Map<String, Long> rejected = new ConcurrentHashMap<>();
    /**
     * Hashes of tokens TeamCity answered 401 for, oldest first. A session carrying one stays refused until a login
     * with the same token proves it valid again, or for {@link #REVOKED_MEMORY}.
     */
    private final Map<String, Long> revoked = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > REVOKED_MAX;
        }
    };
    private final AtomicInteger cursor = new AtomicInteger();
    private final long ttlMs;
    private final LongSupplier nowMs;

    TokenPool(long ttlMs) {
        this(ttlMs, System::currentTimeMillis);
    }

    TokenPool(long ttlMs, LongSupplier nowMs) {
        this.ttlMs = ttlMs;
        this.nowMs = nowMs;
    }

    /**
     * Record or refresh a token {@code user} donated. Returns true if the pool had been empty (nothing to warm
     * with yet). A {@code verified} token has just been accepted by TeamCity, which clears any
     * earlier rejection; an unverified re-donation of a rejected one is ignored until the cooldown
     * ends, so an expired token can't make every cycle hammer ci2 with 401s.
     */
    boolean offer(String user, String token, boolean verified) {
        if (token == null || token.isBlank())
            return false;

        if (verified) {
            String key = hash(token);
            rejected.remove(key);
            synchronized (revoked) {
                revoked.remove(key);
            }
        }
        else if (inCooldown(token))
            return false;

        boolean wasEmpty = tokens().isEmpty();
        offers.put(token, new Offer(user, nowMs.getAsLong()));

        return wasEmpty;
    }

    /**
     * Drops a token its user took back by logging out. Not a refusal: the next offer of the same token, from the
     * standing options or a later login, counts at once.
     */
    void forget(String token) {
        if (token != null)
            offers.remove(token);
    }

    /** Drops every token {@code user} offered, but those {@code kept} names; see {@link #forget}. */
    void forgetAllOf(String user, Predicate<String> kept) {
        offers.entrySet().removeIf(e -> user.equals(e.getValue().user()) && !kept.test(e.getKey()));
    }

    /** Drop a token TeamCity rejected (revoked/expired) so it isn't tried again while it cools down. */
    void remove(String token) {
        remove(token, false);
    }

    /** Same, and with {@code revoked} (a 401, TeamCity's verdict on the token itself) also refuse its sessions. */
    void remove(String token, boolean revoked) {
        long now = nowMs.getAsLong();
        String key = hash(token);
        offers.remove(token);
        rejected.values().removeIf(at -> now - at > ttlMs);
        rejected.put(key, now);
        if (!revoked)
            return;

        synchronized (this.revoked) {
            this.revoked.remove(key);
            this.revoked.put(key, now);
        }
    }

    /** Whether TeamCity answered 401 for this token lately and no login has proven it valid since. */
    boolean revoked(String token) {
        if (token == null)
            return false;

        String key = hash(token);
        synchronized (revoked) {
            Long at = revoked.get(key);
            if (at == null)
                return false;
            if (nowMs.getAsLong() - at <= REVOKED_MEMORY.toMillis())
                return true;

            revoked.remove(key);

            return false;
        }
    }

    private boolean inCooldown(String token) {
        String key = hash(token);
        Long at = rejected.get(key);
        if (at == null)
            return false;

        if (nowMs.getAsLong() - at <= ttlMs)
            return true;

        rejected.remove(key); // cooled down: worth another try (a revoked one just gets rejected again)

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
        long now = nowMs.getAsLong();
        offers.entrySet().removeIf(e -> now - e.getValue().at() > ttlMs);

        return List.copyOf(offers.keySet());
    }

    /** What the pool keeps of a refused token. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Who offered a token, and when last. */
    private record Offer(String user, long at) {
    }
}
