package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Standing auto-visa: an opted-in user gets the verdict posted to the ticket for EVERY finished
 * RunAll they triggered — no per-PR arming, no open tab. The price, stated plainly in the UI: the
 * user's TeamCity and JIRA tokens are stored encrypted (session key) for as long as the option is
 * on; disabling removes them. Each finished build is posted at most once (per user+PR build memory).
 */
@Component
public class StandingVisas implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(StandingVisas.class);
    /** An IGNITE ticket key, in any case. Without the hyphen "from Ignite 3" would name ticket IGNITE-3. */
    private static final Pattern ISSUE = Pattern.compile("(?i)\\bIGNITE-\\d{4,}\\b");
    private static final Pattern PR_BRANCH = Pattern.compile("pull/(\\d+)/head");

    private final ObjectMapper mapper;
    private final SessionCodec codec;
    private final TcClient tc;
    private final GithubClient github;
    private final BlockerAnalyzer analyzer;
    private final JiraClient jira;
    private final VisaService visas;
    private final RerunTracker rerunTracker;
    private final Warmer warmer;
    private final PendingCommits pending;
    private final ConcurrentMap<String, Enrollment> enrolled = new ConcurrentHashMap<>();
    /**
     * Auto re-run waves per RunAll chain: each chain keeps its own, so re-runs of a chain still going never
     * take over the count of the one being settled. Persisted with the enrollments.
     */
    private final ConcurrentMap<Long, Retry> waves = new ConcurrentHashMap<>();
    /** Suites already re-run mid-chain, per chain build — so a restart can't re-queue them again. */
    private final ConcurrentMap<Long, java.util.Set<String>> earlyReruns = new ConcurrentHashMap<>();

    /** Early re-runs do TeamCity work; one thread keeps them off the tracker's polling thread. */
    private final ExecutorService earlyPool;

    /** One PR is settled at a time, whoever asks — the sweep or an event — so a wave is never queued twice. */
    private final Object settleLock = new Object();

    /** Settles the PRs whose chains and re-runs the tracker reports finished, off its polling thread. */
    private final ExecutorService settler;

    /** PRs with a settle queued on {@link #settler} that has not started yet. */
    private final Set<Integer> settleQueued = ConcurrentHashMap.newKeySet();

    /** When someone waiting for a PR's run last asked for its settle. */
    private final ConcurrentMap<Integer, Long> settleAsked = new ConcurrentHashMap<>();

    /**
     * The finished RunAll of each PR whose auto re-runs are still to be decided on. Its finish, when someone has
     * auto re-runs on, or the settle that takes it up marks it, those that cannot decide yet (TeamCity errors, a
     * lookup that named an older run) keep the mark, and it goes once the run is settled or a newer run takes its
     * place.
     */
    private final ConcurrentMap<Integer, Long> deciding = new ConcurrentHashMap<>();

    /** The newest RunAll of each PR a settle saw: the latest finished one, or a newer one still going. */
    private final ConcurrentMap<Integer, Long> newestSeen = new ConcurrentHashMap<>();

    /** The run of each PR held back while a newer one goes — logged once. */
    private final ConcurrentMap<Integer, Long> heldForNewer = new ConcurrentHashMap<>();

    /** PRs found closed, and whether they were merged: their runs are no longer settled. */
    private final ConcurrentMap<Integer, Boolean> closedPrs = new ConcurrentHashMap<>();

    /** Runs settled without a visa because their PR's title names no IGNITE ticket. */
    private final Set<Long> ticketless = ConcurrentHashMap.newKeySet();

    /** How often the sweep goes through the open PRs. */
    private static final long SWEEP_MS = 600_000;

    /**
     * How long before the last sweep a chain that finished may still be news to it: the clocks of TeamCity and the
     * checker differ, and a chain may show as finished a little after its finish date.
     */
    private static final long SWEEP_OVERLAP_MS = 300_000;

    /** Every this many sweeps (once an hour) every listed PR is looked up, whatever TeamCity says finished. */
    private static final int FULL_SWEEP_EVERY = 6;

    /** When the last sweep started that knew of every RunAll finished before it; 0 until one has. */
    private volatile long sweptUpTo;

    /** Sweeps since the last one that looked every listed PR up. */
    private int sweepsSinceFull;

    /**
     * The PRs whose last settle left nothing to do until a RunAll of theirs finishes, with the run it found, 0 for
     * none: the sweep skips them while no chain of theirs finished since.
     */
    private final ConcurrentMap<Integer, Long> resting = new ConcurrentHashMap<>();

    /** How many times the blocker suites are re-run before the visa is posted as-is. */
    private static final int MAX_RERUNS = 2;
    /** Up to this many blocker suites jump the queue; more go to the tail so others aren't pushed back. */
    private static final int TOP_QUEUE_LIMIT = 10;
    /** Above this many blocker suites, auto re-run is pointless (systemic breakage) — the visa posts as-is. */
    private static final int MAX_SUITES_PER_RERUN = 30;
    private final java.util.concurrent.atomic.AtomicInteger postedTotal = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastSweepAt;
    private volatile long lastSweepMs;

    /** Off in the dev profile, so a local run never acts on PRs next to the production instance. */
    @Value("${automation.enabled:true}")
    private boolean automation = true;

    @Autowired
    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer,
        PendingCommits pending, @Qualifier("earlyRerunExecutor") ExecutorService earlyPool,
        @Qualifier("settleExecutor") ExecutorService settler) {
        this.mapper = mapper;
        this.codec = codec;
        this.tc = tc;
        this.github = github;
        this.analyzer = analyzer;
        this.jira = jira;
        this.visas = visas;
        this.rerunTracker = rerunTracker;
        this.warmer = warmer;
        this.pending = pending;
        this.earlyPool = earlyPool;
        this.settler = settler;
    }

    /** Standing options whose early re-runs and settles run on daemon threads of their own. */
    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer,
        PendingCommits pending) {
        this(mapper, codec, tc, github, analyzer, jira, visas, rerunTracker, warmer, pending, daemon("early-rerun"),
            daemon("settle"));
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);

            return t;
        });
    }

    /**
     * Lends the enrolled users' TeamCity tokens to the warm pool. Standing options already mean "act
     * on my behalf in the background", and unlike a browsing session they don't expire — without
     * this the pool empties an hour after the last visitor leaves and every background job (warming,
     * the eager re-analysis of a finished run, live run states) silently stops until someone opens
     * the page, so the next visitor pays the full cold analysis.
     */
    private void donateWarmTokens() {
        for (Enrollment e : enrolled.values())
            backgroundToken(e).ifPresent(warmer::offerToken);
    }

    /**
     * The TeamCity token background work may borrow: not a refused one, and not one stored for PR
     * commands alone — that option promises to act on the user's own commands and nothing else.
     */
    private Optional<String> backgroundToken(Enrollment e) {
        return e.tc().rejected() || e.options().commandsOnly() ? Optional.empty() : decrypt(e.tc());
    }

    /**
     * Donates once the whole context is up, so a restart resumes warming without waiting for a
     * visitor. Deliberately not done while loading the snapshot: cache load order is bean order, and
     * a token donated before the analysis cache is back would kick a cycle that recomputes 50 PRs
     * whose results were about to be restored from disk.
     */
    @EventListener(ApplicationReadyEvent.class)
    void donateOnStartup() {
        donateWarmTokens();
    }

    /**
     * Applies a settings change, touching only the options it names: a click on one switch must not
     * turn the others off. An option that needs a token takes the one the session carries when it is
     * new (checked with the service first), else the stored one, so a fresh login without the PATs in
     * its cookie still works; a stored token goes only when the last option that needs it is switched
     * off explicitly. Empty when the change was made, else what is missing.
     */
    public Optional<Refusal> change(String username, String tcToken, String sessionJira, String sessionGh,
        OptionChange change) {
        Enrollment prev = enrolled.get(username);
        Options after = (prev == null ? Options.NONE : prev.options()).apply(change);
        if (!after.any()) {
            disable(username);

            return Optional.empty();
        }

        Credential jiraCred = null;
        String tz = null;
        if (after.autoVisa()) {
            String stored = prev == null ? null : decrypt(prev.jira()).orElse(null);
            boolean fresh = sessionJira != null && !sessionJira.equals(stored);
            if (fresh && jira.myself(sessionJira).isPresent()) {
                jiraCred = new Credential(codec.encryptString(sessionJira), 0);
                tz = jira.myTimezone(sessionJira).orElse(null);
            }
            else if (stored == null)
                return Optional.of(new Refusal("jira", fresh ? "JIRA rejected the token — paste a new one"
                    : "auto-visa needs your JIRA token"));
        }
        else if (Boolean.FALSE.equals(change.visa()))
            jiraCred = Credential.NONE;

        Credential ghCred = null;
        String login = null;
        if (after.needsGh()) {
            String stored = prev == null ? null : decrypt(prev.gh()).orElse(null);
            boolean fresh = sessionGh != null && !sessionGh.equals(stored);
            // A token GitHub can't put a name to is dead — an expired PAT still riding in the session
            // cookie, say. Storing it buys nothing and costs the login: with no login, the command poll
            // stops recognising the author of "/run-all" and replies to them as a stranger.
            login = fresh ? github.ghUser(sessionGh).orElse(null) : null;
            if (login != null)
                ghCred = new Credential(codec.encryptString(sessionGh), 0);
            else if (stored == null) {
                if (fresh)
                    log.warn("GitHub token offered for {} was not accepted by GitHub — kept out of the enrollment",
                        username);

                return Optional.of(new Refusal("github", fresh ? "GitHub rejected the token — paste a new one"
                    : "this option needs your GitHub token"));
            }
        }
        else if (Boolean.FALSE.equals(change.gh()) || Boolean.FALSE.equals(change.style()))
            ghCred = Credential.NONE;

        if (Boolean.TRUE.equals(change.commands()) && login == null && (prev == null || prev.ghLogin() == null))
            return Optional.of(new Refusal("login", "link your GitHub login to use PR commands"));

        Credential sessionTc = new Credential(codec.encryptString(tcToken), 0);
        Credential jiraNext = jiraCred;
        Credential ghNext = ghCred;
        String tzNext = tz;
        String loginNext = login;
        long now = System.currentTimeMillis();

        // A settings change must not forget which builds were already handled (or their comments),
        // nor a GitHub login the user linked by hand (a PAT-derived one is authoritative though).
        Enrollment e = enrolled.compute(username, (u, cur) -> {
            Enrollment base = cur != null ? cur : Enrollment.fresh(sessionTc);
            Options options = base.options().apply(change);
            if (!options.any())
                return null;

            Enrollment next = base
                .withTc(tcKept(base.tc(), tcToken))
                .switchedTo(options, now)
                .withEnabledAt(cur == null || options.switchedOnSince(base.options()) ? now : base.enabledAt());
            next = jiraNext == null ? next : next.withJira(jiraNext);
            next = ghNext == null ? next : next.withGh(ghNext);
            next = tzNext == null ? next : next.withTz(tzNext);

            return loginNext == null ? next : next.withGhLogin(loginNext);
        });
        if (loginNext != null && e != null)
            releaseLogin(loginNext, username);
        log.info("standing options for {}: {} (gh login {}, tz {})", username, e == null ? "off" : e.options(),
            e == null ? null : e.ghLogin(), e == null ? null : e.tz());
        donateWarmTokens();

        return Optional.empty();
    }

    /** The options and the state of their tokens, as the settings panel shows them. */
    public Settings settings(String username) {
        Enrollment e = enrolled.get(username);
        if (e == null)
            return new Settings(false, false, false, false, false, null, false, false, false, false, false);

        Options o = e.options();

        return new Settings(o.autoVisa(), o.autoRerun(), o.ghComment(), o.styleFix(), o.commands(), e.ghLogin(),
            e.jira().token() != null, e.gh().token() != null, e.jira().rejected(), e.gh().rejected(),
            e.tc().rejected());
    }

    /** A settings change: each option is switched on, off, or left as it is (null). */
    public record OptionChange(Boolean visa, Boolean rerun, Boolean gh, Boolean style, Boolean commands) {
    }

    /**
     * Why a settings change was not made: what it {@code need}s ("jira" or "github" token, or the GitHub
     * "login") and what is wrong.
     */
    public record Refusal(String need, String error) {
    }

    /**
     * What the settings panel shows. {@code jiraStored}/{@code ghStored}: the server holds that token,
     * so the options run whether or not this browser's session carries it.
     */
    public record Settings(boolean visa, boolean rerun, boolean gh, boolean style, boolean commands, String login,
        boolean jiraStored, boolean ghStored, boolean jiraTokenRejected, boolean ghTokenRejected,
        boolean tcTokenRejected) {
    }

    /**
     * The user behind a GitHub login, with decrypted tokens — the PR command poll resolves the
     * comment's author through this; whether they may command is {@link #commandsOn}.
     */
    public Optional<GhActor> actorByGhLogin(String login) {
        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            String held = en.getValue().ghLogin();
            if (!login.equalsIgnoreCase(held))
                continue;

            // GitHub logins ignore case and the poll gets GitHub's own spelling: a login typed in
            // another case still matches, and is stored the way GitHub spells it from now on.
            if (!login.equals(held))
                enrolled.computeIfPresent(en.getKey(), (u, e) -> held.equals(e.ghLogin()) ? e.withGhLogin(login) : e);

            // The PAT is optional for commands: without one the checker acks and narrates
            // from its own (the operator's) account instead of the user's.
            Optional<GhActor> actor = actorOf(en.getKey(), en.getValue());
            if (actor.isPresent())
                return actor;
        }

        return Optional.empty();
    }

    private Optional<GhActor> actorOf(String username, Enrollment e) {
        return decrypt(e.tc()).map(tcToken -> new GhActor(username, tcToken, decrypt(e.gh()).orElse(null), e.tz()));
    }

    /** The stored token in clear; empty when there is none or the session secret has changed since. */
    private Optional<String> decrypt(Credential c) {
        return c.token() == null ? Optional.empty() : codec.decryptString(c.token());
    }

    /**
     * Forgets a GitHub token GitHub itself rejected. Keeping it would fail every reaction, ack and
     * per-minute narration edit with the same 401 — which is exactly how a working /run-all (the
     * TeamCity chain was queued) looked to its author like nothing had happened. The login stays:
     * it is how commands resolve the actor, and the checker narrates from its own account until a
     * fresh token is pasted.
     */
    public void dropGhToken(String username) {
        if (username == null)
            return;

        // The two options that need this token go off with it. Leaving them checked would promise
        // work the checker can no longer do — the point of the switch is that it means something.
        boolean dropped = changed(username, e -> e.gh().token() == null ? e
            : e.withGh(Credential.refusedAt(System.currentTimeMillis())).withOptions(e.options().withoutGh()));
        if (dropped)
            log.warn("GitHub token of {} was rejected by GitHub: dropped, its options switched off — "
                + "acks come from the app account until a fresh PAT is saved", username);
    }

    /**
     * Same for JIRA: a PAT the ticket tracker refuses can't post a visa, so auto-visa goes off and
     * the panel asks for a new one instead of silently skipping every ticket from now on.
     */
    public void dropJiraToken(String username) {
        if (username == null)
            return;

        boolean dropped = changed(username, e -> e.jira().token() == null ? e
            : e.withJira(Credential.refusedAt(System.currentTimeMillis())).withOptions(e.options().withoutVisa()));
        if (dropped)
            log.warn("JIRA token of {} was rejected: dropped and auto-visa switched off until a fresh PAT is saved",
                username);
    }

    /**
     * Records that TeamCity refused the user's stored token. The options stay as they were but pause:
     * nothing runs under a dead token, the settings panel and the PR commands say why, and the first
     * request that brings a working token resumes them.
     */
    public void markTcRejected(String username) {
        long now = System.currentTimeMillis();
        boolean marked = changed(username, e -> e.tc().rejected() ? e : e.withTc(new Credential(e.tc().token(), now)));
        if (marked)
            log.warn("TeamCity rejected the stored token of {}: their options pause until a working token comes",
                username);
    }

    /** When TeamCity refused the user's stored token; 0 when it has not. */
    public long tcRejectedAt(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? 0 : e.tc().rejectedAt();
    }

    public boolean tcTokenRejected(String username) {
        return tcRejectedAt(username) > 0;
    }

    /**
     * A token TeamCity has just accepted at login becomes the stored one: logging in again is how a
     * user replaces an expired token, and it used to leave the dead one in charge of their options.
     */
    public void tcTokenAccepted(String username, String token) {
        boolean wasRejected = tcTokenRejected(username);
        Credential accepted = new Credential(codec.encryptString(token), 0);
        changed(username, e -> !e.tc().rejected() && decrypt(e.tc()).filter(token::equals).isPresent() ? e
            : e.withTc(accepted));
        if (wasRejected)
            log.info("TeamCity token of {} renewed at login: their options resume", username);
    }

    /** The token a logged-in request carries replaces a stored one TeamCity refused; see {@link #tcKept}. */
    public void tcTokenOffered(String username, String token) {
        Enrollment e = enrolled.get(username);
        if (e == null || tcKept(e.tc(), token) == e.tc())
            return;

        boolean replaced = changed(username, cur -> {
            Credential kept = tcKept(cur.tc(), token);

            return kept == cur.tc() ? cur : cur.withTc(kept);
        });
        if (replaced)
            log.info("refused or unreadable TeamCity token of {} replaced by the one of their session: options resume",
                username);
    }

    /**
     * The TeamCity credential to keep when a request brings its session's token: the stored one while
     * it works, because the session's token is unchecked and a tab of an older login must not swap a
     * live token for its own dead one; the session's one when TeamCity refused the stored token or it
     * can no longer be read. A login, which TeamCity checks, replaces it outright.
     */
    private Credential tcKept(Credential stored, String sessionToken) {
        Optional<String> token = decrypt(stored);

        return token.isPresent() && (!stored.rejected() || token.get().equals(sessionToken)) ? stored
            : new Credential(codec.encryptString(sessionToken), 0);
    }

    /** A TeamCity call under the user's own stored token; a refusal is recorded against them. */
    public <T> T asUser(String username, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        }
        catch (RuntimeException e) {
            if (tcRefused(e))
                markTcRejected(username);

            throw e;
        }
    }

    /**
     * Whether TeamCity refused the token itself. Only a 401 says so: ci2's firewall answers 403 to
     * requests it dislikes whatever the token.
     */
    public static boolean tcRefused(Throwable e) {
        return e instanceof org.springframework.web.client.RestClientResponseException rest
            && rest.getStatusCode().value() == 401;
    }

    /**
     * Runs a read any enrolled user's TeamCity token may do — finding a PR's builds, who started a
     * chain. Tokens are tried in turn and one TeamCity refuses is recorded, so a single dead token no
     * longer stops everyone's visas, re-runs and comments.
     */
    private <T> T lookup(java.util.function.Function<String, T> read) {
        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            Optional<String> token = backgroundToken(en.getValue());
            if (token.isEmpty())
                continue;

            try {
                return read.apply(token.get());
            }
            catch (RuntimeException e) {
                if (!tcRefused(e))
                    throw e;

                markTcRejected(en.getKey());
            }
        }

        throw new IllegalStateException("TeamCity accepts none of the stored tokens");
    }

    private boolean anyLiveTcToken() {
        return enrolled.values().stream().anyMatch(e -> backgroundToken(e).isPresent());
    }

    /** Applies {@code change} to the user's enrollment atomically; true when it changed anything. */
    private boolean changed(String username, java.util.function.UnaryOperator<Enrollment> change) {
        boolean[] changed = new boolean[1];
        enrolled.computeIfPresent(username, (u, e) -> {
            Enrollment next = change.apply(e);
            changed[0] = next != e;

            return next;
        });

        return changed[0];
    }

    /** Whether a stored credential was refused and is waiting to be replaced. */
    public boolean ghTokenRejected(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.gh().rejected();
    }

    public boolean jiraTokenRejected(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.jira().rejected();
    }

    /** Whether this user's GitHub-account features are waiting for a fresh PAT. */
    public boolean ghTokenMissing(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.gh().token() == null;
    }

    /**
     * Links a GitHub login, as GitHub spells it, and switches PR commands on — the login is all they
     * need besides the TeamCity token, so no other option has to be on. Returns "ok", "taken"
     * (someone else holds it) or "token" (the user's login comes from their GitHub token, which proves
     * it, so a typed one cannot replace it).
     */
    public String linkGhLogin(String username, String tcToken, String login) {
        if (heldByAnother(login, username))
            return "taken";

        Credential sessionTc = new Credential(codec.encryptString(tcToken), 0);
        long now = System.currentTimeMillis();
        String[] result = {"ok"};
        enrolled.compute(username, (u, cur) -> {
            Enrollment base = cur != null ? cur : Enrollment.fresh(sessionTc).withEnabledAt(now);
            boolean fromToken = base.gh().token() != null && base.ghLogin() != null;
            if (fromToken && !login.equalsIgnoreCase(base.ghLogin())) {
                result[0] = "token";

                return cur;
            }

            return base.withTc(tcKept(base.tc(), tcToken))
                .withGhLogin(fromToken ? base.ghLogin() : login)
                .withOptions(base.options().withCommands());
        });
        if (result[0].equals("ok"))
            log.info("PR commands on for {} as GitHub user {}", username, login);

        return result[0];
    }

    private boolean heldByAnother(String login, String username) {
        return enrolled.entrySet().stream()
            .anyMatch(en -> !en.getKey().equals(username) && login.equalsIgnoreCase(en.getValue().ghLogin()));
    }

    /**
     * A login proven by a GitHub token belongs to its owner alone: a claim someone typed in by hand
     * (a typo, or someone else's login) would route the owner's commands to the claimant's account.
     * The claimant's PR commands go off with the login: the poll finds a commander by login, so left
     * on they would promise what cannot happen. Like any last option switched off, it takes the
     * enrollment with it when nothing else is on.
     */
    private void releaseLogin(String login, String owner) {
        enrolled.forEach((u, e) -> {
            if (u.equals(owner) || !login.equalsIgnoreCase(e.ghLogin()))
                return;

            enrolled.computeIfPresent(u, (k, cur) -> {
                if (!login.equalsIgnoreCase(cur.ghLogin()))
                    return cur;

                Options left = cur.options().with(Option.COMMANDS, false);

                return left.any() ? cur.withGhLogin(null).withOptions(left) : null;
            });
            log.warn("GitHub login {} moved from {} to {}: a GitHub token of {} proves the account; PR commands of {} "
                + "switched off", login, u, owner, owner, u);
        });
    }

    /** The user's linked GitHub login (PAT-derived or hand-linked), or null. */
    public String ghLoginOf(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? null : e.ghLogin();
    }

    /** Whether anyone has PR commands on — gates the PR command poll entirely. */
    public boolean anyGhEnrolled() {
        return enrolled.values().stream().anyMatch(e -> e.options().commands() && e.ghLogin() != null);
    }

    /** Whether the user's comments on pull requests are taken as commands. */
    public boolean commandsOn(String username) {
        return options(username).commands();
    }

    /** Whether something waits for the user's finished runs: a visa, re-runs or a PR comment. */
    public boolean settlesRuns(String username) {
        return options(username).settlesRuns();
    }

    /** The auto re-run wave currently settling a build — for external narrators (the command comment). */
    public Optional<WaveStatus> waveStatus(int pr, long buildId) {
        Retry r = waves.get(buildId);
        if (r == null || r.pr() != pr)
            return Optional.empty();

        return Optional.of(new WaveStatus(r.wave(), r.what(), activeEtaEpoch(pr)));
    }

    /**
     * Where the verdict of the PR's run {@code buildId} stands, for the page: "running" while a RunAll of the
     * PR is under way, "settling" while auto re-runs settle that run or the decision on them is being made,
     * "final" when nothing will change it any more.
     */
    public Phase phase(int pr, long buildId) {
        if (rerunTracker.newestChainUnderWay(pr) > 0)
            return new Phase(Phase.RUNNING, 0, MAX_RERUNS, null, null);

        Retry r = waves.get(buildId);
        if (r != null && r.pr() == pr)
            return new Phase(Phase.SETTLING, r.wave(), MAX_RERUNS, r.what(), activeEtaEpoch(pr));
        if (Long.valueOf(buildId).equals(deciding.get(pr)))
            return new Phase(Phase.SETTLING, 0, MAX_RERUNS, null, null);

        return new Phase(Phase.FINAL, 0, MAX_RERUNS, null, null);
    }

    /**
     * See {@link #phase}: {@code wave} of up to {@code of} is the one going, 0 while the decision is made;
     * {@code what} it re-runs and {@code etaEpochSec} when it should settle, null when not known.
     */
    public record Phase(String phase, int wave, int of, String what, Long etaEpochSec) {
        static final String RUNNING = "running";

        static final String SETTLING = "settling";

        static final String FINAL = "final";
    }

    /**
     * Why a finished run is not going to be settled after all: a newer RunAll of the PR replaced it
     * ({@code replacedBy}), or the PR was closed ({@code replacedBy} 0, {@code merged} or not). Empty while it
     * may still be.
     */
    public Optional<RunEnd> runEnd(int pr, long buildId) {
        Boolean merged = closedPrs.get(pr);
        if (merged != null)
            return Optional.of(new RunEnd(0, merged));

        long newer = Math.max(newestSeen.getOrDefault(pr, 0L), rerunTracker.newestChainUnderWay(pr));

        return newer > buildId ? Optional.of(new RunEnd(newer, false)) : Optional.empty();
    }

    /** See {@link #runEnd}. */
    public record RunEnd(long replacedBy, boolean merged) {
    }

    /** Whether the run was settled without a visa because its PR's title names no IGNITE ticket. */
    public boolean settledWithoutTicket(long buildId) {
        return ticketless.contains(buildId);
    }

    /**
     * Cancels the re-runs of the user's earlier runs of the PR, mid-run and settling alike, and forgets their
     * waves: a new /run-all of theirs replaces those runs. How many builds were cancelled. Only a refused
     * token stops the new command; re-runs that could not be cancelled are left to finish.
     */
    public int cancelWaves(String username, String tcToken, int pr) {
        List<Retry> theirs = waves.values().stream().filter(r -> r.pr() == pr && username.equals(r.by())).toList();
        Set<Long> ids = new java.util.HashSet<>();
        theirs.forEach(r -> ids.addAll(r.queued() == null ? List.of() : r.queued()));
        theirs.forEach(r -> waves.remove(r.buildId(), r));
        if (ids.isEmpty())
            return 0;

        try {
            int cancelled = asUser(username, () -> tc.cancelOwnBuilds(tcToken, pr, username, b -> ids.contains(b.id())));
            log.info("waves of {} on PR {} dropped for their new /run-all: {} re-run(s) cancelled", username, pr,
                cancelled);

            return cancelled;
        }
        catch (RuntimeException e) {
            if (tcRefused(e))
                throw e;

            log.warn("re-runs of the earlier runs of {} on PR {} not cancelled: {}", username, pr, e.toString());

            return 0;
        }
    }

    /** The user's PR comment that carries this build's verdict, if one was posted. */
    public java.util.OptionalLong verdictCommentId(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);
        GhThread t = e == null ? null : e.handled().ghThreads().get(pr);

        return t != null && t.buildId() == buildId ? java.util.OptionalLong.of(t.commentId())
            : java.util.OptionalLong.empty();
    }

    /**
     * What the user's standing auto-visa does about this build's verdict in {@code issue}: it is in
     * (the living visa of the build, interim or final), the sweep is still to post it, or neither. A PR
     * the sweep no longer lists keeps its posted visa: the visa went to the ticket of the PR's title. A run
     * a newer RunAll of the PR replaces, or holds back while it goes, gets no standing visa (see {@link #runEnd}).
     */
    public VisaCover visaCover(String username, int pr, long buildId, String issue) {
        Enrollment e = username == null ? null : enrolled.get(username);
        Optional<String> ticket = titleTicket(pr);
        if (e == null || ticket.isPresent() && !ticket.get().equals(issue))
            return VisaCover.NONE;

        JiraThread t = e.handled().jiraThreads().get(pr);
        if (t != null && t.buildId() == buildId)
            return VisaCover.POSTED;

        boolean settled = Long.valueOf(buildId).equals(e.handled().posted().get(pr));
        boolean comes = !settled && runEnd(pr, buildId).isEmpty();

        return comes && visaTicket(username, pr).isPresent() ? VisaCover.PENDING : VisaCover.NONE;
    }

    /** See {@link #visaCover}. */
    public enum VisaCover {
        POSTED, PENDING, NONE
    }

    /**
     * The user whose standing auto-visa posts the verdict of the PR's run under way: the starter of the
     * chains the rerun tracker watches. Null when no chain is under way or its starter's visa posts none.
     */
    public String visaOwnerOfRunUnderWay(int pr) {
        String starter = rerunTracker.chainStarter(pr);

        return starter != null && visaTicket(starter, pr).isPresent() ? starter : null;
    }

    /**
     * The ticket the sweep posts the user's visas for the PR to: their auto-visa is on and not paused,
     * with a JIRA token, and the PR is among those the sweep goes through.
     */
    private Optional<String> visaTicket(String username, int pr) {
        Enrollment e = enrolled.get(username);
        if (e == null || !e.options().autoVisa() || e.tc().rejected() || e.jira().token() == null)
            return Optional.empty();

        return titleTicket(pr);
    }

    /** The IGNITE ticket in the title of an open PR the sweep goes through. */
    private Optional<String> titleTicket(int pr) {
        return github.openPrs().stream().filter(p -> p.number() == pr).findFirst().flatMap(p -> ticketIn(p.title()));
    }

    /** The IGNITE ticket a PR title names, spelled as JIRA spells it; empty when it names none. */
    static Optional<String> ticketIn(String title) {
        Matcher m = title == null ? null : ISSUE.matcher(title);

        return m != null && m.find() ? Optional.of(m.group().toUpperCase(java.util.Locale.ROOT)) : Optional.empty();
    }

    /** Whether the build's verdict has been posted for the user — i.e. the run's story is over. */
    public boolean buildHandled(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);

        return e != null && Long.valueOf(buildId).equals(e.handled().posted().get(pr));
    }

    /** One settling wave as seen from outside: its number, what it re-runs, and the settle estimate. */
    public record WaveStatus(int wave, String what, Long etaEpochSec) {
    }

    /** Same as {@link #actorByGhLogin} but by the TC username — for follow-ups on an accepted command. */
    public Optional<GhActor> actor(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? Optional.empty() : actorOf(username, e);
    }

    /**
     * An option whose credential is gone is switched off. A token can leave without a 401 — dropped
     * as unusable when saved, made undecryptable by a rotated secret, or lost by an older build that
     * only dropped the token — and a switch left on then promises work that silently never happens.
     */
    private void switchOffOptionsWithoutTokens() {
        enrolled.replaceAll((u, e) -> {
            boolean ghGone = e.options().needsGh() && e.gh().token() == null;
            boolean jiraGone = e.options().autoVisa() && e.jira().token() == null;
            if (!ghGone && !jiraGone)
                return e;

            log.warn("options of {} switched off for want of a token: {}{}", u,
                ghGone ? "GitHub comment/checkstyle autofix " : "", jiraGone ? "auto-visa" : "");

            long now = System.currentTimeMillis();
            Enrollment next = e;
            if (ghGone)
                next = next.withGh(next.gh().rejected() ? next.gh() : Credential.refusedAt(now))
                    .withOptions(next.options().withoutGh());
            if (jiraGone)
                next = next.withJira(next.jira().rejected() ? next.jira() : Credential.refusedAt(now))
                    .withOptions(next.options().withoutVisa());

            return next;
        });
    }

    /**
     * Backfills {@code ghLogin} and {@code tz} for enrollments made before those were recorded
     * (one GitHub/JIRA call per such user, once); no-op when everything is already resolved.
     */
    public void ensureGhLogins() {
        switchOffOptionsWithoutTokens();
        enrolled.forEach((u, e) -> {
            String login = e.options().ghComment() && e.ghLogin() == null && e.gh().token() != null
                ? decrypt(e.gh()).flatMap(github::ghUser).filter(l -> !heldByAnother(l, u)).orElse(null) : null;
            String tz = e.tz() == null && e.jira().token() != null
                ? decrypt(e.jira()).flatMap(jira::myTimezone).orElse(null) : null;
            if (login == null && tz == null)
                return;

            // The lookups ran outside the map, so only what is still missing gets filled in.
            Enrollment next = enrolled.computeIfPresent(u, (k, cur) -> cur
                .withGhLogin(cur.ghLogin() == null && login != null ? login : cur.ghLogin())
                .withTz(cur.tz() == null && tz != null ? tz : cur.tz()));
            log.info("backfilled for {}: gh login {}, tz {}", u, next == null ? null : next.ghLogin(),
                next == null ? null : next.tz());
        });
    }

    /** Whether checkstyle autofix on own runs is on for the user. */
    public boolean styleFixOn(String username) {
        return options(username).styleFix();
    }

    /** Whether GitHub PR comments are on for the user. */
    public boolean ghOn(String username) {
        return options(username).ghComment();
    }

    /** Whether the standing auto-visa is on for the user. */
    public boolean visaOn(String username) {
        return options(username).autoVisa();
    }

    /** Whether auto-rerun of blocker suites is on for the user. */
    public boolean rerunOn(String username) {
        return options(username).autoRerun();
    }

    private Options options(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? Options.NONE : e.options();
    }

    /** Removes the enrollment and both stored tokens. */
    public void disable(String username) {
        if (enrolled.remove(username) != null)
            log.info("standing auto-visa disabled for {}", username);
    }

    public boolean enabled(String username) {
        return enrolled.containsKey(username);
    }

    /**
     * A suite of a running chain just finished red: settle it now instead of at the end of the chain.
     * The chain still has hours of suites to go, so a re-run queued at this moment runs alongside
     * them and the answer is usually in before the chain finishes — where the settled sweep would
     * only start the same re-run afterwards, adding its whole queue wait to the wall clock.
     *
     * <p>Same bar as the settled pass: only the chain triggerer's own enrollment, only with auto
     * re-run on, and only suites the analysis calls a blocker, a watch item or broken — a suite that
     * failed on pre-existing/flaky tests is left alone. Each suite is re-run once per chain, and a
     * chain that keeps producing them stops at {@link #TOP_QUEUE_LIMIT}: past that it is systemic and
     * the settled pass will say so. Nor does any go while the verdict asks to re-run more than
     * {@link #MAX_SUITES_PER_RERUN} suites, the settled pass's own bar: of the 60 suites one ci2 glitch broke on
     * PR 13655, the first ten would go to the top of the queue.
     */
    @EventListener
    void onSuiteFailedMidRun(RerunTracker.SuiteFailedMidRun ev) {
        earlyPool.execute(() -> {
            try {
                earlyRerun(ev);
            }
            catch (RuntimeException e) {
                log.info("early re-run of {} for PR {} skipped: {}", ev.suiteName(), ev.pr(), e.toString());
            }
        });
    }

    void earlyRerun(RerunTracker.SuiteFailedMidRun ev) {
        if (!automation || enrolled.isEmpty())
            return;

        java.util.Set<String> before = earlyReruns.getOrDefault(ev.chainBuildId(), java.util.Set.of());
        if (before.size() >= TOP_QUEUE_LIMIT || before.contains(ev.suite()))
            return; // already settled this suite, or this chain is failing wholesale

        // Any enrolled token can read who started the chain; only that person's enrollment may act.
        String who = lookup(token -> tc.buildTriggeredBy(token, ev.chainBuildId())).orElse(null);
        Enrollment e = who == null ? null : enrolled.get(who);
        if (e == null || !e.options().autoRerun() || e.tc().rejected())
            return;

        Optional<String> tcToken = decrypt(e.tc());
        if (tcToken.isEmpty())
            return;

        // The cached verdict of a running chain is usually older than the failure just announced, and
        // the announcement comes once: judged by a verdict that never saw the suite fail, it would look
        // innocent and the early re-run would be lost for good.
        Optional<AnalysisResult> res = asUser(who, () -> analyzer.analyze(tcToken.get(), ev.pr()));
        if (res.isPresent() && !sawRun(res.get(), ev.suiteBuildId()))
            res = asUser(who, () -> analyzer.analyzeAfterNow(tcToken.get(), ev.pr()));
        if (res.isEmpty() || !worthRerunning(res.get(), ev.suite()))
            return;
        int toRerun = WaveSuites.of(res.get()).all().size();
        if (toRerun > MAX_SUITES_PER_RERUN) {
            log.info("early re-run of {} for PR {} skipped: {} suites to re-run is too many, this looks systemic",
                ev.suiteName(), ev.pr(), toRerun);

            return;
        }

        // Created only now: a chain nobody re-runs for must not leave an empty memo behind.
        java.util.Set<String> done =
            earlyReruns.computeIfAbsent(ev.chainBuildId(), id -> ConcurrentHashMap.newKeySet());
        if (done.size() >= TOP_QUEUE_LIMIT || !done.add(ev.suite()))
            return; // a concurrent event beat us to it

        TcModel.Build b;
        try {
            b = asUser(who, () -> tc.triggerBuildReplacingQueued(tcToken.get(), ev.suite(), ev.pr(), true,
                "Early re-run by Ignite PR Checker: this suite failed while RunAll " + ev.chainBuildId()
                    + " is still running, settling it now rather than after the chain"));
        }
        catch (RuntimeException ex) {
            // The wave counts only what was re-queued; the announcement a restart repeats may try this one again.
            done.remove(ev.suite());

            throw ex;
        }
        rerunTracker.record(ev.pr(), b);
        // All re-runs of a running chain are its first wave, so the settled pass continues from here
        // instead of starting over — two waves per chain stays two. The chain is still running, so no
        // settled wave can precede it.
        String wave = earlyWave(done.size());
        waves.compute(ev.chainBuildId(), (id, r) -> {
            List<Long> queued = new ArrayList<>(r != null && r.queued() != null ? r.queued() : List.of());
            queued.add(b.id());

            return new Retry(ev.pr(), id, 1, wave, List.of(wave), r != null ? r.note() : null, who, queued);
        });
        log.info("early re-run of {} for PR {} queued at top (chain {} still running, build {})",
            ev.suiteName(), ev.pr(), ev.chainBuildId(), b.id());
    }

    /**
     * Hands the running RunAll chains of users with auto re-run on to the rerun tracker: its watch is
     * what raises {@link RerunTracker.SuiteFailedMidRun}, and on its own it only knew the chains the
     * checker had started or someone had open on the PR page. A chain started from the TeamCity UI ran
     * unwatched, so its failed suites waited for the settled pass. One call covers every running
     * chain; a suite that failed before the sweep saw its chain is still announced on the tracker's
     * first look, so the sweep's period can delay such an early re-run but never loses it.
     */
    private void watchRunningChains() {
        java.util.Set<String> rerunners = new java.util.HashSet<>();
        enrolled.forEach((user, e) -> {
            if (e.options().autoRerun() && !e.tc().rejected())
                rerunners.add(user);
        });
        if (rerunners.isEmpty())
            return; // nobody to re-run for: not worth a TeamCity call

        try {
            for (TcModel.Build chain : lookup(tc::runningRunAllChains)) {
                Matcher pr = chain.branchName() == null ? null : PR_BRANCH.matcher(chain.branchName());
                String who = chain.triggered() == null || chain.triggered().user() == null
                    ? null : chain.triggered().user().username();
                if (pr != null && pr.matches() && rerunners.contains(who))
                    rerunTracker.record(Integer.parseInt(pr.group(1)), chain);
            }
        }
        catch (RuntimeException e) {
            log.warn("running RunAll chains not handed to the rerun tracker this sweep: {}", e.toString());
        }
    }

    /** Whether the analysis has looked at this suite build: something in it is anchored there, checked or not. */
    private static boolean sawRun(AnalysisResult r, long suiteBuildId) {
        return java.util.stream.Stream.of(r.blockers(), r.watch(), r.filtered(), r.unverified())
            .flatMap(List::stream).anyMatch(v -> v.suiteBuildId() == suiteBuildId)
            || r.brokenSuites().stream().anyMatch(b -> b.suiteBuildId() == suiteBuildId);
    }

    /**
     * Whether the analysis blames this suite for something a re-run can settle. A suite that failed only because the
     * Build it needed failed is not: re-run, each would pull a new Build of its own.
     */
    static boolean worthRerunning(AnalysisResult r, String suite) {
        WaveSuites w = WaveSuites.of(r);

        return w.blockers().contains(suite) || w.watch().contains(suite) || w.broken().contains(suite);
    }

    /**
     * Sweep: settles each open PR the list holds (see {@link #settle}), then the PRs out of the list that
     * still have a living comment or a wave open. Events settle a run as soon as its chain or a re-run
     * finishes; the sweep keeps the ones that wait going and catches what an event missed. A PR at rest is
     * not looked up again while TeamCity says no RunAll of it finished since the last sweep.
     */
    @Scheduled(fixedDelay = SWEEP_MS, initialDelay = 180_000)
    void sweep() {
        long t0 = System.currentTimeMillis();
        lastSweepAt = t0;
        lastSweepMs = 0;
        if (!automation)
            return;

        donateWarmTokens(); // keeps the background pool alive between visitors
        if (enrolled.isEmpty())
            return;

        // Any enrolled user's TC token can look up builds; per-PR analysis uses the triggerer's own.
        if (!anyLiveTcToken())
            return;

        watchRunningChains();

        List<PrSummary> listed = github.openPrs();
        resting.keySet().retainAll(listed.stream().map(PrSummary::number).toList());
        Set<Integer> finished = chainsFinishedSinceLastSweep(t0);
        int posted = 0;
        for (PrSummary pr : listed) {
            if (finished == null || finished.contains(pr.number()) || !resting(pr.number()))
                posted += settle(pr);
        }
        settleUnlisted(listed);

        lastSweepMs = System.currentTimeMillis() - t0;
        if (posted > 0)
            log.info("standing auto-visa sweep: {} visa(s) posted", posted);
    }

    /**
     * The PRs with a RunAll chain finished since the last sweep, by one TeamCity call across all branches; null when
     * every listed PR is to be looked up: on the first sweep, on every {@link #FULL_SWEEP_EVERY}th one, and whenever
     * TeamCity cannot say. Looking each PR up cost 50 calls a sweep, nearly all of them to learn that nothing changed.
     */
    private Set<Integer> chainsFinishedSinceLastSweep(long now) {
        long since = sweptUpTo;
        sweptUpTo = now;
        if (since == 0 || ++sweepsSinceFull >= FULL_SWEEP_EVERY) {
            sweepsSinceFull = 0;

            return null;
        }

        try {
            return lookup(token -> tc.prsWithChainsFinishedAfter(token, (since - SWEEP_OVERLAP_MS) / 1000))
                .orElse(null);
        }
        catch (RuntimeException e) {
            log.warn("RunAll chains finished since the last sweep not listed, every PR is looked up: {}", e.toString());

            return null;
        }
    }

    /**
     * Whether the PR's last settle left nothing to do until a RunAll of it finishes: the run it found is handled, and
     * no wave, decision or living comment of the PR waits for a settle.
     */
    private boolean resting(int pr) {
        Long run = resting.get(pr);
        if (run == null || deciding.containsKey(pr) || waves.values().stream().anyMatch(r -> r.pr() == pr))
            return false;

        return enrolled.values().stream().noneMatch(e -> waitsForSettle(e, pr, run));
    }

    /**
     * Whether a living comment of the user's on the PR waits for a settle: one still saying "re-run in progress", or a
     * verdict comment of a run before {@code latest} not yet marked superseded.
     */
    private static boolean waitsForSettle(Enrollment e, int pr, long latest) {
        GhThread g = e.handled().ghThreads().get(pr);
        JiraThread j = e.handled().jiraThreads().get(pr);
        boolean ghWaits = g != null && (!g.unmarked().isEmpty() || open(e, pr, g.buildId(), g.done())
            || g.buildId() < latest && !g.superseded());

        return ghWaits || j != null && open(e, pr, j.buildId(), j.done());
    }

    /**
     * A finished chain is settled at once: its first wave of re-runs goes in, or its verdict comes out. The settle
     * may wait behind others for minutes, and the page must not call the chain's verdict final meanwhile.
     */
    @EventListener
    void onChainFinished(RerunTracker.ChainFinished ev) {
        if (!ev.cancelled() && anyRerunner())
            deciding.merge(ev.pr(), ev.chainBuildId(), Math::max);
        settleSoon(ev.pr());
    }

    /** Whether anyone's finished runs may get auto re-runs: someone has them on, with a token to settle with. */
    private boolean anyRerunner() {
        return enrolled.values().stream().anyMatch(e -> e.options().autoRerun() && backgroundToken(e).isPresent());
    }

    /** A finished re-run is settled at once: once its wave is over, the next one goes in or the verdict comes out. */
    @EventListener
    void onRerunFinished(RerunTracker.RerunFinished ev) {
        settleSoon(ev.pr());
    }

    /**
     * Settles the PR on the settle thread: a finished chain gets its first wave or its verdict, and a
     * finished wave the next step, within seconds instead of at the next sweep. Several asks while one is
     * queued make one settle.
     */
    public void settleSoon(int pr) {
        if (enrolled.isEmpty() || !settleQueued.add(pr))
            return;

        settler.execute(() -> {
            settleQueued.remove(pr);
            try {
                settleNow(pr);
            }
            catch (RuntimeException e) {
                log.warn("settling PR {} failed: {}", pr, e.toString());
            }
        });
    }

    /**
     * Asks for a settle of the PR on behalf of someone who waits for it (a /run-all story), at most once a
     * sweep period: the sweep goes only through the 50 most recently updated PRs.
     */
    public void settleRequested(int pr) {
        long now = System.currentTimeMillis();
        Long last = settleAsked.get(pr);
        if (last != null && now - last < SWEEP_MS)
            return;

        settleAsked.put(pr, now);
        settleSoon(pr);
    }

    /** Settles the PR whether the sweep lists it or not; a closed PR has its open living comments ended. */
    void settleNow(int pr) {
        if (!anyLiveTcToken())
            return;

        Optional<PrSummary> listed = github.openPrs().stream().filter(p -> p.number() == pr).findFirst();
        if (listed.isPresent()) {
            settle(listed.get());

            return;
        }

        Optional<GithubClient.PullState> state = github.pullState(pr);
        if (state.isPresent() && state.get().open())
            settle(new PrSummary(pr, state.get().title(), null, null, null, null));
        else
            closePr(pr, state.map(GithubClient.PullState::title).orElse(null),
                state.map(GithubClient.PullState::merged).orElse(false));
    }

    /**
     * The PRs out of the sweep's list that still have a living comment, a wave open or a finished run to decide on.
     * The list holds the 50 most recently updated open PRs only, and a run whose PR dropped out of it stayed "in
     * progress" for good.
     */
    private void settleUnlisted(List<PrSummary> listed) {
        Set<Integer> prs = new java.util.TreeSet<>();
        enrolled.values().forEach(e -> {
            e.handled().ghThreads().forEach((pr, t) -> {
                if (open(e, pr, t.buildId(), t.done()))
                    prs.add(pr);
            });
            e.handled().jiraThreads().forEach((pr, t) -> {
                if (open(e, pr, t.buildId(), t.done()))
                    prs.add(pr);
            });
        });
        waves.values().forEach(r -> prs.add(r.pr()));
        prs.addAll(deciding.keySet());
        listed.forEach(p -> prs.remove(p.number()));

        for (int pr : prs) {
            try {
                settleNow(pr);
            }
            catch (RuntimeException e) {
                log.warn("PR {} out of the sweep's list not settled this time: {}", pr, e.toString());
            }
        }
    }

    /**
     * Settles the PR's latest finished RunAll for the user who triggered it: while waves remain, re-runs the
     * suites its verdict blames; then posts that verdict, once, to the PR's ticket and the PR. A run that a
     * newer one of the PR is replacing gets neither. The living comments of the runs before it get their
     * last line. One PR at a time, whoever asks, so a wave is never queued twice. 1 when a visa was posted.
     */
    int settle(PrSummary pr) {
        synchronized (settleLock) {
            String who = null;
            boolean undecided = false;
            boolean rests = false;
            long settling = Long.MAX_VALUE;
            resting.remove(pr.number());
            try {
                closedPrs.remove(pr.number());
                Optional<TcModel.Build> build = lookup(token -> tc.findRunAllBuildForPr(token, pr.number()));
                if (build.isEmpty()) {
                    rests = true;
                    return 0;
                }

                long buildId = build.get().id();
                settling = buildId;
                newestSeen.merge(pr.number(), buildId, Math::max);
                endReplaced(pr, buildId);
                markSuperseded(pr.number(), buildId);

                who = TcClient.starter(build.get());
                Enrollment e = who == null ? null : enrolled.get(who);
                if (e == null) {
                    waves.remove(buildId); // nobody settles this run any more
                    rests = true;
                    return 0;
                }
                if (e.tc().rejected())
                    return 0; // a refused token pauses its owner's options until a working one comes

                Long last = e.handled().posted().get(pr.number());
                if (last != null && last == buildId) {
                    // v1.20.11 marked runs handled without dropping their waves, which then read as settling for good.
                    settled(e, pr.number(), buildId);
                    rests = true;
                    return 0; // this run is already handled (visa'd, or settled without one)
                }

                // Only runs that FINISHED after an option was switched on get acted upon by it: the
                // first sweep must not spam week-old tickets with back-filled visas or re-runs.
                long finishedMs = TcDates.epochSeconds(build.get().finishDate()) * 1000L;
                Options acting = e.actingOn(finishedMs);
                if (!acting.settlesRuns()) {
                    endThreads(who, e, pr, b -> b == buildId,
                        e.options().settlesRuns() ? visas.notFollowedAfterChange() : visas.notFollowed(), 0);
                    settled(e, pr.number(), buildId);
                    rests = true;
                    return 0; // nothing to post or re-run: a verdict computed now would go nowhere
                }

                Optional<String> tcToken = decrypt(e.tc());
                Optional<String> jiraToken = decrypt(e.jira());
                Optional<String> ghToken = decrypt(e.gh());
                if (tcToken.isEmpty() || (e.options().autoVisa() && jiraToken.isEmpty())
                    || (e.options().ghComment() && ghToken.isEmpty())) {
                    enrolled.remove(who);
                    log.warn("standing options for {} dropped: tokens undecryptable (secret rotated?)", who);
                    rests = true;
                    return 0;
                }

                if (acting.autoRerun()) {
                    deciding.put(pr.number(), buildId);
                    undecided = true;
                    if (rerunTracker.hasActive(pr.number())) {
                        refreshInterim(who, e, ghToken, pr.number(), buildId, tcToken.get());
                        return 0; // the verdict waits until the re-runs settle
                    }
                }

                Optional<AnalysisResult> res = verdictOf(tcToken.get(), pr.number(), buildId);
                if (res.isEmpty() || res.get().buildId() != buildId)
                    return 0; // raced with a newer run; the next sweep settles it
                if (analyzer.stillRetrying(res.get()))
                    return 0; // TeamCity errors left part of it unchecked: the next sweep tries it again first

                long newer = newerRunGoing(pr.number(), buildId, res.get(), tcToken.get());
                if (newer > 0) {
                    undecided = false; // the newer run takes its place
                    newestSeen.merge(pr.number(), newer, Math::max);
                    if (!Long.valueOf(buildId).equals(heldForNewer.put(pr.number(), buildId)))
                        log.info("PR {}: RunAll {} is not settled while the newer RunAll {} goes", pr.number(),
                            buildId, newer);
                    return 0;
                }

                if (acting.autoRerun() && queueWave(who, pr.number(), buildId, res.get(), tcToken.get()))
                    return 0; // the verdict waits until the wave settles

                undecided = false;
                rests = true;
                return postSettled(who, e, acting, pr, buildId, res.get(), tcToken.get(), jiraToken.orElse(null),
                    ghToken.orElse(null));
            }
            catch (RuntimeException ex) {
                // Lookups never get here with a refusal; what does came from the triggerer's own token.
                if (who != null && tcRefused(ex))
                    markTcRejected(who);
                log.warn("standing auto-visa sweep: PR {} skipped: {}", pr.number(), ex.toString());
                undecided = true; // nothing was decided: the page goes on saying what it said
                rests = false;

                return 0;
            }
            finally {
                // A run still to be decided on stays so between settles: the page must not call its verdict final.
                if (!undecided)
                    decided(pr.number(), settling);
                if (rests)
                    resting.put(pr.number(), settling == Long.MAX_VALUE ? 0 : settling);
            }
        }
    }

    /**
     * The PR's runs up to {@code upTo} are decided on. A chain that finished while this settle looked at the run
     * before it stays marked for its own settle.
     */
    private void decided(int pr, long upTo) {
        deciding.computeIfPresent(pr, (k, marked) -> marked > upTo ? marked : null);
    }

    /**
     * The verdict of the PR's run {@code buildId} to act on. The analysis finds the PR's run through a lookup
     * cached for half a minute: right after the chain finished, when its event settles it, that lookup may
     * still name the run before it, and then the run is looked up afresh.
     */
    private Optional<AnalysisResult> verdictOf(String tcToken, int pr, long buildId) {
        Optional<AnalysisResult> res = analyzer.analyzeForAction(tcToken, pr);
        if (res.isEmpty() || res.get().buildId() >= buildId)
            return res;

        res = analyzer.forceRefresh(tcToken, pr);
        // A compute under way since before the finish is shared, and it saw the chain unfinished.
        if (res.isPresent() && res.get().buildId() == buildId && res.get().finishedAt() == 0)
            res = analyzer.analyzeAfterNow(tcToken, pr);

        return res;
    }

    /**
     * A RunAll of the PR newer than {@code buildId} that is still going; 0 when there is none. A verdict
     * posted meanwhile would be out of date at once. The tracker knows the chains it watches; one only the
     * analysis saw going is asked about, as the verdict may have been computed before it was cancelled.
     */
    private long newerRunGoing(int pr, long buildId, AnalysisResult res, String tcToken) {
        long tracked = rerunTracker.newestChainUnderWay(pr);
        if (tracked > buildId)
            return tracked;
        if (!res.live() || res.liveBuildId() <= buildId)
            return 0;

        try {
            TcModel.Build b = tc.getBuildState(tcToken, res.liveBuildId());

            return b != null && !"finished".equalsIgnoreCase(b.state()) ? res.liveBuildId() : 0;
        }
        catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 404)
                return 0; // TeamCity no longer has it

            throw e;
        }
    }

    /**
     * Queues the next wave of re-runs of the suites the verdict blames, while waves remain; true when one
     * went in, so the verdict waits for it. Nothing is posted meanwhile: the PR comment and the visa come
     * once, settled, and the PR page and the /run-all story tell the waves as they go.
     */
    private boolean queueWave(String who, int pr, long buildId, AnalysisResult res, String tcToken) {
        Retry r = waves.get(buildId);
        int attempts = r != null ? r.attempts() : 0;
        WaveSuites w = WaveSuites.of(res);
        List<String> suites = w.all();
        String what = suitesLabel(w.blockers().size(), w.watch().size(), w.broken().size(), w.cancelled().size());
        if (!suites.isEmpty() && suites.size() > MAX_SUITES_PER_RERUN && attempts == 0) {
            // Systemic breakage: re-running dozens of suites would only hammer the shared CI.
            waves.put(buildId, new Retry(pr, buildId, MAX_RERUNS, what, List.of(),
                "(i) Auto re-run skipped: " + suites.size() + " suites is too many — "
                    + "this looks systemic; fix the cause and re-trigger RunAll.", who, List.of()));

            return false;
        }
        if (suites.isEmpty() || attempts >= MAX_RERUNS)
            return false;

        // <= TOP_QUEUE_LIMIT suites jump the queue; more go in normally (tail) so the
        // re-run doesn't shove everyone else's builds back.
        boolean top = suites.size() <= TOP_QUEUE_LIMIT;
        List<String> history = new ArrayList<>(r != null && r.history() != null ? r.history() : List.of());
        history.add(what);
        String tcComment = "Auto re-run #" + history.size() + " of up to " + MAX_RERUNS
            + " by Ignite PR Checker, settling RunAll " + buildId;
        List<Long> queued = new ArrayList<>(r != null && r.queued() != null ? r.queued() : List.of());
        int before = queued.size();
        RuntimeException failed = null;
        for (String suite : suites) {
            try {
                TcModel.Build b = tc.triggerBuildReplacingQueued(tcToken, suite, pr, top, tcComment);
                rerunTracker.record(pr, b);
                queued.add(b.id());
            }
            catch (RuntimeException ex) {
                if (tcRefused(ex))
                    throw ex;

                log.warn("auto re-run of {} for PR {} not queued: {}", suite, pr, ex.toString());
                failed = ex;
            }
        }
        // A wave counts once anything of it is queued: settled again, it would go in a second time.
        if (queued.size() == before)
            throw failed;

        String note = top ? (r != null ? r.note() : null)
            : "(i) " + suites.size() + " suites were re-queued at the TAIL of the queue "
                + "(too many to jump it without disturbing others) — this may need a real fix "
                + "and a fresh RunAll rather than re-runs.";
        waves.put(buildId, new Retry(pr, buildId, attempts + 1, what, history, note, who, queued));
        log.info("auto-rerun {}/{} for PR {}: {} re-queued at {}", attempts + 1, MAX_RERUNS, pr, what,
            top ? "top" : "tail");

        return true;
    }

    /**
     * Posts the settled verdict — the visa to the PR's ticket and the PR comment, as the {@code acting} options
     * say — and marks the run handled. 1 when a visa went out.
     */
    private int postSettled(String who, Enrollment e, Options acting, PrSummary pr, long buildId, AnalysisResult res,
        String tcToken, String jiraToken, String ghToken) {
        Retry done = waves.get(buildId);
        String note = done != null ? done.note() : null;
        String settled = done != null ? settledLine(done.history()) : null;
        PendingCommits.Ahead ahead = pending.since(tcToken, pr.number(), buildId);
        Integer commitsAhead = ahead == null ? null : Math.max(ahead.commits(), 1);
        String sha = revision(tcToken, buildId);

        int posted = 0;
        if (acting.autoVisa()) {
            String body = visas.compose(pr.number(), res, commitsAhead, sha);
            if (ahead != null)
                body = body + "\n\n_" + revisions(ahead, "{{", "}}") + "_";
            if (settled != null)
                body = body + "\n\n" + settled;
            if (note != null)
                body = body + "\n\n" + note;
            posted = postVisa(who, e, pr, buildId, res, jiraToken, body, sha);
        }
        if (acting.ghComment()) {
            String md = visas.composeMarkdown(pr.number(), res, commitsAhead, sha);
            if (ahead != null)
                md = md + "\n\n_" + revisions(ahead, "`", "`") + "_";
            if (settled != null)
                md = md + "\n\n_" + settled + "_";
            if (note != null)
                md = md + "\n\n_" + note + "_";
            if (Caveats.standing(res, commitsAhead) == Caveats.Standing.BLOCKERS)
                md = md + NEXT_STEP;
            upsertGhComment(who, e, ghToken, pr.number(), buildId, md, true);
        }
        settled(e, pr.number(), buildId);

        return posted;
    }

    /**
     * Posts the visa to the ticket the PR title names. None when it names none, and no second one when the
     * last visa there said the same of the same revision: the ticket filled up with copies.
     */
    private int postVisa(String who, Enrollment e, PrSummary pr, long buildId, AnalysisResult res, String jiraToken,
        String body, String sha) {
        Optional<String> issue = ticketIn(pr.title());
        if (issue.isEmpty()) {
            ticketless.add(buildId);
            log.info("no visa for PR {} (build {}, by {}): its title names no IGNITE ticket", pr.number(), buildId, who);

            return 0;
        }

        String verdict = verdictKey(res);
        JiraThread last = e.handled().jiraThreads().get(pr.number());
        if (last != null && issue.get().equals(last.issue()) && sha != null && sha.equals(last.sha())
            && verdict.equals(last.verdict())) {
            e.handled().jiraThreads().put(pr.number(),
                new JiraThread(buildId, last.commentId(), last.issue(), sha, verdict, true));
            log.info("no new visa for PR {} (build {}, by {}): the last one in {} says the same of revision {}",
                pr.number(), buildId, who, issue.get(), sha);

            return 0;
        }

        String url = upsertVisa(who, e, jiraToken, issue.get(), pr.number(), buildId, body, sha, verdict);
        postedTotal.incrementAndGet();
        log.info("standing auto-visa posted for PR {} (build {}, by {}) -> {}", pr.number(), buildId, who,
            url != null ? url : "updated in place");

        return 1;
    }

    /** The run is handled: the sweep leaves it, and its waves and mid-run memo are spent. */
    private void settled(Enrollment e, int pr, long buildId) {
        e.handled().posted().put(pr, buildId);
        waves.remove(buildId);
        earlyReruns.remove(buildId);
        heldForNewer.remove(pr, buildId);
        deciding.remove(pr, buildId);
    }

    /** The revision the build ran on; null when TeamCity does not say. */
    private String revision(String tcToken, long buildId) {
        try {
            return tc.buildRevision(tcToken, buildId).orElse(null);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** What a verdict says, for telling two visas of one revision apart: what is blamed, and the caveats. */
    static String verdictKey(AnalysisResult r) {
        java.util.function.Function<List<TestVerdict>, String> tests = list -> list.stream()
            .map(v -> v.suite() + ":" + v.name()).sorted().collect(java.util.stream.Collectors.joining(","));

        return String.join("|", tests.apply(r.blockers()), tests.apply(r.watch()), tests.apply(r.unverified()),
            r.brokenSuites().stream().map(BrokenSuite::suite).sorted().collect(java.util.stream.Collectors.joining(",")),
            String.join(";", Caveats.keyOf(r)));
    }

    /** "Tested revision abc1234; the PR head is now def5678." with the revisions wrapped in the markup's code marks. */
    private static String revisions(PendingCommits.Ahead ahead, String open, String close) {
        return "Tested revision " + open + ahead.builtShort() + close + "; the PR head is now " + open
            + ahead.headShort() + close + ".";
    }

    /**
     * A living comment from before this release says "re-run in progress"; while its re-runs go, its settle
     * estimate stays honest. Runs from now on get no such comment: theirs comes out once, settled.
     */
    private void refreshInterim(String who, Enrollment e, Optional<String> ghToken, int pr, long buildId,
        String tcToken) {
        GhThread t = e.handled().ghThreads().get(pr);
        if (!e.options().ghComment() || ghToken.isEmpty() || t == null || t.buildId() != buildId || t.done())
            return;

        Optional<AnalysisResult> res = analyzer.analyzeForAction(tcToken, pr);
        if (res.isEmpty() || res.get().buildId() != buildId)
            return;

        Retry r = waves.get(buildId);
        String verdict = visas.composeMarkdown(pr, res.get(), null, revision(tcToken, buildId));
        upsertGhComment(who, e, ghToken.get(), pr, buildId, verdict
            + pendingLine(r != null ? r.what() : "re-run suite(s)", r != null ? r.attempts() : 1,
                r != null ? r.history() : null, activeEtaEpoch(pr), e.tz(), "**"), false);
    }

    /**
     * Ends the living comments of the PR's runs before {@code latest}: comments from before this release
     * that a newer run left "in progress" for good. Their waves go too.
     */
    private void endReplaced(PrSummary pr, long latest) {
        VisaService.Ending ending = visas.replaced(pr.number(), latest);
        enrolled.forEach((user, e) -> endThreads(user, e, pr, b -> b < latest, ending, latest));
        waves.values().removeIf(r -> r.pr() == pr.number() && r.buildId() < latest && !rerunTracker.tracks(r.buildId()));
    }

    /**
     * Marks the PR's verdict comments of runs before {@code latest}, whoever posted them, as superseded: the PR
     * read like a pile of current verdicts, the old red ones among them. Each gets one line, once, edited with its
     * owner's token. A comment that already says a newer run replaced it, or that no token of its owner can edit
     * any more, is only marked so here; a failure leaves it for the next settle, also once a newer verdict of its
     * owner has taken its place.
     */
    private void markSuperseded(int pr, long latest) {
        enrolled.forEach((user, e) -> {
            GhThread t = e.handled().ghThreads().get(pr);
            if (t == null)
                return;

            for (long comment : t.unmarked())
                if (supersede(user, e, pr, comment, latest))
                    e.handled().ghThreads().computeIfPresent(pr, (k, now) -> now.marked(comment));
            if (t.buildId() >= latest || t.superseded() || open(e, pr, t.buildId(), t.done()))
                return; // a comment still waiting for its re-runs gets its own last line from endReplaced

            if (supersede(user, e, pr, t.commentId(), latest))
                e.handled().ghThreads().computeIfPresent(pr,
                    (k, now) -> now.commentId() == t.commentId() ? now.supersededNow() : now);
        });
    }

    /**
     * Puts the superseded line on one verdict comment of the user's. False when reading or editing it failed and it
     * is to be tried again.
     */
    private boolean supersede(String user, Enrollment e, int pr, long commentId, long latest) {
        Optional<String> token = decrypt(e.gh());
        Optional<String> body;
        try {
            body = token.isEmpty() ? Optional.empty() : github.commentBody(commentId);
        }
        catch (RuntimeException ex) {
            log.warn("reading verdict comment {} of PR {} failed, marked superseded later: {}", commentId, pr,
                ex.toString());

            return false;
        }
        try {
            if (body.isPresent() && !visas.saysSuperseded(body.get()))
                github.updatePrComment(token.get(), commentId, body.get().stripTrailing()
                    + visas.superseded(pr, latest));
            log.info("verdict comment {} of PR {} marked superseded by RunAll {}", commentId, pr, latest);

            return true;
        }
        catch (RuntimeException ex) {
            if (refused(ex)) {
                dropGhToken(user);

                return true;
            }

            log.warn("marking verdict comment {} of PR {} superseded failed, tried again later: {}", commentId, pr,
                ex.toString());

            return false;
        }
    }

    /** A closed PR's runs are not settled any more: its open living comments get their last line, its waves go. */
    private void closePr(int pr, String title, boolean merged) {
        synchronized (settleLock) {
            closedPrs.put(pr, merged);
            deciding.remove(pr);
            PrSummary summary = new PrSummary(pr, title, null, null, null, null);
            VisaService.Ending ending = visas.prClosed(merged);
            enrolled.forEach((user, e) -> endThreads(user, e, summary, b -> true, ending, 0));
            if (waves.values().removeIf(r -> r.pr() == pr))
                log.info("PR {} was {}: its auto re-run waves are dropped", pr, merged ? "merged" : "closed");
        }
    }

    /**
     * Ends the user's open living comments of the PR whose build {@code ofBuild} accepts; {@code newer} is the
     * RunAll that replaced their runs, 0 when none did.
     */
    private void endThreads(String user, Enrollment e, PrSummary pr, java.util.function.LongPredicate ofBuild,
        VisaService.Ending ending, long newer) {
        GhThread g = e.handled().ghThreads().get(pr.number());
        if (g != null && ofBuild.test(g.buildId()) && open(e, pr.number(), g.buildId(), g.done()))
            endGh(user, e, pr.number(), g, ending.markdown(), newer);

        JiraThread j = e.handled().jiraThreads().get(pr.number());
        if (j != null && ofBuild.test(j.buildId()) && open(e, pr.number(), j.buildId(), j.done()))
            endJira(user, e, pr, j, ending.wiki());
    }

    /**
     * Whether a living comment may still say "re-run in progress": it is not marked ended, and its run was not
     * settled. Comments from before this release carry no mark; the settled ones were finished then.
     */
    private static boolean open(Enrollment e, int pr, long buildId, boolean done) {
        return !done && !Long.valueOf(buildId).equals(e.handled().posted().get(pr));
    }

    /**
     * Puts the last line in place of a PR comment's "re-run in progress" one. A comment without it was
     * finished already: it is only marked ended, or marked superseded when the {@code newer} RunAll replaced
     * its run. One GitHub no longer has, or that no token of its owner can edit any more, is forgotten. A
     * failure leaves it for the next settle.
     */
    private void endGh(String user, Enrollment e, int pr, GhThread t, String line, long newer) {
        Optional<String> token = decrypt(e.gh());
        Optional<String> body;
        try {
            body = token.isEmpty() ? Optional.empty() : github.commentBody(t.commentId());
        }
        catch (RuntimeException ex) {
            // The comment is read under the app's token: a refusal there says nothing of the owner's PAT.
            log.warn("reading living comment {} of PR {} failed, tried again later: {}", t.commentId(), pr,
                ex.toString());

            return;
        }
        if (body.isEmpty()) {
            e.handled().ghThreads().remove(pr, t);
            log.info("living comment {} of PR {} forgotten: {}", t.commentId(), pr,
                token.isEmpty() ? "no GitHub token of " + user + " to edit it with" : "GitHub has no such comment");

            return;
        }
        try {
            if (pendingAt(body.get()) >= 0)
                github.updatePrComment(token.get(), t.commentId(), withLastLine(body.get(), line));
            else if (newer > 0 && !visas.saysSuperseded(body.get()))
                github.updatePrComment(token.get(), t.commentId(), body.get().stripTrailing()
                    + visas.superseded(pr, newer));
            e.handled().ghThreads().replace(pr, t, newer > 0 ? t.ended().supersededNow() : t.ended());
            log.info("living comment {} of PR {} (build {}) ended", t.commentId(), pr, t.buildId());
        }
        catch (RuntimeException ex) {
            if (refused(ex)) {
                dropGhToken(user);
                e.handled().ghThreads().remove(pr, t);

                return;
            }

            log.warn("ending living comment {} of PR {} failed, tried again later: {}", t.commentId(), pr,
                ex.toString());
        }
    }

    /** The same for a visa in the ticket; see {@link #endGh}. */
    private void endJira(String user, Enrollment e, PrSummary pr, JiraThread t, String line) {
        Optional<String> token = decrypt(e.jira());
        String issue = t.issue() != null ? t.issue() : ticketIn(pr.title()).orElse(null);
        try {
            Optional<String> body = token.isEmpty() || issue == null || t.commentId() == null ? Optional.empty()
                : jira.commentBody(token.get(), issue, t.commentId());
            if (body.isEmpty()) {
                e.handled().jiraThreads().remove(pr.number(), t);
                log.info("living visa {} of PR {} forgotten: {}", t.commentId(), pr.number(),
                    token.isEmpty() ? "no JIRA token of " + user + " to edit it with"
                        : issue == null ? "no ticket known" : "JIRA has no such comment");

                return;
            }
            if (pendingAt(body.get()) >= 0)
                jira.updateComment(token.get(), issue, t.commentId(), withLastLine(body.get(), line));
            e.handled().jiraThreads().replace(pr.number(), t, t.ended());
            log.info("living visa {} of PR {} (build {}) ended", t.commentId(), pr.number(), t.buildId());
        }
        catch (RuntimeException ex) {
            if (refused(ex)) {
                dropJiraToken(user);
                e.handled().jiraThreads().remove(pr.number(), t);

                return;
            }

            log.warn("ending living visa {} of PR {} failed, tried again later: {}", t.commentId(), pr.number(),
                ex.toString());
        }
    }

    /** Where a living comment's "re-run in progress" line starts; -1 when it has none. */
    private static int pendingAt(String body) {
        return body.indexOf(PENDING_MARK);
    }

    /** The comment with its "re-run in progress" line, and all after it, replaced by {@code line}. */
    private static String withLastLine(String body, String line) {
        return body.substring(0, pendingAt(body)).stripTrailing() + "\n\n" + line;
    }

    /**
     * The whole run's story lives in ONE PR comment, edited in place when it is posted again for the same
     * build. A failure never breaks the sweep (the JIRA visa may already be out), and a failed edit falls back
     * to a fresh comment rather than losing the verdict. {@code done}: the comment carries the settled verdict.
     */
    private void upsertGhComment(String who, Enrollment e, String ghToken, int pr, long buildId, String md,
        boolean done) {
        try {
            GhThread t = e.handled().ghThreads().get(pr);
            if (t != null && t.buildId() == buildId) {
                try {
                    github.updatePrComment(ghToken, t.commentId(), md);
                    e.handled().ghThreads().put(pr, new GhThread(buildId, t.commentId(), done, false, t.unmarked()));
                    log.info("standing GitHub comment updated for PR {} (build {})", pr, buildId);

                    return;
                }
                catch (RuntimeException editEx) {
                    log.warn("editing GitHub comment {} for PR {} failed ({}), posting fresh",
                        t.commentId(), pr, editEx.toString());
                }
            }
            GithubClient.PostedComment posted = github.addPrComment(ghToken, pr, md);
            e.handled().ghThreads().put(pr, new GhThread(buildId, posted.id(), done, false,
                unmarkedAfter(e, pr, t, buildId)));
            log.info("standing GitHub comment posted for PR {} (build {}) -> {}", pr, buildId, posted.htmlUrl());
        }
        catch (RuntimeException ghEx) {
            if (refused(ghEx)) {
                dropGhToken(who);

                return;
            }

            log.warn("standing GitHub comment for PR {} failed: {}", pr, ghEx.toString());
        }
    }

    /**
     * The owner's verdict comments on the PR still to be marked superseded once a comment of {@code buildId} takes
     * the place of {@code t}: its own, when it is an earlier verdict not marked yet.
     */
    private static List<Long> unmarkedAfter(Enrollment e, int pr, GhThread t, long buildId) {
        if (t == null)
            return List.of();
        if (t.buildId() >= buildId || t.superseded() || open(e, pr, t.buildId(), t.done()))
            return t.unmarked();

        List<Long> out = new ArrayList<>(t.unmarked());
        out.add(t.commentId());

        return out;
    }

    public int enrolledCount() {
        return enrolled.size();
    }

    public int postedCount() {
        return postedTotal.get();
    }

    public long lastSweepAt() {
        return lastSweepAt;
    }

    public long lastSweepMs() {
        return lastSweepMs;
    }

    @Override
    public String fileName() {
        return "standing-visas.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        enrolled.forEach((u, e) -> snap.add(Persisted.of(u, e)));
        dropSpentEarlyReruns();
        Map<Long, List<String>> early = new HashMap<>();
        earlyReruns.forEach((build, suites) -> early.put(build, List.copyOf(suites)));
        Snapshots.writeAtomic(mapper, file, new Snapshot(snap, newestPerPr(), early, new HashMap<>(waves)));
    }

    /**
     * The mid-run memo only stops a restart from re-running a suite of a chain that is still going.
     * A chain the tracker no longer watches and no PR is settling has finished or was superseded, and
     * the sweep never sees it again to clear it.
     */
    private void dropSpentEarlyReruns() {
        java.util.Set<Long> settling = new java.util.HashSet<>();
        settling.addAll(waves.keySet());
        earlyReruns.entrySet().removeIf(en -> en.getValue().isEmpty()
            || !settling.contains(en.getKey()) && !rerunTracker.tracks(en.getKey()));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted[] enrollments;
        try {
            Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
            enrollments = s.enrollments() == null ? new Persisted[0] : s.enrollments().toArray(new Persisted[0]);
            if (s.waves() != null)
                s.waves().forEach((build, r) -> waves.put(build, r.mergingEarlyWaves()));
            if (s.retries() != null)
                s.retries().forEach((pr, r) -> waves.putIfAbsent(r.buildId(), r.ofPr(pr).mergingEarlyWaves()));
            if (s.earlyReruns() != null)
                s.earlyReruns().forEach((build, suites) -> earlyReruns
                    .computeIfAbsent(build, id -> ConcurrentHashMap.newKeySet()).addAll(suites));
        }
        catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
            // The pre-retries snapshot was a bare enrollment array — read it once, save in the new shape.
            enrollments = mapper.readValue(file.toFile(), Persisted[].class);
        }

        for (Persisted p : enrollments)
            enrolled.put(p.username(), p.enrollment());
    }

    /**
     * One user's standing options. Every change goes through {@link ConcurrentMap#compute} with one of
     * the {@code with*} copies, so a settings click, a refused token and the poll's backfill can never
     * undo each other. {@code enabledAt} is when an option that acts on runs was last switched on, and
     * {@code onSince} when each one was; snapshots of v1.20.11 and before have {@code enabledAt} only.
     */
    private record Enrollment(Credential tc, Credential jira, Credential gh, String ghLogin, String tz,
        long enabledAt, Options options, Handled handled, Map<Option, Long> onSince) {
        static Enrollment fresh(Credential tc) {
            return new Enrollment(tc, Credential.NONE, Credential.NONE, null, null, 0, Options.NONE, Handled.empty(),
                Map.of());
        }

        Enrollment withTc(Credential c) {
            return new Enrollment(c, jira, gh, ghLogin, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withJira(Credential c) {
            return new Enrollment(tc, c, gh, ghLogin, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withGh(Credential c) {
            return new Enrollment(tc, jira, c, ghLogin, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withGhLogin(String login) {
            return new Enrollment(tc, jira, gh, login, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withTz(String zone) {
            return new Enrollment(tc, jira, gh, ghLogin, zone, enabledAt, options, handled, onSince);
        }

        Enrollment withOptions(Options o) {
            return new Enrollment(tc, jira, gh, ghLogin, tz, enabledAt, o, handled, onSince);
        }

        Enrollment withEnabledAt(long at) {
            return new Enrollment(tc, jira, gh, ghLogin, tz, at, options, handled, onSince);
        }

        /** With the options {@code next}: one switched on {@code now} acts on the runs that finish from now on. */
        Enrollment switchedTo(Options next, long now) {
            Map<Option, Long> since = new EnumMap<>(Option.class);
            next.on().forEach(o -> since.put(o, options.on().contains(o) ? since(o) : now));

            return new Enrollment(tc, jira, gh, ghLogin, tz, enabledAt, next, handled,
                Collections.unmodifiableMap(since));
        }

        /** When the option was switched on. */
        long since(Option o) {
            return Math.min(onSince.getOrDefault(o, enabledAt), enabledAt);
        }

        /**
         * The options that act on a run that finished at {@code finishedMs}: those on now that were on by then.
         * Switching one on mid-settle neither cuts the run off from the others nor posts a visa it never promised.
         */
        Options actingOn(long finishedMs) {
            if (finishedMs <= 0)
                return options;

            Set<Option> acting = EnumSet.noneOf(Option.class);
            options.on().stream().filter(o -> since(o) <= finishedMs).forEach(acting::add);

            return new Options(acting);
        }
    }

    /** A stored token (encrypted; null once dropped) and when its service last refused it (0: never). */
    private record Credential(String token, long rejectedAt) {
        static final Credential NONE = new Credential(null, 0);

        static Credential refusedAt(long at) {
            return new Credential(null, at);
        }

        boolean rejected() {
            return rejectedAt > 0;
        }
    }

    /** A standing switch. */
    private enum Option {
        VISA, RERUN, GH_COMMENT, STYLE_FIX, COMMANDS
    }

    /** The standing switches that are on, independent of each other. */
    private record Options(Set<Option> on) {
        static final Options NONE = new Options(EnumSet.noneOf(Option.class));

        Options {
            on = Collections.unmodifiableSet(on.isEmpty() ? EnumSet.noneOf(Option.class) : EnumSet.copyOf(on));
        }

        /** The switch turned on or off; unchanged when {@code value} is null. */
        Options with(Option o, Boolean value) {
            if (value == null || value == on.contains(o))
                return this;

            EnumSet<Option> next = EnumSet.noneOf(Option.class);
            next.addAll(on);
            if (value)
                next.add(o);
            else
                next.remove(o);

            return new Options(next);
        }

        Options apply(OptionChange c) {
            return with(Option.VISA, c.visa()).with(Option.RERUN, c.rerun()).with(Option.GH_COMMENT, c.gh())
                .with(Option.STYLE_FIX, c.style()).with(Option.COMMANDS, c.commands());
        }

        boolean autoVisa() {
            return on.contains(Option.VISA);
        }

        boolean autoRerun() {
            return on.contains(Option.RERUN);
        }

        boolean ghComment() {
            return on.contains(Option.GH_COMMENT);
        }

        boolean styleFix() {
            return on.contains(Option.STYLE_FIX);
        }

        boolean commands() {
            return on.contains(Option.COMMANDS);
        }

        boolean any() {
            return !on.isEmpty();
        }

        /**
         * Whether an option that acts on finished runs was off in {@code before} and is on now. PR
         * commands act on the commands only, so switching them on moves no cutoff.
         */
        boolean switchedOnSince(Options before) {
            return on.stream().anyMatch(o -> o != Option.COMMANDS && !before.on().contains(o));
        }

        /** Whether an option acts on the user's finished runs, so the sweep has something to do for them. */
        boolean settlesRuns() {
            return autoVisa() || autoRerun() || ghComment();
        }

        boolean commandsOnly() {
            return on.equals(EnumSet.of(Option.COMMANDS));
        }

        /** Both options that act from the user's GitHub account need the GitHub token. */
        boolean needsGh() {
            return ghComment() || styleFix();
        }

        Options withoutGh() {
            return with(Option.GH_COMMENT, false).with(Option.STYLE_FIX, false);
        }

        Options withoutVisa() {
            return with(Option.VISA, false);
        }

        Options withCommands() {
            return with(Option.COMMANDS, true);
        }
    }

    /** What was already done for the user's runs: the build handled per PR and its living comments. */
    private record Handled(ConcurrentMap<Integer, Long> posted, ConcurrentMap<Integer, GhThread> ghThreads,
        ConcurrentMap<Integer, JiraThread> jiraThreads) {
        static Handled empty() {
            return new Handled(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    /**
     * The one living visa comment of a run in the JIRA ticket: which build it narrates, where to edit it, the
     * ticket, the revision and what the verdict says ({@link #verdictKey}), and whether it carries its last
     * word ({@code done}). Visas of v1.20.11 and before carry the first two only.
     */
    private record JiraThread(long buildId, String commentId, String issue, String sha, String verdict, boolean done) {
        JiraThread ended() {
            return new JiraThread(buildId, commentId, issue, sha, verdict, true);
        }
    }

    /**
     * The one living PR comment of a run: which build it narrates, where to edit it, whether it is final, whether it
     * says a newer run superseded it, and the owner's earlier verdict comments on the PR still to be marked so.
     * Comments of v1.20.11 and before carry the first two only.
     */
    private record GhThread(long buildId, long commentId, boolean done, boolean superseded, List<Long> unmarked) {
        GhThread {
            unmarked = unmarked == null ? List.of() : List.copyOf(unmarked);
        }

        GhThread ended() {
            return new GhThread(buildId, commentId, true, superseded, unmarked);
        }

        GhThread supersededNow() {
            return new GhThread(buildId, commentId, done, true, unmarked);
        }

        GhThread marked(long comment) {
            return new GhThread(buildId, commentId, done, superseded,
                unmarked.stream().filter(c -> c != comment).toList());
        }
    }

    /** Where a living comment's "re-run in progress" line starts, in either markup. */
    private static final String PENDING_MARK = "⏳ _Auto re-run ";

    /** An enrolled user resolved from a GitHub login, tokens decrypted and ready to act with. */
    public record GhActor(String username, String tcToken, String ghToken, String tz) {
    }

    /**
     * One chain's auto re-run bookkeeping: its PR and build, the waves spent, what the last one re-queued, every
     * wave so far, a note for the verdict, who triggered the chain, and the re-run builds queued for it.
     * Snapshots of v1.20.11 and before have neither the PR nor the last two.
     */
    private record Retry(int pr, long buildId, int attempts, String what, List<String> history, String note,
        String by, List<Long> queued) {
        /** The number of the wave going: each wave so far counts, a skipped re-run counts as its attempts. */
        int wave() {
            return history != null && !history.isEmpty() ? history.size() : attempts;
        }

        Retry ofPr(int number) {
            return new Retry(number, buildId, attempts, what, history, note, by, queued);
        }

        /**
         * Snapshots of v1.20.11 and before kept each mid-run re-run as a wave of its own ("early: Cache 1"),
         * so three of them read as wave #3 of the promised two; they are one wave. A single one already
         * counts right and is kept as it was.
         */
        Retry mergingEarlyWaves() {
            long early = history == null ? 0 : history.stream().filter(h -> h.startsWith("early: ")).count();
            if (early < 2)
                return this;

            List<String> merged = new ArrayList<>();
            merged.add(earlyWave((int) early));
            history.stream().filter(h -> !h.startsWith("early: ")).forEach(merged::add);

            return new Retry(pr, buildId, attempts, merged.size() == 1 ? merged.get(0) : what, merged, note, by,
                queued);
        }
    }

    /** The first wave of a chain: every suite re-run while the chain was still going. */
    private static String earlyWave(int suites) {
        return suites + (suites == 1 ? " suite" : " suites") + " that failed mid-run";
    }

    /** The last line of a red verdict in the PR: what the author does next. */
    private static final String NEXT_STEP = "\n\n➡️ **Next:** fix the blockers, push, and run RunAll again — a "
        + "`/run-all` comment here does it when PR commands are on in the checker's ⚙.";

    /**
     * The snapshot on disk: enrollments plus the auto re-run bookkeeping (so a restart can't grant extra
     * attempts or lose a wave). {@code waves} holds every chain's; {@code retries}, one per PR, its newest
     * chain's, is what v1.20.11 and before read.
     */
    private record Snapshot(List<Persisted> enrollments, Map<Integer, Retry> retries,
        Map<Long, List<String>> earlyReruns, Map<Long, Retry> waves) {
    }

    /** Each PR's newest chain's waves, keyed by the PR as v1.20.11 keyed them. */
    private Map<Integer, Retry> newestPerPr() {
        Map<Integer, Retry> out = new HashMap<>();
        waves.values().forEach(r -> out.merge(r.pr(), r, (a, b) -> a.buildId() >= b.buildId() ? a : b));

        return out;
    }

    /** The ⏳ status line of the living comment while re-runs settle, numbered, with the waves so far.
     * {@code b} is the bold marker of the target markup: {@code **} for GitHub, {@code *} for JIRA. */
    private static String pendingLine(String what, int attempt, List<String> history, Long etaEpochSec, String tz,
        String b) {
        return "\n\n⏳ _Auto re-run " + b + "#" + (history == null || history.isEmpty() ? attempt : history.size())
            + b + " (of up to " + MAX_RERUNS + ") in progress — " + what + " re-queued"
            + (etaEpochSec == null ? "" : ", " + b + "≈ settled by " + wallClock(etaEpochSec, tz) + b)
            + ". This comment updates when they settle._"
            + earlierWaves(history);
    }

    /**
     * The run's visa in the ticket, posted once it is settled; one of the same build already there (a visa
     * from before this release, posted while re-runs went) is edited in place. A failed edit falls back to
     * a fresh comment; the fresh-post URL is returned (null when an edit sufficed). {@code sha} and
     * {@code verdict} are kept with it, to tell whether the next visa would say anything new.
     */
    private String upsertVisa(String who, Enrollment e, String jiraToken, String issueKey, int pr, long buildId,
        String body, String sha, String verdict) {
        JiraThread t = e.handled().jiraThreads().get(pr);
        if (t != null && t.buildId() == buildId && t.commentId() != null) {
            try {
                jira.updateComment(jiraToken, issueKey, t.commentId(), body);
                e.handled().jiraThreads().put(pr, new JiraThread(buildId, t.commentId(), issueKey, sha, verdict, true));
                log.info("standing visa updated in place for PR {} (build {})", pr, buildId);

                return null;
            }
            catch (RuntimeException editEx) {
                if (refused(editEx)) {
                    dropJiraToken(who);

                    return null;
                }

                log.warn("editing visa comment {} for PR {} failed ({}), posting fresh",
                    t.commentId(), pr, editEx.toString());
            }
        }

        JiraClient.PostedComment posted;
        try {
            posted = jira.addCommentWithId(jiraToken, issueKey, body);
        }
        catch (RuntimeException e2) {
            if (!refused(e2))
                throw e2;

            dropJiraToken(who);

            return null;
        }
        e.handled().jiraThreads().put(pr, new JiraThread(buildId, posted.id(), issueKey, sha, verdict, true));

        return posted.url();
    }

    /** Whether the service refused the credential itself — an expired or revoked token, not a blip. */
    private static boolean refused(RuntimeException e) {
        return e instanceof org.springframework.web.client.RestClientResponseException rest
            && (rest.getStatusCode().value() == 401 || rest.getStatusCode().value() == 403);
    }

    /** "Earlier re-runs: #1 — …" for every wave before the current one; empty when none. */
    private static String earlierWaves(List<String> history) {
        if (history == null || history.size() < 2)
            return "";

        StringBuilder b = new StringBuilder("\n_Earlier re-runs:");
        for (int i = 0; i < history.size() - 1; i++)
            b.append(i == 0 ? " " : "; ").append("#").append(i + 1).append(" — ").append(history.get(i));

        return b.append("._").toString();
    }

    /** The final "how it settled" line: every re-run wave in order; null when there were none. */
    private static String settledLine(List<String> history) {
        if (history == null || history.isEmpty())
            return null;

        StringBuilder b = new StringBuilder("♻️ Settled after ").append(history.size()).append(" auto re-run wave(s):");
        for (int i = 0; i < history.size(); i++)
            b.append(i == 0 ? " " : "; ").append("#").append(i + 1).append(" — ").append(history.get(i));

        return b.append(".").toString();
    }

    /** The suites a wave re-runs, by why, each suite once and in this order; see {@link #of}. */
    record WaveSuites(List<String> blockers, List<String> watch, List<String> broken, List<String> cancelled) {
        /**
         * The suites a verdict asks to re-run: those of its blockers and watch items, what its broken groups re-run
         * (a failed Build alone, nothing when it failed to compile, never the suites it kept from running, see
         * {@link BrokenGroup}) and the suites TeamCity cancelled by itself for any other reason.
         */
        static WaveSuites of(AnalysisResult res) {
            List<String> blockers = res.blockers().stream()
                .map(v -> v.suite()).filter(x -> x != null && !x.isBlank())
                .distinct().toList();
            // A watch item is exactly what a re-run settles: too few runs of this revision to
            // tell a real break from a flake. Re-running is what turns it into a verdict —
            // without it a PR whose only finding is a watch item waits for a human forever.
            List<String> watch = res.watch().stream()
                .map(v -> v.suite()).filter(x -> x != null && !x.isBlank())
                .distinct().filter(s -> !blockers.contains(s)).toList();
            // Broken suites (timeout/crash) deserve the same retry a human would
            // give them — and a passing re-run now clears them from the verdict too.
            List<String> broken = BrokenGroup.rerunSuites(BrokenGroup.of(res)).stream()
                .filter(s -> !blockers.contains(s) && !watch.contains(s)).toList();
            // A suite TeamCity cancelled by itself never ran, and a re-run is what gets it a result.
            // One a person cancelled was meant not to run.
            List<String> cancelled = res.cancelledSuites().stream()
                .filter(c -> c.byTeamCity() && c.failedUpstream() == null).map(CancelledSuite::suite)
                .filter(x -> x != null && !x.isBlank()).distinct()
                .filter(s -> !blockers.contains(s) && !watch.contains(s) && !broken.contains(s))
                .toList();

            return new WaveSuites(blockers, watch, broken, cancelled);
        }

        List<String> all() {
            return java.util.stream.Stream.of(blockers, watch, broken, cancelled).flatMap(List::stream).toList();
        }
    }

    /** e.g. {@code "2 blocker suite(s)"}, {@code "2 blocker + 1 watch + 3 broken + 4 cancelled suite(s)"}. */
    private static String suitesLabel(int blockers, int watch, int broken, int cancelled) {
        List<String> parts = new ArrayList<>();
        if (blockers > 0)
            parts.add(blockers + " blocker");
        if (watch > 0)
            parts.add(watch + " watch");
        if (broken > 0)
            parts.add(broken + " broken");
        if (cancelled > 0)
            parts.add(cancelled + " cancelled");

        return parts.isEmpty() ? "0 suite(s)" : String.join(" + ", parts) + " suite(s)";
    }

    /** Queue-aware settle estimate for the PR's live re-runs, from the tracker; null when unknown. */
    private Long activeEtaEpoch(int pr) {
        long now = System.currentTimeMillis() / 1000;
        long max = -1;
        for (RerunTracker.ActiveRerun a : rerunTracker.active()) {
            if (a.pr() == pr && a.leftSec() != null)
                max = Math.max(max, now + a.leftSec());
        }

        return max > 0 ? max : null;
    }

    /** Wall-clock stamp in the user's JIRA-profile timezone (UTC when unknown). */
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

        return java.time.Instant.ofEpochSecond(epochSec).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm zzz", java.util.Locale.ENGLISH));
    }

    /**
     * One enrollment as it is written to disk; the field names are the file format. {@code onSince} is keyed by
     * the option's name, so a snapshot that names an option this version does not know still loads.
     */
    private record Persisted(String username, String tcToken, String jiraToken, String ghToken, String ghLogin,
        String tz, long enabledAt, Map<Integer, Long> posted, Map<Integer, GhThread> ghThreads,
        Map<Integer, JiraThread> jiraThreads,
        Boolean autoVisa, boolean autoRerun, Boolean ghComment, Boolean styleFix,
        Long ghRejectedAt, Long jiraRejectedAt, Long tcRejectedAt, Boolean commands, Map<String, Long> onSince) {
        static Persisted of(String username, Enrollment e) {
            Options o = e.options();
            Handled h = e.handled();
            Map<String, Long> since = new HashMap<>();
            e.onSince().forEach((option, at) -> since.put(option.name(), at));

            return new Persisted(username, e.tc().token(), e.jira().token(), e.gh().token(), e.ghLogin(), e.tz(),
                e.enabledAt(), new HashMap<>(h.posted()), new HashMap<>(h.ghThreads()), new HashMap<>(h.jiraThreads()),
                o.autoVisa(), o.autoRerun(), o.ghComment(), o.styleFix(), e.gh().rejectedAt(), e.jira().rejectedAt(),
                e.tc().rejectedAt(), o.commands(), since);
        }

        /**
         * Missing fields are what the snapshots written before them meant: before the PR commands
         * switch, a linked GitHub login was all commands needed.
         */
        Enrollment enrollment() {
            Handled h = Handled.empty();
            if (posted != null)
                h.posted().putAll(posted);
            if (ghThreads != null)
                h.ghThreads().putAll(ghThreads);
            if (jiraThreads != null)
                h.jiraThreads().putAll(jiraThreads);
            Map<Option, Long> since = new EnumMap<>(Option.class);
            for (Option o : Option.values()) {
                Long at = onSince == null ? null : onSince.get(o.name());
                if (at != null)
                    since.put(o, at);
            }

            return new Enrollment(new Credential(tcToken, tcRejectedAt == null ? 0 : tcRejectedAt),
                new Credential(jiraToken, jiraRejectedAt == null ? 0 : jiraRejectedAt),
                new Credential(ghToken, ghRejectedAt == null ? 0 : ghRejectedAt), ghLogin, tz, enabledAt,
                Options.NONE.with(Option.VISA, autoVisa == null || autoVisa).with(Option.RERUN, autoRerun)
                    .with(Option.GH_COMMENT, ghComment != null && ghComment)
                    .with(Option.STYLE_FIX, styleFix != null && styleFix)
                    .with(Option.COMMANDS, commands == null ? ghLogin != null : commands), h,
                Collections.unmodifiableMap(since));
        }
    }
}
