package com.github.igniteprchecker.github;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.Markup;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.QueuedBelowTopException;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.TcResponseException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Slash commands in PR comments: an enrolled user (PR commands on) comments {@code /run-all} on a pull request and
 * the whole RunAll chain is queued under their own TeamCity token. The checker answers from its own GitHub account,
 * the app account, whatever token the user saved: a 🚀 on the command, and a comment of its own that tells the run
 * until its verdict is in. The poll is ONE repo-wide comments request a minute (more pages only after a gap), so
 * working from the PR costs nothing extra per PR. In a PR of the Python thin client the same commands run the client's
 * one suite instead (see {@link PythonClientRuns}): its comments cost one more request a minute.
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

    /** What a participant is told when a comment tries a command without opening with it. */
    private static final String NEAR_MISS_HINT = "💡 _Nothing was run: a command has to be the very first word of"
        + " the comment, as is — `/run-all`, `/run-all top` or `/top`. Post it that way in a new comment._";

    /** At most this many replies on what a person lacks for a command go to one person in any 24 hours. */
    private static final int EXPLAINED_PER_DAY = 3;

    /** At most this many such replies go to the GitHub accounts nobody linked, all together, in any 24 hours. */
    private static final int UNLINKED_PER_DAY = 5;

    /** The case of a command from a GitHub account nobody linked. */
    private static final String UNLINKED = "link";

    /** How long a reply on what a person lacks keeps the same case on the same PR from another. */
    private static final long EXPLAINED_MEMORY_MS = 30 * 24 * 3600_000L;

    /** A /top command. */
    private static final Cmd TOP = new Cmd("/top", true);

    /** How long a reaction or reply that GitHub did not take is tried again: a day later it would only confuse. */
    private static final long OUTBOX_MS = 24 * 3600_000L;

    /** How long after the start the first poll is due. */
    public static final long FIRST_POLL_MS = 30_000;

    private final ObjectMapper mapper;
    private final GithubClient github;
    private final StandingVisas standing;
    private final TcClient tc;
    private final RerunTracker tracker;
    private final StyleFixService styleFix;
    private final com.github.igniteprchecker.analysis.SuiteBaseline baseline;

    /** The runs of the Python thin client's suite for its PRs; null when the client's PRs are not served. */
    private final PythonClientRuns python;

    private volatile long sinceMs = System.currentTimeMillis();

    /** The same for the comments of the Python thin client's repo, which are read apart. */
    private volatile long clientSinceMs = System.currentTimeMillis();
    /** Handled comment ids -> when; survives restarts so a redeploy can't double-trigger. */
    private final ConcurrentMap<Long, Long> handled = new ConcurrentHashMap<>();
    /**
     * Accepted commands whose runs are still being told, by command comment: two people's /run-all on one PR,
     * or a run and the one that replaced it, each get their story to the end.
     */
    private final ConcurrentMap<Long, CommandRun> watching = new ConcurrentHashMap<>();
    /**
     * The replies on what a commander lacks, by "login#pr#case", and when they went: one per person, PR and case.
     * Survives restarts, as what they lack does.
     */
    private final ConcurrentMap<String, Long> explained = new ConcurrentHashMap<>();
    /** Comments that tried a command without opening with it, already answered with a hint. */
    private final ConcurrentMap<Long, Long> hinted = new ConcurrentHashMap<>();
    /** Reactions and replies GitHub did not take when they were made, by what they are; tried again at each poll. */
    private final ConcurrentMap<String, Reply> outbox = new ConcurrentHashMap<>();
    private final AtomicInteger handledTotal = new AtomicInteger();
    private volatile long lastPollAt;

    /** Off in the dev profile, so a local run never acts on PR comments next to the production instance. */
    @Value("${automation.enabled:true}")
    private boolean automation = true;

    private final String publicUrl;
    private final String tcBaseUrl;

    /** Commands in the PRs of the repo the checker lists only. */
    public PrCommands(ObjectMapper mapper, GithubClient github, StandingVisas standing, TcClient tc,
        RerunTracker tracker, StyleFixService styleFix,
        com.github.igniteprchecker.analysis.SuiteBaseline baseline, String publicUrl, TeamcityProperties teamcity) {
        this(mapper, github, standing, tc, tracker, styleFix, baseline, publicUrl, teamcity, null);
    }

    @Autowired
    public PrCommands(ObjectMapper mapper, GithubClient github, StandingVisas standing, TcClient tc,
        RerunTracker tracker, StyleFixService styleFix,
        com.github.igniteprchecker.analysis.SuiteBaseline baseline,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl,
        TeamcityProperties teamcity, PythonClientRuns python) {
        this.mapper = mapper;
        this.github = github;
        this.standing = standing;
        this.tc = tc;
        this.tracker = tracker;
        this.styleFix = styleFix;
        this.baseline = baseline;
        this.python = python;
        this.publicUrl = publicUrl;
        String base = teamcity.baseUrl() == null ? "https://ci2.ignite.apache.org" : teamcity.baseUrl();
        this.tcBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Reads the comments updated since the last poll that worked, a few minutes more to be safe. The
     * window moves on only once it was read: when GitHub fails, the next poll reads it again. Without an account to
     * answer from there are no PR commands. The logins earlier versions kept are proven first, also for users with
     * no PR commands: the verdict comment mentions only a proven login.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = FIRST_POLL_MS)
    void poll() {
        long now = System.currentTimeMillis();
        lastPollAt = now;
        if (!automation)
            return;

        sendOutbox(now);
        boolean commanded = github.writes() && standing.anyGhEnrolled();
        if (commanded || standing.anyGhLoginToAsk())
            standing.ensureGhLogins();
        if (!commanded) {
            sinceMs = now; // nobody's comments are commands: they must not run once someone switches commands on
            clientSinceMs = now;

            return;
        }

        String sinceIso = window(sinceMs, now);
        try {
            for (GithubClient.IssueComment c : github.recentIssueComments(sinceIso))
                handleOne(c, null);
            sinceMs = now;
        }
        catch (Throwable e) {
            log.warn("PR command poll failed, comments since {} are read again next time: {}", sinceIso,
                e.toString());
        }
        if (python != null)
            pollClient(now);

        handled.values().removeIf(t -> t < now - HANDLED_MEMORY_MS);
        hinted.values().removeIf(t -> t < now - HANDLED_MEMORY_MS);
        explained.values().removeIf(t -> t < now - EXPLAINED_MEMORY_MS);
    }

    /**
     * Reads the comments of the Python thin client's repo as {@link #poll} reads the checker's: one more call, with a
     * window of its own, so a failure in one repo does not make the other read its window again.
     */
    private void pollClient(long now) {
        String sinceIso = window(clientSinceMs, now);
        try {
            for (GithubClient.IssueComment c : github.recentIssueComments(python.repo(), sinceIso))
                handleOne(c, python.repo());
            clientSinceMs = now;
        }
        catch (Throwable e) {
            log.warn("PR command poll of {} failed, comments since {} are read again next time: {}", python.repo(),
                sinceIso, e.toString());
        }
    }

    /** Where a poll starts reading: a little before the end of the last one that worked, an hour back at most. */
    private static String window(long since, long now) {
        return Instant.ofEpochMilli(Math.max(since - OVERLAP_MS, now - MAX_LOOKBACK_MS))
            .truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * One comment's trouble stays with it: the rest of the window is still read, and the window moves on. {@code repo}
     * is the Python thin client's for its comments, null for those of the repo the checker lists.
     */
    private void handleOne(GithubClient.IssueComment c, String repo) {
        try {
            handle(c, repo);
        }
        catch (RuntimeException e) {
            log.warn("comment {} skipped: {}", c.id(), e.toString());
        }
    }

    private void handle(GithubClient.IssueComment c, String repo) {
        if (c.user() == null || c.body() == null || handled.containsKey(c.id()))
            return;

        Matcher m = c.htmlUrl() == null ? null : PR_URL.matcher(c.htmlUrl());
        if (m == null || !m.find())
            return;

        PrRef pr = new PrRef(repo, Integer.parseInt(m.group(1)));
        Cmd cmd = command(c.body());
        if (cmd == null) {
            if (triesCommand(c.body()))
                hint(c, pr);

            return;
        }

        handled.put(c.id(), System.currentTimeMillis());
        if (writtenBefore(c, System.currentTimeMillis() - COMMAND_MAX_AGE_MS)) {
            log.info("{} by {} in comment {} not run: the comment was written at {} and edited since",
                cmd.name(), c.user().login(), c.id(), c.createdAt());

            return;
        }

        Optional<StandingVisas.GhActor> actor = standing.actorByGhUser(c.user());
        if (actor.isEmpty()) {
            noActor(pr, c, cmd, standing.whyNoActor(c.user()));

            return;
        }
        if (!standing.commandsOn(actor.get().username())) {
            lacking(pr, c, actor.get().username(), cmd, "commands", lead(cmd) + ": **PR commands** are off for you."
                + " Switch them on in ⚙ at " + publicUrl + ", under **GitHub account**.");

            return;
        }
        if (edited(c)) {
            lacking(pr, c, actor.get().username(), cmd, "edited", lead(cmd)
                + ": the comment was edited. Post the command in a new comment.");

            return;
        }
        if (standing.tcTokenRejected(actor.get().username())) {
            tcTokenRefused(pr, c, actor.get(), cmd);

            return;
        }
        if (standing.firstCommand(actor.get().username()))
            firstCommand(pr, c, actor.get().username());
        if (cmd.name().equals("/top")) {
            top(c, actor.get(), pr);

            return;
        }
        if (!pr.ignite()) {
            runClient(c, actor.get(), pr, cmd);

            return;
        }
        String user = actor.get().username();
        int number = pr.number();
        TcModel.Build build = null;
        try {
            // A new commanded run supersedes the commander's previous chain on this PR: cancel it
            // first (their OWN chains only) — on unchanged revisions the new chain reuses the
            // finished suites, so nothing useful is lost, and the queue isn't paid twice.
            int chains = standing.asUser(user, () -> tc.cancelOwnRunAllChains(actor.get().tcToken(), number, user));
            int reruns = standing.cancelWaves(user, actor.get().tcToken(), number);

            // Style first, trigger second: the fix commit must be the revision the chain builds. It is pushed with the
            // user's own token, the one thing the checker does with it.
            String styleNote = actor.get().ghToken() != null && standing.styleFixOn(user)
                ? styleFix.fixForCommand(number, actor.get(), c.user().login())
                : standing.styleFixOn(user) ? styleFixWaits(user) : null;

            QueuedBelowTopException belowTop = null;
            try {
                build = standing.asUser(user, () -> tc.triggerRunAll(actor.get().tcToken(), number, cmd.top()));
            }
            catch (QueuedBelowTopException e) {
                build = e.build();
                belowTop = e;
            }
            tracker.record(number, build);
            endStoriesOf(user, pr, "\n🛑 _Superseded by "
                + (c.htmlUrl() == null ? "a newer /run-all" : "[a newer /run-all](" + c.htmlUrl() + ")") + "._");
            handledTotal.incrementAndGet();
            react(pr, user, c.id(), "rocket");

            long buildId = build.id();
            String link = build.webUrl() == null || build.webUrl().isBlank()
                ? "build " + buildId : "[build " + buildId + "](" + build.webUrl() + ")";
            String ack = (styleNote == null ? "" : styleNote + "\n\n")
                + "🚀 **RunAll queued"
                + (belowTop != null ? ", but not at the top" : cmd.top() ? " at the top of the queue" : "")
                + "** — " + link + " · live progress & verdict: [Ignite PR Checker](" + publicUrl + "/?pr=" + number
                + ")."
                + (belowTop == null ? "" : " " + notMovedUp("it", belowTop.status()) + ".")
                + superseded(chains, reruns)
                + " When the run finishes, a new comment brings you the verdict, and this story goes.";
            tell(pr, c, user, ack, styleNote, buildId, null);

            log.info("/run-all by {} ({}): RunAll queued for PR {}{}", c.user().login(), user, pr,
                belowTop != null ? " (top refused)" : cmd.top() ? " (top)" : "");
        }
        catch (Throwable e) {
            // Throwable: an Error escaping here once took the whole command poll down with it.
            log.warn("/run-all by {} for PR {} failed{}: {}", c.user().login(), pr,
                build == null ? "" : " after RunAll " + build.id() + " was queued", e.toString());
            if (build != null)
                return; // queued and acked: only the story around it is missing
            if (standing.tcTokenRejected(user))
                tcTokenRefused(pr, c, actor.get(), cmd);
            else {
                react(pr, user, c.id(), "confused");
                explain(pr, user, c, "🚀 _Nothing was queued: " + queueFailure(e) + "._");
            }
        }
    }

    /**
     * A {@code /run-all} in a PR of the Python thin client: the client's suite is queued under the commander's token,
     * with the PR's head and Ignite's master, and its story is told as a RunAll's is. The commander's own earlier runs
     * of the suite on the PR are cancelled first, as a new RunAll cancels the old. There is no checkstyle fix: the
     * client's code is not checked with checkstyle.
     */
    private void runClient(GithubClient.IssueComment c, StandingVisas.GhActor actor, PrRef pr, Cmd cmd) {
        String user = actor.username();
        PythonClientRuns.Queued queued = null;
        try {
            int cancelled = standing.asUser(user, () -> python.cancelOwn(actor.tcToken(), user, pr.number()));
            String why = "Ignite PR Checker: /run-all by @" + c.user().login() + " in " + pullUrl(pr);
            queued = standing.asUser(user, () -> python.queue(actor.tcToken(), pr.number(), cmd.top(), why));
            TcModel.Build build = queued.build();
            endStoriesOf(user, pr, "\n🛑 _Superseded by "
                + (c.htmlUrl() == null ? "a newer /run-all" : "[a newer /run-all](" + c.htmlUrl() + ")") + "._");
            handledTotal.incrementAndGet();
            react(pr, user, c.id(), "rocket");

            String suite = build.buildType() == null || build.buildType().name() == null ? build.buildTypeId()
                : build.buildType().name();
            String link = build.webUrl() == null || build.webUrl().isBlank()
                ? "build " + build.id() : "[build " + build.id() + "](" + build.webUrl() + ")";
            boolean belowTop = queued.belowTop() != 0;
            String ack = "🚀 **" + Markup.text(suite) + " queued"
                + (belowTop ? ", but not at the top" : cmd.top() ? " at the top of the queue" : "") + "** — " + link
                + ": this PR's head `" + shortSha(clientSha(queued.pinned())) + "` against Ignite master at `"
                + shortSha(igniteSha(queued.pinned())) + "`."
                + (belowTop ? " " + notMovedUp("it", queued.belowTop()) + "." : "")
                + (cancelled == 0 ? "" : cancelled == 1 ? " Your previous run was cancelled — this one supersedes it."
                    : " Your previous runs were cancelled — this one supersedes them.")
                + " When the run finishes, a new comment brings you the verdict, and this story goes.";
            tell(pr, c, user, ack, null, build.id(), queued.pinned());

            log.info("/run-all by {} ({}): {} queued for PR {}{}", c.user().login(), user, build.buildTypeId(), pr,
                belowTop ? " (top refused)" : cmd.top() ? " (top)" : "");
        }
        catch (Throwable e) {
            log.warn("/run-all by {} for PR {} failed{}: {}", c.user().login(), pr,
                queued == null ? "" : " after build " + queued.build().id() + " was queued", e.toString());
            if (queued != null)
                return;
            if (standing.tcTokenRejected(user))
                tcTokenRefused(pr, c, actor, cmd);
            else {
                react(pr, user, c.id(), "confused");
                explain(pr, user, c, "🚀 _Nothing was queued: " + (e instanceof PythonClientRuns.NotQueued
                    ? e.getMessage() : queueFailure(e)) + "._");
            }
        }
    }

    /** Where the PR is seen on GitHub. */
    private static String pullUrl(PrRef pr) {
        return "https://github.com/" + pr.repo() + "/pull/" + pr.number();
    }

    /** The client's commit a run of the Python thin client's suite was pinned to. */
    private static String clientSha(PinnedRun run) {
        return run.properties().get(TcModel.OVERRIDDEN_BRANCH);
    }

    /** Ignite's commit a run of the Python thin client's suite was pinned to: the commit of its other VCS root. */
    private static String igniteSha(PinnedRun run) {
        String client = clientSha(run);

        return run.revisions().stream().map(PinnedRun.Pin::version).filter(v -> !v.equals(client)).findFirst()
            .orElse("");
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }

    /**
     * The one line on the first command from a GitHub account since its link to a ci2 user: the thread shows whose
     * ci2 account the commands of that GitHub account now run under, so a link nobody expected gets seen.
     */
    private void firstCommand(PrRef pr, GithubClient.IssueComment c, String user) {
        reply(pr, user, "first:" + c.id(), "ℹ️ First command from @" + c.user().login() + " for ci2 user "
            + Markup.text(user) + ": [Ignite PR Checker](" + publicUrl + ") runs the commands of @" + c.user().login()
            + " under that ci2 account.");
        log.info("first command from GitHub user {} for {}: told in PR {}", c.user().login(), user, pr);
    }

    /**
     * The note of a commanded run whose checkstyle fix waits for a GitHub token for commits: a restored copy of the
     * state lacked it, or the checker could not read the one it stored. Null when the fix has no such reason to wait.
     */
    private String styleFixWaits(String user) {
        return standing.waitingToken(user, SettingsJournal.Entry.GITHUB)
            .map(why -> StandingVisas.TOKEN_REMOVED.equals(why)
                ? "🎨 _Fix checkstyle before my commanded runs waits for your GitHub token for commits: the checker's"
                    + " state was restored from a backup older than 2 days, which keeps no tokens. Save the token in ⚙"
                    + " at " + publicUrl + " again._"
                : "🎨 _Fix checkstyle before my commanded runs waits for a new GitHub token for commits: "
                    + (SettingsJournal.Entry.KEY_CHANGED.equals(why)
                        ? "the server key was changed, so the checker could no longer read the stored one."
                        : "the checker could not read the stored one.")
                    + " Save a new token in ⚙ at " + publicUrl + "._")
            .orElse(null);
    }

    /** What the ack says of the commander's chains and re-runs the new run cancelled; empty when there were none. */
    private static String superseded(int chains, int reruns) {
        if (chains > 0)
            return reruns > 0
                ? " Your previous run and your earlier re-runs were cancelled — this one supersedes them."
                : " Your previous run was cancelled — this one supersedes it.";

        return reruns > 0 ? " The re-runs of your previous run were cancelled — this one supersedes it." : "";
    }

    /** Why TeamCity did not move {@code what} up the queue, in words: ci2 lets only some accounts reorder it. */
    private static String notMovedUp(String what, int status) {
        return "TeamCity refused to move " + what + " up (HTTP " + status + ")"
            + (status == 403 ? ": your ci2 account may not have the permission to reorder the build queue" : "");
    }

    /** Why a call failed, in a few words a commander can act on. */
    private static String failure(Throwable e) {
        return (e instanceof RestClientResponseException rest
            ? "the request failed with HTTP " + rest.getStatusCode().value() : "the checker hit an error")
            + ". Try again in a few minutes";
    }

    /**
     * Why queuing RunAll failed. A 403 from ci2 is ci2 refusing the account as often as its firewall refusing the
     * request; a 403 from GitHub, to the checkstyle fix that reads the PR first, says nothing of the ci2 account.
     */
    private static String queueFailure(Throwable e) {
        return e instanceof TcResponseException ci2 && ci2.getStatusCode().value() == 403
            ? "ci2 answered HTTP 403. Your ci2 account may not run builds here, or ci2's firewall blocked the request:"
                + " try again in a few minutes"
            : failure(e);
    }

    /**
     * A participant's comment that tries a command but does not open with it gets a 😕 and a hint, once.
     * Strangers get nothing: the thread is public, and the hint is only for those who use commands.
     */
    private void hint(GithubClient.IssueComment c, PrRef pr) {
        long now = System.currentTimeMillis();
        if (hinted.containsKey(c.id()) || writtenBefore(c, now - COMMAND_MAX_AGE_MS) || edited(c))
            return;

        Optional<StandingVisas.GhActor> actor = standing.actorByGhUser(c.user());
        if (actor.isEmpty() || !standing.commandsOn(actor.get().username()))
            return;

        hinted.put(c.id(), now);
        react(pr, actor.get().username(), c.id(), "confused");
        explain(pr, actor.get().username(), c, NEAR_MISS_HINT);
        log.info("comment {} by {} on PR {} tries a command without opening with it: hinted", c.id(),
            c.user().login(), pr);
    }

    /** Tells the commander in words why nothing happened, in a reply from the checker's account. */
    private void explain(PrRef pr, String user, GithubClient.IssueComment c, String text) {
        reply(pr, user, "explain:" + c.id(), "@" + c.user().login() + " " + text);
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
     * Whether someone edited the comment after it was written. Anyone with write access may edit another's comment,
     * so an edit that turns it into a command must not run under its author's account.
     */
    private static boolean edited(GithubClient.IssueComment c) {
        if (c.createdAt() == null || c.updatedAt() == null)
            return false;

        try {
            return Instant.parse(c.updatedAt()).isAfter(Instant.parse(c.createdAt()));
        }
        catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** TeamCity no longer accepts the token the checker keeps for this commander, so nothing can run. */
    private void tcTokenRefused(PrRef pr, GithubClient.IssueComment c, StandingVisas.GhActor actor, Cmd cmd) {
        lacking(pr, c, actor.username(), cmd, "tc" + standing.tcRejectedAt(actor.username()), lead(cmd)
            + ": TeamCity no longer accepts the token the checker stores for you (expired or revoked). Log in at "
            + publicUrl + " with a fresh TeamCity token: it replaces the stored one, and your commands and options"
            + " resume.");
    }

    /**
     * Answers a command its author lacks something for: a 😕 each time, and in words once per person, PR and case
     * ({@code what}). A person gets at most {@value #EXPLAINED_PER_DAY} such replies a day, and the accounts nobody
     * linked get {@value #UNLINKED_PER_DAY} a day together: the replies go into public threads. The text never quotes
     * the comment, which anyone can write. {@code user} is whose writes the reply counts among.
     */
    private void lacking(PrRef pr, GithubClient.IssueComment c, String user, Cmd cmd, String what, String text) {
        long now = System.currentTimeMillis();
        react(pr, user, c.id(), "confused");

        String login = c.user().login().toLowerCase(Locale.ROOT);
        String key = login + "#" + pr + "#" + what;
        long dayAgo = now - 24 * 3600_000L;
        String quiet = explained.containsKey(key) ? "told on this PR before"
            : toldSince(k -> k.startsWith(login + "#"), dayAgo) >= EXPLAINED_PER_DAY ? "their daily limit"
            : what.equals(UNLINKED) && toldSince(k -> k.endsWith("#" + UNLINKED), dayAgo) >= UNLINKED_PER_DAY
                ? "the daily limit of replies to unlinked accounts" : null;
        if (quiet != null) {
            log.info("{} by {} on PR {} not run ({}), no reply: {}", cmd.name(), c.user().login(), pr, what, quiet);

            return;
        }

        explained.put(key, now);
        explain(pr, user, c, text);
        log.info("{} by {} on PR {} not run ({}): told why", cmd.name(), c.user().login(), pr, what);
    }

    /** How many replies of {@link #lacking} whose key matches went out since {@code since}. */
    private long toldSince(java.util.function.Predicate<String> key, long since) {
        return explained.entrySet().stream().filter(e -> e.getValue() >= since && key.test(e.getKey())).count();
    }

    /** How a command that did nothing begins its reply. */
    private static String lead(Cmd cmd) {
        return cmd.name().equals("/top") ? "nothing moved" : "nothing was queued";
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
            return TOP;
        if (!words[0].equals("/run-all") && !words[0].equals("/runall"))
            return null;

        return new Cmd("/run-all", Arrays.stream(words).skip(1).anyMatch(w -> w.equals("top") || w.equals("--top")));
    }

    private static String firstLine(String body) {
        return body.strip().lines().findFirst().orElse("").strip();
    }

    /**
     * Promotes the run STARTED BY THE AUTHOR'S OWN COMMAND to the top of the queue — top belongs to
     * the command, so someone else's builds on the same PR are never touched. The run's story says so: a reply would
     * be one more comment in the PR for each /top.
     */
    private void top(GithubClient.IssueComment c, StandingVisas.GhActor actor, PrRef pr) {
        String user = actor.username();
        try {
            CommandRun run = watching.values().stream()
                .filter(r -> r.of().equals(pr) && r.username().equals(user) && r.ending() == null)
                .max(java.util.Comparator.comparingLong(CommandRun::buildId)).orElse(null);
            if (run == null) {
                lacking(pr, c, user, TOP, "top", "⬆️ _Nothing moved: `/top` moves " + (pr.ignite() ? "the RunAll"
                    : "the run") + " that your own `/run-all` started on this PR, and none is in progress._");
                log.info("/top by {} for PR {}: no commanded run of theirs to promote", c.user().login(), pr);

                return;
            }

            var b = standing.asUser(user, () -> tc.getBuildState(actor.tcToken(), run.buildId()));
            if (b == null || !"queued".equalsIgnoreCase(b.state())) {
                String where = b == null ? "not found"
                    : "finished".equalsIgnoreCase(b.state()) ? "finished" : "already running";
                lacking(pr, c, user, TOP, "top" + run.buildId(), "⬆️ _Nothing moved: build " + run.buildId() + " is "
                    + where + ", and `/top` only moves a build that still waits in the queue._");
                log.info("/top by {} for PR {}: build {} is not queued", c.user().login(), pr, run.buildId());

                return;
            }

            try {
                standing.asUser(user, () -> {
                    tc.moveToQueueTop(actor.tcToken(), run.buildId());

                    return null;
                });
            }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 403)
                    throw e;

                lacking(pr, c, user, TOP, "top403", "⬆️ _Nothing moved: " + notMovedUp("build " + run.buildId(), 403)
                    + "._");
                log.info("/top by {} for PR {}: TeamCity refused to move build {} up (403)", c.user().login(), pr,
                    run.buildId());

                return;
            }
            handledTotal.incrementAndGet();
            react(pr, user, c.id(), "rocket");
            movedToTop(run);
            log.info("/top by {} ({}): build {} of PR {} moved to the queue top",
                c.user().login(), user, run.buildId(), pr);
        }
        catch (RuntimeException e) {
            if (standing.tcTokenRejected(user))
                tcTokenRefused(pr, c, actor, TOP);
            else {
                react(pr, user, c.id(), "confused");
                explain(pr, user, c, "⬆️ _Nothing moved: " + failure(e) + "._");
            }
            log.warn("/top by {} for PR {} failed: {}", c.user().login(), pr, e.toString());
        }
    }

    /** The run's story gets the line /top answered with, and keeps it; shown at once when the story is out. */
    private void movedToTop(CommandRun run) {
        String line = "\n⬆️ **Build " + run.buildId() + " moved to the top of the queue.**";
        CommandRun moved = watching.computeIfPresent(run.commentId(),
            (k, r) -> r.withBase(r.baseBody() + line));
        if (moved == null || moved.narrationId() == 0)
            return;

        String last = lastNarration.get(run.commentId());
        String status = last != null && last.startsWith(run.baseBody()) ? last.substring(run.baseBody().length()) : "";
        if (!narrate(moved, moved.baseBody() + status))
            shown.remove(run.commentId()); // the next look shows the whole story again
    }

    /**
     * A command from a GitHub account no user acts for: the reply says what to do in the checker, by the names ⚙
     * shows. Nobody's name is in it: the account may be linked to nobody, or an earlier version may have kept its
     * login for someone without a proof.
     */
    private void noActor(PrRef pr, GithubClient.IssueComment c, Cmd cmd, StandingVisas.NoActor why) {
        String stranger = "@" + c.user().login().toLowerCase(Locale.ROOT);
        switch (why) {
            case UNPROVEN -> lacking(pr, c, stranger, cmd, "proof", lead(cmd) + ": the checker keeps this GitHub login"
                + " from an earlier version, with no proof that the account is yours. Log in at " + publicUrl + ", "
                + linkSteps());
            case TOKEN_UNREADABLE -> lacking(pr, c, stranger, cmd, "tc-unreadable", lead(cmd) + ": the checker can no"
                + " longer read the TeamCity token it stores for you. Log in at " + publicUrl + " again, and it keeps"
                + " the new one.");
            case KEY_CHANGED -> lacking(pr, c, stranger, cmd, "tc-key", lead(cmd) + ": the server key of the checker"
                + " was changed, so it could no longer read the TeamCity token it stored for you. Log in at "
                + publicUrl + " again: the checker stores the token of that login, and your commands work again.");
            case TOKEN_REMOVED -> lacking(pr, c, stranger, cmd, "tc-removed", lead(cmd) + ": the checker's state was"
                + " restored from a backup older than 2 days, which keeps no tokens. Open " + publicUrl + " while"
                + " logged in, or log in there again: the checker takes the TeamCity token of that login, and your"
                + " commands work again.");
            default -> lacking(pr, c, stranger, cmd, UNLINKED, lead(cmd) + ": this GitHub account is not linked to a"
                + " ci2 account in [Ignite PR Checker](" + publicUrl + "). Log in there with a [TeamCity access token]("
                + tcBaseUrl + "/profile.html?item=accessTokens), " + linkSteps() + " After that, `/run-all` here"
                + " queues " + (pr.ignite() ? "RunAll" : "the Python thin client suite") + " under your ci2 account.");
        }
    }

    /** What links the commenter's GitHub account in ⚙: Link GitHub, or a GitHub token for commits where it is not. */
    private String linkSteps() {
        return github.linksAccounts()
            ? "open ⚙ and press **Link GitHub** under **GitHub account**: that also switches on **PR commands**."
            : "open ⚙ and switch on **Add my PR's tests that no suite runs** with a GitHub token of this account, which"
                + " links it. Then switch on **PR commands**.";
    }

    /** Reacts to a comment from the checker's account; one GitHub does not take now goes later. */
    private void react(PrRef pr, String user, long commentId, String content) {
        write(new Reply("react:" + commentId + ":" + content, pr.number(), user, commentId, content, null,
            System.currentTimeMillis(), pr.repo()));
    }

    /** Replies in the PR from the checker's account; {@code key} names the reply, so it never goes twice. */
    private void reply(PrRef pr, String user, String key, String body) {
        if (readsAsCommand(body))
            throw new IllegalStateException("the checker must never post a comment that reads as a command");

        write(new Reply(key, pr.number(), user, 0, null, body, System.currentTimeMillis(), pr.repo()));
    }

    /** Posts a comment of the checker's account in the PR, of whichever repo it is. */
    private GithubClient.PostedComment comment(PrRef pr, String user, String body) {
        return pr.ignite() ? github.appComment(pr.number(), user, body)
            : github.appComment(pr.repo(), pr.number(), user, body);
    }

    /** Makes the write now, or keeps it for the next polls when GitHub does not take it. */
    private void write(Reply r) {
        if (!outbox.containsKey(r.key()) && !sent(r))
            outbox.put(r.key(), r);
    }

    /**
     * Tries the writes GitHub did not take before: GitHub asked to wait, a cap of writes was reached, or GitHub failed.
     * One that waited a day goes no more.
     */
    private void sendOutbox(long now) {
        for (Reply r : List.copyOf(outbox.values())) {
            if (r.since() < now - OUTBOX_MS) {
                outbox.remove(r.key(), r);
                log.warn("{} on PR {} dropped: GitHub did not take it for a day", r.key(), r.pr());
            }
            else if (sent(r))
                outbox.remove(r.key(), r);
        }
    }

    /**
     * Whether the write is done with: made, or refused in a way a retry would not change (the comment is gone, say).
     * False when it is to be tried again.
     */
    private boolean sent(Reply r) {
        try {
            if (r.reaction() != null && r.repo() == null)
                github.appReact(r.pr(), r.user(), r.commentId(), r.reaction());
            else if (r.reaction() != null)
                github.appReact(r.repo(), r.pr(), r.user(), r.commentId(), r.reaction());
            else
                comment(r.of(), r.user(), r.body());

            return true;
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("{} on PR {} waits: {}", r.key(), r.pr(), e.getMessage());

            return false;
        }
        catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("{} on PR {} dropped: GitHub answered {}", r.key(), r.pr(), e.getStatusCode().value());

            return true;
        }
        catch (RuntimeException e) {
            log.warn("{} on PR {} failed, tried again at the next poll: {}", r.key(), r.pr(), e.toString());

            return false;
        }
    }

    /**
     * Starts the story of an accepted command: a comment of the checker's account that mentions the commander. It
     * carries only the checker's words: the commander's comment, whatever it says, is never posted in its voice. One
     * GitHub does not take now goes at the next look.
     */
    private void tell(PrRef pr, GithubClient.IssueComment c, String user, String ack, String note, long buildId,
        PinnedRun pinned) {
        String body = "@" + c.user().login() + " " + ack;
        if (readsAsCommand(body))
            throw new IllegalStateException("the checker must never post a comment that reads as a command");

        CommandRun run = new CommandRun(c.id(), body, buildId, user, 0, true, pr.number(), note, null, 0, pr.repo(),
            pinned);
        watching.put(c.id(), run);
        narrate(run, body);
    }

    /**
     * Shows {@code body} as the run's story when it is not what it shows already, so the edit history holds no
     * no-op revisions. True when the story shows it; a write that did not go in is not remembered, so the next look
     * tries it again.
     */
    private boolean narrate(CommandRun run, String body) {
        if (body.equals(lastNarration.get(run.commentId())))
            return true;

        boolean shownNow = show(run, body);
        if (shownNow)
            lastNarration.put(run.commentId(), body);

        return shownNow;
    }

    /**
     * Posts the story's comment when it has none yet, else edits it. The poll starts a story while the looks go on
     * another thread: the comment is posted under a lock, so one story never gets two. A story whose comment someone
     * deleted ends.
     */
    private boolean show(CommandRun run, String body) {
        try {
            long narration = current(run).narrationId();
            if (narration == 0) {
                synchronized (posting) {
                    narration = current(run).narrationId();
                    if (narration == 0) {
                        GithubClient.PostedComment n = comment(run.of(), run.username(), body);
                        if (n == null)
                            return false;

                        watching.computeIfPresent(run.commentId(), (k, r) -> r.postedAs(n.id()));

                        return true;
                    }
                }
            }

            return run.repo() == null ? github.appEdit(run.pr(), run.username(), narration, body)
                : github.appEdit(run.repo(), run.pr(), run.username(), narration, body);
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("the story of RunAll {} on PR {} waits: {}", run.buildId(), run.of(), e.getMessage());

            return false;
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("the story of RunAll {} on PR {} ends: GitHub has its comment no more", run.buildId(), run.pr());
            stopNarrating(run);

            return false;
        }
        catch (RuntimeException e) {
            log.warn("the story of RunAll {} on PR {} not shown, tried again later: {}", run.buildId(), run.pr(),
                e.toString());

            return false;
        }
    }

    /** Held while a story's comment is posted. */
    private final Object posting = new Object();

    /** Last narration rendered per command comment — identical re-edits are skipped so the minute-level
     * cadence doesn't flood the comment's edit history with no-op revisions. */
    private final ConcurrentMap<Long, String> lastNarration = new ConcurrentHashMap<>();

    /** The stage and estimate each narration last showed — what decides whether a new one is worth an edit. */
    private final ConcurrentMap<Long, Shown> shown = new ConcurrentHashMap<>();

    /** The stage of a story whose run finished and whose verdict is being computed. */
    private static final String ANALYSING = "analysing";

    /**
     * The verdicts of runs of the Python thin client's suite that GitHub did not take yet, by command comment: the
     * next look posts the same text instead of judging the run again.
     */
    private final ConcurrentMap<Long, String> verdictBodies = new ConcurrentHashMap<>();

    /** The stage of a story that waits for a token TeamCity accepts. */
    private static final String TOKEN_REFUSED = "token refused";

    /** The stage of a story that waits for a TeamCity token the checker no longer has, followed by why it is gone. */
    private static final String TOKEN_GONE = "token gone: ";

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

    /** One look at a narrated run; every story ends with a last line or the verdict, whatever ends it. */
    private void look(CommandRun run) {
        if (run.ending() != null) {
            finishEnding(run);

            return;
        }

        int pr = run.pr();
        Optional<StandingVisas.GhActor> actor = standing.actor(run.username());
        if (actor.isEmpty()) {
            Optional<String> gone = standing.waitingToken(run.username(), SettingsJournal.Entry.TEAMCITY);
            if (gone.isPresent())
                waitForTokenBack(run, gone.get());
            else
                end(run, "\n🛑 _No longer followed: the options of the user who started it were switched off._");

            return;
        }
        if (standing.tcTokenRejected(run.username())) {
            waitForToken(run);

            return;
        }
        if (run.repo() != null) {
            lookClient(run, actor.get());

            return;
        }

        try {
            var b = standing.asUser(run.username(), () -> tc.getBuildState(actor.get().tcToken(), run.buildId()));
            if (b == null)
                return;

            if ("finished".equalsIgnoreCase(b.state())) {
                if ("UNKNOWN".equalsIgnoreCase(b.status())) {
                    end(run, "\n🛑 _Run cancelled._");

                    return;
                }

                // After the chain finishes the story keeps telling the blocker/broken auto re-run waves and closes
                // once the verdict has actually landed — or right away when nothing settles the user's runs.
                if ((standing.buildHandled(run.username(), pr, run.buildId())
                    || !standing.settlesRuns(run.username()))
                    && !standing.verdictCommentDue(run.username(), pr, run.buildId())) {
                    finishWithVerdict(run);

                    return;
                }

                Optional<StandingVisas.WaveStatus> w = standing.waveStatus(pr, run.buildId());
                if (w.isEmpty()) {
                    Optional<StandingVisas.RunEnd> end = standing.runEnd(pr, run.buildId());
                    if (end.isPresent()) {
                        end(run, endLine(end.get(), pr));

                        return;
                    }
                    standing.settleRequested(pr);
                }

                String stage = w.map(s -> "wave " + s.wave() + ": " + s.what()).orElse("analysing");
                long settleAt = w.map(StandingVisas.WaveStatus::etaEpochSec).orElse(-1L);
                if (!worthEditing(run, stage, settleAt))
                    return;

                String line = w.isEmpty()
                    ? "\n🏁 _Run finished — analysing; the verdict follows " + (standing.ghOn(run.username())
                        ? "in its own comment._" : "on " + verdictLink(run, pr, "the checker's page") + "._")
                    : "\n🏁 _Run finished._ ♻️ _Auto re-run **#" + w.get().wave() + "** — " + w.get().what()
                        + (settleAt < 0 ? "" : ", **≈ settled by " + wallClock(settleAt, actor.get().tz()) + "**")
                        + " — " + verdictLink(run, pr, "details") + "._";
                if (narrate(run, run.baseBody() + line))
                    shown.put(run.commentId(), new Shown(run.buildId(), stage, settleAt));

                return; // keep narrating until the verdict lands
            }

            long left = tc.chainRemainingSeconds(actor.get().tcToken(), run.buildId(),
                baseline.durations(actor.get().tcToken()));
            tellProgress(run, "queued".equalsIgnoreCase(b.state()),
                left < 0 ? -1 : System.currentTimeMillis() / 1000 + left, actor.get().tz());
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                log.warn("ETA update for PR {} failed: {}", pr, e.toString());

                return;
            }

            end(run, "\n🛑 _TeamCity no longer has build " + run.buildId() + ", so this story ends here._");
        }
        catch (RuntimeException e) {
            log.warn("ETA update for PR {} failed: {}", pr, e.toString());
        }
    }

    /**
     * Shows whether the run waits in the queue or runs, and when it is expected to finish ({@code finishAt}, epoch
     * seconds; -1 when unknown) in the commander's time zone.
     */
    private void tellProgress(CommandRun run, boolean queued, long finishAt, String tz) {
        String stage = queued ? "queued" : "running";
        if (worthEditing(run, stage, finishAt)
            && narrate(run, run.baseBody() + "\n⏱ _" + (queued ? "Queued" : "Running")
                + (finishAt < 0 ? " — no finish estimate yet._"
                    : " — expected to finish **≈ " + wallClock(finishAt, tz) + "**._")
                + (queued ? " _Reply `/top` to jump the queue._" : "")))
            shown.put(run.commentId(), new Shown(run.buildId(), stage, finishAt));
    }

    /**
     * One look at a run of the Python thin client's suite: its stage and estimate while it waits and runs. Once it
     * finished, a new comment that mentions the commander brings the verdict, and the story's comment goes, as a
     * RunAll's does.
     */
    private void lookClient(CommandRun run, StandingVisas.GhActor actor) {
        try {
            TcModel.Build b = standing.asUser(run.username(), () -> tc.getBuildState(actor.tcToken(), run.buildId()));
            if (b == null)
                return;

            if ("finished".equalsIgnoreCase(b.state())) {
                if ("UNKNOWN".equalsIgnoreCase(b.status()))
                    end(run, "\n🛑 _Run cancelled._");
                else
                    finishClientRun(run, actor, b);

                return;
            }

            tellProgress(run, "queued".equalsIgnoreCase(b.state()), clientFinishAt(actor.tcToken(), b), actor.tz());
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                log.warn("ETA update for PR {} failed: {}", run.of(), e.toString());

                return;
            }

            end(run, "\n🛑 _TeamCity no longer has build " + run.buildId() + ", so this story ends here._");
        }
        catch (RuntimeException e) {
            log.warn("ETA update for PR {} failed: {}", run.of(), e.toString());
        }
    }

    /**
     * When a run of one suite is expected to finish, epoch seconds: TeamCity's estimate, else what is left of the
     * suite's typical master duration; -1 when neither is known.
     */
    private long clientFinishAt(String token, TcModel.Build b) {
        long now = System.currentTimeMillis() / 1000;
        long estimate = TcDates.epochSeconds(b.finishEstimate());
        if (estimate > 0)
            return Math.max(estimate, now);

        TcModel.RunningInfo ri = b.runningInfo();
        long elapsed = ri != null && ri.elapsedSeconds() != null ? ri.elapsedSeconds() : 0;
        if (ri != null && ri.estimatedTotalSeconds() != null && ri.elapsedSeconds() != null)
            return now + Math.max(0, ri.estimatedTotalSeconds() - elapsed);

        Long typical = baseline.durations(token).get(b.buildTypeId());

        return typical == null ? -1 : now + Math.max(0, typical - elapsed);
    }

    /**
     * The verdict of a finished run of the Python thin client's suite goes to the PR in a new comment that mentions
     * the commander, and the story's comment goes. While a verdict that TeamCity errors left incomplete is computed
     * again, the story says the run is being analysed. A verdict GitHub did not take now goes at a later look.
     */
    private void finishClientRun(CommandRun run, StandingVisas.GhActor actor, TcModel.Build b) {
        CommandRun now = current(run);
        if (now.readyId() == 0) {
            String body = verdictBodies.get(now.commentId());
            if (body == null) {
                Optional<AnalysisResult> verdict = standing.asUser(now.username(),
                    () -> python.verdict(actor.tcToken(), now.pr(), now.buildId()));
                if (verdict.isEmpty()) {
                    if (worthEditing(now, ANALYSING, -1) && narrate(now, now.baseBody() + "\n🏁 _Run finished —"
                        + " analysing; the verdict follows in a new comment._"))
                        shown.put(now.commentId(), new Shown(now.buildId(), ANALYSING, -1));

                    return;
                }

                body = python.verdictComment(standing.ghLoginOf(now.username()), now.pr(), b, now.pinned(),
                    verdict.get());
                if (readsAsCommand(body))
                    throw new IllegalStateException("the checker must never post a comment that reads as a command");

                verdictBodies.put(now.commentId(), body);
            }

            long ready = postVerdict(now, body);
            if (ready == 0)
                return;

            verdictBodies.remove(now.commentId());
            CommandRun told = watching.computeIfPresent(now.commentId(), (k, r) -> r.readyAs(ready));
            if (told == null)
                return;

            log.info("the verdict of build {} on PR {} is posted", told.buildId(), told.of());
            finishStory(told);

            return;
        }
        finishStory(now);
    }

    /** The story's comment goes once the verdict is out; a story GitHub never showed has nothing to remove. */
    private void finishStory(CommandRun run) {
        if (run.narrationId() == 0 || removeStory(run))
            stopNarrating(run);
    }

    /** Posts the verdict of a run of the Python thin client's suite: its comment's id, 0 when it did not go in now. */
    private long postVerdict(CommandRun run, String body) {
        try {
            GithubClient.PostedComment c = comment(run.of(), run.username(), body);

            return c == null ? 0 : c.id();
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("the verdict of build {} on PR {} waits: {}", run.buildId(), run.of(), e.getMessage());

            return 0;
        }
        catch (RuntimeException e) {
            log.warn("the verdict of build {} on PR {} failed, tried again later: {}", run.buildId(), run.of(),
                e.toString());

            return 0;
        }
    }

    /**
     * Says once that the story waits: TeamCity refused the token the checker stores for the commander, so it cannot
     * see how the run goes. The next look with a working token tells the run again.
     */
    private void waitForToken(CommandRun run) {
        if (worthEditing(run, TOKEN_REFUSED, -1) && narrate(run, run.baseBody() + "\n⏸ _TeamCity no longer accepts "
            + "the token the checker stores for you, so this story waits. The run itself goes on in TeamCity. Log in at "
            + publicUrl + " with a fresh TeamCity token to continue the story._"))
            shown.put(run.commentId(), new Shown(run.buildId(), TOKEN_REFUSED, -1));
    }

    /**
     * Says once that the story waits for the commander's TeamCity token, which the checker no longer has: a restored
     * copy of the state lacked it, or the checker could not read it. The commander's options wait for it too, so the
     * next look after a login tells the run again.
     */
    private void waitForTokenBack(CommandRun run, String why) {
        String stage = TOKEN_GONE + why;
        String line = StandingVisas.TOKEN_REMOVED.equals(why)
            ? "The checker's state was restored from a backup older than 2 days, which keeps no tokens. This story"
                + " waits for your TeamCity token; the run itself goes on in TeamCity. Open " + publicUrl
                + " while logged in, or log in there again, and the story goes on."
            : (SettingsJournal.Entry.KEY_CHANGED.equals(why)
                ? "The server key of the checker was changed, so it could no longer read the TeamCity token it stored"
                    + " for you."
                : "The checker can no longer read the TeamCity token it stores for you.")
                + " This story waits for a new one; the run itself goes on in TeamCity. Log in at " + publicUrl
                + " again, and the story goes on.";
        if (worthEditing(run, stage, -1) && narrate(run, run.baseBody() + "\n⏸ _" + line + "_"))
            shown.put(run.commentId(), new Shown(run.buildId(), stage, -1));
    }

    /** The run as the story keeps it now: posting its comment gives it an id. */
    private CommandRun current(CommandRun run) {
        CommandRun now = watching.get(run.commentId());

        return now == null ? run : now;
    }

    /**
     * The verdict of the run is in: the commander is mentioned in a new comment that has it — the verdict comment of
     * "Comment my runs' verdicts", or else one that says it is ready — and the story goes, so the run leaves one
     * comment of the checker in the PR. The checkstyle autofix's note, which says what was pushed to the commander's
     * branch, moves into that comment. Each step that does not go in now is tried again at the next look.
     */
    private void finishWithVerdict(CommandRun run) {
        CommandRun now = current(run);
        if (now.readyId() == 0) {
            java.util.OptionalLong verdict = standing.verdictCommentId(now.username(), now.pr(), now.buildId());
            long ready = verdict.isEmpty() ? postReady(now) : withNote(now, verdict.getAsLong());
            if (ready == 0)
                return;

            now = watching.computeIfPresent(now.commentId(), (k, r) -> r.readyAs(ready));
            if (now == null)
                return;
        }
        if (now.narrationId() == 0 || removeStory(now))
            stopNarrating(now);
    }

    /** Says in a new comment that the verdict of the run is ready; its id, 0 when it did not go in now. */
    private long postReady(CommandRun run) {
        String login = standing.ghLoginOf(run.username());
        String body = (login == null || login.isBlank() ? "The verdict of a `/run-all`" : "@" + login
            + " the verdict of your `/run-all`") + " is ready: RunAll [" + run.buildId() + "](" + tcBaseUrl + "/build/"
            + run.buildId() + ") — " + verdictLink(run, run.pr(), "see the verdict") + "."
            + (standing.visaOn(run.username()) && standing.settledWithoutTicket(run.buildId())
                ? " No JIRA visa: the PR title names no IGNITE ticket."
                : standing.visaWaiting(run.username(), run.pr(), run.buildId()).map(line -> " " + line).orElse(""))
            + (run.note() == null ? "" : "\n\n" + run.note());
        if (readsAsCommand(body))
            throw new IllegalStateException("the checker must never post a comment that reads as a command");

        try {
            GithubClient.PostedComment c = github.appComment(run.pr(), run.username(), body);

            return c == null ? 0 : c.id();
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("the verdict notice of RunAll {} on PR {} waits: {}", run.buildId(), run.pr(), e.getMessage());

            return 0;
        }
        catch (RuntimeException e) {
            log.warn("the verdict notice of RunAll {} on PR {} failed, tried again later: {}", run.buildId(), run.pr(),
                e.toString());

            return 0;
        }
    }

    /**
     * The verdict comment of the run, with the autofix's note added at its end when the run has one: its id, 0 when
     * the note did not go in now. A verdict comment GitHub no longer has gives way to a new comment that the verdict is
     * ready.
     */
    private long withNote(CommandRun run, long verdict) {
        if (run.note() == null)
            return verdict;

        try {
            Optional<String> body = github.commentBody(verdict);
            if (body.isEmpty())
                return postReady(run);
            if (body.get().contains(run.note()))
                return verdict;

            return github.appEdit(run.pr(), run.username(), verdict, body.get().stripTrailing() + "\n\n" + run.note())
                ? verdict : 0;
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("the autofix note of RunAll {} on PR {} waits: {}", run.buildId(), run.pr(), e.getMessage());

            return 0;
        }
        catch (RuntimeException e) {
            log.warn("the autofix note of RunAll {} on PR {} not added to its verdict, tried again later: {}",
                run.buildId(), run.pr(), e.toString());

            return 0;
        }
    }

    /** Deletes the story's comment once the verdict is out. True when done. */
    private boolean removeStory(CommandRun run) {
        try {
            return run.repo() == null ? github.appDelete(run.pr(), run.username(), run.narrationId())
                : github.appDelete(run.repo(), run.pr(), run.username(), run.narrationId());
        }
        catch (GithubClient.WriteDeferred e) {
            log.info("the story of RunAll {} on PR {} stays a while: {}", run.buildId(), run.pr(), e.getMessage());

            return false;
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return true;
        }
        catch (RuntimeException e) {
            log.warn("the story of RunAll {} on PR {} not removed, tried again later: {}", run.buildId(), run.pr(),
                e.toString());

            return false;
        }
    }

    /** Ends the story with {@code line}: shown now, or at the next looks until it goes in. */
    private void end(CommandRun run, String line) {
        CommandRun ending = watching.computeIfPresent(run.commentId(), (k, r) -> r.endingWith(line));
        if (ending != null)
            finishEnding(ending);
    }

    /** Shows the story's last line; a story GitHub never showed has nothing to end. */
    private void finishEnding(CommandRun run) {
        if (current(run).narrationId() == 0 || narrate(run, run.baseBody() + run.ending()))
            stopNarrating(run);
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

    /**
     * Ends the stories of the user's runs of the PR with {@code line}: their new /run-all, queued now, replaces
     * those runs, whose chains and waves it cancelled.
     */
    private void endStoriesOf(String user, PrRef pr, String line) {
        for (CommandRun old : watching.values()) {
            if (old.of().equals(pr) && old.username().equals(user) && old.ending() == null)
                end(old, line);
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
        verdictBodies.remove(run.commentId());
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
     * A wall-clock time in the AUTHOR'S time zone (the one their browser named when they last changed ⚙ — GitHub
     * exposes none); UTC when unknown, so the stamp is honest either way.
     */
    private static String wallClock(long epochSec, String tz) {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        if (tz != null) {
            try {
                zone = java.time.ZoneId.of(tz);
            }
            catch (java.time.DateTimeException ignored) {
                // a zone of an older snapshot this JDK does not know falls back to UTC
            }
        }

        return Instant.ofEpochSecond(epochSec).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm zzz", java.util.Locale.ENGLISH));
    }

    /** The Python thin client's repo, whose PRs' commands run its suite; null when the client's PRs are not served. */
    public String clientRepo() {
        return python == null ? null : python.repo();
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
        List<CommandRun> runAlls = watching.values().stream().filter(r -> r.repo() == null).toList();
        Map<Integer, CommandRun> newestPerPr = new HashMap<>();
        runAlls.forEach(r -> newestPerPr.merge(r.pr(), r, (a, b) -> a.buildId() >= b.buildId() ? a : b));
        Snapshots.writeAtomic(mapper, file, new Persisted(sinceMs, new HashMap<>(handled), handledTotal.get(),
            newestPerPr, null, null, null, null, new HashMap<>(hinted), runAlls,
            outbox.values().stream().filter(r -> r.repo() == null).toList(), new HashMap<>(explained), clientSinceMs,
            watching.values().stream().filter(r -> r.repo() != null).toList(),
            outbox.values().stream().filter(r -> r.repo() != null).toList()));
    }

    /**
     * Reads pr-commands.json. A story an earlier version wrote into the commander's own comment, with their token,
     * is not told on: the checker writes only to its own comments.
     */
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
        if (p.clientRuns() != null && python != null)
            p.clientRuns().forEach(r -> watching.put(r.commentId(), r));
        if (p.clientSinceMs() > 0)
            clientSinceMs = p.clientSinceMs();
        watching.values().removeIf(r -> {
            if (r.app())
                return false;

            log.info("the story of RunAll {} on PR {} is not told on: it lives in the commander's own comment, which "
                + "the checker no longer edits", r.buildId(), r.pr());

            return true;
        });
        if (p.explained() != null)
            explained.putAll(p.explained());
        explainedBefore(p);
        if (p.hinted() != null)
            hinted.putAll(p.hinted());
        if (p.outbox() != null)
            p.outbox().forEach(r -> outbox.put(r.key(), r));
        if (p.clientOutbox() != null && python != null)
            p.clientOutbox().forEach(r -> outbox.put(r.key(), r));
    }

    /**
     * The replies an earlier version gave to commands from accounts without PR commands, as replies of {@link
     * #lacking}. It answered a person once, and a PR once: its {@code onboarded} logins and {@code onboardedPrs} took
     * the same moment, and {@code strangerCommands} holds the pair. A TeamCity refusal it told ({@code toldTcRefused})
     * names no PR, so it says nothing of the cases now kept per PR.
     */
    private void explainedBefore(Persisted p) {
        if (p.onboarded() == null || p.onboardedPrs() == null || p.strangerCommands() == null)
            return;

        p.onboarded().forEach((login, at) -> p.onboardedPrs().forEach((pr, prAt) -> {
            String pair = login.toLowerCase(Locale.ROOT) + "#" + pr;
            if (at.equals(prAt) && p.strangerCommands().containsKey(pair))
                explained.putIfAbsent(pair + "#" + UNLINKED, at);
        }));
    }

    /**
     * pr-commands.json. {@code narrations} holds every RunAll being told; {@code watching}, one per PR, its newest,
     * is what v1.20.11 and before read and wrote. {@code outbox}: the reactions and replies still to go. {@code
     * explained}: the replies on what a commander lacks; earlier versions kept them in {@code onboarded}, {@code
     * onboardedPrs}, {@code strangerCommands} and {@code toldTcRefused}, which are only read. The Python thin client's
     * PRs have fields of their own, {@code clientSinceMs}, {@code clientRuns} and {@code clientOutbox}: a version
     * before them reads none of them, and so never takes a PR of the client for the Ignite PR of the same number.
     * Records without a repo are of the repo the checker lists.
     */
    private record Persisted(long sinceMs, Map<Long, Long> handled, int handledTotal,
        Map<Integer, CommandRun> watching, Map<String, Long> onboarded, Map<Integer, Long> onboardedPrs,
        Map<String, Long> strangerCommands, Map<String, Long> toldTcRefused, Map<Long, Long> hinted,
        List<CommandRun> narrations, List<Reply> outbox, Map<String, Long> explained, long clientSinceMs,
        List<CommandRun> clientRuns, List<Reply> clientOutbox) {
    }

    /**
     * An accepted command still being told: its comment, the story's text without its status line ({@code
     * baseBody}), the chain it watches, and the checker's comment that tells it ({@code narrationId}, 0 until GitHub
     * took it). {@code app}: the story is the checker's own comment; earlier versions wrote some into the commander's.
     * {@code note}: the checkstyle autofix's note, which goes on into the comment that brings the verdict. {@code
     * ending}: the last line once the story is over, null while it goes. {@code readyId}: the comment that brought the
     * commander the verdict, the verdict comment or one saying it is ready; 0 until one did. The PR came from the key
     * of v1.20.11's snapshot, which has it in no field. {@code repo}: the Python thin client's for a run of its suite,
     * null for a RunAll of the repo the checker lists; {@code pinned}: the commits and parameters the client's run was
     * queued with, null for a RunAll.
     */
    private record CommandRun(long commentId, String baseBody, long buildId, String username,
        long narrationId, boolean app, int pr, String note, String ending, long readyId, String repo,
        PinnedRun pinned) {
        CommandRun ofPr(int number) {
            return new CommandRun(commentId, baseBody, buildId, username, narrationId, app, number, note, ending,
                readyId, repo, pinned);
        }

        CommandRun postedAs(long id) {
            return new CommandRun(commentId, baseBody, buildId, username, id, app, pr, note, ending, readyId, repo,
                pinned);
        }

        CommandRun withBase(String body) {
            return new CommandRun(commentId, body, buildId, username, narrationId, app, pr, note, ending, readyId,
                repo, pinned);
        }

        CommandRun endingWith(String line) {
            return new CommandRun(commentId, baseBody, buildId, username, narrationId, app, pr, note, line, readyId,
                repo, pinned);
        }

        CommandRun readyAs(long id) {
            return new CommandRun(commentId, baseBody, buildId, username, narrationId, app, pr, note, ending, id, repo,
                pinned);
        }

        /** The PR the run is for. */
        PrRef of() {
            return new PrRef(repo, pr);
        }
    }

    /**
     * A reaction or reply of the checker's account to go to the PR: a {@code reaction} to comment {@code commentId},
     * or a comment with {@code body}; {@code user}, whose writes it counts among; {@code since}, when it was first
     * tried; {@code repo}, the Python thin client's for its PRs, null for the repo the checker lists.
     */
    private record Reply(String key, int pr, String user, long commentId, String reaction, String body, long since,
        String repo) {
        /** The PR the write goes to. */
        PrRef of() {
            return new PrRef(repo, pr);
        }
    }

    /**
     * A PR by its repo and number: the PR numbers of the Python thin client's repo overlap those of Ignite (#69 is in
     * both). {@code repo} is null for the repo the checker lists, as in the records of earlier versions; it reads as
     * the number alone, as it did before other repos came.
     */
    private record PrRef(String repo, int number) {
        /** Whether it is a PR of the repo the checker lists, whose {@code /run-all} queues RunAll. */
        boolean ignite() {
            return repo == null;
        }

        @Override
        public String toString() {
            return repo == null ? String.valueOf(number) : repo + "#" + number;
        }
    }

    /** What a narration last showed: for which chain, at which stage, with which estimate (-1: none). */
    private record Shown(long buildId, String stage, long estimateEpochSec) {
    }

    /** A parsed command: the canonical name and whether "top" was asked for. */
    private record Cmd(String name, boolean top) {
    }
}
