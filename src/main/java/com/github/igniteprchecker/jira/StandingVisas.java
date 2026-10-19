package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.github.CommitTokens;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.QueuedBelowTopException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Standing auto-visa: an opted-in user gets the verdict posted to the ticket for EVERY finished
 * RunAll they triggered, with no tab open. The price, stated plainly in the UI: the
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
    private final SettingsJournal journal;
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

    /** What the settle of each run did with its visa, by build, the newest {@link #RUN_VISAS_KEPT}. Persisted. */
    private final ConcurrentSkipListMap<Long, RunVisa> runVisas = new ConcurrentSkipListMap<>();

    /** How many runs' visas are kept for the PR page; the oldest runs go first. */
    private static final int RUN_VISAS_KEPT = 2000;

    /** How often the sweep goes through the open PRs. */
    private static final long SWEEP_MS = 600_000;

    /**
     * Why a token is gone while its options wait, next to the reasons of an unread one: the state was restored from a
     * copy old enough to have had its tokens taken out.
     */
    public static final String TOKEN_REMOVED = "removed";

    /** How long after the start the first sweep is due. */
    public static final long FIRST_SWEEP_MS = 180_000;

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

    /**
     * The PRs out of the sweep's list whose RunAll, started by a user whose visa or verdict comment it owes, finished
     * and is not settled yet: the sweep settles them until they rest or close.
     */
    private final Set<Integer> finishedUnlisted = ConcurrentHashMap.newKeySet();

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
        @Qualifier("settleExecutor") ExecutorService settler, SettingsJournal journal) {
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
        this.journal = journal;
    }

    /** Standing options with a journal of their own. */
    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer,
        PendingCommits pending, ExecutorService earlyPool, ExecutorService settler) {
        this(mapper, codec, tc, github, analyzer, jira, visas, rerunTracker, warmer, pending, earlyPool, settler,
            new SettingsJournal(mapper));
    }

    /** Standing options whose early re-runs and settles run on daemon threads of their own. */
    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer,
        PendingCommits pending) {
        this(mapper, codec, tc, github, analyzer, jira, visas, rerunTracker, warmer, pending,
            new SettingsJournal(mapper));
    }

    /** Standing options that write to {@code journal}, with early re-runs and settles on daemon threads. */
    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer,
        PendingCommits pending, SettingsJournal journal) {
        this(mapper, codec, tc, github, analyzer, jira, visas, rerunTracker, warmer, pending, daemon("early-rerun"),
            daemon("settle"), journal);
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
        enrolled.forEach((user, e) -> backgroundToken(e).ifPresent(t -> warmer.offerToken(user, t)));
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

    /** A settings change with no time zone from the browser. */
    public Optional<Refusal> change(String username, String tcToken, String offeredJira, String offeredGh,
        OptionChange change) {
        return change(username, tcToken, offeredJira, offeredGh, change, null);
    }

    /**
     * Applies a settings change, touching only the options it names: a click on one switch must not
     * turn the others off. An option that needs a token takes the one offered with the change when it is
     * new (checked with the service first), else the stored one; a stored token goes only when the last option that
     * needs it is switched off explicitly. A new token the service refuses or cannot check refuses the change and
     * leaves the stored one, since the user meant to replace it. Only the switch of an option that needs a token, or a
     * token offered, asks for one: an option that waits for its token stops no other switch. {@code offeredJira} is the
     * JIRA token the page sends with the switch of the visa, and {@code offeredGh} the GitHub token it sends with the
     * switch of an option that commits: each is kept on the server only, and a GitHub token only when it commits and
     * does no more. The options that write to GitHub go on only while the checker has an account to write with.
     * {@code browserTz}, the browser's time zone, becomes the user's when it is one. A verdict an earlier refusal held
     * back goes out once its option is on again. Empty when the change was made, else what is missing.
     */
    public Optional<Refusal> change(String username, String tcToken, String offeredJira, String offeredGh,
        OptionChange change, String browserTz) {
        return change(username, tcToken, offeredJira, offeredGh, change, browserTz, SettingsJournal.By.of(null, null));
    }

    /**
     * See {@link #change(String, String, String, String, OptionChange, String)}; the journal names {@code by} as who
     * made the change.
     */
    public Optional<Refusal> change(String username, String tcToken, String offeredJira, String offeredGh,
        OptionChange change, String browserTz, SettingsJournal.By by) {
        Enrollment prev = enrolled.get(username);
        Options before = prev == null ? Options.NONE : prev.options();
        Options after = before.apply(change);
        if (Boolean.TRUE.equals(change.style()) && !after.commands())
            return Optional.of(new Refusal("commands",
                "Fix checkstyle before my commanded runs needs PR commands: switch them on first"));
        if (change.switchesOnGithub() && !github.writes())
            return Optional.of(Refusal.UNAVAILABLE);
        if (!after.any()) {
            disable(username);
            journalSwitches(username, before, Options.NONE, by, false, false);
            if (prev != null && prev.ghAccount() != null) {
                journal.add(username, SettingsJournal.Entry.unlinkedAllOff(by, prev.ghLogin(),
                    prev.gh().token() != null || prev.gh().unread() != null));
            }

            return Optional.empty();
        }

        Credential jiraCred = null;
        if (after.autoVisa() && (Boolean.TRUE.equals(change.visa()) || offeredJira != null)) {
            String stored = prev == null ? null : decrypt(prev.jira()).orElse(null);
            boolean fresh = offeredJira != null && !offeredJira.equals(stored);
            Optional<String> jiraUser = Optional.empty();
            boolean unchecked = false;
            try {
                jiraUser = fresh ? jira.myself(offeredJira) : Optional.empty();
            }
            catch (RuntimeException e) {
                log.warn("JIRA could not check the token offered for {}: {}", username, e.toString());
                unchecked = true;
            }
            if (jiraUser.isPresent())
                jiraCred = new Credential(codec.encryptString(offeredJira), 0);
            else if (fresh || stored == null)
                return Optional.of(unchecked ? Refusal.unchecked("JIRA") : new Refusal("jira", fresh
                    ? "JIRA rejected the token — paste a new one" : "Visa my runs in JIRA needs your JIRA token"));
        }
        else if (Boolean.FALSE.equals(change.visa()))
            jiraCred = Credential.NONE;

        Credential ghCred = null;
        GithubClient.GhUser login = null;
        if (after.needsGh() && (change.switchesOnCommits() || offeredGh != null)) {
            String stored = prev == null ? null : decrypt(prev.gh()).orElse(null);
            boolean fresh = offeredGh != null && !offeredGh.equals(stored);
            // A token GitHub can't put a name to is dead. Storing it buys nothing and costs the login: with no login,
            // the command poll stops recognising the author of "/run-all" and replies to them as a stranger.
            boolean unchecked = false;
            GithubClient.TokenOwner owner = null;
            try {
                owner = fresh ? github.tokenOwner(offeredGh).orElse(null) : null;
            }
            catch (RuntimeException e) {
                log.warn("GitHub could not check the token offered for {}: {}", username, e.toString());
                unchecked = true;
            }
            login = owner == null ? null : owner.user();
            if (login != null && github.isAppAccount(login)) {
                return Optional.of(new Refusal("github", "this token is of @" + login.login() + ", the checker's own"
                    + " GitHub account — paste a token of your own account"));
            }
            Optional<String> tooMuch = owner == null ? Optional.empty()
                : CommitTokens.refusal(offeredGh, owner.scopes());
            if (tooMuch.isPresent()) {
                log.info("GitHub token offered for {} not kept: {}", username, tooMuch.get());

                return Optional.of(new Refusal("github", tooMuch.get()));
            }
            if (login != null)
                ghCred = new Credential(codec.encryptString(offeredGh), 0,
                    CommitTokens.note(offeredGh, owner.scopes()));
            else if (fresh || stored == null) {
                if (unchecked)
                    return Optional.of(Refusal.unchecked("GitHub"));
                if (fresh)
                    log.warn("GitHub token offered for {} was not accepted by GitHub — kept out of the enrollment",
                        username);

                return Optional.of(new Refusal("github", fresh ? "GitHub rejected the token — paste a new one"
                    : "this option needs your GitHub token for commits"));
            }
        }
        else if (!after.needsGh() && (Boolean.FALSE.equals(change.style()) || Boolean.FALSE.equals(change.suites())
            || prev != null && prev.options().needsGh()))
            ghCred = Credential.NONE;

        if (Boolean.TRUE.equals(change.commands()) && login == null && (prev == null || !prev.ghLoginProven()))
            return Optional.of(new Refusal("login", "PR commands need your linked GitHub account"));

        Credential sessionTc = new Credential(codec.encryptString(tcToken), 0);
        Credential jiraNext = jiraCred;
        Credential ghNext = ghCred;
        String tzNext = zoneOf(browserTz);
        GithubClient.GhUser loginNext = login;
        long now = System.currentTimeMillis();

        // A settings change must not forget which builds were already handled (or their comments),
        // nor a GitHub login proven before (the one the offered token proves now replaces it). A token of the account
        // linked already is no new link: the link keeps its date and browser, as the journal names no new one.
        Options[] was = {Options.NONE};
        boolean[] newLink = {false};
        Enrollment e = enrolled.compute(username, (u, cur) -> {
            Enrollment base = cur != null ? cur : Enrollment.fresh(sessionTc);
            was[0] = cur == null ? Options.NONE : cur.options();
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
            if (loginNext == null)
                return next;

            newLink[0] = !base.ghLoginProven() || !base.ghAccount().is(loginNext);

            return next.withGhAccount(newLink[0] ? GhAccount.linked(base.ghAccount(), loginNext, by, now)
                : base.ghAccount().renamedTo(loginNext));
        });
        if (loginNext != null && e != null)
            releaseLogin(loginNext, username);
        if (newLink[0] && e != null)
            journal.add(username, SettingsJournal.Entry.linked(by, loginNext.login(), List.of(), true));
        journalSwitches(username, was[0], e == null ? Options.NONE : e.options(), by,
            e != null && jiraNext != null && jiraNext.token() != null,
            e != null && ghNext != null && ghNext.token() != null);
        log.info("standing options for {}: {} (gh login {}, tz {})", username, e == null ? "off" : e.options(),
            e == null ? null : e.ghLogin(), e == null ? null : e.tz());
        donateWarmTokens();
        if (e != null)
            e.handled().owed().forEach((pr, owed) -> {
                if (deliverable(owed, e))
                    settleSoon(pr);
            });

        return Optional.empty();
    }

    /**
     * Writes the options switched on and off between {@code was} and {@code now} into the user's journal, with the
     * JIRA or GitHub token that the switch of an option saved for it. A token saved with no switch of an option that
     * uses it is an entry of its own: a replaced token.
     */
    private void journalSwitches(String username, Options was, Options now, SettingsJournal.By by, boolean jiraSaved,
        boolean ghSaved) {
        boolean jiraTold = false;
        boolean ghTold = false;
        for (Option o : Option.values()) {
            boolean on = now.on().contains(o);
            if (on == was.on().contains(o))
                continue;
            if (!on) {
                journal.add(username, SettingsJournal.Entry.switchedOff(by, o.key));

                continue;
            }

            String token = null;
            if (o == Option.VISA && jiraSaved && !jiraTold) {
                token = SettingsJournal.Entry.JIRA;
                jiraTold = true;
            }
            else if ((o == Option.STYLE_FIX || o == Option.SUITE_FIX) && ghSaved && !ghTold) {
                token = SettingsJournal.Entry.GITHUB;
                ghTold = true;
            }
            journal.add(username, SettingsJournal.Entry.switchedOn(by, o.key, token));
        }
        if (jiraSaved && !jiraTold)
            journal.add(username, SettingsJournal.Entry.token(by, SettingsJournal.Entry.JIRA));
        if (ghSaved && !ghTold)
            journal.add(username, SettingsJournal.Entry.token(by, SettingsJournal.Entry.GITHUB));
    }

    /**
     * The time zone the browser named, as Java names it; null when it names none. Narrations of PR commands give
     * their times in it.
     */
    static String zoneOf(String browserTz) {
        if (browserTz == null || browserTz.isBlank())
            return null;

        try {
            return java.time.ZoneId.of(browserTz.strip()).getId();
        }
        catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** The options and the state of their tokens, as the settings panel shows them. */
    public Settings settings(String username) {
        Enrollment e = enrolled.get(username);
        GithubClient.AppAccount account = github.writes() ? github.appAccount() : null;
        String app = account == null ? null : account.login();
        Boolean appPushes = account == null ? null : account.canPush();
        boolean linkable = github.writes() && github.linksAccounts();
        if (e == null) {
            return new Settings(false, false, false, false, false, null, false, false, false, false, false, false,
                false, github.writes(), app, appPushes, null, linkable, null);
        }

        Options o = e.options();
        GhAccount gh = e.ghAccount();
        List<String> unread = unreadTokens(e);

        return new Settings(o.autoVisa(), o.autoRerun(), o.ghComment(), o.styleFix(), o.commands(), e.ghLogin(),
            e.jira().token() != null, e.gh().token() != null, e.jira().rejected(), e.gh().rejected(),
            e.tc().rejected(), o.suiteFix(), e.ghLogin() != null && !e.ghLoginProven(), github.writes(), app,
            appPushes, e.gh().token() == null ? null : e.gh().note(), linkable,
            decrypt(e.gh()).map(CommitTokens::kind).orElse(null), tokensRemoved(e), gh == null ? null : gh.linkedAt(),
            gh == null ? null : gh.linkedFrom(), unread, unread.isEmpty() ? null : unreadWhy(e));
    }

    /** The tokens a restored copy of the state lacked that options still wait for. */
    private static List<String> tokensRemoved(Enrollment e) {
        List<String> removed = new ArrayList<>();
        if (e.jira().removed() && e.options().autoVisa())
            removed.add("jira");
        if (e.gh().removed() && e.options().needsGh())
            removed.add("github");

        return removed;
    }

    /**
     * The tokens the checker could not read and dropped that the user's options wait for, by service: the TeamCity
     * token for any option, the JIRA token for the visa, the GitHub token for the options that commit.
     */
    private static List<String> unreadTokens(Enrollment e) {
        List<String> out = new ArrayList<>();
        if (e.tc().unread() != null)
            out.add(SettingsJournal.Entry.TEAMCITY);
        if (e.jira().unread() != null && e.options().autoVisa())
            out.add(SettingsJournal.Entry.JIRA);
        if (e.gh().unread() != null && e.options().needsGh())
            out.add(SettingsJournal.Entry.GITHUB);

        return out;
    }

    /** Why the checker could not read the user's tokens: the server key was changed, or else they are damaged. */
    private static String unreadWhy(Enrollment e) {
        return java.util.stream.Stream.of(e.tc(), e.jira(), e.gh())
            .anyMatch(c -> SettingsJournal.Entry.KEY_CHANGED.equals(c.unread()))
            ? SettingsJournal.Entry.KEY_CHANGED : SettingsJournal.Entry.DAMAGED;
    }

    /**
     * How many users' options wait for tokens the checker could not read after a change of the server key: until
     * they log in again, or save a new JIRA or GitHub token. Decrypts no token.
     */
    public int waitingAfterKeyChange() {
        return (int)enrolled.values().stream()
            .filter(e -> !unreadTokens(e).isEmpty() && SettingsJournal.Entry.KEY_CHANGED.equals(unreadWhy(e)))
            .count();
    }

    /**
     * Why the user's stored token of the {@code service} is gone while an option of theirs waits for it: {@link
     * #TOKEN_REMOVED}, {@link SettingsJournal.Entry#KEY_CHANGED} or {@link SettingsJournal.Entry#DAMAGED}; empty when
     * none waits.
     */
    public Optional<String> waitingToken(String username, String service) {
        Enrollment e = enrolled.get(username);
        if (e == null)
            return Optional.empty();

        Credential c = switch (service) {
            case SettingsJournal.Entry.TEAMCITY -> e.tc();
            case SettingsJournal.Entry.JIRA -> e.options().autoVisa() ? e.jira() : Credential.NONE;
            default -> e.options().needsGh() ? e.gh() : Credential.NONE;
        };

        return Optional.ofNullable(whyGone(c));
    }

    /** Why the token is gone while its options wait, {@link #TOKEN_REMOVED} or why it was unread; else null. */
    private static String whyGone(Credential c) {
        return c.removed() ? TOKEN_REMOVED : c.unread();
    }

    /** The JIRA and the GitHub token the server stores for the user's options, decrypted; null where none is kept. */
    public StoredTokens storedTokens(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? new StoredTokens(null, null)
            : new StoredTokens(decrypt(e.jira()).orElse(null), decrypt(e.gh()).orElse(null));
    }

    /** See {@link #storedTokens}. */
    public record StoredTokens(String jira, String github) {
        @Override
        public String toString() {
            return "StoredTokens[jira=" + Secrets.masked(jira) + ", github=" + Secrets.masked(github) + "]";
        }
    }

    /** A settings change: each option is switched on, off, or left as it is (null). */
    public record OptionChange(Boolean visa, Boolean rerun, Boolean gh, Boolean style, Boolean commands,
        Boolean suites) {
        /** A change that leaves adding tests to suites as it is. */
        public OptionChange(Boolean visa, Boolean rerun, Boolean gh, Boolean style, Boolean commands) {
            this(visa, rerun, gh, style, commands, null);
        }

        /** Whether it switches on an option that writes to GitHub: all but the visa and the auto re-run. */
        boolean switchesOnGithub() {
            return Boolean.TRUE.equals(gh) || Boolean.TRUE.equals(style) || Boolean.TRUE.equals(commands)
                || Boolean.TRUE.equals(suites);
        }

        /** Whether it switches on an option that commits with the user's GitHub token. */
        boolean switchesOnCommits() {
            return Boolean.TRUE.equals(style) || Boolean.TRUE.equals(suites);
        }
    }

    /**
     * Why a settings change was not made: what it {@code need}s ("jira" or "github" token, the GitHub "login", PR
     * "commands" on, or the checker's own GitHub account, "app") and what is wrong. Null {@code need}: the service
     * could not check the token, and nothing is wrong with it as far as anyone knows.
     */
    public record Refusal(String need, String error) {
        /** An option that writes to GitHub, on a checker with no account to write with. */
        public static final Refusal UNAVAILABLE = new Refusal("app", "not available: this checker has no GitHub account"
            + " to write with (GITHUB_TOKEN); its operator can add one");

        static Refusal unchecked(String service) {
            return new Refusal(null, service + " could not check the token right now — try again in a minute");
        }
    }

    /**
     * What the settings panel shows. {@code jiraStored}/{@code ghStored}: the server holds that token,
     * so the options run whether or not this browser's session carries it. {@code loginUnproven}: no token proved
     * the {@code login} an earlier version kept, and PR commands wait until one does. {@code githubAvailable}: the
     * checker has a GitHub account to write with, {@code appLogin} (null until GitHub named it), and the options that
     * write to GitHub may go on; {@code appPushes}: whether that account may push to the repo, null while GitHub has
     * not said. {@code ghTokenNote}: what the user should know of the GitHub token the server holds.
     * {@code githubLinkable}: the user can link their GitHub account with the checker's OAuth App.
     * {@code ghTokenKind}: what kind of token the server holds, {@link CommitTokens#kind}; null when none.
     * {@code tokensRemoved}: the tokens ("jira", "github") the state was restored without, whose options pause until
     * the user saves them again.
     * {@code linkedAt} and {@code linkedFrom}: when the user linked the GitHub account and the label of the browser
     * they did it from; null when not known. {@code tokensUnread}: the tokens, by service, that the checker could not
     * read and dropped, which options wait for; {@code tokensUnreadWhy}: why, {@link SettingsJournal.Entry#KEY_CHANGED}
     * or {@link SettingsJournal.Entry#DAMAGED}, null when none waits.
     */
    public record Settings(boolean visa, boolean rerun, boolean gh, boolean style, boolean commands, String login,
        boolean jiraStored, boolean ghStored, boolean jiraTokenRejected, boolean ghTokenRejected,
        boolean tcTokenRejected, boolean suites, boolean loginUnproven, boolean githubAvailable, String appLogin,
        Boolean appPushes, String ghTokenNote, boolean githubLinkable, String ghTokenKind,
        List<String> tokensRemoved, Long linkedAt, String linkedFrom, List<String> tokensUnread,
        String tokensUnreadWhy) {
        /** The settings of a user whose GitHub link is not dated and whose stored tokens are all there. */
        public Settings(boolean visa, boolean rerun, boolean gh, boolean style, boolean commands, String login,
            boolean jiraStored, boolean ghStored, boolean jiraTokenRejected, boolean ghTokenRejected,
            boolean tcTokenRejected, boolean suites, boolean loginUnproven, boolean githubAvailable, String appLogin,
            Boolean appPushes, String ghTokenNote, boolean githubLinkable, String ghTokenKind) {
            this(visa, rerun, gh, style, commands, login, jiraStored, ghStored, jiraTokenRejected, ghTokenRejected,
                tcTokenRejected, suites, loginUnproven, githubAvailable, appLogin, appPushes, ghTokenNote,
                githubLinkable, ghTokenKind, List.of(), null, null, List.of(), null);
        }

        /**
         * The settings of a user whose login, if any, is proven, on a checker with a GitHub account and no OAuth App to
         * link one with.
         */
        public Settings(boolean visa, boolean rerun, boolean gh, boolean style, boolean commands, String login,
            boolean jiraStored, boolean ghStored, boolean jiraTokenRejected, boolean ghTokenRejected,
            boolean tcTokenRejected, boolean suites) {
            this(visa, rerun, gh, style, commands, login, jiraStored, ghStored, jiraTokenRejected, ghTokenRejected,
                tcTokenRejected, suites, false, true, null, null, null, false, null);
        }

        /** The settings of a user who does not add tests to suites. */
        public Settings(boolean visa, boolean rerun, boolean gh, boolean style, boolean commands, String login,
            boolean jiraStored, boolean ghStored, boolean jiraTokenRejected, boolean ghTokenRejected,
            boolean tcTokenRejected) {
            this(visa, rerun, gh, style, commands, login, jiraStored, ghStored, jiraTokenRejected, ghTokenRejected,
                tcTokenRejected, false);
        }
    }

    /**
     * The user whose proven GitHub account wrote a comment, with decrypted tokens — the PR command poll
     * resolves the comment's author through this; whether they may command is {@link #commandsOn}. The
     * account is matched by its id: a login its owner gave up can be someone else's now. The checker's own
     * account is nobody's.
     */
    public Optional<GhActor> actorByGhUser(GithubClient.GhUser author) {
        if (github.isAppAccount(author))
            return Optional.empty();

        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            GhAccount held = en.getValue().ghAccount();
            if (held == null || !held.proven() || !held.is(author))
                continue;

            // The login as GitHub spells it now, a rename included.
            GhAccount now = held.renamedTo(author);
            if (!now.equals(held))
                enrolled.computeIfPresent(en.getKey(), (u, e) -> held.equals(e.ghAccount()) ? e.withGhAccount(now) : e);

            Optional<GhActor> actor = actorOf(en.getKey(), en.getValue());
            if (actor.isPresent())
                return actor;
        }

        return Optional.empty();
    }

    /**
     * The user as their commands act, tokens decrypted. A user whose TeamCity token TeamCity refused is there without
     * it, as only its hash is kept: a command of theirs is answered that TeamCity refused the token.
     */
    private Optional<GhActor> actorOf(String username, Enrollment e) {
        String gh = decrypt(e.gh()).orElse(null);
        if (e.tc().rejected())
            return Optional.of(new GhActor(username, null, gh, e.tz()));

        return decrypt(e.tc()).map(tcToken -> new GhActor(username, tcToken, gh, e.tz()));
    }

    /**
     * Why {@link #actorByGhUser} finds nobody for the GitHub account that wrote a comment. The answer names no user:
     * it goes into a public thread.
     */
    public NoActor whyNoActor(GithubClient.GhUser author) {
        if (github.isAppAccount(author))
            return NoActor.UNLINKED;

        boolean unproven = false;
        for (Enrollment e : enrolled.values()) {
            GhAccount held = e.ghAccount();
            if (held == null)
                continue;
            if (held.proven() && held.is(author)) {
                return e.tc().removed() ? NoActor.TOKEN_REMOVED
                    : SettingsJournal.Entry.KEY_CHANGED.equals(e.tc().unread()) ? NoActor.KEY_CHANGED
                    : NoActor.TOKEN_UNREADABLE;
            }
            unproven |= !held.proven() && held.login().equalsIgnoreCase(author.login());
        }

        return unproven ? NoActor.UNPROVEN : NoActor.UNLINKED;
    }

    /** Why no user acts for a GitHub account; see {@link #whyNoActor}. */
    public enum NoActor {
        /** No user linked the account. */
        UNLINKED,

        /** An earlier version kept the account's login for a user without a proof that it is theirs. */
        UNPROVEN,

        /** The user who linked the account has a TeamCity token the checker can no longer decrypt. */
        TOKEN_UNREADABLE,

        /** The server key was changed, so the checker could not read the TeamCity token of the user who linked it. */
        KEY_CHANGED,

        /** The state was restored from a copy without the TeamCity token of the user who linked the account. */
        TOKEN_REMOVED
    }

    /** The stored token in clear; empty when there is none or the session secret has changed since. */
    private Optional<String> decrypt(Credential c) {
        return c.token() == null ? Optional.empty() : codec.decryptString(c.token());
    }

    /**
     * Forgets a GitHub token GitHub itself rejected on a commit made with it: every next commit would fail the
     * same way. The login stays: it is how commands resolve the actor. Only the user's own commits come here; what
     * the checker's account writes never touches the user's token.
     */
    public void dropGhToken(String username) {
        if (username == null)
            return;

        dropGhTokenIf(username, e -> e.gh().token() != null);
    }

    /**
     * Forgets the GitHub token {@code asked} that GitHub refused when asked whose it is, as after a refused commit, if
     * an option that commits still has it. A token saved since stays, and one no option needs goes without a note in ⚙.
     */
    private void dropRefusedGhToken(String username, String asked) {
        dropGhTokenIf(username, cur -> asked.equals(cur.gh().token()) && cur.options().needsGh());
    }

    /**
     * Forgets the user's GitHub token when {@code refused} holds for their enrollment, and switches off the options
     * that commit with it. Leaving them on would promise work the checker can no longer do.
     */
    private void dropGhTokenIf(String username, java.util.function.Predicate<Enrollment> refused) {
        Options[] was = {null};
        boolean dropped = changed(username, e -> {
            if (!refused.test(e))
                return e;

            was[0] = e.options();

            return e.withGh(Credential.refusedAt(System.currentTimeMillis())).withOptions(e.options().withoutGh())
                .withTcOnlyWhileOn();
        });
        if (dropped) {
            journal.add(username, SettingsJournal.Entry.refused(SettingsJournal.Entry.GITHUB,
                offWith(was[0], Option.STYLE_FIX, Option.SUITE_FIX)));
            log.warn("GitHub token of {} was rejected by GitHub: dropped, the checkstyle autofix and tests to suites "
                + "switched off until a fresh token is saved", username);
        }
    }

    /**
     * Same for JIRA: a PAT the ticket tracker refuses can't post a visa, so auto-visa goes off and
     * the panel asks for a new one instead of silently skipping every ticket from now on.
     */
    public void dropJiraToken(String username) {
        if (username == null)
            return;

        Options[] was = {null};
        boolean dropped = changed(username, e -> {
            if (e.jira().token() == null)
                return e;

            was[0] = e.options();

            return e.withJira(Credential.refusedAt(System.currentTimeMillis())).withOptions(e.options().withoutVisa())
                .withTcOnlyWhileOn();
        });
        if (dropped) {
            journal.add(username, SettingsJournal.Entry.refused(SettingsJournal.Entry.JIRA, offWith(was[0],
                Option.VISA)));
            log.warn("JIRA token of {} was rejected: dropped and auto-visa switched off until a fresh PAT is saved",
                username);
        }
    }

    /**
     * Records that TeamCity refused the user's stored token. The options stay as they were but pause:
     * nothing runs under a dead token, the settings panel and the PR commands say why, and the first
     * request that brings a working token resumes them. Of the dead token only its hash stays, to know it again.
     */
    public void markTcRejected(String username) {
        long now = System.currentTimeMillis();
        boolean marked = changed(username, e -> e.tc().rejected() ? e : e.withTc(refused(e.tc(), now)));
        if (marked) {
            journal.add(username, SettingsJournal.Entry.refused(SettingsJournal.Entry.TEAMCITY, List.of()));
            log.warn("TeamCity rejected the stored token of {}: their options pause until a working token comes",
                username);
        }
    }

    /** Whether the user's options lend this TeamCity token to the background reads; see {@link #backgroundToken}. */
    public boolean lendsTcToken(String username, String token) {
        Enrollment e = enrolled.get(username);

        return e != null && backgroundToken(e).filter(t -> t.equals(token)).isPresent();
    }

    /** Whether options of the user are on and act with a TeamCity token the server stores, one TeamCity accepts. */
    public boolean keepsTcToken(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && !e.tc().rejected() && e.tc().token() != null;
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
     * user replaces an expired token, and it used to leave the dead one in charge of their options. A user with no
     * option on gets none stored.
     */
    public void tcTokenAccepted(String username, String token) {
        tcTokenAccepted(username, token, SettingsJournal.By.of(null, null));
    }

    /** See {@link #tcTokenAccepted(String, String)}: a login {@code by}, which the journal names for a new token. */
    public void tcTokenAccepted(String username, String token, SettingsJournal.By by) {
        boolean wasRejected = tcTokenRejected(username);
        Credential accepted = new Credential(codec.encryptString(token), 0);
        boolean replaced = changed(username, e -> !e.options().any() || !e.tc().rejected()
            && decrypt(e.tc()).filter(token::equals).isPresent() ? e : e.withTc(accepted));
        if (replaced)
            journal.add(username, SettingsJournal.Entry.token(by, SettingsJournal.Entry.TEAMCITY));
        if (wasRejected)
            log.info("TeamCity token of {} renewed at login: their options resume", username);
    }

    /** The token a logged-in request carries replaces a stored one TeamCity refused; see {@link #tcKept}. */
    public void tcTokenOffered(String username, String token) {
        tcTokenOffered(username, token, SettingsJournal.By.of(null, null));
    }

    /** See {@link #tcTokenOffered(String, String)}: a request of the login {@code by}, which the journal names. */
    public void tcTokenOffered(String username, String token, SettingsJournal.By by) {
        Enrollment e = enrolled.get(username);
        if (e == null || !e.options().any() || tcKept(e.tc(), token) == e.tc())
            return;

        boolean replaced = changed(username, cur -> {
            Credential kept = tcKept(cur.tc(), token);

            return kept == cur.tc() || !cur.options().any() ? cur : cur.withTc(kept);
        });
        if (replaced) {
            journal.add(username, SettingsJournal.Entry.token(by, SettingsJournal.Entry.TEAMCITY));
            log.info("refused or unreadable TeamCity token of {} replaced by the one of their session: options resume",
                username);
        }
    }

    /**
     * The TeamCity credential to keep when a request brings its session's token: the stored one while
     * it works, because the session's token is unchecked and a tab of an older login must not swap a
     * live token for its own dead one; the session's one when TeamCity refused the stored token or it
     * can no longer be read. The refused token itself, known by its hash, does not resume the options. A login,
     * which TeamCity checks, replaces it outright.
     */
    private Credential tcKept(Credential stored, String sessionToken) {
        boolean kept = stored.rejected() ? Secrets.sha256(sessionToken).equals(stored.hash())
            : decrypt(stored).isPresent();

        return kept ? stored : new Credential(codec.encryptString(sessionToken), 0);
    }

    /** The TeamCity credential TeamCity refused at {@code at}: its hash, without the ciphertext. */
    private Credential refused(Credential tc, long at) {
        return Credential.refused(decrypt(tc).map(Secrets::sha256).orElse(null), at);
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

    /** Links a proven GitHub account with no time zone from the browser. */
    public String linkGhLogin(String username, String tcToken, GithubClient.GhUser proven) {
        return linkGhLogin(username, tcToken, proven, null);
    }

    /**
     * Links the GitHub account the user just proved theirs on GitHub, and switches PR commands on — the login is all
     * they need besides the TeamCity token, so no other option has to be on. The account leaves whoever held it
     * before: the proof is the user's. The browser's time zone {@code browserTz} becomes the user's when it is one.
     * Returns "ok", "app" (the checker's own account, whose comments are nobody's commands), "token" (the GitHub token
     * the server keeps for the user proves another account, whose login stays) or "unavailable" (the checker has no
     * GitHub account to answer commands from).
     */
    public String linkGhLogin(String username, String tcToken, GithubClient.GhUser proven, String browserTz) {
        return linkGhLogin(username, tcToken, proven, browserTz, SettingsJournal.By.of(null, null));
    }

    /**
     * See {@link #linkGhLogin(String, String, GithubClient.GhUser, String)}; the link is made from the login {@code
     * by}, which the journal and the GitHub account's line in ⚙ name.
     */
    public String linkGhLogin(String username, String tcToken, GithubClient.GhUser proven, String browserTz,
        SettingsJournal.By by) {
        if (!github.writes())
            return "unavailable";
        if (github.isAppAccount(proven))
            return "app";

        String tz = zoneOf(browserTz);

        Credential sessionTc = new Credential(codec.encryptString(tcToken), 0);
        long now = System.currentTimeMillis();
        String[] result = {"ok"};
        boolean[] commandsOn = {false};
        enrolled.compute(username, (u, cur) -> {
            Enrollment base = cur != null ? cur : Enrollment.fresh(sessionTc).withEnabledAt(now);
            if (base.gh().token() != null && base.ghLoginProven() && !base.ghAccount().is(proven)) {
                result[0] = "token";

                return cur;
            }

            commandsOn[0] = !base.options().commands();

            return base.withTc(tcKept(base.tc(), tcToken))
                .withGhAccount(GhAccount.linked(base.ghAccount(), proven, by, now))
                .withTz(tz == null ? base.tz() : tz)
                .withOptions(base.options().withCommands());
        });
        if (result[0].equals("ok")) {
            releaseLogin(proven, username);
            journal.add(username, SettingsJournal.Entry.linked(by, proven.login(),
                commandsOn[0] ? List.of(Option.COMMANDS.key) : List.of(), false));
            log.info("PR commands on for {} as GitHub user {} (id {}), linked through GitHub", username,
                proven.login(), proven.id());
        }

        return result[0];
    }

    /**
     * Whether a command from the user's linked GitHub account is the first since the user linked it, or since a change
     * of the server key; true once per link and once per key. A link older than this check has none until the key
     * changes.
     */
    public boolean firstCommand(String username) {
        return changed(username, e -> e.ghAccount() == null || !e.ghAccount().firstCommandDue() ? e
            : e.withGhAccount(e.ghAccount().commandTold()));
    }

    /**
     * Forgets the user's GitHub account, and the options that rest on it: PR commands, whose comments it names; the
     * checkstyle autofix, which runs only from a command; tests to suites, which finds the user's PRs by its login. The
     * GitHub token goes with them: it is of that account, and it would prove the account again. The other options stay,
     * and the enrollment goes when none is left.
     */
    public void unlinkGhLogin(String username) {
        unlinkGhLogin(username, SettingsJournal.By.of(null, null));
    }

    /** See {@link #unlinkGhLogin(String)}; the journal names {@code by} as who unlinked it. */
    public void unlinkGhLogin(String username, SettingsJournal.By by) {
        Enrollment[] was = {null};
        enrolled.computeIfPresent(username, (u, cur) -> {
            if (cur.ghAccount() == null && !cur.options().commands())
                return cur;

            was[0] = cur;
            Options left = cur.options().with(Option.COMMANDS, false).withoutGh();
            if (!left.any())
                return null;

            return cur.withGhAccount(null).withOptions(left).withGh(new Credential(null, cur.gh().rejectedAt()));
        });
        if (was[0] == null)
            return;

        log.info("GitHub account of {} unlinked: PR commands, the checkstyle autofix and tests to suites are off",
            username);
        if (was[0].ghAccount() != null) {
            journal.add(username, SettingsJournal.Entry.unlinked(by, was[0].ghLogin(),
                offWith(was[0].options(), Option.COMMANDS, Option.STYLE_FIX, Option.SUITE_FIX),
                was[0].gh().token() != null || was[0].gh().unread() != null));
        }
        else
            journal.add(username, SettingsJournal.Entry.switchedOff(by, Option.COMMANDS.key));
    }

    /** The keys of those of {@code going} that are on in {@code on}, in the order of the switches. */
    private static List<String> offWith(Options on, Option... going) {
        Set<Option> gone = EnumSet.noneOf(Option.class);
        gone.addAll(List.of(going));

        return on.on().stream().filter(gone::contains).sorted().map(o -> o.key).toList();
    }

    private boolean heldByAnother(GithubClient.GhUser account, String username) {
        return enrolled.entrySet().stream().anyMatch(en -> !en.getKey().equals(username)
            && en.getValue().ghAccount() != null && en.getValue().ghAccount().is(account));
    }

    /**
     * A GitHub account belongs to whoever proved it last: a login typed in by hand, as earlier versions
     * took it (a typo, or someone else's login), would route the owner's commands to the claimant's
     * account. The other user's PR commands go off with the login: left on they would promise what cannot
     * happen. The checkstyle autofix, which runs only from a command, goes with them, and so does the
     * GitHub token when nothing left needs it. Like any last option switched off, it takes the enrollment
     * with it when nothing else is on.
     */
    private void releaseLogin(GithubClient.GhUser account, String owner) {
        enrolled.forEach((u, e) -> {
            if (u.equals(owner) || e.ghAccount() == null || !e.ghAccount().is(account))
                return;

            Options[] was = {null};
            enrolled.computeIfPresent(u, (k, cur) -> {
                if (cur.ghAccount() == null || !cur.ghAccount().is(account))
                    return cur;

                was[0] = cur.options();
                Options left = cur.options().with(Option.COMMANDS, false).consistent();
                if (!left.any())
                    return null;

                Enrollment released = cur.withGhAccount(null).withOptions(left);

                return left.needsGh() ? released : released.withGh(new Credential(null, cur.gh().rejectedAt()));
            });
            if (was[0] == null)
                return;

            journal.add(u, SettingsJournal.Entry.moved(account.login(),
                offWith(was[0], Option.COMMANDS, Option.STYLE_FIX)));
            log.warn("GitHub login {} moved from {} to {}: a GitHub token of {} proves the account; PR commands of {} "
                + "switched off", account.login(), u, owner, owner, u);
        });
    }

    /** The user's linked GitHub login, proven or not; null when none. */
    public String ghLoginOf(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? null : e.ghLogin();
    }

    /**
     * Whether anyone has PR commands on with a proven login, or with one their GitHub token is yet to be asked about —
     * gates the PR command poll entirely.
     */
    public boolean anyGhEnrolled() {
        return enrolled.values().stream()
            .anyMatch(e -> e.options().commands() && (e.ghLoginProven() || e.ghLoginToAsk()));
    }

    /** Whether a GitHub token an earlier version kept is yet to be asked whether it names the login kept with it. */
    public boolean anyGhLoginToAsk() {
        return enrolled.values().stream().anyMatch(Enrollment::ghLoginToAsk);
    }

    /** Whether the user's comments on pull requests are taken as commands. */
    public boolean commandsOn(String username) {
        return options(username).commands();
    }

    /** Whether something waits for the user's finished runs: a visa, re-runs or a PR comment. */
    public boolean settlesRuns(String username) {
        return options(username).settlesRuns();
    }

    /** The auto re-run wave currently settling a build — for external narrators (the story of a /run-all). */
    public Optional<WaveStatus> waveStatus(int pr, long buildId) {
        Retry r = waves.get(buildId);
        if (r == null || r.pr() != pr)
            return Optional.empty();

        return Optional.of(new WaveStatus(r.wave(), inPresentWords(r.what()), activeEtaEpoch(pr)));
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
            return new Phase(Phase.SETTLING, r.wave(), MAX_RERUNS, inPresentWords(r.what()), activeEtaEpoch(pr));
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

    /** The PR comment that carries the verdict of the user's build, if one was posted. */
    public java.util.OptionalLong verdictCommentId(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);
        GhThread t = e == null ? null : e.handled().ghThreads().get(pr);

        return t != null && t.buildId() == buildId ? java.util.OptionalLong.of(t.commentId())
            : java.util.OptionalLong.empty();
    }

    /**
     * Whether the verdict comment of the user's build is still to come: the run is settled, "Comment my runs'
     * verdicts" is on, and GitHub did not take the comment yet. The next settles post it, or a newer run forgets it.
     */
    public boolean verdictCommentDue(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);
        Owed owed = e == null ? null : e.handled().owed().get(pr);

        return owed != null && owed.buildId() == buildId && owed.comment() && e.options().ghComment();
    }

    /**
     * Where the visa of the PR's run {@code buildId} stands, for the line on the PR page {@code viewer} opened. The
     * visa of a run comes only from the option of whoever started it; {@code run} asks TeamCity who that is, and only
     * when the checker's own records do not answer. {@code merged}: the PR is merged. Of someone else's run the viewer
     * learns what became of the run and its visa, whose option it is and whether it is on, and nothing more: their
     * paused tokens and when they switched the option on stay theirs.
     */
    public VisaLine visaLine(String viewer, int pr, long buildId, Supplier<Run> run, boolean merged) {
        RunVisa v = runVisas.get(buildId);
        if (v != null && v.pr() == pr && v.commentId() != null)
            return posted(viewer, v);
        if (v != null && v.pr() == pr)
            return VisaLine.of(VisaLine.NO_TICKET, v.by(), v.by().equals(viewer));

        Optional<RunEnd> end = runEnd(pr, buildId);
        if (merged || end.isPresent() && end.get().replacedBy() == 0)
            return VisaLine.of(VisaLine.CLOSED, null, false);
        if (end.isPresent())
            return VisaLine.of(VisaLine.REPLACED, null, false);

        Run r = run.get();
        if (r.starter() == null)
            return VisaLine.of(VisaLine.UNKNOWN, null, false);
        boolean mine = r.starter().equals(viewer);
        if (r.cancelled())
            return VisaLine.of(VisaLine.CANCELLED, r.starter(), mine);
        Enrollment e = enrolled.get(r.starter());
        if (e == null || !e.options().autoVisa() || e.jira().token() == null && !e.jira().waits())
            return new VisaLine(VisaLine.OFF, r.starter(), mine, null, null, mine && e != null && e.jira().rejected());
        if (!mine)
            return VisaLine.of(VisaLine.ON, r.starter(), false);

        boolean settled = Long.valueOf(buildId).equals(e.handled().posted().get(pr));
        Owed owed = e.handled().owed().get(pr);
        boolean late = settled ? owed == null || owed.buildId() != buildId || !owed.visa()
            : r.finishedMs() > 0 && e.since(Option.VISA) > r.finishedMs();
        if (late)
            return VisaLine.of(VisaLine.LATE, viewer, true);
        if (e.jira().unread() != null)
            return new VisaLine(VisaLine.WAITS, viewer, true, null, null, false, e.jira().unread());
        if (e.tc().rejected())
            return VisaLine.of(VisaLine.PAUSED, viewer, true);
        if (e.jira().removed())
            return VisaLine.of(VisaLine.JIRA_TOKEN_REMOVED, viewer, true);

        return VisaLine.of(settled ? VisaLine.RETRYING : VisaLine.DUE, viewer, true);
    }

    /**
     * The line of a posted visa. Visas of v1.20.11 and before do not name their ticket: it is the one the PR title
     * names while the PR is open, and unknown, with no link, after.
     */
    private VisaLine posted(String viewer, RunVisa v) {
        String issue = v.issue() != null ? v.issue() : github.openPrs().stream().filter(p -> p.number() == v.pr())
            .findFirst().flatMap(p -> ticketIn(p.title())).orElse(null);

        return new VisaLine(VisaLine.POSTED, v.by(), v.by().equals(viewer), issue,
            issue == null ? null : jira.commentUrl(issue, v.commentId()), false);
    }

    private void keepRunVisa(long buildId, RunVisa v) {
        runVisas.put(buildId, v);
        while (runVisas.size() > RUN_VISAS_KEPT)
            runVisas.pollFirstEntry();
    }

    /**
     * What TeamCity says of a run: who started it ({@code starter}, null when it names nobody), whether it was
     * {@code cancelled}, and when it finished ({@code finishedMs}, 0 while it goes).
     */
    public record Run(String starter, boolean cancelled, long finishedMs) {
        /** A run TeamCity tells nothing of. */
        public static final Run UNKNOWN = new Run(null, false, 0);
    }

    /**
     * See {@link #visaLine}. {@code starter}: whose option the line is about, null when it is about none. {@code
     * issue} and {@code url}: the ticket and the comment of the posted visa, null for the other states and when the
     * ticket is not known. {@code mine}: the viewer is the starter. {@code jiraRefused}: JIRA refused the viewer's own
     * token, which switched their option off. {@code unread}: why the checker could not read the viewer's JIRA token,
     * {@link SettingsJournal.Entry#KEY_CHANGED} or {@link SettingsJournal.Entry#DAMAGED}, for {@link #WAITS} only.
     */
    public record VisaLine(String state, String starter, boolean mine, String issue, String url, boolean jiraRefused,
        String unread) {
        /** The visa of the run is in the ticket. */
        public static final String POSTED = "posted";

        /** The viewer's option posts it once the run, with its re-runs, settles. */
        public static final String DUE = "due";

        /** The visa did not go in when the run settled: JIRA failed it, and the next sweeps try again. */
        public static final String RETRYING = "retrying";

        /** The starter's option is off. */
        public static final String OFF = "off";

        /** Someone else started the run, and their option is on. */
        public static final String ON = "on";

        /** TeamCity refused the token the viewer's options use: they wait for a login with a fresh one. */
        public static final String PAUSED = "paused";

        /**
         * The state was restored from a copy without the viewer's JIRA token: the option stays on and the visa waits
         * until they save the token again.
         */
        public static final String JIRA_TOKEN_REMOVED = "jira-token-removed";

        /** The run finished before the viewer switched their option on, so the option does not act on it. */
        public static final String LATE = "late";

        /** The run settled while the PR title named no IGNITE ticket, so no visa went out. */
        public static final String NO_TICKET = "no-ticket";

        /** Someone cancelled the run: a cancelled run gets no visa. */
        public static final String CANCELLED = "cancelled";

        /** A newer RunAll of the PR replaces the run: that one gets the visa. */
        public static final String REPLACED = "replaced";

        /** The PR was closed or merged before the run's visa went in. */
        public static final String CLOSED = "closed";

        /** TeamCity did not say who started the run. */
        public static final String UNKNOWN = "unknown";

        /** The viewer's option waits for a new JIRA token: the checker could not read the one it stored. */
        public static final String WAITS = "waits";

        /** A line that names no unread token. */
        public VisaLine(String state, String starter, boolean mine, String issue, String url, boolean jiraRefused) {
            this(state, starter, mine, issue, url, jiraRefused, null);
        }

        static VisaLine of(String state, String starter, boolean mine) {
            return new VisaLine(state, starter, mine, null, null, false);
        }
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

    /** Same as {@link #actorByGhUser} but by the TC username — for follow-ups on an accepted command. */
    public Optional<GhActor> actor(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? Optional.empty() : actorOf(username, e);
    }

    /**
     * An option whose credential is gone is switched off. A token can leave without a 401 — dropped
     * as unusable when saved, made undecryptable by a rotated secret, or lost by an older build that
     * only dropped the token — and a switch left on then promises work that silently never happens.
     * A token taken out of a restored copy of the state, or one the checker could not read at the start, only pauses
     * its options: ⚙ asks the user for a new one.
     */
    private void switchOffOptionsWithoutTokens() {
        enrolled.replaceAll((u, e) -> {
            boolean ghGone = e.options().needsGh() && e.gh().token() == null && !e.gh().waits();
            boolean jiraGone = e.options().autoVisa() && e.jira().token() == null && !e.jira().waits();
            if (!ghGone && !jiraGone)
                return e;

            if (ghGone) {
                journal.add(u, SettingsJournal.Entry.noToken(SettingsJournal.Entry.GITHUB,
                    offWith(e.options(), Option.STYLE_FIX, Option.SUITE_FIX)));
            }
            if (jiraGone)
                journal.add(u, SettingsJournal.Entry.noToken(SettingsJournal.Entry.JIRA, List.of(Option.VISA.key)));

            log.warn("options of {} switched off for want of a token: {}{}", u,
                ghGone ? "checkstyle autofix/suite fix " : "", jiraGone ? "auto-visa" : "");

            long now = System.currentTimeMillis();
            Enrollment next = e;
            if (ghGone)
                next = next.withGh(next.gh().rejected() ? next.gh() : Credential.refusedAt(now))
                    .withOptions(next.options().withoutGh());
            if (jiraGone)
                next = next.withJira(next.jira().rejected() ? next.jira() : Credential.refusedAt(now))
                    .withOptions(next.options().withoutVisa());

            return next.withTcOnlyWhileOn();
        });
    }

    /**
     * Backfills the GitHub login of enrollments made before it was recorded, and asks the GitHub token kept next to a
     * login of an earlier version whether it names that login (one GitHub call per such user, until GitHub answers);
     * no-op when everything is already resolved. GitHub failing to answer leaves it for the next time.
     */
    public void ensureGhLogins() {
        switchOffOptionsWithoutTokens();
        enrolled.forEach((u, e) -> {
            boolean answered = e.ghAccount() == null ? backfillLogin(u, e)
                : !e.ghAccount().askToken() || askToken(u, e);
            if (answered)
                releaseUnneededGhToken(u);
        });
    }

    /**
     * Drops a GitHub token an earlier version kept for the PR comment once GitHub said whose it is: the checker's
     * account posts the comment now, and a token is kept only to commit with.
     */
    private void releaseUnneededGhToken(String username) {
        boolean dropped = changed(username, e -> e.gh().token() == null || e.options().needsGh() || e.ghLoginToAsk()
            ? e : e.withGh(new Credential(null, e.gh().rejectedAt())));
        if (dropped)
            log.info("GitHub token of {} dropped: no option of theirs commits, and the checker's account posts their"
                + " PR comments", username);
    }

    /**
     * False when GitHub could not say whose the token is, and the login is to be asked about again. A token GitHub
     * refuses goes, as after a refused commit.
     */
    private boolean backfillLogin(String username, Enrollment e) {
        if (e.gh().token() == null)
            return true;

        Optional<String> token = decrypt(e.gh());
        Optional<GithubClient.GhUser> named;
        try {
            named = token.flatMap(github::ghIdentity);
        }
        catch (RuntimeException ex) {
            log.info("GitHub login of {} not backfilled this time: {}", username, ex.toString());

            return false;
        }
        if (token.isPresent() && named.isEmpty())
            dropRefusedGhToken(username, e.gh().token());
        GithubClient.GhUser login = named.filter(l -> !heldByAnother(l, username) && !github.isAppAccount(l))
            .orElse(null);
        if (login == null)
            return true;

        // The lookup ran outside the map, so the login goes in only if it is still missing.
        Enrollment next = enrolled.computeIfPresent(username, (k, cur) -> cur.ghAccount() == null
            ? cur.withGhAccount(GhAccount.of(login)) : cur);
        log.info("backfilled for {}: gh login {}", username, next == null ? null : next.ghLogin());

        return true;
    }

    /**
     * A login an earlier version kept next to the user's GitHub token is proven only if that token names it: v1.27.0
     * could also keep a login typed in by hand there, next to a token of another account, and that login's owner would
     * then command under this user's TeamCity token. GitHub's answer gives the account's id. A token GitHub refuses,
     * or one gone since, proves nothing; a refused one goes, as after a refused commit. False when GitHub could not
     * say, and the token is to be asked again.
     */
    private boolean askToken(String username, Enrollment e) {
        GhAccount held = e.ghAccount();
        Optional<String> token = decrypt(e.gh());
        Optional<GithubClient.GhUser> named;
        try {
            named = token.flatMap(github::ghIdentity);
        }
        catch (RuntimeException ex) {
            log.info("GitHub login {} of {} not checked with the GitHub token this time: {}", held.login(), username,
                ex.toString());

            return false;
        }

        GhAccount next = named.filter(n -> n.login().equalsIgnoreCase(held.login()) && !github.isAppAccount(n))
            .map(GhAccount::of).orElse(GhAccount.unproven(held.login()));
        enrolled.computeIfPresent(username, (k, cur) -> held.equals(cur.ghAccount()) ? cur.withGhAccount(next) : cur);
        if (token.isPresent() && named.isEmpty())
            dropRefusedGhToken(username, e.gh().token());
        if (next.proven())
            log.info("GitHub login {} of {} proven by the GitHub token, id {}", next.login(), username, next.id());
        else
            log.warn("GitHub login {} of {} is not proven: the GitHub token kept with it {}; PR commands wait until "
                + "the account is linked", held.login(), username, named.map(n -> github.isAppAccount(n)
                    ? "is of the app account" : "is of " + n.login()).orElse("is refused or gone"));

        return true;
    }

    /** Whether checkstyle autofix on own runs is on for the user. */
    public boolean styleFixOn(String username) {
        return options(username).styleFix();
    }

    /**
     * The users who add their PRs' tests to suites, by GitHub login, with the GitHub token it commits with. Like the
     * other options, it pauses while TeamCity refuses the user's token. None without the checker's account: a commit
     * would land in the PR with no comment to say what it is.
     */
    public Map<String, GhActor> suiteFixers() {
        if (!github.writes())
            return Map.of();

        Map<String, GhActor> out = new LinkedHashMap<>();
        enrolled.forEach((u, e) -> {
            if (e.options().suiteFix() && e.ghLogin() != null && !e.tc().rejected() && !e.gh().rejected())
                actorOf(u, e).filter(a -> a.ghToken() != null).ifPresent(a -> out.put(e.ghLogin(), a));
        });

        return out;
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
            b = asUser(who, () -> QueuedBelowTopException.queued(() -> tc.triggerBuildReplacingQueued(tcToken.get(),
                ev.suite(), ev.pr(), true, "Early re-run by Ignite PR Checker: this suite failed while RunAll "
                    + ev.chainBuildId() + " is still running, settling it now rather than after the chain")));
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
        log.info("early re-run of {} for PR {} queued (chain {} still running, build {})",
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
     * still have a living comment or a wave open, or a finished RunAll whose visa or verdict comment is owed. Events
     * settle a run as soon as its chain or a re-run finishes; the sweep keeps the ones that wait going and catches
     * what an event missed. A PR at rest is not looked up again while TeamCity says no RunAll of it finished since the
     * last sweep.
     */
    @Scheduled(fixedDelay = SWEEP_MS, initialDelay = FIRST_SWEEP_MS)
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
        Finished finished = chainsFinishedSinceLastSweep(t0);
        int posted = 0;
        for (PrSummary pr : listed) {
            if (finished.everyPr() || finished.prs().containsKey(pr.number()) || !resting(pr.number()))
                posted += settle(pr);
        }
        settleUnlisted(listed, finished.prs());

        lastSweepMs = System.currentTimeMillis() - t0;
        if (posted > 0)
            log.info("standing auto-visa sweep: {} visa(s) posted", posted);
    }

    /**
     * The PRs with a RunAll chain finished since the last sweep, with who started the chains, by one TeamCity call
     * across all branches. Looking each PR up cost 50 calls a sweep, nearly all of them to learn that nothing changed.
     * The sweep that looks every PR up asks too: a PR out of its list has no other way to tell a finished run.
     */
    private Finished chainsFinishedSinceLastSweep(long now) {
        long since = sweptUpTo;
        sweptUpTo = now;
        if (since == 0)
            return Finished.UNKNOWN;

        boolean everyPr = ++sweepsSinceFull >= FULL_SWEEP_EVERY;
        if (everyPr)
            sweepsSinceFull = 0;
        try {
            return lookup(token -> tc.prsWithChainsFinishedAfter(token, (since - SWEEP_OVERLAP_MS) / 1000))
                .map(prs -> new Finished(everyPr, prs)).orElse(Finished.UNKNOWN);
        }
        catch (RuntimeException e) {
            log.warn("RunAll chains finished since the last sweep not listed, every PR is looked up: {}", e.toString());

            return Finished.UNKNOWN;
        }
    }

    /**
     * What a sweep knows of the RunAll chains finished since the last one: the PRs, each with the users who started
     * those chains. {@code everyPr}: every listed PR is looked up anyway: on the first sweep, on every {@link
     * #FULL_SWEEP_EVERY}th one, and whenever TeamCity cannot say, which also leaves {@code prs} empty.
     */
    private record Finished(boolean everyPr, Map<Integer, Set<String>> prs) {
        static final Finished UNKNOWN = new Finished(true, Map.of());
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
     * verdict comment of a run before {@code latest} not yet marked superseded, while the checker's account can write.
     */
    private boolean waitsForSettle(Enrollment e, int pr, long latest) {
        GhThread g = e.handled().ghThreads().get(pr);
        JiraThread j = e.handled().jiraThreads().get(pr);
        boolean ghWaits = g != null && github.canWrite() && (!g.unmarked().isEmpty()
            || open(e, pr, g.buildId(), g.done()) || g.buildId() < latest && !g.superseded());
        Owed owed = e.handled().owed().get(pr);

        return ghWaits || j != null && open(e, pr, j.buildId(), j.done()) || owed != null && deliverable(owed, e);
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
     * progress" for good. So did the visa of a RunAll that finished on such a PR while nobody watched it: of the PRs
     * in {@code finished}, those whose run owes a visa or a verdict comment are settled until they rest.
     */
    private void settleUnlisted(List<PrSummary> listed, Map<Integer, Set<String>> finished) {
        Set<Integer> numbers = listed.stream().map(PrSummary::number).collect(Collectors.toSet());
        finished.forEach((pr, starters) -> {
            if (starters.stream().anyMatch(this::postsVerdicts))
                finishedUnlisted.add(pr);
        });
        finishedUnlisted.removeAll(numbers);

        Set<Integer> prs = new java.util.TreeSet<>(finishedUnlisted);
        enrolled.values().forEach(e -> {
            e.handled().ghThreads().forEach((pr, t) -> {
                if (open(e, pr, t.buildId(), t.done()))
                    prs.add(pr);
            });
            e.handled().jiraThreads().forEach((pr, t) -> {
                if (open(e, pr, t.buildId(), t.done()))
                    prs.add(pr);
            });
            e.handled().owed().forEach((pr, owed) -> {
                if (deliverable(owed, e))
                    prs.add(pr);
            });
        });
        waves.values().forEach(r -> prs.add(r.pr()));
        prs.addAll(deciding.keySet());
        prs.removeAll(numbers);

        for (int pr : prs) {
            try {
                settleNow(pr);
            }
            catch (RuntimeException e) {
                log.warn("PR {} out of the sweep's list not settled this time: {}", pr, e.toString());
            }
        }
        finishedUnlisted.removeIf(pr -> resting.containsKey(pr) || closedPrs.containsKey(pr));
    }

    /** Whether the user's finished runs get a visa or a verdict comment, the ones a PR page says are coming. */
    private boolean postsVerdicts(String user) {
        Enrollment e = enrolled.get(user);

        return e != null && (e.options().autoVisa() || e.options().ghComment());
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
                if (e.tc().rejected() || e.tc().waits())
                    return 0; // a refused, removed or unread token pauses its owner's options until a working one comes

                Long last = e.handled().posted().get(pr.number());
                if (last != null && last == buildId) {
                    // v1.20.11 marked runs handled without dropping their waves, which then read as settling for good.
                    settled(e, pr.number(), buildId);
                    rests = true;
                    Optional<String> tcToken = decrypt(e.tc());

                    // this run is already handled (visa'd, or settled without one); a part a refusal held back may go
                    return tcToken.isEmpty() ? 0 : deliverOwed(who, e, pr, buildId, tcToken.get());
                }

                // Only runs that FINISHED after an option was switched on get acted upon by it: the
                // first sweep must not spam week-old tickets with back-filled visas or re-runs.
                long finishedMs = TcDates.epochSeconds(build.get().finishDate()) * 1000L;
                Options acting = e.actingOn(finishedMs);
                if (!acting.settlesRuns()) {
                    endThreads(who, e, pr, b -> b == buildId,
                        e.options().settlesRuns() ? visas.notFollowedAfterChange() : visas.notFollowed());
                    settled(e, pr.number(), buildId);
                    rests = true;
                    return 0; // nothing to post or re-run: a verdict computed now would go nowhere
                }

                Optional<String> tcToken = decrypt(e.tc());
                Optional<String> jiraToken = decrypt(e.jira());
                if (tcToken.isEmpty() || (e.options().autoVisa() && jiraToken.isEmpty() && !e.jira().waits())) {
                    enrolled.remove(who);
                    log.warn("standing options for {} dropped: no TeamCity token, or no JIRA token for auto-visa", who);
                    rests = true;
                    return 0;
                }

                if (acting.autoRerun()) {
                    deciding.put(pr.number(), buildId);
                    undecided = true;
                    if (rerunTracker.hasActive(pr.number()))
                        return 0; // the verdict waits until the re-runs settle
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
                return postSettled(who, e, acting, pr, buildId, res.get(), tcToken.get(), jiraToken.orElse(null));
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
                TcModel.Build b = QueuedBelowTopException.queued(
                    () -> tc.triggerBuildReplacingQueued(tcToken, suite, pr, top, tcComment));
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
     * say — and marks the run handled; what did not go out stays owed. 1 when a visa went out.
     */
    private int postSettled(String who, Enrollment e, Options acting, PrSummary pr, long buildId, AnalysisResult res,
        String tcToken, String jiraToken) {
        Retry done = waves.get(buildId);
        Owed due = new Owed(buildId, acting.autoVisa(), acting.ghComment(), done == null ? null : done.history(),
            done == null ? null : done.note());
        // A JIRA token a restored copy of the state lacked, or one the checker could not read: the visa stays owed
        // until the user saves a new one.
        int posted = deliver(who, e, pr, due, due.visa() && jiraToken != null, due.comment(), res, tcToken, jiraToken);
        settled(e, pr.number(), buildId);

        return posted;
    }

    /**
     * Posts what the run's verdict still owes the user, as far as their options and tokens allow now; see
     * {@link Owed}. A newer RunAll of the PR going holds it back, as it holds back any verdict. 1 when a visa went out.
     */
    private int deliverOwed(String who, Enrollment e, PrSummary pr, long buildId, String tcToken) {
        Owed owed = e.handled().owed().get(pr.number());
        if (owed == null || owed.buildId() != buildId || !deliverable(owed, e))
            return 0;

        Optional<String> jiraToken = decrypt(e.jira());
        boolean visa = owed.visa() && e.options().autoVisa() && jiraToken.isPresent();
        boolean comment = owed.comment() && e.options().ghComment() && github.canWrite();
        if (!visa && !comment)
            return 0;

        Optional<AnalysisResult> res = verdictOf(tcToken, pr.number(), buildId);
        if (res.isEmpty() || res.get().buildId() != buildId || analyzer.stillRetrying(res.get())
            || newerRunGoing(pr.number(), buildId, res.get(), tcToken) > 0)
            return 0;

        log.info("PR {}: the verdict of RunAll {} held back from {} goes out now ({}{})", pr.number(), buildId, who,
            visa ? "visa " : "", comment ? "PR comment" : "");

        return deliver(who, e, pr, owed, visa, comment, res.get(), tcToken, jiraToken.orElse(null));
    }

    /**
     * Whether the user's options and tokens let some of what the run's verdict owes them go out now: the visa, or the
     * PR comment while the checker's account can write.
     */
    private boolean deliverable(Owed owed, Enrollment e) {
        return owed.visaDeliverableBy(e) || owed.comment() && !e.tc().rejected() && e.options().ghComment()
            && github.canWrite();
    }

    /**
     * Posts the parts of the verdict {@code owed} that {@code visa} and {@code comment} name, and keeps owed what is
     * left of it. 1 when a visa went out.
     */
    private int deliver(String who, Enrollment e, PrSummary pr, Owed owed, boolean visa, boolean comment,
        AnalysisResult res, String tcToken, String jiraToken) {
        long buildId = owed.buildId();
        String note = owed.note();
        String settled = settledLine(owed.waves());
        PendingCommits.Ahead ahead = pending.since(tcToken, pr.number(), buildId);
        Integer commitsAhead = ahead == null ? null : Math.max(ahead.commits(), 1);
        String sha = revision(tcToken, buildId);

        Delivery visaSent = Delivery.NOT_DUE;
        if (visa) {
            String body = visas.compose(pr.number(), res, commitsAhead, sha);
            if (ahead != null)
                body = body + "\n\n_" + revisions(ahead, "{{", "}}") + "_";
            if (settled != null)
                body = body + "\n\n" + settled;
            if (note != null)
                body = body + "\n\n" + note;
            visaSent = postVisa(who, e, pr, buildId, res, jiraToken, body, sha);
        }
        boolean commentIn = false;
        if (comment) {
            String md = mention(e) + visas.composeMarkdown(pr.number(), res, commitsAhead, sha);
            if (ahead != null)
                md = md + "\n\n_" + revisions(ahead, "`", "`") + "_";
            if (settled != null)
                md = md + "\n\n_" + settled + "_";
            if (note != null)
                md = md + "\n\n_" + note + "_";
            if (e.options().autoVisa() && ticketless.contains(buildId))
                md = md + "\n\n_No JIRA visa: the PR title names no IGNITE ticket._";
            else if (owed.visa() && !visa && e.options().autoVisa() && e.jira().waits())
                md = md + "\n\n_" + visaWaits(whyGone(e.jira())) + "_";
            if (Caveats.standing(res, commitsAhead) == Caveats.Standing.BLOCKERS)
                md = md + NEXT_STEP;
            commentIn = upsertGhComment(who, e, pr.number(), buildId, md);
        }

        Owed left = owed.after(visa && visaSent != Delivery.FAILED, commentIn);
        if (left.visa() || left.comment())
            e.handled().owed().put(pr.number(), left);
        else
            e.handled().owed().remove(pr.number());

        return visaSent == Delivery.POSTED ? 1 : 0;
    }

    /**
     * "@login " for the start of the verdict comment of the user's run, when a token proved their GitHub login: the
     * checker's account posts it, and GitHub tells the user of a mention. Empty when their login is not proven.
     */
    private static String mention(Enrollment e) {
        return e.ghLoginProven() ? "@" + e.ghLogin() + " " : "";
    }

    /**
     * What the comment that brings the verdict of the user's run {@code buildId} of PR {@code pr} says of its visa: it
     * waits for a JIRA token, as a restored copy of the state lacked it or the checker could not read the stored one.
     * Empty when the visa of the run does not wait for that.
     */
    public Optional<String> visaWaiting(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);
        if (e == null || !e.options().autoVisa() || !e.jira().waits())
            return Optional.empty();

        Owed owed = e.handled().owed().get(pr);

        return owed != null && owed.buildId() == buildId && owed.visa() ? Optional.of(visaWaits(whyGone(e.jira())))
            : Optional.empty();
    }

    /** What the PR comment says of a visa that waits for a JIRA token, by why the stored one is gone. */
    static String visaWaits(String why) {
        if (TOKEN_REMOVED.equals(why)) {
            return "No JIRA visa yet: the checker's state was restored from a backup older than 2 days, which keeps no"
                + " tokens. The visa goes out once whoever started the run saves their JIRA token in ⚙ again.";
        }

        return "No JIRA visa yet: " + (SettingsJournal.Entry.KEY_CHANGED.equals(why)
            ? "the server key was changed, so the checker could no longer read the JIRA token it stored."
            : "the checker could not read the JIRA token it stored.")
            + " The visa goes out once whoever started the run saves a new token in ⚙.";
    }

    /** What became of a visa: it went out, there was none to post, or it is still to go out. */
    private enum Delivery {
        POSTED, NOT_DUE, FAILED
    }

    /**
     * Posts the visa to the ticket the PR title names. None when it names none, and no second one when the
     * last visa there said the same of the same revision: the ticket filled up with copies.
     */
    private Delivery postVisa(String who, Enrollment e, PrSummary pr, long buildId, AnalysisResult res,
        String jiraToken, String body, String sha) {
        Optional<String> issue = ticketIn(pr.title());
        if (issue.isEmpty()) {
            ticketless.add(buildId);
            keepRunVisa(buildId, new RunVisa(pr.number(), who, null, null));
            log.info("no visa for PR {} (build {}, by {}): its title names no IGNITE ticket", pr.number(), buildId, who);

            return Delivery.NOT_DUE;
        }

        String verdict = verdictKey(res);
        JiraThread last = e.handled().jiraThreads().get(pr.number());
        if (last != null && issue.get().equals(last.issue()) && sha != null && sha.equals(last.sha())
            && verdict.equals(last.verdict())) {
            e.handled().jiraThreads().put(pr.number(),
                new JiraThread(buildId, last.commentId(), last.issue(), sha, verdict, true));
            if (last.commentId() != null)
                keepRunVisa(buildId, new RunVisa(pr.number(), who, last.issue(), last.commentId()));
            log.info("no new visa for PR {} (build {}, by {}): the last one in {} says the same of revision {}",
                pr.number(), buildId, who, issue.get(), sha);

            return Delivery.NOT_DUE;
        }

        if (!upsertVisa(who, e, jiraToken, issue.get(), pr.number(), buildId, body, sha, verdict))
            return Delivery.FAILED;

        postedTotal.incrementAndGet();

        return Delivery.POSTED;
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

    /** "Tested commit abc1234; the PR is now at def5678." with the commits wrapped in the markup's code marks. */
    private static String revisions(PendingCommits.Ahead ahead, String open, String close) {
        return "Tested commit " + open + ahead.builtShort() + close + "; the PR is now at " + open
            + ahead.headShort() + close + ".";
    }

    /**
     * Ends the living comments of the PR's runs before {@code latest}: comments from before this release
     * that a newer run left "in progress" for good. Their waves go too.
     */
    private void endReplaced(PrSummary pr, long latest) {
        VisaService.Ending ending = visas.replaced(pr.number(), latest);
        enrolled.forEach((user, e) -> endThreads(user, e, pr, b -> b < latest, ending));
        enrolled.forEach((user, e) -> forgetOwed(user, e, pr.number(), latest, "RunAll " + latest + " replaced it"));
        waves.values().removeIf(r -> r.pr() == pr.number() && r.buildId() < latest && !rerunTracker.tracks(r.buildId()));
    }

    /**
     * Marks the PR's verdict comments of runs before {@code latest}, whoever's runs they tell, as superseded: the PR
     * read like a pile of current verdicts, the old red ones among them. Each gets one line, once, edited by the
     * checker's account, which posted it. A comment that already says a newer run replaced it, or that GitHub no
     * longer has, is only marked so here; a failure leaves it for the next settle, also once a newer verdict of its
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
        if (!github.canWrite())
            return false;

        Optional<String> body;
        try {
            body = github.commentBody(commentId);
        }
        catch (RuntimeException ex) {
            log.warn("reading verdict comment {} of PR {} failed, marked superseded later: {}", commentId, pr,
                ex.toString());

            return false;
        }
        try {
            if (body.isPresent() && !visas.saysSuperseded(body.get()))
                github.appEdit(pr, user, commentId, body.get().stripTrailing() + visas.superseded(pr, latest));
            log.info("verdict comment {} of PR {} marked superseded by RunAll {}", commentId, pr, latest);

            return true;
        }
        catch (RuntimeException ex) {
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
            enrolled.forEach((user, e) -> endThreads(user, e, summary, b -> true, ending));
            enrolled.forEach((user, e) -> forgetOwed(user, e, pr, Long.MAX_VALUE,
                "the PR was " + (merged ? "merged" : "closed")));
            if (waves.values().removeIf(r -> r.pr() == pr))
                log.info("PR {} was {}: its auto re-run waves are dropped", pr, merged ? "merged" : "closed");
        }
    }

    /**
     * Forgets what the verdict of the user's run of the PR before {@code latest} still owed them, and says why. Not
     * while a living comment of that run still says "re-run in progress": it stays open by what is owed, so the next
     * settle tries its last line again.
     */
    private static void forgetOwed(String user, Enrollment e, int pr, long latest, String why) {
        Owed owed = e.handled().owed().get(pr);
        if (owed == null || owed.buildId() >= latest || living(e, pr, owed.buildId()))
            return;

        if (e.handled().owed().remove(pr, owed))
            log.info("PR {}: the held-back verdict of RunAll {} for {} no longer goes out: {}", pr, owed.buildId(),
                user, why);
    }

    /** Whether a comment or visa of the user's run {@code buildId} of the PR is not marked ended yet. */
    private static boolean living(Enrollment e, int pr, long buildId) {
        GhThread g = e.handled().ghThreads().get(pr);
        JiraThread j = e.handled().jiraThreads().get(pr);

        return g != null && g.buildId() == buildId && !g.done() || j != null && j.buildId() == buildId && !j.done();
    }

    /**
     * Ends the user's open living visas of the PR whose build {@code ofBuild} accepts. A PR comment is posted only
     * once its run is settled, and needs no last line.
     */
    private void endThreads(String user, Enrollment e, PrSummary pr, java.util.function.LongPredicate ofBuild,
        VisaService.Ending ending) {
        JiraThread j = e.handled().jiraThreads().get(pr.number());
        if (j != null && ofBuild.test(j.buildId()) && open(e, pr.number(), j.buildId(), j.done()))
            endJira(user, e, pr, j, ending.wiki());
    }

    /**
     * Whether a living comment may still say "re-run in progress": it is not marked ended, and its run was not
     * settled, or its settled verdict did not all go out. Comments from before this release carry no mark; the
     * settled ones were finished then.
     */
    private static boolean open(Enrollment e, int pr, long buildId, boolean done) {
        Owed owed = e.handled().owed().get(pr);

        return !done && (!Long.valueOf(buildId).equals(e.handled().posted().get(pr))
            || owed != null && owed.buildId() == buildId);
    }

    /**
     * Puts the last line in place of a visa's "re-run in progress" one. A visa without it was finished already: it is
     * only marked ended. One JIRA no longer has, or that no token of its owner can edit any more, is forgotten. A
     * failure leaves it for the next settle.
     */
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
            if (pendingAt(body.get()) >= 0) {
                jira.updateComment(token.get(), issue, t.commentId(), withLastLine(body.get(), line));
                journalVisa(user, true, pr.number(), issue, t.buildId(), t.commentId());
            }
            e.handled().jiraThreads().replace(pr.number(), t, t.ended());
            log.info("living visa {} of PR {} (build {}) ended", t.commentId(), pr.number(), t.buildId());
        }
        catch (RuntimeException ex) {
            if (token.isPresent() && jiraRefused(user, token.get())) {
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
     * The run's verdict in ONE PR comment of the checker's account, edited in place when it is posted again for the
     * same build. A failure never breaks the sweep (the JIRA visa may already be out), and a failed edit falls back
     * to a fresh comment rather than losing the verdict. False when the comment is not in: GitHub failed, or asked
     * the checker to wait, and it is tried again at the next settle. What the checker's account is refused never
     * touches the user's own token.
     */
    private boolean upsertGhComment(String who, Enrollment e, int pr, long buildId, String md) {
        try {
            GhThread t = e.handled().ghThreads().get(pr);
            if (t != null && t.buildId() == buildId) {
                try {
                    if (github.appEdit(pr, who, t.commentId(), md)) {
                        e.handled().ghThreads().put(pr, GhThread.posted(buildId, t.commentId(), t.unmarked()));
                        log.info("standing GitHub comment updated for PR {} (build {})", pr, buildId);

                        return true;
                    }
                }
                catch (GithubClient.WriteDeferred deferred) {
                    throw deferred;
                }
                catch (RuntimeException editEx) {
                    log.warn("editing GitHub comment {} for PR {} failed ({}), posting fresh",
                        t.commentId(), pr, editEx.toString());
                }
            }
            GithubClient.PostedComment posted = github.appComment(pr, who, md);
            if (posted == null)
                return false;

            e.handled().ghThreads().put(pr, GhThread.posted(buildId, posted.id(), unmarkedAfter(e, pr, t, buildId)));
            log.info("standing GitHub comment posted for PR {} (build {}) -> {}", pr, buildId, posted.htmlUrl());

            return true;
        }
        catch (RuntimeException ghEx) {
            log.warn("standing GitHub comment for PR {} failed, tried again at the next settle: {}", pr,
                ghEx.toString());

            return false;
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

    /**
     * How many users have the option that adds their PRs' tests to suites on, whether or not a sweep can act for them.
     * Decrypts no token: the public status page asks every few seconds.
     */
    public int suiteFixOnCount() {
        return (int)enrolled.values().stream().filter(e -> e.options().suiteFix()).count();
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
        Snapshots.writeAtomic(mapper, file, new Snapshot(snap, newestPerPr(), early, new HashMap<>(waves),
            new HashMap<>(runVisas), codec.keyCheck()));
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
        Map<Long, RunVisa> visasOfRuns = null;
        String keyCheck = null;
        try {
            Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
            enrollments = s.enrollments() == null ? new Persisted[0] : s.enrollments().toArray(new Persisted[0]);
            visasOfRuns = s.visas();
            keyCheck = s.keyCheck();
            if (s.waves() != null)
                s.waves().forEach((build, r) -> waves.put(build, r.mergingEarlyWaves()));
            if (s.retries() != null)
                s.retries().forEach((pr, r) -> waves.putIfAbsent(r.buildId(), r.ofPr(pr).mergingEarlyWaves()));
            if (s.earlyReruns() != null)
                s.earlyReruns().forEach((build, suites) -> earlyReruns
                    .computeIfAbsent(build, id -> ConcurrentHashMap.newKeySet()).addAll(suites));
        }
        catch (tools.jackson.databind.exc.MismatchedInputException e) {
            // The pre-retries snapshot was a bare enrollment array — read it once, save in the new shape.
            enrollments = mapper.readValue(file.toFile(), Persisted[].class);
        }

        boolean keyChanged = keyCheck != null && !keyCheck.equals(codec.keyCheck());
        for (Persisted p : enrollments) {
            Enrollment e = p.enrollment().withTcOnlyWhileOn();
            boolean refusedKept = e.tc().rejected() && e.tc().token() != null;
            Enrollment loaded = withUnreadDropped(p.username(),
                refusedKept ? e.withTc(refused(e.tc(), e.tc().rejectedAt())) : e, keyChanged);
            enrolled.put(p.username(), keyChanged ? withFirstCommandDue(loaded) : loaded);
        }
        if (visasOfRuns != null)
            visasOfRuns.forEach(this::keepRunVisa);
        else
            keepPostedVisasOf(enrollments);
    }

    /**
     * The enrollment with each stored token the server's key cannot read dropped and marked so: its options stay on
     * and wait for a new token. The file names the key its tokens were encrypted with, so a key other than the
     * server's means the key was changed; under the same key, or in a file of an earlier version, which names none,
     * the token is damaged. A token taken out of a restored copy is marked the same way when the key changed since the
     * copy: the key changes only when it leaked, so the user has to store a new token, not the same one again. The
     * user's journal says which tokens went.
     */
    private Enrollment withUnreadDropped(String user, Enrollment e, boolean keyChanged) {
        String why = keyChanged ? SettingsJournal.Entry.KEY_CHANGED : SettingsJournal.Entry.DAMAGED;
        List<String> dropped = new ArrayList<>();
        Enrollment next = e;
        if (unreadable(e.tc(), keyChanged)) {
            next = next.withTc(Credential.unread(why));
            dropped.add(SettingsJournal.Entry.TEAMCITY);
        }
        if (unreadable(e.jira(), keyChanged)) {
            next = next.withJira(Credential.unread(why));
            dropped.add(SettingsJournal.Entry.JIRA);
        }
        if (unreadable(e.gh(), keyChanged)) {
            next = next.withGh(Credential.unread(why));
            dropped.add(SettingsJournal.Entry.GITHUB);
        }
        if (dropped.isEmpty())
            return e;

        journal.add(user, SettingsJournal.Entry.unread(dropped, why));
        log.warn("stored tokens of {} could not be read ({}): {} dropped, their options wait for new ones", user,
            keyChanged ? "the server key was changed" : "damaged", dropped);

        return next;
    }

    /**
     * The enrollment whose proven GitHub account has its first command due again. Whoever had the old key could make a
     * cookie of any user and link their own account to it: the first command after a change of the key names in the
     * PR whose ci2 account the commands of the account run under.
     */
    private static Enrollment withFirstCommandDue(Enrollment e) {
        return e.ghLoginProven() ? e.withGhAccount(e.ghAccount().commandDue()) : e;
    }

    /** Whether the token goes as unread: it does not decrypt, or a restored copy lost it before the key changed. */
    private boolean unreadable(Credential c, boolean keyChanged) {
        return c.token() != null && decrypt(c).isEmpty() || keyChanged && c.removed();
    }

    /** The visas of a snapshot written before the visas of runs were kept apart: those its enrollments still edit. */
    private void keepPostedVisasOf(Persisted[] enrollments) {
        for (Persisted p : enrollments) {
            if (p.jiraThreads() == null)
                continue;

            p.jiraThreads().forEach((pr, t) -> {
                if (t.commentId() != null)
                    keepRunVisa(t.buildId(), new RunVisa(pr, p.username(), t.issue(), t.commentId()));
            });
        }
    }

    /**
     * One user's standing options. Every change goes through {@link ConcurrentMap#compute} with one of
     * the {@code with*} copies, so a settings click, a refused token and the poll's backfill can never
     * undo each other. {@code enabledAt} is when an option that acts on runs was last switched on, and
     * {@code onSince} when each one was; snapshots of v1.20.11 and before have {@code enabledAt} only.
     */
    private record Enrollment(Credential tc, Credential jira, Credential gh, GhAccount ghAccount, String tz,
        long enabledAt, Options options, Handled handled, Map<Option, Long> onSince) {
        static Enrollment fresh(Credential tc) {
            return new Enrollment(tc, Credential.NONE, Credential.NONE, null, null, 0, Options.NONE, Handled.empty(),
                Map.of());
        }

        /** The login of the user's GitHub account, proven or not; null when none is linked. */
        String ghLogin() {
            return ghAccount == null ? null : ghAccount.login();
        }

        /** Whether a token proved the user's GitHub account. */
        boolean ghLoginProven() {
            return ghAccount != null && ghAccount.proven();
        }

        /** Whether the user's GitHub token is yet to be asked whether it names the login an earlier version kept. */
        boolean ghLoginToAsk() {
            return ghAccount != null && ghAccount.askToken() && gh.token() != null;
        }

        Enrollment withTc(Credential c) {
            return new Enrollment(c, jira, gh, ghAccount, tz, enabledAt, options, handled, onSince);
        }

        /**
         * The same without the TeamCity token once no option is on. Such an enrollment stays only for what ⚙ says of a
         * refused token, and the TeamCity token would lie on disk and in the backups for nothing.
         */
        Enrollment withTcOnlyWhileOn() {
            return options.any() ? this : withTc(Credential.NONE);
        }

        Enrollment withJira(Credential c) {
            return new Enrollment(tc, c, gh, ghAccount, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withGh(Credential c) {
            return new Enrollment(tc, jira, c, ghAccount, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withGhAccount(GhAccount account) {
            return new Enrollment(tc, jira, gh, account, tz, enabledAt, options, handled, onSince);
        }

        Enrollment withTz(String zone) {
            return new Enrollment(tc, jira, gh, ghAccount, zone, enabledAt, options, handled, onSince);
        }

        Enrollment withOptions(Options o) {
            return new Enrollment(tc, jira, gh, ghAccount, tz, enabledAt, o, handled, onSince);
        }

        Enrollment withEnabledAt(long at) {
            return new Enrollment(tc, jira, gh, ghAccount, tz, at, options, handled, onSince);
        }

        /** With the options {@code next}: one switched on {@code now} acts on the runs that finish from now on. */
        Enrollment switchedTo(Options next, long now) {
            Map<Option, Long> since = new EnumMap<>(Option.class);
            next.on().forEach(o -> since.put(o, options.on().contains(o) ? since(o) : now));

            return new Enrollment(tc, jira, gh, ghAccount, tz, enabledAt, next, handled,
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

    /**
     * The GitHub account the user's PR comments come from: its {@code login}, its {@code id} once known, and whether
     * GitHub named it to a token of the user ({@code proven}). Only a proven account's comments are their commands: a
     * login typed in by hand, as earlier versions took it, could be anyone's. {@code askToken}: an earlier version
     * kept the login next to the user's GitHub token, which is yet to be asked whether it names that login. {@code
     * linkedAt} and {@code linkedFrom}: when the user linked it, and the label of the browser they linked it from;
     * null for a link older than these fields and for one an earlier version kept. {@code firstCommandDue}: no command
     * came from the account since a link made it the user's, or since a change of the server key.
     */
    private record GhAccount(String login, Long id, boolean proven, boolean askToken, Long linkedAt, String linkedFrom,
        boolean firstCommandDue) {
        GhAccount(String login, Long id, boolean proven, boolean askToken) {
            this(login, id, proven, askToken, null, null, false);
        }

        static GhAccount of(GithubClient.GhUser user) {
            return new GhAccount(user.login(), user.id() > 0 ? user.id() : null, true, false);
        }

        /**
         * The account {@code user}, linked {@code at} from the browser of {@code by}, in place of {@code held}. The
         * account a user links is new to them unless it is the proven one they hold: then its first command is told
         * already, or still due, as before.
         */
        static GhAccount linked(GhAccount held, GithubClient.GhUser user, SettingsJournal.By by, long at) {
            boolean same = held != null && held.proven() && held.is(user);

            return new GhAccount(user.login(), user.id() > 0 ? user.id() : null, true, false, at,
                by == null ? null : by.browser(), !same || held.firstCommandDue());
        }

        /** The same proven account, under the login GitHub spells it with now. */
        GhAccount renamedTo(GithubClient.GhUser user) {
            return new GhAccount(user.login(), user.id() > 0 ? user.id() : null, true, false, linkedAt, linkedFrom,
                firstCommandDue);
        }

        /** The account once its first command since the link is told. */
        GhAccount commandTold() {
            return new GhAccount(login, id, proven, askToken, linkedAt, linkedFrom, false);
        }

        /** The account whose next command is told in the PR as its first. */
        GhAccount commandDue() {
            return new GhAccount(login, id, proven, askToken, linkedAt, linkedFrom, true);
        }

        static GhAccount unproven(String login) {
            return new GhAccount(login, null, false, false);
        }

        /** Whether {@code user} is this account: by the id, or by the login while the id is not known. */
        boolean is(GithubClient.GhUser user) {
            return id != null ? id == user.id() : login.equalsIgnoreCase(user.login());
        }
    }

    /**
     * A stored token (encrypted; null once dropped), when its service last refused it (0: never), and what the user
     * should know of it ({@code note}, null when nothing). Of a TeamCity token TeamCity refused only its SHA-256
     * ({@code hash}) stays, to know the dead token when a session brings it again. {@code removed}: the state was
     * restored from a copy old enough to have lost the token ({@link com.github.igniteprchecker.persist.TokenScrub}).
     * {@code unread}: why the checker could not read the stored token at the start and dropped it, {@link
     * SettingsJournal.Entry#KEY_CHANGED} or {@link SettingsJournal.Entry#DAMAGED}; null when it dropped none. These are
     * two causes, and the user is told each as it is; either way the options that need the token wait for a new one.
     */
    private record Credential(String token, long rejectedAt, String note, String hash, boolean removed,
        String unread) {
        static final Credential NONE = new Credential(null, 0);

        static final Credential REMOVED = new Credential(null, 0, null, null, true, null);

        Credential(String token, long rejectedAt) {
            this(token, rejectedAt, null);
        }

        Credential(String token, long rejectedAt, String note) {
            this(token, rejectedAt, note, null);
        }

        Credential(String token, long rejectedAt, String note, String hash) {
            this(token, rejectedAt, note, hash, false, null);
        }

        /** A token the checker could not read, and why. */
        static Credential unread(String why) {
            return new Credential(null, 0, null, null, false, why);
        }

        /** Whether the token is gone, taken out of a restored copy or unread, and its options wait for a new one. */
        boolean waits() {
            return removed || unread != null;
        }

        static Credential refusedAt(long at) {
            return new Credential(null, at);
        }

        static Credential refused(String hash, long at) {
            return new Credential(null, at, null, hash);
        }

        boolean rejected() {
            return rejectedAt > 0;
        }
    }

    /** A standing switch, and its {@code key}: its name in the settings the page reads and in the journal. */
    private enum Option {
        VISA("visa"), RERUN("rerun"), GH_COMMENT("gh"), STYLE_FIX("style"), COMMANDS("commands"), SUITE_FIX("suites");

        final String key;

        Option(String key) {
            this.key = key;
        }
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
                .with(Option.STYLE_FIX, c.style()).with(Option.COMMANDS, c.commands())
                .with(Option.SUITE_FIX, c.suites()).consistent();
        }

        /** The checkstyle autofix runs only from a PR command: without PR commands it is off. */
        Options consistent() {
            return commands() ? this : with(Option.STYLE_FIX, false);
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

        boolean suiteFix() {
            return on.contains(Option.SUITE_FIX);
        }

        boolean any() {
            return !on.isEmpty();
        }

        /**
         * Whether an option that acts on finished runs was off in {@code before} and is on now. PR
         * commands act on the commands only, and adding tests to suites on the PRs' heads, so switching
         * them on moves no cutoff.
         */
        boolean switchedOnSince(Options before) {
            return on.stream().anyMatch(o -> o != Option.COMMANDS && o != Option.SUITE_FIX
                && !before.on().contains(o));
        }

        /** Whether an option acts on the user's finished runs, so the sweep has something to do for them. */
        boolean settlesRuns() {
            return autoVisa() || autoRerun() || ghComment();
        }

        boolean commandsOnly() {
            return on.equals(EnumSet.of(Option.COMMANDS));
        }

        /**
         * The options that commit to the branch of the user's PR need their GitHub token; whatever the checker writes
         * in a PR, it writes from its own account.
         */
        boolean needsGh() {
            return styleFix() || suiteFix();
        }

        Options withoutGh() {
            return with(Option.STYLE_FIX, false).with(Option.SUITE_FIX, false);
        }

        Options withoutVisa() {
            return with(Option.VISA, false);
        }

        Options withCommands() {
            return with(Option.COMMANDS, true);
        }
    }

    /**
     * What was already done for the user's runs: the build handled per PR, its living comments, and what its verdict
     * still owes them.
     */
    private record Handled(ConcurrentMap<Integer, Long> posted, ConcurrentMap<Integer, GhThread> ghThreads,
        ConcurrentMap<Integer, JiraThread> jiraThreads, ConcurrentMap<Integer, Owed> owed) {
        static Handled empty() {
            return new Handled(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>());
        }
    }

    /**
     * What of the verdict of the handled run {@code buildId} did not go out when it was settled: the {@code visa}, the
     * PR {@code comment}, with the re-run {@code waves} and the {@code note} they were to carry. Its service refused
     * the token, failed, or asked to wait. It goes out once its option is on with a working token, the comment while
     * the checker's account can write, while the run is the PR's latest finished RunAll and no newer one goes; a newer
     * RunAll or the PR's close forgets it.
     */
    private record Owed(long buildId, boolean visa, boolean comment, List<String> waves, String note) {
        /** What is left owed once the visa and the comment that went out ({@code visaOut}, {@code commentOut}) are. */
        Owed after(boolean visaOut, boolean commentOut) {
            return new Owed(buildId, visa && !visaOut, comment && !commentOut, waves, note);
        }

        /** Whether the user's options and tokens let its visa go out now. */
        boolean visaDeliverableBy(Enrollment e) {
            return visa && !e.tc().rejected() && e.options().autoVisa() && e.jira().token() != null;
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
     * What the settle of a run of PR {@code pr} did with its visa: posted it to {@code issue} as comment {@code
     * commentId} under the option of {@code by}, or posted none, as the PR title named no IGNITE ticket ({@code
     * commentId} null). Kept apart from the options, so the PR page still tells of a posted visa after {@code by}
     * switched every option off. Visas of v1.20.11 and before do not name their {@code issue}.
     */
    private record RunVisa(int pr, String by, String issue, String commentId) {
    }

    /**
     * The verdict comment of a run in the PR: which build it tells, where to edit it, whether it is final, whether it
     * says a newer run superseded it, and the owner's earlier verdict comments on the PR still to be marked so.
     * {@code app}: the checker's account posted it. Earlier versions posted with the owner's token, which the checker
     * no longer writes with; comments of v1.20.11 and before carry the first two fields only.
     */
    private record GhThread(long buildId, long commentId, boolean done, boolean superseded, List<Long> unmarked,
        boolean app) {
        GhThread {
            unmarked = unmarked == null ? List.of() : List.copyOf(unmarked);
        }

        /** The settled verdict of the run, just posted by the checker's account. */
        static GhThread posted(long buildId, long commentId, List<Long> unmarked) {
            return new GhThread(buildId, commentId, true, false, unmarked, true);
        }

        GhThread supersededNow() {
            return new GhThread(buildId, commentId, done, true, unmarked, app);
        }

        GhThread marked(long comment) {
            return new GhThread(buildId, commentId, done, superseded,
                unmarked.stream().filter(c -> c != comment).toList(), app);
        }
    }

    /** Where a living comment's "re-run in progress" line starts, in either markup. */
    private static final String PENDING_MARK = "⏳ _Auto re-run ";

    /** An enrolled user resolved from a GitHub login, tokens decrypted and ready to act with. */
    public record GhActor(String username, String tcToken, String ghToken, String tz) {
        @Override
        public String toString() {
            return "GhActor[username=" + username + ", tcToken=" + Secrets.masked(tcToken) + ", ghToken="
                + Secrets.masked(ghToken) + ", tz=" + tz + "]";
        }
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
     * chain's, is what v1.20.11 and before read. {@code visas}, what the settle of each run did with its visa, is
     * absent from the files of earlier versions: their posted visas are those the enrollments still edit. {@code
     * keyCheck} names the key the tokens were encrypted with, {@link SessionCodec#keyCheck}; the files of earlier
     * versions have none.
     */
    private record Snapshot(List<Persisted> enrollments, Map<Integer, Retry> retries,
        Map<Long, List<String>> earlyReruns, Map<Long, Retry> waves, Map<Long, RunVisa> visas, String keyCheck) {
    }

    /** Each PR's newest chain's waves, keyed by the PR as v1.20.11 keyed them. */
    private Map<Integer, Retry> newestPerPr() {
        Map<Integer, Retry> out = new HashMap<>();
        waves.values().forEach(r -> out.merge(r.pr(), r, (a, b) -> a.buildId() >= b.buildId() ? a : b));

        return out;
    }

    /**
     * The run's visa in the ticket, posted once it is settled; one of the same build already there (a visa
     * from before this release, posted while re-runs went) is edited in place. A failed edit falls back to
     * a fresh comment. {@code sha} and {@code verdict} are kept with it, to tell whether the next visa would
     * say anything new. False when the visa is not in: JIRA failed, or refused the token, which is dropped.
     */
    private boolean upsertVisa(String who, Enrollment e, String jiraToken, String issueKey, int pr, long buildId,
        String body, String sha, String verdict) {
        JiraThread t = e.handled().jiraThreads().get(pr);
        if (t != null && t.buildId() == buildId && t.commentId() != null) {
            try {
                jira.updateComment(jiraToken, issueKey, t.commentId(), body);
                e.handled().jiraThreads().put(pr, new JiraThread(buildId, t.commentId(), issueKey, sha, verdict, true));
                keepRunVisa(buildId, new RunVisa(pr, who, issueKey, t.commentId()));
                journalVisa(who, true, pr, issueKey, buildId, t.commentId());
                log.info("standing visa updated in place for PR {} (build {}, by {})", pr, buildId, who);

                return true;
            }
            catch (RuntimeException editEx) {
                if (jiraRefused(who, jiraToken))
                    return false;

                log.warn("editing visa comment {} for PR {} failed ({}), posting fresh",
                    t.commentId(), pr, editEx.toString());
            }
        }

        JiraClient.PostedComment posted;
        try {
            posted = jira.addCommentWithId(jiraToken, issueKey, body);
        }
        catch (RuntimeException postEx) {
            if (!jiraRefused(who, jiraToken))
                log.warn("standing visa for PR {} (build {}) failed, tried again at the next settle: {}", pr,
                    buildId, postEx.toString());

            return false;
        }
        e.handled().jiraThreads().put(pr, new JiraThread(buildId, posted.id(), issueKey, sha, verdict, true));
        keepRunVisa(buildId, new RunVisa(pr, who, issueKey, posted.id()));
        journalVisa(who, false, pr, issueKey, buildId, posted.id());
        log.info("standing auto-visa posted for PR {} (build {}, by {}) -> {}", pr, buildId, who, posted.url());

        return true;
    }

    /** Writes a visa the checker posted, or edited, with the user's JIRA token into their journal. */
    private void journalVisa(String user, boolean edited, int pr, String issue, long buildId, String commentId) {
        journal.add(user, SettingsJournal.Entry.visa(edited, pr, issue, buildId, jira.commentUrl(issue, commentId)));
    }

    /**
     * Whether JIRA refuses the user's token, after a call with it failed: then it is dropped. The failure itself
     * does not tell: ASF JIRA serves a dead token as an anonymous user, and answers 403 for a ticket the user may
     * not comment on too.
     */
    private boolean jiraRefused(String user, String jiraToken) {
        if (!jira.tokenRejected(jiraToken))
            return false;

        dropJiraToken(user);

        return true;
    }

    /** The final "how it settled" line: every re-run wave in order; null when there were none. */
    private static String settledLine(List<String> history) {
        if (history == null || history.isEmpty())
            return null;

        StringBuilder b = new StringBuilder("♻️ Settled after ").append(history.size()).append(" auto re-run wave")
            .append(history.size() == 1 ? ":" : "s:");
        for (int i = 0; i < history.size(); i++) {
            b.append(i == 0 ? " " : "; ").append("#").append(i + 1).append(" — ")
                .append(inPresentWords(history.get(i)));
        }

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

    /**
     * The suites a wave re-runs, by why: {@code "3 suites (2 with blockers, 1 broken)"}, or by the one reason when
     * there is one, {@code "2 suites with blockers"}, {@code "1 suite with tests that started failing"},
     * {@code "3 broken suites"}, {@code "4 cancelled suites"}.
     */
    private static String suitesLabel(int blockers, int watch, int broken, int cancelled) {
        List<String> parts = new ArrayList<>();
        if (blockers > 0)
            parts.add(blockers + " with blockers");
        if (watch > 0)
            parts.add(watch + " " + WATCH_SUITES);
        if (broken > 0)
            parts.add(broken + " broken");
        if (cancelled > 0)
            parts.add(cancelled + " cancelled");
        int n = blockers + watch + broken + cancelled;
        String suites = n + (n == 1 ? " suite" : " suites");
        if (parts.size() > 1)
            return suites + " (" + String.join(", ", parts) + ")";
        if (blockers > 0)
            return suites + " with blockers";
        if (watch > 0)
            return suites + " " + WATCH_SUITES;
        if (broken > 0)
            return n + " broken" + (n == 1 ? " suite" : " suites");
        if (cancelled > 0)
            return n + " cancelled" + (n == 1 ? " suite" : " suites");

        return suites;
    }

    /** A wave's label as releases before this one wrote it: "2 blocker + 1 broken suite(s)", "0 suite(s)". */
    private static final Pattern EARLIER_LABEL = Pattern.compile(
        "(?:0|\\d+ (?:blocker|watch|broken|cancelled)(?: \\+ \\d+ (?:blocker|watch|broken|cancelled))*) suite\\(s\\)");

    private static final Pattern EARLIER_LABEL_PART = Pattern.compile("(\\d+) (blocker|watch|broken|cancelled)");

    /** What a wave's label says of the suites it re-runs for tests under Recently started failing. */
    private static final String WATCH_SUITES = "with tests that started failing";

    /** How a part of a wave's label with several reasons read: "2 with blockers", "1 to watch". */
    private static final String WATCH_LABEL_PART = "\\d+ (?:with blockers|to watch|broken|cancelled)";

    /** A wave's label that called those suites "to watch": "1 suite to watch", "2 suites (1 to watch, 1 broken)". */
    private static final Pattern WATCH_LABEL = Pattern.compile(
        "\\d+ suites? to watch|\\d+ suites \\(" + WATCH_LABEL_PART + "(?:, " + WATCH_LABEL_PART + ")*\\)");

    /**
     * A wave's label in this release's words. The snapshot keeps each label as the release that queued the wave
     * wrote it, so a wave queued before a deploy is said after it in the earlier words otherwise.
     */
    private static String inPresentWords(String label) {
        if (label != null && WATCH_LABEL.matcher(label).matches())
            return label.replace(" to watch", " " + WATCH_SUITES);
        if (label == null || !EARLIER_LABEL.matcher(label).matches())
            return label;

        Map<String, Integer> counts = new HashMap<>();
        Matcher m = EARLIER_LABEL_PART.matcher(label);
        while (m.find())
            counts.merge(m.group(2), Integer.parseInt(m.group(1)), Integer::sum);

        return suitesLabel(counts.getOrDefault("blocker", 0), counts.getOrDefault("watch", 0),
            counts.getOrDefault("broken", 0), counts.getOrDefault("cancelled", 0));
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

    /**
     * One enrollment as it is written to disk; the field names are the file format. {@code onSince} is keyed by
     * the option's name, so a snapshot that names an option this version does not know still loads. Snapshots without
     * {@code tcRefusedHash} kept the ciphertext of a TeamCity token TeamCity refused; it is hashed at load. A token
     * taken out of a copy of the state is marked {@code tcTokenRemoved}, {@code jiraTokenRemoved} or {@code
     * ghTokenRemoved}, and its refusal time there was set for builds that do not know the mark. {@code ghLinkedAt},
     * {@code ghLinkedFrom} and {@code ghFirstCommandDue} are absent for a link made before they were kept: its date is
     * not known, and it has no first command to tell. {@code tcTokenUnread}, {@code jiraTokenUnread} and {@code
     * ghTokenUnread} say why the checker dropped a token it could not read; absent when it dropped none.
     */
    private record Persisted(String username, String tcToken, String jiraToken, String ghToken, String ghLogin,
        String tz, long enabledAt, Map<Integer, Long> posted, Map<Integer, GhThread> ghThreads,
        Map<Integer, JiraThread> jiraThreads,
        Boolean autoVisa, boolean autoRerun, Boolean ghComment, Boolean styleFix,
        Long ghRejectedAt, Long jiraRejectedAt, Long tcRejectedAt, Boolean commands, Map<String, Long> onSince,
        Boolean suiteFix, Map<Integer, Owed> owed, Long ghId, Boolean ghLoginProven, String ghTokenNote,
        String tcRefusedHash, Boolean tcTokenRemoved, Boolean jiraTokenRemoved, Boolean ghTokenRemoved,
        Long ghLinkedAt, String ghLinkedFrom, Boolean ghFirstCommandDue, String tcTokenUnread, String jiraTokenUnread,
        String ghTokenUnread) {
        static Persisted of(String username, Enrollment e) {
            Options o = e.options();
            Handled h = e.handled();
            Map<String, Long> since = new HashMap<>();
            e.onSince().forEach((option, at) -> since.put(option.name(), at));
            GhAccount gh = e.ghAccount();

            return new Persisted(username, e.tc().token(), e.jira().token(), e.gh().token(), e.ghLogin(), e.tz(),
                e.enabledAt(), new HashMap<>(h.posted()), new HashMap<>(h.ghThreads()), new HashMap<>(h.jiraThreads()),
                o.autoVisa(), o.autoRerun(), o.ghComment(), o.styleFix(), e.gh().rejectedAt(), e.jira().rejectedAt(),
                e.tc().rejectedAt(), o.commands(), since, o.suiteFix(), new HashMap<>(h.owed()),
                gh == null ? null : gh.id(), gh == null || gh.askToken() ? null : gh.proven(), e.gh().note(),
                e.tc().hash(), e.tc().removed(), e.jira().removed(), e.gh().removed(),
                gh == null ? null : gh.linkedAt(), gh == null ? null : gh.linkedFrom(),
                gh != null && gh.firstCommandDue() ? Boolean.TRUE : null, e.tc().unread(), e.jira().unread(),
                e.gh().unread());
        }

        /**
         * Missing fields are what the snapshots written before them meant: before the PR commands
         * switch, a linked GitHub login was all commands needed. The checkstyle autofix saved on without PR
         * commands, which it never ran without, is off, and the GitHub token goes when nothing else needs it.
         * Before logins were proven, one stored with a GitHub token mostly came from that token, but one typed in by
         * hand could sit next to a token of another account: the token is asked whose it is, since PR commands and
         * the mention in the verdict comment take only a proven login. One stored without a token could have been
         * typed in by hand. Before the checker's account wrote the PR comments, the user's GitHub token did: a token
         * kept for nothing else goes, once asked, and the verdict comments it posted are no longer the checker's to
         * edit. A token kept to commit next to a login it did not prove is asked again: v1.28.0 kept it when GitHub
         * refused it, and the options that commit stayed on with it.
         */
        Enrollment enrollment() {
            Handled h = Handled.empty();
            if (posted != null)
                h.posted().putAll(posted);
            if (ghThreads != null) {
                ghThreads.forEach((pr, t) -> {
                    if (t.app())
                        h.ghThreads().put(pr, t);
                    else
                        log.info("verdict comment {} of {} on PR {} forgotten: it was posted with their own GitHub "
                            + "token, and the checker edits only its own comments", t.commentId(), username, pr);
                });
            }
            if (jiraThreads != null)
                h.jiraThreads().putAll(jiraThreads);
            if (owed != null)
                h.owed().putAll(owed);
            Map<Option, Long> since = new EnumMap<>(Option.class);
            for (Option o : Option.values()) {
                Long at = onSince == null ? null : onSince.get(o.name());
                if (at != null)
                    since.put(o, at);
            }

            Options saved = Options.NONE.with(Option.VISA, autoVisa == null || autoVisa).with(Option.RERUN, autoRerun)
                .with(Option.GH_COMMENT, ghComment != null && ghComment)
                .with(Option.STYLE_FIX, styleFix != null && styleFix)
                .with(Option.COMMANDS, commands == null ? ghLogin != null : commands)
                .with(Option.SUITE_FIX, suiteFix != null && suiteFix);
            Options options = saved.consistent();
            boolean ghUnneeded = saved.needsGh() && !options.needsGh();
            if (options != saved)
                log.info("checkstyle autofix of {} is off: it runs only from a PR command, and PR commands are off{}",
                    username, ghUnneeded ? "; the GitHub token, which nothing else needs, is dropped" : "");

            boolean ask = ghToken != null && ghLogin != null
                && (ghLoginProven == null || !ghLoginProven && options.needsGh());
            boolean ghKept = ghToken != null && (options.needsGh() || ask);
            if (ghToken != null && !ghKept && !ghUnneeded)
                log.info("GitHub token of {} dropped: no option of theirs commits, and the checker's account posts"
                    + " their PR comments", username);
            GhAccount account = ghLogin == null ? null : ghLoginProven != null && !ask
                ? new GhAccount(ghLogin, ghId, ghLoginProven, false, ghLinkedAt, ghLinkedFrom,
                    Boolean.TRUE.equals(ghFirstCommandDue))
                : new GhAccount(ghLogin, null, false, ask);

            return new Enrollment(
                Boolean.TRUE.equals(tcTokenRemoved) ? Credential.REMOVED
                    : tcTokenUnread != null ? Credential.unread(tcTokenUnread)
                    : new Credential(tcToken, tcRejectedAt == null ? 0 : tcRejectedAt, null, tcRefusedHash),
                Boolean.TRUE.equals(jiraTokenRemoved) && options.autoVisa() ? Credential.REMOVED
                    : jiraTokenUnread != null ? Credential.unread(jiraTokenUnread)
                    : new Credential(jiraToken, jiraRejectedAt == null ? 0 : jiraRejectedAt),
                Boolean.TRUE.equals(ghTokenRemoved) && options.needsGh() ? Credential.REMOVED
                    : ghTokenUnread != null && options.needsGh() ? Credential.unread(ghTokenUnread)
                    : new Credential(ghKept ? ghToken : null, ghRejectedAt == null ? 0 : ghRejectedAt,
                        ghKept ? ghTokenNote : null), account, tz, enabledAt, options, h,
                Collections.unmodifiableMap(since));
        }
    }
}
