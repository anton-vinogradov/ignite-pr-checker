package com.github.igniteprchecker.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Keeps a stream of logins from becoming a stream of TeamCity calls. Every login asks TeamCity "whoami" from the
 * server's address, and if ci2's WAF blocks that address the service stops for everyone. So each client gets a
 * few attempts per window, all clients together get a few TeamCity checks per minute, and a token TeamCity has
 * rejected is answered from memory (by its hash, never the token itself) for a while.
 */
@Component
public class LoginThrottle {
    static final int ATTEMPTS_PER_CLIENT = 10;

    static final Duration CLIENT_WINDOW = Duration.ofMinutes(10);

    static final int CHECKS_PER_MINUTE = 30;

    static final Duration REJECTION_MEMORY = Duration.ofMinutes(15);

    /** Bounds memory under a flood from many addresses; past it the per-client history starts over. */
    private static final int MAX_TRACKED = 10_000;

    private static final long MINUTE_MS = Duration.ofMinutes(1).toMillis();

    private final LongSupplier nowMs;

    private final Map<String, Deque<Long>> attempts = new HashMap<>();

    private final Deque<Long> checks = new ArrayDeque<>();

    private final Map<String, Long> rejectedUntil = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    LoginThrottle(LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    /** Counts one login attempt from {@code client}; null when it may go ahead, else how long to wait. */
    public synchronized Duration admitAttempt(String client) {
        long now = nowMs.getAsLong();
        if (attempts.size() >= MAX_TRACKED)
            forgetIdleClients(now);

        Deque<Long> times = attempts.computeIfAbsent(client, k -> new ArrayDeque<>());

        return admit(times, ATTEMPTS_PER_CLIENT, CLIENT_WINDOW.toMillis(), now);
    }

    /** Takes one TeamCity check from the budget shared by all clients; null when granted, else how long to wait. */
    public synchronized Duration admitCheck() {
        return admit(checks, CHECKS_PER_MINUTE, MINUTE_MS, nowMs.getAsLong());
    }

    /** Whether TeamCity rejected this very token a short while ago. */
    public synchronized boolean recentlyRejected(String token) {
        String key = hash(token);
        Long until = rejectedUntil.get(key);
        if (until == null)
            return false;

        if (until > nowMs.getAsLong())
            return true;

        rejectedUntil.remove(key);

        return false;
    }

    public synchronized void rejected(String token) {
        String key = hash(token);
        rejectedUntil.remove(key);
        rejectedUntil.put(key, nowMs.getAsLong() + REJECTION_MEMORY.toMillis());
    }

    /**
     * The client's address. X-Forwarded-For counts only when the request came from a proxy on this host (Caddy):
     * from anywhere else the header is whatever the caller wrote. The last entry is the one that proxy added.
     */
    public static String clientOf(HttpServletRequest req) {
        String peer = req.getRemoteAddr();
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !loopback(peer))
            return peer;

        String[] hops = forwarded.split(",");

        return hops[hops.length - 1].trim();
    }

    private static Duration admit(Deque<Long> times, int limit, long windowMs, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= windowMs)
            times.pollFirst();

        if (times.size() >= limit)
            return Duration.ofMillis(times.peekFirst() + windowMs - now);

        times.addLast(now);

        return null;
    }

    private void forgetIdleClients(long now) {
        long windowMs = CLIENT_WINDOW.toMillis();
        for (Iterator<Deque<Long>> it = attempts.values().iterator(); it.hasNext(); ) {
            Deque<Long> times = it.next();
            if (times.isEmpty() || now - times.peekLast() >= windowMs)
                it.remove();
        }

        if (attempts.size() >= MAX_TRACKED)
            attempts.clear();
    }

    private static boolean loopback(String addr) {
        try {
            return addr != null && InetAddress.getByName(addr).isLoopbackAddress();
        }
        catch (UnknownHostException e) {
            return false;
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
