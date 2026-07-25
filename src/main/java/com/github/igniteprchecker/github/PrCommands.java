package com.github.igniteprchecker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.style.StyleFixService;
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
    private final StyleFixService styleFix;
    private final com.github.igniteprchecker.analysis.SuiteBaseline baseline;

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
    private final String tcBaseUrl;

    public PrCommands(ObjectMapper mapper, GithubClient github, StandingVisas standing, TcClient tc,
        RerunTracker tracker, StyleFixService styleFix,
        com.github.igniteprchecker.analysis.SuiteBaseline baseline,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl,
        TeamcityProperties teamcity) {
        this.mapper = mapper;
        this.github = github;
        this.standing = standing;
        this.tc = tc;
        this.tracker = tracker;
        this.styleFix = styleFix;
        this.baseline = baseline;
        this.publicUrl = publicUrl;
        String base = teamcity.baseUrl() == null ? "https://ci2.ignite.apache.org" : teamcity.baseUrl();
        this.tcBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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
            onboard(pr, c.id(), c.user().login(), cmd.name());

            return;
        }
        if (cmd.name().equals("/top")) {
            top(c, actor.get(), pr);

            return;
        }
        boolean pat = actor.get().ghToken() != null;
        try {
            // Style first, trigger second: the fix commit must be the revision the chain builds
            // (the autofix pushes under the user's PAT, so it needs one).
            String styleNote = pat && standing.styleFixOn(actor.get().username())
                ? styleFix.fixForCommand(pr, actor.get(), c.user().login()) : null;

            var build = tc.triggerRunAll(actor.get().tcToken(), pr, cmd.top());
            tracker.record(pr, build);
            handledTotal.incrementAndGet();
            react(actor.get(), c.id(), "rocket");

            String link = build.webUrl() == null || build.webUrl().isBlank()
                ? "build " + build.id() : "[build " + build.id() + "](" + build.webUrl() + ")";
            String ack = (styleNote == null ? "" : styleNote + "\n")
                + "🚀 **RunAll queued" + (cmd.top() ? " at the top of the queue" : "")
                + "** — " + link + " · live progress & verdict: [Ignite PR Checker](" + publicUrl + "/?pr=" + pr
                + "). The verdict lands here when the run finishes.";

            if (pat) {
                // Their own PAT: the ack lives inside their command comment.
                String base = c.body() + "\n\n---\n" + ack;
                edit(actor.get().ghToken(), c.id(), base);
                watching.put(pr, new CommandRun(c.id(), base, build.id(), actor.get().username(), 0, false));
            }
            else {
                // No PAT: the checker narrates from its own account in a separate living comment.
                GithubClient.PostedComment n = github.addPrCommentAsAppWithId(pr,
                    "@" + c.user().login() + " " + ack);
                if (n != null)
                    watching.put(pr, new CommandRun(c.id(), "@" + c.user().login() + " " + ack, build.id(),
                        actor.get().username(), n.id(), true));
            }

            log.info("/run-all by {} ({}): RunAll queued for PR {}{}{}", c.user().login(), actor.get().username(),
                pr, cmd.top() ? " (top)" : "", pat ? "" : " (app-narrated)");
        }
        catch (RuntimeException e) {
            react(actor.get(), c.id(), "confused");
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
                react(actor, c.id(), "confused");
                log.info("/top by {} for PR {}: no commanded run of theirs to promote", c.user().login(), pr);

                return;
            }

            var b = tc.getBuildState(actor.tcToken(), run.buildId());
            if (b == null || !"queued".equalsIgnoreCase(b.state())) {
                react(actor, c.id(), "confused");
                log.info("/top by {} for PR {}: build {} is not queued", c.user().login(), pr, run.buildId());

                return;
            }

            tc.moveToQueueTop(actor.tcToken(), run.buildId());
            handledTotal.incrementAndGet();
            react(actor, c.id(), "rocket");
            if (actor.ghToken() != null)
                edit(actor.ghToken(), c.id(), c.body()
                    + "\n\n---\n⬆️ **Build " + run.buildId() + " moved to the top of the queue.**");
            log.info("/top by {} ({}): build {} of PR {} moved to the queue top",
                c.user().login(), actor.username(), run.buildId(), pr);
        }
        catch (RuntimeException e) {
            react(actor, c.id(), "confused");
            log.warn("/top by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    /**
     * A command from a not-yet-enrolled user is a sales lead, not noise: reply ONCE per login (from
     * the app's account) with exactly where to go and what to switch on.
     */
    private void onboard(int pr, long commentId, String login, String cmd) {
        try {
            github.reactToCommentAsApp(commentId, "confused"); // never silent, even on repeats
        }
        catch (RuntimeException e) {
            log.warn("confused reaction for {} failed: {}", login, e.toString());
        }
        if (onboarded.putIfAbsent(login, System.currentTimeMillis()) != null) {
            log.info("{} by {} ignored: not enrolled (already onboarded)", cmd, login);

            return;
        }

        try {
            String tcTokens = tcBaseUrl + "/profile.html?item=accessTokens";
            String ghPat = "https://github.com/settings/tokens/new?scopes=public_repo&description=Ignite+PR+Checker";
            String jiraPat = "https://issues.apache.org/jira/secure/ViewProfile.jspa"
                + "?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens";
            boolean sent = github.addPrCommentAsApp(pr,
                "@" + login + " that looks like an [Ignite PR Checker](" + publicUrl + ") command — but the"
                + " checker doesn't know your accounts yet, so **nothing was triggered**. Everything it does runs"
                + " under your own accounts (there is no bot); setting that up takes about two minutes:\n\n"
                + "1. **Log in** at " + publicUrl + " with a TeamCity ([ci2](" + tcBaseUrl + ")) access token —"
                + " create one at [ci2 → Profile → Access Tokens](" + tcTokens + ").\n"
                + "2. In settings (⚙) switch on at least one option — **Auto re-run blocker suites** needs"
                + " nothing extra — and save your **GitHub login** in the PR-commands field. That's enough:"
                + " commands work, the checker acks and narrates from its own account.\n"
                + "3. **The full experience** — switch on **Comment my runs' verdicts on the GitHub PR** with a"
                + " GitHub personal access token ([create one here](" + ghPat + "), classic, `public_repo`"
                + " scope): acks and the live run status then come from your own account, plus checkstyle"
                + " autofix becomes available. **Auto-visa all my runs** posts the verdict to the IGNITE ticket"
                + " (needs a [JIRA PAT](" + jiraPat + ")).\n\n"
                + "Then comment here:\n\n"
                + "- `/run-all` — queue the whole RunAll chain under your TeamCity account (`/run-all top` — at"
                + " the top of the build queue);\n"
                + "- `/top` — move the run your command started to the top of the queue while it still waits.\n\n"
                + "Your command comment gets a 🚀 and narrates the run — live ETA, finish, auto re-run waves —"
                + " and the verdict lands as one comment that updates in place until everything settles."
                + " Tokens are stored encrypted, and only while the options are on.");
            log.info("{} by {}: not enrolled, onboarding reply {}", cmd, login, sent ? "posted" : "skipped (no app token)");
        }
        catch (RuntimeException e) {
            log.warn("onboarding reply to {} on PR {} failed: {}", login, pr, e.toString());
        }
    }

    private void react(StandingVisas.GhActor actor, long commentId, String content) {
        try {
            if (actor.ghToken() != null)
                github.reactToComment(actor.ghToken(), commentId, content);
            else
                github.reactToCommentAsApp(commentId, content);
        }
        catch (RuntimeException e) {
            log.warn("reaction on comment {} failed: {}", commentId, e.toString());
        }
    }

    /**
     * The queued-build ack and the remaining-time line live INSIDE the command comment itself (it's
     * the author's own comment, edited with their own PAT) — still zero extra messages in the thread.
     */
    /** Edits the narration (the command comment, or the checker's own one for PAT-less commanders)
     * only when it actually changed — no no-op revisions. */
    private void narrate(int pr, StandingVisas.GhActor actor, CommandRun run, String body) {
        if (body.equals(lastNarration.put(pr, body)))
            return;

        try {
            if (run.app())
                github.updatePrCommentAsApp(run.narrationId(), body);
            else
                github.updatePrComment(actor.ghToken(), run.commentId(), body);
        }
        catch (RuntimeException e) {
            log.warn("editing narration for PR {} failed: {}", pr, e.toString());
        }
    }

    private void edit(String ghToken, long commentId, String body) {
        try {
            github.updatePrComment(ghToken, commentId, body);
        }
        catch (RuntimeException e) {
            log.warn("editing command comment {} failed: {}", commentId, e.toString());
        }
    }

    /** Last narration line rendered per PR — identical re-edits are skipped so the minute-level
     * cadence doesn't flood the comment's edit history with no-op revisions. */
    private final ConcurrentMap<Integer, String> lastNarration = new ConcurrentHashMap<>();

    /** Refreshes the "~Xh Ym remaining" line of every accepted command's comment; final line on finish. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
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
                    if ("UNKNOWN".equalsIgnoreCase(b.status())) {
                        narrate(pr, actor.get(), run, run.baseBody() + "\n🛑 _Run cancelled._");
                        watching.remove(pr);
                        lastNarration.remove(pr);

                        return;
                    }

                    // The command comment narrates the whole story: after the chain finishes it keeps
                    // reporting the blocker/broken auto re-run waves and only closes once the verdict
                    // has actually landed.
                    if (standing.buildHandled(run.username(), pr, run.buildId())) {
                        narrate(pr, actor.get(), run, run.baseBody()
                            + "\n🏁 _Run finished — " + (standing.ghOn(run.username())
                                ? "the verdict comment has the full story._"
                                : "the verdict: " + publicUrl + "/?pr=" + pr + "_"));
                        watching.remove(pr);
                        lastNarration.remove(pr);

                        return;
                    }

                    Optional<StandingVisas.WaveStatus> w = standing.waveStatus(pr, run.buildId());
                    String line = w.isEmpty()
                        ? "\n🏁 _Run finished — analysing; the verdict comment follows._"
                        : "\n🏁 _Run finished._ ♻️ _Auto re-run **#" + w.get().wave() + "** — " + w.get().what()
                            + (w.get().etaEpochSec() == null ? "" : ", **≈ settled by " + finishAt(
                                Math.max(0, w.get().etaEpochSec() - System.currentTimeMillis() / 1000),
                                actor.get().tz()) + "**")
                            + " — details in the verdict comment._";
                    narrate(pr, actor.get(), run, run.baseBody() + line);

                    return; // keep narrating until the verdict lands
                }

                long eta = tc.chainRemainingSeconds(actor.get().tcToken(), run.buildId(),
                    baseline.durations(actor.get().tcToken()));
                if (eta >= 0)
                    narrate(pr, actor.get(), run, run.baseBody()
                        + "\n⏱ _~" + fmtDur(eta) + " remaining — **≈ " + finishAt(eta, actor.get().tz())
                        + "** (updates every minute)._"
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

    /** An accepted command still being narrated: where its comment is, which chain it watches, and —
     * for PAT-less commanders — the checker's own narration comment that gets edited instead. */
    private record CommandRun(long commentId, String baseBody, long buildId, String username,
        long narrationId, boolean app) {
    }

    /** A parsed command: the canonical name and whether "top" was asked for. */
    private record Cmd(String name, boolean top) {
    }
}
