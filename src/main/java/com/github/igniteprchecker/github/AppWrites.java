package com.github.igniteprchecker.github;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keeps the writes of the app account within what GitHub and a PR's readers take. GitHub answers a burst of writes
 * with 403 or 429 and asks to wait: writing on through it gets the account banned, and a ban stops the comments of
 * every user at once. Any TeamCity user who runs RunAll on someone's PR makes the account write there, so the writes
 * per PR and per user are capped too. A write that is not to be made now is {@link GithubClient.WriteDeferred
 * deferred}: its caller keeps it and tries again later.
 */
final class AppWrites {
    /** Writes of the app account to one PR in an hour, at most: a run's story takes some 15. */
    static final int PER_PR_HOUR = 40;

    /** Writes on behalf of one user in an hour, at most, over all PRs. */
    static final int PER_USER_HOUR = 60;

    /** How long to wait when GitHub asks to without saying how long; doubled for each refusal in a row. */
    static final Duration FIRST_WAIT = Duration.ofMinutes(1);

    private static final Duration LONGEST_WAIT = Duration.ofHours(1);

    private static final long HOUR_MS = 3600_000L;

    /** Above this many PRs and users written for, those with no write in the last hour are forgotten. */
    private static final int KEYS_KEPT = 500;

    /** Writes made in the last hour, by PR ("PR:N") and by user ("user:name"). */
    private final Map<String, Deque<Long>> recent = new HashMap<>();

    /** Who ran into a cap, and when last. */
    private final Map<String, Long> capped = new HashMap<>();

    private long pausedUntil;

    /** Rate-limit refusals since the last write that went in. */
    private int strikes;

    /** What GitHub said when it last refused a write for another reason than its limits; null since one went in. */
    private String refused;

    private long refusedAt;

    /** Throws {@link GithubClient.WriteDeferred} when a write to the PR for the user is not to be made now. */
    synchronized void admit(int pr, String user, long now) {
        if (now < pausedUntil)
            throw new GithubClient.WriteDeferred("GitHub asked the app account to wait until " + pausedUntil);
        if (recent.size() > KEYS_KEPT)
            recent.entrySet().removeIf(en -> en.getValue().isEmpty() || en.getValue().peekLast() <= now - HOUR_MS);

        for (String key : keys(pr, user)) {
            Deque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst() <= now - HOUR_MS)
                times.pollFirst();
            if (times.size() >= cap(key)) {
                capped.put(key, now);

                throw new GithubClient.WriteDeferred(key + " had " + cap(key) + " writes of the app account in the "
                    + "last hour");
            }
        }
    }

    /** A write to the PR for the user reached GitHub. */
    synchronized void sent(int pr, String user, long now) {
        for (String key : keys(pr, user))
            recent.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
    }

    /** GitHub took a write: the account may write. */
    synchronized void worked() {
        strikes = 0;
        refused = null;
    }

    /**
     * Whether GitHub's refusal of a write asks to wait: then writes pause for as long as it says, or a minute, doubled
     * for each such refusal in a row. Any other refusal is kept for the status page.
     */
    synchronized boolean limited(RestClientResponseException e, long now) {
        Long until = waitUntil(e, now, FIRST_WAIT.toMillis() << Math.min(strikes, 6));
        if (until == null) {
            if (e.getStatusCode().value() == 403) {
                refused = "GitHub answered 403 to a write: " + message(e);
                refusedAt = now;
            }

            return false;
        }

        strikes++;
        pausedUntil = Math.max(pausedUntil, Math.min(until, now + LONGEST_WAIT.toMillis()));

        return true;
    }

    /** Whether GitHub asked the app account to wait at {@code now}. */
    synchronized boolean paused(long now) {
        return now < pausedUntil;
    }

    /** What the status page shows of the writes now. */
    synchronized GithubClient.WriteState state(long now) {
        List<String> over = new ArrayList<>();
        capped.entrySet().removeIf(en -> en.getValue() <= now - HOUR_MS);
        capped.keySet().stream().sorted().forEach(key -> over.add(key.replace(':', ' ')));

        return new GithubClient.WriteState(now < pausedUntil ? pausedUntil : 0, refused,
            refused == null ? 0 : refusedAt, over);
    }

    /**
     * When GitHub lets the account write again after refusing {@code e}: a 429, or a 403 of its rate limits; null for
     * another refusal. Its {@code Retry-After} first, then the reset of an exhausted limit, else {@code wait} from now.
     */
    static Long waitUntil(RestClientResponseException e, long now, long wait) {
        int code = e.getStatusCode().value();
        HttpHeaders h = e.getResponseHeaders();
        String retryAfter = h == null ? null : h.getFirst(HttpHeaders.RETRY_AFTER);
        String remaining = h == null ? null : h.getFirst("X-RateLimit-Remaining");
        boolean saysLimit = message(e).toLowerCase(Locale.ROOT).contains("rate limit");
        if (code != 429 && !(code == 403 && (retryAfter != null || "0".equals(remaining) || saysLimit)))
            return null;

        Long seconds = number(retryAfter);
        if (seconds != null)
            return now + seconds * 1000;

        Long reset = "0".equals(remaining) ? number(h.getFirst("X-RateLimit-Reset")) : null;

        return reset != null && reset * 1000 > now ? reset * 1000 : now + wait;
    }

    private static Long number(String header) {
        try {
            return header == null ? null : Long.parseLong(header.strip());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static String message(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();

        return body.isBlank() ? e.getStatusText() : body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static List<String> keys(int pr, String user) {
        return user == null ? List.of("PR:" + pr) : List.of("PR:" + pr, "user:" + user);
    }

    private static int cap(String key) {
        return key.startsWith("PR:") ? PER_PR_HOUR : PER_USER_HOUR;
    }
}
