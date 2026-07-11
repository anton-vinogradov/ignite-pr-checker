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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

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
    /** Accepted commands whose chains are still running — their comments carry a live ETA line. */
    private final ConcurrentMap<Integer, CommandRun> watching = new ConcurrentHashMap<>();
    /** Logins that already got the one-time onboarding reply — never advertise to the same person twice. */
    private final ConcurrentMap<String, Long> onboarded = new ConcurrentHashMap<>();
    private final AtomicInteger handledTotal = new AtomicInteger();
    private volatile long lastPollAt;
    private final String publicUrl;

    public PrCommands(ObjectMapper mapper, GithubClient github, StandingVisas standing, TcClient tc,
        RerunTracker tracker,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl) {
        this.mapper = mapper;
        this.github = github;
        this.standing = standing;
        this.tc = tc;
        this.tracker = tracker;
        this.publicUrl = publicUrl;
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
        Cmd cmd = m == null || !m.find() ? null : command(c.body());
        if (cmd == null)
            return;

        handled.put(c.id(), System.currentTimeMillis());

        int pr = Integer.parseInt(m.group(1));
        Optional<StandingVisas.GhActor> actor = standing.actorByGhLogin(c.user().login());
        if (actor.isEmpty()) {
            onboard(pr, c.user().login(), cmd.name());

            return;
        }
        if (cmd.name().equals("/top")) {
            top(c, actor.get(), pr);

            return;
        }
        try {
            var build = tc.triggerRunAll(actor.get().tcToken(), pr, cmd.top());
            tracker.record(pr, build);
            handledTotal.incrementAndGet();
            react(actor.get().ghToken(), c.id(), "rocket");

            String link = build.webUrl() == null || build.webUrl().isBlank()
                ? "build " + build.id() : "[build " + build.id() + "](" + build.webUrl() + ")";
            String base = c.body() + "\n\n---\n🚀 **RunAll queued" + (cmd.top() ? " at the top of the queue" : "")
                + "** — " + link + ". The verdict lands here when the run finishes.";
            edit(actor.get().ghToken(), c.id(), base);
            watching.put(pr, new CommandRun(c.id(), base, build.id(), actor.get().username()));

            log.info("/run-all by {} ({}): RunAll queued for PR {}{}", c.user().login(), actor.get().username(),
                pr, cmd.top() ? " (top)" : "");
        }
        catch (RuntimeException e) {
            react(actor.get().ghToken(), c.id(), "confused");
            log.warn("/run-all by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    private static Cmd command(String body) {
        String[] words = body.strip().lines().findFirst().orElse("").strip().toLowerCase().split("\\s+");

        return words[0].equals("/run-all") || words[0].equals("/runall")
            ? new Cmd("/run-all", words.length > 1 && words[1].equals("top"))
            : words[0].equals("/top") ? new Cmd("/top", true) : null;
    }

    /**
     * Promotes the run STARTED BY THE AUTHOR'S OWN COMMAND to the top of the queue — top belongs to
     * the command, so someone else's builds on the same PR are never touched.
     */
    private void top(GithubClient.IssueComment c, StandingVisas.GhActor actor, int pr) {
        try {
            CommandRun run = watching.get(pr);
            if (run == null || !run.username().equals(actor.username())) {
                react(actor.ghToken(), c.id(), "confused");
                log.info("/top by {} for PR {}: no commanded run of theirs to promote", c.user().login(), pr);

                return;
            }

            var b = tc.getBuildState(actor.tcToken(), run.buildId());
            if (b == null || !"queued".equalsIgnoreCase(b.state())) {
                react(actor.ghToken(), c.id(), "confused");
                log.info("/top by {} for PR {}: build {} is not queued", c.user().login(), pr, run.buildId());

                return;
            }

            tc.moveToQueueTop(actor.tcToken(), run.buildId());
            handledTotal.incrementAndGet();
            react(actor.ghToken(), c.id(), "rocket");
            edit(actor.ghToken(), c.id(), c.body()
                + "\n\n---\n⬆️ **Build " + run.buildId() + " moved to the top of the queue.**");
            log.info("/top by {} ({}): build {} of PR {} moved to the queue top",
                c.user().login(), actor.username(), run.buildId(), pr);
        }
        catch (RuntimeException e) {
            react(actor.ghToken(), c.id(), "confused");
            log.warn("/top by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    /**
     * A command from a not-yet-enrolled user is a sales lead, not noise: reply ONCE per login (from
     * the app's account) with exactly where to go and what to switch on.
     */
    private void onboard(int pr, String login, String cmd) {
        if (onboarded.putIfAbsent(login, System.currentTimeMillis()) != null) {
            log.info("{} by {} ignored: not enrolled (already onboarded)", cmd, login);

            return;
        }

        try {
            boolean sent = github.addPrCommentAsApp(pr,
                "@" + login + " that looks like an [Ignite PR Checker](" + publicUrl + ") command — but the"
                + " checker doesn't know your accounts yet, so nothing was triggered. Two steps to make it work:\n\n"
                + "1. Log in at " + publicUrl + " with your TeamCity ([ci2](https://ci2.ignite.apache.org)) access"
                + " token.\n"
                + "2. In settings (⚙) switch on **Comment my runs' verdicts on the GitHub PR** (takes a GitHub"
                + " personal access token, `public_repo` scope).\n\n"
                + "After that, commenting `/run-all` (or `/run-all top`) here queues the whole RunAll chain under"
                + " your own TeamCity account, this thread gets a live ETA, and the verdict lands in the PR when"
                + " the run settles — JIRA visas and automatic re-runs of blocker suites are separate switches"
                + " next to it.");
            log.info("{} by {}: not enrolled, onboarding reply {}", cmd, login, sent ? "posted" : "skipped (no app token)");
        }
        catch (RuntimeException e) {
            log.warn("onboarding reply to {} on PR {} failed: {}", login, pr, e.toString());
        }
    }

    private void react(String ghToken, long commentId, String content) {
        try {
            github.reactToComment(ghToken, commentId, content);
        }
        catch (RuntimeException e) {
            log.warn("reaction on comment {} failed: {}", commentId, e.toString());
        }
    }

    /**
     * The queued-build ack and the remaining-time line live INSIDE the command comment itself (it's
     * the author's own comment, edited with their own PAT) — still zero extra messages in the thread.
     */
    private void edit(String ghToken, long commentId, String body) {
        try {
            github.updatePrComment(ghToken, commentId, body);
        }
        catch (RuntimeException e) {
            log.warn("editing command comment {} failed: {}", commentId, e.toString());
        }
    }

    /** Refreshes the "~Xh Ym remaining" line of every accepted command's comment; final line on finish. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    void updateEtas() {
        watching.forEach((pr, run) -> {
            Optional<StandingVisas.GhActor> actor = standing.actor(run.username());
            if (actor.isEmpty()) {
                watching.remove(pr); // the option was switched off — stop touching the comment

                return;
            }

            try {
                var b = tc.getBuildState(actor.get().tcToken(), run.buildId());
                if (b == null)
                    return;

                if ("finished".equalsIgnoreCase(b.state())) {
                    boolean cancelled = "UNKNOWN".equalsIgnoreCase(b.status());
                    edit(actor.get().ghToken(), run.commentId(), run.baseBody()
                        + (cancelled ? "\n🛑 _Run cancelled._" : "\n🏁 _Run finished — the verdict comment follows._"));
                    watching.remove(pr);

                    return;
                }

                long eta = tc.chainRemainingSeconds(actor.get().tcToken(), run.buildId());
                if (eta >= 0)
                    edit(actor.get().ghToken(), run.commentId(), run.baseBody()
                        + "\n⏱ _~" + fmtDur(eta) + " remaining — ≈ " + finishAt(eta, actor.get().tz())
                        + " (updates every ~5 min)._"
                        + ("queued".equalsIgnoreCase(b.state()) ? " _Reply `/top` to jump the queue._" : ""));
            }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404)
                    watching.remove(pr); // the build is gone — nothing left to narrate
                else
                    log.warn("ETA update for PR {} failed: {}", pr, e.toString());
            }
            catch (RuntimeException e) {
                log.warn("ETA update for PR {} failed: {}", pr, e.toString());
            }
        });
    }

    private static String fmtDur(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;

        return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m" : "<1m";
    }

    /**
     * The estimated finish as wall-clock time in the AUTHOR'S timezone (their JIRA-profile one —
     * GitHub exposes none); UTC when unknown, so the stamp is honest either way.
     */
    private static String finishAt(long etaSeconds, String tz) {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        if (tz != null) {
            try {
                zone = java.time.ZoneId.of(tz);
            }
            catch (java.time.DateTimeException ignored) {
                // an unparsable profile timezone falls back to UTC
            }
        }

        return java.time.ZonedDateTime.now(zone).plusSeconds(etaSeconds)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm zzz", java.util.Locale.ENGLISH));
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
        Snapshots.writeAtomic(mapper, file, new Persisted(sinceMs, new HashMap<>(handled), handledTotal.get(),
            new HashMap<>(watching), new HashMap<>(onboarded)));
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
        if (p.watching() != null)
            watching.putAll(p.watching());
        if (p.onboarded() != null)
            onboarded.putAll(p.onboarded());
    }

    private record Persisted(long sinceMs, Map<Long, Long> handled, int handledTotal,
        Map<Integer, CommandRun> watching, Map<String, Long> onboarded) {
    }

    /** An accepted command still being narrated: where its comment is and which chain it watches. */
    private record CommandRun(long commentId, String baseBody, long buildId, String username) {
    }

    /** A parsed command: the canonical name and whether "top" was asked for. */
    private record Cmd(String name, boolean top) {
    }
}
