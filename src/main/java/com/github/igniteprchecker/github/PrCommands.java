package com.github.igniteprchecker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * request a minute (more pages only after a gap), so working from the PR costs nothing extra per PR.
 */
@Component
public class PrCommands implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(PrCommands.class);

    /** PR number out of a PR comment's html url; a plain issue comment (no {@code /pull/}) won't match. */
    private static final Pattern PR_URL = Pattern.compile("/pull/(\\d+)#");

    /**
     * A command comment written longer ago than this is not run. Edits bump a comment into the poll
     * again, the checker's own included, and an old /run-all that comes back must not cancel the
     * chain it started and queue a new one.
     */
    private static final long COMMAND_MAX_AGE_MS = 3600_000L;

    /** Never look further back than a command may be old — a long downtime must not replay stale ones. */
    private static final long MAX_LOOKBACK_MS = COMMAND_MAX_AGE_MS;

    /**
     * Each poll reads again this much before the end of the last one that worked: a comment GitHub
     * lists a little late, or a clock running behind GitHub's, still lands in a window. Comments read
     * twice are told apart by {@link #handled}.
     */
    private static final long OVERLAP_MS = 3 * 60_000L;

    /** How long a handled command is remembered: far past the age at which it could still run. */
    private static final long HANDLED_MEMORY_MS = 30 * 24 * 3600_000L;

    /** A command named in the first line: "/run-all" with its typos ("/run all", "/run_all"), or "/top". */
    private static final Pattern NEAR_MISS = Pattern.compile("(?<![\\w/-])/(run[-_ ]?all|top)(?![\\w-])");

    /** A longer first line mentions a command rather than tries one. */
    private static final int NEAR_MISS_MAX_WORDS = 4;

    /** Where the checker's part of a commander's own comment begins. */
    private static final String SEPARATOR = "\n\n---\n";

    /** What a participant is told when a comment tries a command without opening with it. */
    private static final String NEAR_MISS_HINT = "💡 _Nothing was run: a command has to be the very first word of"
        + " the comment, as is — `/run-all`, `/run-all top` or `/top`. Post it that way in a new comment._";

    /** At most this many onboarding replies in any 24 hours, across all PRs. */
    private static final int ONBOARDING_PER_DAY = 5;

    /** How long a PR counts as already onboarded, and a stranger's command as already answered. */
    private static final long ONBOARDING_MEMORY_MS = 30 * 24 * 3600_000L;

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
    /**
     * Accepted commands whose runs are still being told, by command comment: two people's /run-all on one PR,
     * or a run and the one that replaced it, each get their story to the end.
     */
    private final ConcurrentMap<Long, CommandRun> watching = new ConcurrentHashMap<>();
    /** Logins that already got the one-time onboarding reply — never advertise to the same person twice. */
    private final ConcurrentMap<String, Long> onboarded = new ConcurrentHashMap<>();
    /** PRs that already carry an onboarding reply: one per PR, whoever asks next. */
    private final ConcurrentMap<Integer, Long> onboardedPrs = new ConcurrentHashMap<>();
    /** "login#pr" of commands from people without PR commands, so a repeat on the same PR gets no 😕. */
    private final ConcurrentMap<String, Long> strangerCommands = new ConcurrentHashMap<>();
    /**
     * Per user, the TeamCity refusal they were already told about in a PR — one reply per refusal;
     * survives restarts, as the refusal itself does.
     */
    private final ConcurrentMap<String, Long> toldTcRefused = new ConcurrentHashMap<>();
    /** Comments that tried a command without opening with it, already answered with a hint. */
    private final ConcurrentMap<Long, Long> hinted = new ConcurrentHashMap<>();
    private final AtomicInteger handledTotal = new AtomicInteger();
    private volatile long lastPollAt;

    /** Off in the dev profile, so a local run never acts on PR comments next to the production instance. */
    @Value("${automation.enabled:true}")
    private boolean automation = true;

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

    /**
     * Reads the comments updated since the last poll that worked, a few minutes more to be safe. The
     * window moves on only once it was read: when GitHub fails, the next poll reads it again.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void poll() {
        long now = System.currentTimeMillis();
        lastPollAt = now;
        if (!automation || !standing.anyGhEnrolled()) {
            sinceMs = now; // nobody's comments are commands: they must not run once someone switches commands on

            return;
        }

        standing.ensureGhLogins();

        String sinceIso = Instant.ofEpochMilli(Math.max(sinceMs - OVERLAP_MS, now - MAX_LOOKBACK_MS))
            .truncatedTo(ChronoUnit.SECONDS).toString();
        try {
            for (GithubClient.IssueComment c : github.recentIssueComments(sinceIso))
                handleOne(c);
            sinceMs = now;
        }
        catch (Throwable e) {
            log.warn("PR command poll failed, comments since {} are read again next time: {}", sinceIso,
                e.toString());
        }

        handled.values().removeIf(t -> t < now - HANDLED_MEMORY_MS);
        hinted.values().removeIf(t -> t < now - HANDLED_MEMORY_MS);
        onboardedPrs.values().removeIf(t -> t < now - ONBOARDING_MEMORY_MS);
        strangerCommands.values().removeIf(t -> t < now - ONBOARDING_MEMORY_MS);
    }

    /** One comment's trouble stays with it: the rest of the window is still read, and the window moves on. */
    private void handleOne(GithubClient.IssueComment c) {
        try {
            handle(c);
        }
        catch (RuntimeException e) {
            log.warn("comment {} skipped: {}", c.id(), e.toString());
        }
    }

    private void handle(GithubClient.IssueComment c) {
        if (c.user() == null || c.body() == null || handled.containsKey(c.id()))
            return;

        Matcher m = c.htmlUrl() == null ? null : PR_URL.matcher(c.htmlUrl());
        if (m == null || !m.find())
            return;

        Cmd cmd = command(c.body());
        if (cmd == null) {
            if (triesCommand(c.body()))
                hint(c, Integer.parseInt(m.group(1)));

            return;
        }

        handled.put(c.id(), System.currentTimeMillis());
        if (writtenBefore(c, System.currentTimeMillis() - COMMAND_MAX_AGE_MS)) {
            log.info("{} by {} in comment {} not run: the comment was written at {} and edited since",
                cmd.name(), c.user().login(), c.id(), c.createdAt());

            return;
        }

        int pr = Integer.parseInt(m.group(1));
        Optional<StandingVisas.GhActor> actor = standing.actorByGhLogin(c.user().login());
        if (actor.isEmpty() || !standing.commandsOn(actor.get().username())) {
            onboard(pr, c.id(), c.user().login(), cmd.name());

            return;
        }
        if (standing.tcTokenRejected(actor.get().username())) {
            tcTokenRefused(pr, c, actor.get());

            return;
        }
        if (cmd.name().equals("/top")) {
            top(c, actor.get(), pr);

            return;
        }
        boolean pat = actor.get().ghToken() != null;
        TcModel.Build build = null;
        try {
            // A new commanded run supersedes the commander's previous chain on this PR: cancel it
            // first (their OWN chains only) — on unchanged revisions the new chain reuses the
            // finished suites, so nothing useful is lost, and the queue isn't paid twice.
            String user = actor.get().username();
            int chains = standing.asUser(user, () -> tc.cancelOwnRunAllChains(actor.get().tcToken(), pr, user));
            int reruns = standing.cancelWaves(user, actor.get().tcToken(), pr);

            // Style first, trigger second: the fix commit must be the revision the chain builds
            // (the autofix pushes under the user's PAT, so it needs one).
            String styleNote = pat && standing.styleFixOn(actor.get().username())
                ? styleFix.fixForCommand(pr, actor.get(), c.user().login()) : null;

            build = standing.asUser(user, () -> tc.triggerRunAll(actor.get().tcToken(), pr, cmd.top()));
            tracker.record(pr, build);
            endStoriesOf(user, pr, actor.get(), "\n🛑 _Superseded by "
                + (c.htmlUrl() == null ? "a newer /run-all" : "[a newer /run-all](" + c.htmlUrl() + ")") + "._");
            handledTotal.incrementAndGet();
            react(actor.get(), c.id(), "rocket");

            long buildId = build.id();
            String link = build.webUrl() == null || build.webUrl().isBlank()
                ? "build " + buildId : "[build " + buildId + "](" + build.webUrl() + ")";
            String ack = (styleNote == null ? "" : styleNote + "\n")
                + "🚀 **RunAll queued" + (cmd.top() ? " at the top of the queue" : "")
                + "** — " + link + " · live progress & verdict: [Ignite PR Checker](" + publicUrl + "/?pr=" + pr
                + ")."
                + superseded(chains, reruns)
                + " When the run finishes, this comment links the verdict.";

            if (pat && !standing.ghTokenMissing(actor.get().username())) {
                // Their own PAT: the ack lives inside their command comment. A comment edited in the
                // browser comes back with CRLF line breaks, the hint's separator included. An edit that
                // failed for another reason than the token is made again by the next look at the run.
                String base = c.body().replace("\r\n", "\n").replace(SEPARATOR + NEAR_MISS_HINT, "") + SEPARATOR + ack;
                if (edit(actor.get(), c.id(), base) || !standing.ghTokenRejected(user))
                    watching.put(c.id(), new CommandRun(c.id(), base, buildId, user, 0, false, pr));
                else
                    appNarrate(pr, c, actor.get(), ack + PAT_REJECTED_NOTE, buildId);
            }
            else {
                // No PAT: the checker narrates from its own account in a separate living comment.
                appNarrate(pr, c, actor.get(), ack, buildId);
            }

            log.info("/run-all by {} ({}): RunAll queued for PR {}{}{}", c.user().login(), actor.get().username(),
                pr, cmd.top() ? " (top)" : "", pat ? "" : " (app-narrated)");
        }
        catch (Throwable e) {
            // Throwable: an Error escaping here once took the whole command poll down with it.
            log.warn("/run-all by {} for PR {} failed{}: {}", c.user().login(), pr,
                build == null ? "" : " after RunAll " + build.id() + " was queued", e.toString());
            if (build != null)
                return; // queued and acked: only the story around it is missing
            if (standing.tcTokenRejected(actor.get().username()))
                tcTokenRefused(pr, c, actor.get());
            else {
                react(actor.get(), c.id(), "confused");
                explain(actor.get(), c, pr, "🚀 _Nothing was queued: " + failure(e) + "._");
            }
        }
    }

    /** What the ack says of the commander's chains and re-runs the new run cancelled; empty when there were none. */
    private static String superseded(int chains, int reruns) {
        if (chains > 0)
            return reruns > 0
                ? " Your previous run and your earlier re-runs were cancelled — this one supersedes them."
                : " Your previous run was cancelled — this one supersedes it.";

        return reruns > 0 ? " The re-runs of your previous run were cancelled — this one supersedes it." : "";
    }

    /** Why a call failed, in a few words a commander can act on. */
    private static String failure(Throwable e) {
        return (e instanceof RestClientResponseException rest
            ? "the request failed with HTTP " + rest.getStatusCode().value() : "the checker hit an error")
            + ". Try again in a few minutes";
    }

    /**
     * A participant's comment that tries a command but does not open with it gets a 😕 and a hint, once.
     * Strangers get nothing: the thread is public, and the hint is only for those who use commands.
     */
    private void hint(GithubClient.IssueComment c, int pr) {
        long now = System.currentTimeMillis();
        if (hinted.containsKey(c.id()) || writtenBefore(c, now - COMMAND_MAX_AGE_MS))
            return;

        Optional<StandingVisas.GhActor> actor = standing.actorByGhLogin(c.user().login());
        if (actor.isEmpty() || !standing.commandsOn(actor.get().username()))
            return;

        hinted.put(c.id(), now);
        react(actor.get(), c.id(), "confused");
        explain(actor.get(), c, pr, NEAR_MISS_HINT);
        log.info("comment {} by {} on PR {} tries a command without opening with it: hinted", c.id(),
            c.user().login(), pr);
    }

    /**
     * Tells the commander in words why nothing happened: inside their own comment when the checker may
     * edit it, else in a reply from the checker's account.
     */
    private void explain(StandingVisas.GhActor actor, GithubClient.IssueComment c, int pr, String text) {
        if (actor.ghToken() != null && edit(actor, c.id(), c.body() + SEPARATOR + text))
            return;

        String reply = "@" + c.user().login() + " " + text;
        if (readsAsCommand(reply))
            throw new IllegalStateException("the checker must never post a comment that reads as a command");
        try {
            github.addPrCommentAsApp(pr, reply);
        }
        catch (RuntimeException e) {
            log.warn("explaining to {} on PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    /** Whether the comment was written before the moment; one GitHub gives no date for counts as new. */
    private static boolean writtenBefore(GithubClient.IssueComment c, long epochMs) {
        if (c.createdAt() == null)
            return false;

        try {
            return Instant.parse(c.createdAt()).toEpochMilli() < epochMs;
        }
        catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /**
     * TeamCity no longer accepts the token the checker keeps for this commander, so nothing can run.
     * A bare 😕 left them guessing; the reason goes into the thread in words, once per refusal.
     */
    private void tcTokenRefused(int pr, GithubClient.IssueComment c, StandingVisas.GhActor actor) {
        react(actor, c.id(), "confused");
        long at = standing.tcRejectedAt(actor.username());
        Long told = toldTcRefused.put(actor.username(), at);
        if (told != null && told == at)
            return;

        try {
            github.addPrCommentAsApp(pr, "@" + c.user().login() + " nothing was queued: TeamCity no longer accepts"
                + " the token the checker stores for you (expired or revoked). Log in at " + publicUrl
                + " with a fresh TeamCity token: it replaces the stored one, and your commands and options resume.");
        }
        catch (RuntimeException e) {
            log.warn("telling {} about their refused TeamCity token failed: {}", c.user().login(), e.toString());
        }
    }

    /** Whether this text would be picked up as a command — the guard on everything the checker posts. */
    static boolean readsAsCommand(String body) {
        return command(body) != null;
    }

    /**
     * Whether a short first line names a command without opening with it — "Please /run-all",
     * "`/run-all`", "/run all", "/run-all." — which is someone trying a command, not mentioning one.
     * A quoted line ("> /run-all" of a Quote reply) repeats someone else's command.
     */
    static boolean triesCommand(String body) {
        String line = firstLine(body).toLowerCase(Locale.ROOT);

        return command(body) == null && !line.startsWith(">") && line.split("\\s+").length <= NEAR_MISS_MAX_WORDS
            && NEAR_MISS.matcher(line).find();
    }

    private static Cmd command(String body) {
        String[] words = firstLine(body).toLowerCase(Locale.ROOT).split("\\s+");
        if (words[0].equals("/top"))
            return new Cmd("/top", true);
        if (!words[0].equals("/run-all") && !words[0].equals("/runall"))
            return null;

        return new Cmd("/run-all", Arrays.stream(words).skip(1).anyMatch(w -> w.equals("top") || w.equals("--top")));
    }

    private static String firstLine(String body) {
        return body.strip().lines().findFirst().orElse("").strip();
    }

    /**
     * Promotes the run STARTED BY THE AUTHOR'S OWN COMMAND to the top of the queue — top belongs to
     * the command, so someone else's builds on the same PR are never touched.
     */
    private void top(GithubClient.IssueComment c, StandingVisas.GhActor actor, int pr) {
        try {
            CommandRun run = watching.values().stream()
                .filter(r -> r.pr() == pr && r.username().equals(actor.username()))
                .max(java.util.Comparator.comparingLong(CommandRun::buildId)).orElse(null);
            if (run == null) {
                react(actor, c.id(), "confused");
                explain(actor, c, pr, "⬆️ _Nothing moved: `/top` moves the RunAll that your own `/run-all` started"
                    + " on this PR, and none is in progress._");
                log.info("/top by {} for PR {}: no commanded run of theirs to promote", c.user().login(), pr);

                return;
            }

            var b = standing.asUser(actor.username(), () -> tc.getBuildState(actor.tcToken(), run.buildId()));
            if (b == null || !"queued".equalsIgnoreCase(b.state())) {
                react(actor, c.id(), "confused");
                String where = b == null ? "not found"
                    : "finished".equalsIgnoreCase(b.state()) ? "finished" : "already running";
                explain(actor, c, pr, "⬆️ _Nothing moved: build " + run.buildId() + " is " + where
                    + ", and `/top` only moves a build that still waits in the queue._");
                log.info("/top by {} for PR {}: build {} is not queued", c.user().login(), pr, run.buildId());

                return;
            }

            standing.asUser(actor.username(), () -> {
                tc.moveToQueueTop(actor.tcToken(), run.buildId());

                return null;
            });
            handledTotal.incrementAndGet();
            react(actor, c.id(), "rocket");
            if (actor.ghToken() != null)
                edit(actor, c.id(), c.body() + SEPARATOR
                    + "⬆️ **Build " + run.buildId() + " moved to the top of the queue.**");
            log.info("/top by {} ({}): build {} of PR {} moved to the queue top",
                c.user().login(), actor.username(), run.buildId(), pr);
        }
        catch (RuntimeException e) {
            if (standing.tcTokenRejected(actor.username()))
                tcTokenRefused(pr, c, actor);
            else {
                react(actor, c.id(), "confused");
                explain(actor, c, pr, "⬆️ _Nothing moved: " + failure(e) + "._");
            }
            log.warn("/top by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    /**
     * A command from someone without PR commands gets a short reply saying how to switch them on. It
     * goes into public threads, so it is rationed: once per person, once per PR, a few a day; and a
     * repeat on the same PR gets no second 😕.
     */
    private void onboard(int pr, long commentId, String login, String cmd) {
        long now = System.currentTimeMillis();
        if (strangerCommands.putIfAbsent(login.toLowerCase(java.util.Locale.ROOT) + "#" + pr, now) == null) {
            try {
                github.reactToCommentAsApp(commentId, "confused");
            }
            catch (RuntimeException e) {
                log.warn("confused reaction for {} failed: {}", login, e.toString());
            }
        }

        boolean dailyCapReached = onboarded.values().stream().filter(t -> t > now - 24 * 3600_000L).count()
            >= ONBOARDING_PER_DAY;
        if (onboarded.containsKey(login) || onboardedPrs.containsKey(pr) || dailyCapReached) {
            log.info("{} by {} on PR {} ignored: PR commands are not on for them (no reply: {})", cmd, login, pr,
                onboarded.containsKey(login) ? "already told" : onboardedPrs.containsKey(pr) ? "PR already has one"
                    : "daily limit");

            return;
        }

        try {
            String tcTokens = tcBaseUrl + "/profile.html?item=accessTokens";
            boolean sent = github.addPrCommentAsApp(pr,
                "@" + login + " nothing was queued: [Ignite PR Checker](" + publicUrl + ") runs commands under your"
                + " own TeamCity account, and PR commands are not switched on for your GitHub login. To switch them"
                + " on, log in at " + publicUrl + " with a [TeamCity access token](" + tcTokens + "), open ⚙, tick"
                + " **PR commands** and enter your GitHub login. Then `/run-all` here queues RunAll (`/run-all top`"
                + " at the top of the queue), and `/top` moves it up while it waits.");
            if (sent) {
                onboarded.put(login, now);
                onboardedPrs.put(pr, now);
            }
            log.info("{} by {}: PR commands not on, onboarding reply {}", cmd, login,
                sent ? "posted" : "skipped (no app token)");
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
            if (!patRejected(actor, e)) {
                log.warn("reaction on comment {} failed: {}", commentId, e.toString());

                return;
            }

            try {
                github.reactToCommentAsApp(commentId, content);
            }
            catch (RuntimeException app) {
                log.warn("reaction on comment {} failed under both tokens: {}", commentId, app.toString());
            }
        }
    }

    /**
     * Whether GitHub refused the user's own token — a PAT that expired or was revoked. It is dropped
     * on the spot, so the checker stops retrying a dead credential every minute and switches to its
     * own account instead of leaving the author with a silent PR.
     */
    private boolean patRejected(StandingVisas.GhActor actor, RuntimeException e) {
        if (actor.ghToken() == null || !(e instanceof RestClientResponseException rest))
            return false;

        int code = rest.getStatusCode().value();
        if (code != 401 && code != 403)
            return false;

        standing.dropGhToken(actor.username());

        return true;
    }

    /** The line that explains a suddenly app-narrated run — stated once, where the author is looking. */
    private static final String PAT_REJECTED_NOTE =
        "\n\n⚠️ _GitHub rejected your personal access token, so this is narrated from the checker's own "
            + "account. Save a fresh PAT in the checker's settings to get your own back._";

    /** The same when the token went for another reason: the option that needs it was switched off. */
    private static final String TOKEN_GONE_NOTE =
        "\n\n_The checker no longer holds your GitHub token, so this is narrated from its own account._";

    /**
     * Edits the narration only when it actually changed — no no-op revisions. The narration lives INSIDE
     * the command comment itself (the author's own comment, edited with their own PAT), or in the
     * checker's own comment for PAT-less commanders — still zero extra messages in the thread. True when
     * the comment shows {@code body}; a failed edit is not remembered, so the next look tries it again.
     */
    private boolean narrate(int pr, StandingVisas.GhActor actor, CommandRun run, String body) {
        if (body.equals(lastNarration.get(run.commentId())))
            return true;

        boolean shownNow = show(pr, actor, run, body);
        if (shownNow)
            lastNarration.put(run.commentId(), body);

        return shownNow;
    }

    private boolean show(int pr, StandingVisas.GhActor actor, CommandRun run, String body) {
        // The token was dropped after this run started narrating under it: move the story to the
        // checker's own comment now, instead of editing with a token that no longer exists.
        if (!run.app() && actor.ghToken() == null)
            return takeOverNarration(pr, run, body);

        try {
            if (run.app())
                return github.updatePrCommentAsApp(run.narrationId(), body);

            github.updatePrComment(actor.ghToken(), run.commentId(), body);

            return true;
        }
        catch (RuntimeException e) {
            if (!patRejected(actor, e)) {
                log.warn("editing narration for PR {} failed: {}", pr, e.toString());

                return false;
            }

            // Their token died mid-run: carry the story on from the checker's own account rather
            // than repeating the same 401 every minute for the rest of the chain.
            return takeOverNarration(pr, run, body);
        }
    }

    private boolean takeOverNarration(int pr, CommandRun run, String body) {
        // The story so far lives inside the author's own command comment, so `body` still carries
        // their "/runall" first line. Posting that verbatim made the poll read the checker's own
        // comment as a fresh command and trigger another chain — the status half is all that moves.
        String status = statusOf(body);
        String login = standing.ghLoginOf(run.username());
        String mention = login == null || login.isBlank() ? "" : "@" + login + " ";
        String why = standing.ghTokenRejected(run.username()) ? PAT_REJECTED_NOTE : TOKEN_GONE_NOTE;
        GithubClient.PostedComment n = github.addPrCommentAsAppWithId(pr, mention + status + why);
        if (n == null)
            return false;

        watching.put(run.commentId(), new CommandRun(run.commentId(), mention + status, run.buildId(),
            run.username(), n.id(), true, pr));
        log.info("PR {}: narration taken over by the checker's account — {}'s GitHub token is gone",
            pr, run.username());

        return true;
    }

    /** The checker's half of a command comment — everything after the separator the ack inserts. */
    static String statusOf(String body) {
        int sep = body.indexOf("\n---\n");

        return sep < 0 ? body : body.substring(sep + 5);
    }

    /** Starts (or takes over) the checker-narrated living comment for this command. */
    private void appNarrate(int pr, GithubClient.IssueComment c, StandingVisas.GhActor actor, String ack, long buildId) {
        String body = "@" + c.user().login() + " " + ack;
        if (readsAsCommand(body))
            throw new IllegalStateException("the checker must never post a comment that reads as a command");
        GithubClient.PostedComment n = github.addPrCommentAsAppWithId(pr, body);
        if (n != null)
            watching.put(c.id(), new CommandRun(c.id(), body, buildId, actor.username(), n.id(), true, pr));
    }

    /** Edits the commander's own comment under their PAT; false when the token turned out to be dead. */
    private boolean edit(StandingVisas.GhActor actor, long commentId, String body) {
        try {
            github.updatePrComment(actor.ghToken(), commentId, body);

            return true;
        }
        catch (RuntimeException e) {
            if (!patRejected(actor, e))
                log.warn("editing command comment {} failed: {}", commentId, e.toString());

            return false;
        }
    }

    /** Last narration rendered per command comment — identical re-edits are skipped so the minute-level
     * cadence doesn't flood the comment's edit history with no-op revisions. */
    private final ConcurrentMap<Long, String> lastNarration = new ConcurrentHashMap<>();

    /** The stage and estimate each narration last showed — what decides whether a new one is worth an edit. */
    private final ConcurrentMap<Long, Shown> shown = new ConcurrentHashMap<>();

    /** An estimate has to move this much before the comment is edited for it. */
    private static final long ETA_SHIFT_SEC = 10 * 60;

    /**
     * Looks at every accepted command's chain once a minute; its comment is edited when the stage
     * changes (queued, running, finished, a re-run wave) or the expected finish moves noticeably.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void updateEtas() {
        watching.values().forEach(this::look);
    }

    /** One look at a narrated run; every story ends with a last line, whatever ends it. */
    private void look(CommandRun run) {
        int pr = run.pr();
        Optional<StandingVisas.GhActor> actor = standing.actor(run.username());
        if (actor.isEmpty()) {
            // Their options went, and their token with them: only the checker's own comment can still be told.
            if (run.app())
                endAppStory(run, "\n🛑 _No longer followed: the options of the user who started it were switched off._");
            stopNarrating(run);

            return;
        }
        if (standing.tcTokenRejected(run.username()))
            return; // narration resumes with a working token

        try {
            var b = standing.asUser(run.username(), () -> tc.getBuildState(actor.get().tcToken(), run.buildId()));
            if (b == null)
                return;

            if ("finished".equalsIgnoreCase(b.state())) {
                if ("UNKNOWN".equalsIgnoreCase(b.status())) {
                    narrate(pr, actor.get(), run, run.baseBody() + "\n🛑 _Run cancelled._");
                    stopNarrating(run);

                    return;
                }

                // The command comment narrates the whole story: after the chain finishes it keeps
                // reporting the blocker/broken auto re-run waves and only closes once the verdict
                // has actually landed — or right away when nothing settles the user's runs.
                if (standing.buildHandled(run.username(), pr, run.buildId())
                    || !standing.settlesRuns(run.username())) {
                    narrate(pr, actor.get(), run, run.baseBody() + "\n🏁 _Run finished — "
                        + verdictLink(run, pr, "see the verdict") + "."
                        + (standing.visaOn(run.username()) && standing.settledWithoutTicket(run.buildId())
                            ? " No JIRA visa: the PR title names no IGNITE ticket." : "") + "_");
                    stopNarrating(run);

                    return;
                }

                Optional<StandingVisas.WaveStatus> w = standing.waveStatus(pr, run.buildId());
                if (w.isEmpty()) {
                    Optional<StandingVisas.RunEnd> end = standing.runEnd(pr, run.buildId());
                    if (end.isPresent()) {
                        narrate(pr, actor.get(), run, run.baseBody() + endLine(end.get(), pr));
                        stopNarrating(run);

                        return;
                    }
                    standing.settleRequested(pr);
                }

                String stage = w.map(s -> "wave " + s.wave() + ": " + s.what()).orElse("analysing")
                    + (standing.verdictCommentId(run.username(), pr, run.buildId()).isPresent() ? ", linked" : "");
                long settleAt = w.map(StandingVisas.WaveStatus::etaEpochSec).orElse(-1L);
                if (!worthEditing(run, stage, settleAt))
                    return;

                String line = w.isEmpty()
                    ? "\n🏁 _Run finished — analysing; the verdict follows " + (standing.ghOn(run.username())
                        ? "in its own comment._" : "on " + verdictLink(run, pr, "the checker's page") + "._")
                    : "\n🏁 _Run finished._ ♻️ _Auto re-run **#" + w.get().wave() + "** — " + w.get().what()
                        + (settleAt < 0 ? "" : ", **≈ settled by " + wallClock(settleAt, actor.get().tz()) + "**")
                        + " — " + verdictLink(run, pr, "details") + "._";
                if (narrate(pr, actor.get(), run, run.baseBody() + line))
                    shown.put(run.commentId(), new Shown(run.buildId(), stage, settleAt));

                return; // keep narrating until the verdict lands
            }

            long left = tc.chainRemainingSeconds(actor.get().tcToken(), run.buildId(),
                baseline.durations(actor.get().tcToken()));
            long finishAt = left < 0 ? -1 : System.currentTimeMillis() / 1000 + left;
            boolean queued = "queued".equalsIgnoreCase(b.state());
            String stage = queued ? "queued" : "running";
            if (worthEditing(run, stage, finishAt)
                && narrate(pr, actor.get(), run, run.baseBody() + "\n⏱ _" + (queued ? "Queued" : "Running")
                    + (finishAt < 0 ? " — no finish estimate yet._"
                        : " — expected to finish **≈ " + wallClock(finishAt, actor.get().tz()) + "**._")
                    + (queued ? " _Reply `/top` to jump the queue._" : "")))
                shown.put(run.commentId(), new Shown(run.buildId(), stage, finishAt));
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                log.warn("ETA update for PR {} failed: {}", pr, e.toString());

                return;
            }

            narrate(pr, actor.get(), run, run.baseBody() + "\n🛑 _TeamCity no longer has build " + run.buildId()
                + ", so this story ends here._");
            stopNarrating(run);
        }
        catch (RuntimeException e) {
            log.warn("ETA update for PR {} failed: {}", pr, e.toString());
        }
    }

    /** The last line of a finished run that will not be settled: a newer RunAll replaced it, or the PR closed. */
    private String endLine(StandingVisas.RunEnd end, int pr) {
        String page = "[the checker's page](" + publicUrl + "/?pr=" + pr + ")";
        if (end.replacedBy() > 0)
            return "\n🏁 _Run finished — RunAll [" + end.replacedBy() + "](" + tcBaseUrl + "/build/" + end.replacedBy()
                + ") replaced it before it settled; the newest verdict is on " + page + "._";

        return "\n🏁 _Run finished — the PR was " + (end.merged() ? "merged" : "closed")
            + " before the re-runs settled; the last known verdict is on " + page + "._";
    }

    /** Ends the checker's own narration comment with {@code line}; a failure leaves it as it was. */
    private void endAppStory(CommandRun run, String line) {
        try {
            github.updatePrCommentAsApp(run.narrationId(), run.baseBody() + line);
        }
        catch (RuntimeException e) {
            log.warn("ending the narration of PR {} failed: {}", run.pr(), e.toString());
        }
    }

    /**
     * Ends the stories of the user's runs of the PR with {@code line}: their new /run-all, queued now, replaces
     * those runs, whose chains and waves it cancelled.
     */
    private void endStoriesOf(String user, int pr, StandingVisas.GhActor actor, String line) {
        for (CommandRun old : watching.values()) {
            if (old.pr() != pr || !old.username().equals(user))
                continue;

            try {
                if (old.app())
                    github.updatePrCommentAsApp(old.narrationId(), old.baseBody() + line);
                else if (actor.ghToken() != null)
                    github.updatePrComment(actor.ghToken(), old.commentId(), old.baseBody() + line);
            }
            catch (RuntimeException ignored) {
                // closing the old narration is a courtesy, never a blocker
            }
            stopNarrating(old);
        }
    }

    /**
     * A link to the verdict of the run: the user's own verdict comment once it is posted for this very
     * chain, else the checker's page of the PR.
     */
    private String verdictLink(CommandRun run, int pr, String text) {
        java.util.OptionalLong comment = standing.verdictCommentId(run.username(), pr, run.buildId());

        return "[" + text + "](" + (comment.isPresent() ? github.commentUrl(pr, comment.getAsLong())
            : publicUrl + "/?pr=" + pr) + ")";
    }

    private void stopNarrating(CommandRun run) {
        watching.remove(run.commentId());
        lastNarration.remove(run.commentId());
        shown.remove(run.commentId());
    }

    /**
     * Whether a line carrying an estimate is worth an edit: only a new stage, or an estimate that
     * moved by {@link #ETA_SHIFT_SEC} or more. GitHub keeps every edit in the comment's history, and
     * re-estimating every minute buried the author's own edits under a hundred of the checker's.
     * {@code estimateEpochSec} is -1 when there is none; an unknown estimate never replaces a known one.
     */
    private boolean worthEditing(CommandRun run, String stage, long estimateEpochSec) {
        Shown last = shown.get(run.commentId());
        boolean sameStage = last != null && last.buildId() == run.buildId() && last.stage().equals(stage);
        boolean sameEstimate = estimateEpochSec < 0
            || last != null && last.estimateEpochSec() >= 0
            && Math.abs(estimateEpochSec - last.estimateEpochSec()) < ETA_SHIFT_SEC;

        return !sameStage || !sameEstimate;
    }

    /**
     * A wall-clock time in the AUTHOR'S timezone (their JIRA-profile one — GitHub exposes none); UTC
     * when unknown, so the stamp is honest either way.
     */
    private static String wallClock(long epochSec, String tz) {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        if (tz != null) {
            try {
                zone = java.time.ZoneId.of(tz);
            }
            catch (java.time.DateTimeException ignored) {
                // an unparsable profile timezone falls back to UTC
            }
        }

        return Instant.ofEpochSecond(epochSec).atZone(zone)
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
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Map<Integer, CommandRun> newestPerPr = new HashMap<>();
        watching.values().forEach(r -> newestPerPr.merge(r.pr(), r, (a, b) -> a.buildId() >= b.buildId() ? a : b));
        Snapshots.writeAtomic(mapper, file, new Persisted(sinceMs, new HashMap<>(handled), handledTotal.get(),
            newestPerPr, new HashMap<>(onboarded), new HashMap<>(onboardedPrs),
            new HashMap<>(strangerCommands), new HashMap<>(toldTcRefused), new HashMap<>(hinted),
            List.copyOf(watching.values())));
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
        if (p.narrations() != null)
            p.narrations().forEach(r -> watching.put(r.commentId(), r));
        if (p.watching() != null)
            p.watching().forEach((pr, r) -> watching.putIfAbsent(r.commentId(), r.ofPr(pr)));
        if (p.onboarded() != null)
            onboarded.putAll(p.onboarded());
        if (p.onboardedPrs() != null)
            onboardedPrs.putAll(p.onboardedPrs());
        if (p.strangerCommands() != null)
            strangerCommands.putAll(p.strangerCommands());
        if (p.toldTcRefused() != null)
            toldTcRefused.putAll(p.toldTcRefused());
        if (p.hinted() != null)
            hinted.putAll(p.hinted());
    }

    /**
     * pr-commands.json. {@code narrations} holds every run being told; {@code watching}, one per PR, its newest,
     * is what v1.20.11 and before read and wrote.
     */
    private record Persisted(long sinceMs, Map<Long, Long> handled, int handledTotal,
        Map<Integer, CommandRun> watching, Map<String, Long> onboarded, Map<Integer, Long> onboardedPrs,
        Map<String, Long> strangerCommands, Map<String, Long> toldTcRefused, Map<Long, Long> hinted,
        List<CommandRun> narrations) {
    }

    /** An accepted command still being narrated: where its comment is, which chain it watches, and —
     * for PAT-less commanders — the checker's own narration comment that gets edited instead. The PR came
     * from the key of v1.20.11's snapshot, which has it in no field. */
    private record CommandRun(long commentId, String baseBody, long buildId, String username,
        long narrationId, boolean app, int pr) {
        CommandRun ofPr(int number) {
            return new CommandRun(commentId, baseBody, buildId, username, narrationId, app, number);
        }
    }

    /** What a narration last showed: for which chain, at which stage, with which estimate (-1: none). */
    private record Shown(long buildId, String stage, long estimateEpochSec) {
    }

    /** A parsed command: the canonical name and whether "top" was asked for. */
    private record Cmd(String name, boolean top) {
    }
}
