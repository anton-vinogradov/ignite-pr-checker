package com.github.igniteprchecker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Slash commands in PR comments: an enrolled user (GitHub option on) comments {@code /run-all} on
 * a pull request and the whole RunAll chain is queued — under their own TeamCity token, with a
 * rocket reaction from their own GitHub account as the ack. The poll is ONE repo-wide comments
 * request a minute, so working from the PR costs nothing extra per PR.
 */
@Component
public class PrCommands implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(PrCommands.class);

    /** PR number out of a PR comment's html url; a plain issue comment (no {@code /pull/}) won't match. */
    private static final Pattern PR_URL = Pattern.compile("/pull/(\\d+)#");

    /** Never look further back than this — a long downtime must not replay stale commands. */
    private static final long MAX_LOOKBACK_MS = 15 * 60_000L;

    private final ObjectMapper mapper;
    private final GithubClient github;
    private final StandingVisas standing;
    private final TcClient tc;
    private final RerunTracker tracker;

    private volatile long sinceMs = System.currentTimeMillis();
    /** Handled comment ids -> when; survives restarts so a redeploy can't double-trigger. */
    private final ConcurrentMap<Long, Long> handled = new ConcurrentHashMap<>();
    private final AtomicInteger handledTotal = new AtomicInteger();
    private volatile long lastPollAt;

    public PrCommands(ObjectMapper mapper, GithubClient github, StandingVisas standing, TcClient tc,
        RerunTracker tracker) {
        this.mapper = mapper;
        this.github = github;
        this.standing = standing;
        this.tc = tc;
        this.tracker = tracker;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void poll() {
        long now = System.currentTimeMillis();
        long since = Math.max(sinceMs, now - MAX_LOOKBACK_MS);
        sinceMs = now;
        lastPollAt = now;
        if (!standing.anyGhEnrolled())
            return;

        standing.ensureGhLogins();

        try {
            String sinceIso = Instant.ofEpochMilli(since).truncatedTo(ChronoUnit.SECONDS).toString();
            for (GithubClient.IssueComment c : github.recentIssueComments(sinceIso))
                handle(c);
        }
        catch (RuntimeException e) {
            log.warn("PR command poll failed: {}", e.toString());
        }

        handled.values().removeIf(t -> t < now - 24 * 3600_000L);
    }

    private void handle(GithubClient.IssueComment c) {
        if (c.user() == null || c.body() == null || handled.containsKey(c.id()))
            return;

        Matcher m = c.htmlUrl() == null ? null : PR_URL.matcher(c.htmlUrl());
        if (m == null || !m.find() || !isRunAllCommand(c.body()))
            return;

        handled.put(c.id(), System.currentTimeMillis());

        Optional<StandingVisas.GhActor> actor = standing.actorByGhLogin(c.user().login());
        if (actor.isEmpty()) {
            log.info("/run-all by {} ignored: not enrolled with the GitHub option", c.user().login());

            return;
        }

        int pr = Integer.parseInt(m.group(1));
        try {
            tracker.record(pr, tc.triggerRunAll(actor.get().tcToken(), pr, false));
            handledTotal.incrementAndGet();
            react(actor.get().ghToken(), c.id(), "rocket");
            log.info("/run-all by {} ({}): RunAll queued for PR {}", c.user().login(), actor.get().username(), pr);
        }
        catch (RuntimeException e) {
            react(actor.get().ghToken(), c.id(), "confused");
            log.warn("/run-all by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    private static boolean isRunAllCommand(String body) {
        String firstLine = body.strip().lines().findFirst().orElse("").strip().toLowerCase();

        return firstLine.equals("/run-all") || firstLine.equals("/runall");
    }

    private void react(String ghToken, long commentId, String content) {
        try {
            github.reactToComment(ghToken, commentId, content);
        }
        catch (RuntimeException e) {
            log.warn("reaction on comment {} failed: {}", commentId, e.toString());
        }
    }

    public int handledCount() {
        return handledTotal.get();
    }

    public long lastPollAt() {
        return lastPollAt;
    }

    @Override
    public String fileName() {
        return "pr-commands.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, new Persisted(sinceMs, new HashMap<>(handled), handledTotal.get()));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        sinceMs = p.sinceMs();
        if (p.handled() != null)
            handled.putAll(p.handled());
        handledTotal.set(p.handledTotal());
    }

    private record Persisted(long sinceMs, Map<Long, Long> handled, int handledTotal) {
    }
}
