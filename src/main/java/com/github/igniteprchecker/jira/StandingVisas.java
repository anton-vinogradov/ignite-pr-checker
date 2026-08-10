package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Pattern ISSUE = Pattern.compile("IGNITE-\\d+");

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
    /** Auto-rerun attempts per PR for the build being settled; persisted with the enrollments. */
    private final ConcurrentMap<Integer, Retry> retries = new ConcurrentHashMap<>();
    /** Suites already re-run mid-chain, per chain build — so a restart can't re-queue them again. */
    private final ConcurrentMap<Long, java.util.Set<String>> earlyReruns = new ConcurrentHashMap<>();

    /** Early re-runs do TeamCity work; one thread keeps them off the tracker's polling thread. */
    private final java.util.concurrent.ExecutorService earlyPool =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "early-rerun");
            t.setDaemon(true);
            return t;
        });

    /** How many times the blocker suites are re-run before the visa is posted as-is. */
    private static final int MAX_RERUNS = 2;
    /** Up to this many blocker suites jump the queue; more go to the tail so others aren't pushed back. */
    private static final int TOP_QUEUE_LIMIT = 10;
    /** Above this many blocker suites, auto re-run is pointless (systemic breakage) — the visa posts as-is. */
    private static final int MAX_SUITES_PER_RERUN = 30;
    private final java.util.concurrent.atomic.AtomicInteger postedTotal = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastSweepAt;
    private volatile long lastSweepMs;

    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker, Warmer warmer, PendingCommits pending) {
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
            codec.decryptString(e.tcToken()).ifPresent(warmer::offerToken);
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
     * Enrols the user with two independent switches: auto-visa (needs the JIRA token) and
     * auto-rerun (TC token only). Tokens stay encrypted at rest until {@link #disable}.
     */
    public void enable(String username, String tcToken, String jiraToken, String ghToken,
        boolean autoVisa, boolean autoRerun, boolean ghComment, boolean styleFix) {
        // A settings change must not forget which builds were already handled (or their comments),
        // nor a GitHub login the user linked by hand (a PAT-derived one is authoritative though).
        Enrollment prev = enrolled.get(username);
        String ghLogin = ghToken != null ? github.ghUser(ghToken).orElse(null)
            : prev != null ? prev.ghLogin() : null;
        String tz = jiraToken == null ? null : jira.myTimezone(jiraToken).orElse(null);
        enrolled.put(username, new Enrollment(
            codec.encryptString(tcToken), jiraToken == null ? null : codec.encryptString(jiraToken),
            ghToken == null ? null : codec.encryptString(ghToken), ghLogin, tz,
            System.currentTimeMillis(),
            prev != null ? prev.posted() : new ConcurrentHashMap<>(),
            prev != null ? prev.ghThreads() : new ConcurrentHashMap<>(),
            prev != null ? prev.jiraThreads() : new ConcurrentHashMap<>(),
            autoVisa, autoRerun, ghComment, styleFix));
        log.info("standing options for {}: autoVisa={}, autoRerun={}, ghComment={}, styleFix={} (gh login {}, tz {})",
            username, autoVisa, autoRerun, ghComment, styleFix, ghLogin, tz);
        donateWarmTokens();
    }

    /**
     * The user behind a GitHub login, with decrypted tokens — the PR command poll resolves the
     * comment's author through this. Only users with the GitHub option on are addressable.
     */
    public Optional<GhActor> actorByGhLogin(String login) {
        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            Enrollment e = en.getValue();
            if (login.equals(e.ghLogin())) {
                Optional<String> tcToken = codec.decryptString(e.tcToken());
                if (tcToken.isEmpty())
                    continue;

                // The PAT is optional for commands: without one the checker acks and narrates
                // from its own (the operator's) account instead of the user's.
                String ghToken = e.ghToken() == null ? null : codec.decryptString(e.ghToken()).orElse(null);

                return Optional.of(new GhActor(en.getKey(), tcToken.get(), ghToken, e.tz()));
            }
        }

        return Optional.empty();
    }

    /**
     * Links a GitHub login to the user's enrollment by hand — the no-PAT way into PR commands.
     * Returns "ok", "taken" (someone else claimed it) or "none" (no enrollment to attach to).
     */
    public String setGhLogin(String username, String login) {
        String clean = login.strip().replaceFirst("^@", "");
        if (clean.isBlank())
            return "none";
        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            if (!en.getKey().equals(username) && clean.equalsIgnoreCase(en.getValue().ghLogin()))
                return "taken";
        }

        Enrollment e = enrolled.get(username);
        if (e == null)
            return "none";

        enrolled.put(username, new Enrollment(e.tcToken(), e.jiraToken(), e.ghToken(), clean, e.tz(),
            e.enabledAt(), e.posted(), e.ghThreads(), e.jiraThreads(),
            e.autoVisa(), e.autoRerun(), e.ghComment(), e.styleFix()));
        log.info("gh login for {} linked by hand: {}", username, clean);

        return "ok";
    }

    /** The user's linked GitHub login (PAT-derived or hand-linked), or null. */
    public String ghLoginOf(String username) {
        Enrollment e = enrolled.get(username);

        return e == null ? null : e.ghLogin();
    }

    /** Whether anyone can be addressed by a GitHub login — gates the PR command poll entirely. */
    public boolean anyGhEnrolled() {
        return enrolled.values().stream().anyMatch(e -> e.ghLogin() != null);
    }

    /** The auto re-run wave currently settling a build — for external narrators (the command comment). */
    public Optional<WaveStatus> waveStatus(int pr, long buildId) {
        Retry r = retries.get(pr);
        if (r == null || r.buildId() != buildId)
            return Optional.empty();

        int wave = r.history() != null && !r.history().isEmpty() ? r.history().size() : r.attempts();

        return Optional.of(new WaveStatus(wave, r.what(), activeEtaEpoch(pr)));
    }

    /** Whether the build's verdict has been posted for the user — i.e. the run's story is over. */
    public boolean buildHandled(String username, int pr, long buildId) {
        Enrollment e = enrolled.get(username);

        return e != null && Long.valueOf(buildId).equals(e.posted().get(pr));
    }

    /** One settling wave as seen from outside: its number, what it re-runs, and the settle estimate. */
    public record WaveStatus(int wave, String what, Long etaEpochSec) {
    }

    /** Same as {@link #actorByGhLogin} but by the TC username — for follow-ups on an accepted command. */
    public Optional<GhActor> actor(String username) {
        Enrollment e = enrolled.get(username);
        if (e == null)
            return Optional.empty();

        Optional<String> tcToken = codec.decryptString(e.tcToken());
        if (tcToken.isEmpty())
            return Optional.empty();

        String ghToken = e.ghToken() == null ? null : codec.decryptString(e.ghToken()).orElse(null);

        return Optional.of(new GhActor(username, tcToken.get(), ghToken, e.tz()));
    }

    /**
     * Backfills {@code ghLogin} and {@code tz} for enrollments made before those were recorded
     * (one GitHub/JIRA call per such user, once); no-op when everything is already resolved.
     */
    public void ensureGhLogins() {
        enrolled.replaceAll((u, e) -> {
            String login = e.ghLogin();
            if (e.ghComment() && login == null && e.ghToken() != null)
                login = codec.decryptString(e.ghToken()).flatMap(github::ghUser).orElse(null);

            String tz = e.tz();
            if (tz == null && e.jiraToken() != null)
                tz = codec.decryptString(e.jiraToken()).flatMap(jira::myTimezone).orElse(null);

            if (Objects.equals(login, e.ghLogin()) && Objects.equals(tz, e.tz()))
                return e;

            log.info("backfilled for {}: gh login {}, tz {}", u, login, tz);

            return new Enrollment(e.tcToken(), e.jiraToken(), e.ghToken(), login, tz, e.enabledAt(),
                e.posted(), e.ghThreads(), e.jiraThreads(), e.autoVisa(), e.autoRerun(), e.ghComment(),
                e.styleFix());
        });
    }

    /** Whether checkstyle autofix on own runs is on for the user. */
    public boolean styleFixOn(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.styleFix();
    }

    /** Whether GitHub PR comments are on for the user. */
    public boolean ghOn(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.ghComment();
    }

    /** Whether the standing auto-visa is on for the user. */
    public boolean visaOn(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.autoVisa();
    }

    /** Whether auto-rerun of blocker suites is on for the user. */
    public boolean rerunOn(String username) {
        Enrollment e = enrolled.get(username);

        return e != null && e.autoRerun();
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
     * the settled pass will say so.
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

    private void earlyRerun(RerunTracker.SuiteFailedMidRun ev) {
        if (enrolled.isEmpty())
            return;

        java.util.Set<String> done = earlyReruns.computeIfAbsent(ev.chainBuildId(), id -> ConcurrentHashMap.newKeySet());
        if (done.size() >= TOP_QUEUE_LIMIT || done.contains(ev.suite()))
            return; // already settled this suite, or this chain is failing wholesale

        // Any enrolled token can read who started the chain; only that person's enrollment may act.
        Map.Entry<String, Enrollment> any = enrolled.entrySet().iterator().next();
        Optional<String> lookupToken = codec.decryptString(any.getValue().tcToken());
        if (lookupToken.isEmpty())
            return;

        Optional<String> who = tc.buildTriggeredBy(lookupToken.get(), ev.chainBuildId());
        Enrollment e = who.map(enrolled::get).orElse(null);
        if (e == null || !e.autoRerun())
            return;

        Optional<String> tcToken = codec.decryptString(e.tcToken());
        if (tcToken.isEmpty())
            return;

        Optional<AnalysisResult> res = analyzer.analyze(tcToken.get(), ev.pr());
        if (res.isEmpty() || !worthRerunning(res.get(), ev.suite()))
            return;

        if (!done.add(ev.suite()))
            return; // a concurrent event beat us to it

        TcModel.Build b = tc.triggerBuildReplacingQueued(tcToken.get(), ev.suite(), ev.pr(), true,
            "Early re-run by Ignite PR Checker: this suite failed while RunAll " + ev.chainBuildId()
                + " is still running, settling it now rather than after the chain");
        rerunTracker.record(ev.pr(), b);
        // Count it as the chain's first wave, so the settled pass continues from here instead of
        // starting over — two waves per chain stays two.
        Retry r = retries.get(ev.pr());
        List<String> history = new ArrayList<>(r != null && r.buildId() == ev.chainBuildId() && r.history() != null
            ? r.history() : List.of());
        history.add("early: " + ev.suiteName());
        retries.put(ev.pr(), new Retry(ev.chainBuildId(), 1, "1 suite that failed mid-run", history,
            r != null && r.buildId() == ev.chainBuildId() ? r.note() : null));
        log.info("early re-run of {} for PR {} queued at top (chain {} still running, build {})",
            ev.suiteName(), ev.pr(), ev.chainBuildId(), b.id());
    }

    /** Whether the analysis blames this suite for something a re-run can settle. */
    static boolean worthRerunning(AnalysisResult r, String suite) {
        return r.blockers().stream().anyMatch(v -> suite.equals(v.suite()))
            || r.watch().stream().anyMatch(v -> suite.equals(v.suite()))
            || r.brokenSuites().stream().anyMatch(s -> suite.equals(s.suite()));
    }

    /**
     * Sweep: for every open PR whose latest finished RunAll was triggered by an enrolled user and
     * hasn't been visa'd yet, compute the verdict and post it to the PR's IGNITE ticket.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    void sweep() {
        long t0 = System.currentTimeMillis();
        lastSweepAt = t0;
        lastSweepMs = 0;
        donateWarmTokens(); // keeps the background pool alive between visitors
        if (enrolled.isEmpty())
            return;

        // Any enrolled user's TC token can look up builds; per-PR analysis uses the triggerer's own.
        Map.Entry<String, Enrollment> any = enrolled.entrySet().iterator().next();
        Optional<String> lookupToken = codec.decryptString(any.getValue().tcToken());
        if (lookupToken.isEmpty())
            return;

        int posted = 0;
        for (PrSummary pr : github.openPrs()) {
            Matcher m = pr.title() == null ? null : ISSUE.matcher(pr.title());
            if (m == null || !m.find())
                continue; // nowhere to post

            try {
                Optional<TcModel.Build> build = tc.findRunAllBuildForPr(lookupToken.get(), pr.number());
                if (build.isEmpty() || build.get().triggered() == null || build.get().triggered().user() == null)
                    continue;

                String who = build.get().triggered().user().username();
                Enrollment e = enrolled.get(who);
                if (e == null)
                    continue;

                long buildId = build.get().id();
                Long last = e.posted().get(pr.number());
                if (last != null && last == buildId)
                    continue; // this run is already handled (visa'd, or settled without one)

                // Only runs that FINISHED after the options were switched on get acted upon: the
                // first sweep must not spam week-old tickets with back-filled visas or re-runs.
                long finishedMs = TcDates.epochSeconds(build.get().finishDate()) * 1000L;
                if (finishedMs > 0 && finishedMs < e.enabledAt()) {
                    e.posted().put(pr.number(), buildId);
                    continue;
                }

                Optional<String> tcToken = codec.decryptString(e.tcToken());
                Optional<String> jiraToken = e.jiraToken() == null ? Optional.empty() : codec.decryptString(e.jiraToken());
                Optional<String> ghToken = e.ghToken() == null ? Optional.empty() : codec.decryptString(e.ghToken());
                if (tcToken.isEmpty() || (e.autoVisa() && jiraToken.isEmpty()) || (e.ghComment() && ghToken.isEmpty())) {
                    enrolled.remove(who);
                    log.warn("standing options for {} dropped: tokens undecryptable (secret rotated?)", who);
                    continue;
                }

                Optional<AnalysisResult> res = analyzer.analyze(tcToken.get(), pr.number());
                if (res.isEmpty() || res.get().buildId() != buildId)
                    continue; // raced with a newer run; the next sweep settles it

                // Auto-rerun before the visa: while re-runs of this PR are still live, wait; if the
                // verdict has blockers and attempts remain, re-run their suites (at the top of the
                // queue, under the user's own token) instead of posting a red visa right away.
                if (e.autoRerun()) {
                    if (rerunTracker.hasActive(pr.number())) {
                        // Re-runs still going: keep the living comment's ⏳ line honest about when
                        // they are expected to settle (queue-aware, from the tracker). Anchored on the
                        // persisted comment thread, not on the retry bookkeeping — the line must keep
                        // refreshing even right after a restart.
                        GhThread t = e.ghThreads().get(pr.number());
                        Retry r0 = retries.get(pr.number());
                        if (e.ghComment() && t != null && t.buildId() == buildId) {
                            String what = r0 != null && r0.buildId() == buildId ? r0.what() : "re-run suite(s)";
                            int attempt = r0 != null && r0.buildId() == buildId ? r0.attempts() : 1;
                            List<String> history = r0 != null && r0.buildId() == buildId ? r0.history() : null;
                            upsertGhComment(e, ghToken.get(), pr.number(), buildId,
                                visas.composeMarkdown(pr.number(), res.get())
                                    + pendingLine(what, attempt, history, activeEtaEpoch(pr.number()), e.tz(), "**"));
                        }

                        continue; // the visa waits until the re-runs settle
                    }

                    Retry r = retries.get(pr.number());
                    int attempts = r != null && r.buildId() == buildId ? r.attempts() : 0;
                    List<String> blockerSuites = res.get().blockers().stream()
                        .map(v -> v.suite()).filter(x -> x != null && !x.isBlank())
                        .distinct().toList();
                    // A watch item is exactly what a re-run settles: too few runs of this revision to
                    // tell a real break from a flake. Re-running is what turns it into a verdict —
                    // without it a PR whose only finding is a watch item waits for a human forever.
                    List<String> watchSuites = res.get().watch().stream()
                        .map(v -> v.suite()).filter(x -> x != null && !x.isBlank())
                        .distinct().filter(s -> !blockerSuites.contains(s)).toList();
                    // Broken suites (timeout/crash/compilation) deserve the same retry a human would
                    // give them — and a passing re-run now clears them from the verdict too.
                    List<String> brokenSuites = res.get().brokenSuites().stream().map(s -> s.suite())
                        .filter(x -> x != null && !x.isBlank()).distinct()
                        .filter(s -> !blockerSuites.contains(s) && !watchSuites.contains(s)).toList();
                    List<String> suites = java.util.stream.Stream.of(blockerSuites, watchSuites, brokenSuites)
                        .flatMap(List::stream).toList();
                    String what = suitesLabel(blockerSuites.size(), watchSuites.size(), brokenSuites.size());
                    if (!suites.isEmpty() && suites.size() > MAX_SUITES_PER_RERUN && attempts == 0) {
                        // Systemic breakage: re-running dozens of suites would only hammer the shared CI.
                        retries.put(pr.number(), new Retry(buildId, MAX_RERUNS, what, List.of(),
                            "(i) Auto re-run skipped: " + suites.size() + " suites is too many — "
                                + "this looks systemic; fix the cause and re-trigger RunAll."));
                    }
                    else if (!suites.isEmpty() && attempts < MAX_RERUNS) {
                        // <= TOP_QUEUE_LIMIT suites jump the queue; more go in normally (tail) so the
                        // re-run doesn't shove everyone else's builds back.
                        boolean top = suites.size() <= TOP_QUEUE_LIMIT;
                        List<String> history = new ArrayList<>(
                            r != null && r.buildId() == buildId && r.history() != null ? r.history() : List.of());
                        history.add(what);
                        String tcComment = "Auto re-run " + history.size() + " (attempt " + (attempts + 1) + "/"
                            + MAX_RERUNS + ") by Ignite PR Checker, settling RunAll " + buildId;
                        List<Long> queued = new ArrayList<>();
                        for (String suite : suites) {
                            TcModel.Build b =
                                tc.triggerBuildReplacingQueued(tcToken.get(), suite, pr.number(), top, tcComment);
                            rerunTracker.record(pr.number(), b);
                            queued.add(b.id());
                        }
                        String note = top ? (r != null ? r.note() : null)
                            : "(i) " + suites.size() + " suites were re-queued at the TAIL of the queue "
                                + "(too many to jump it without disturbing others) — this may need a real fix "
                                + "and a fresh RunAll rather than re-runs.";
                        retries.put(pr.number(), new Retry(buildId, attempts + 1, what, history, note));
                        log.info("auto-rerun {}/{} for PR {}: {} re-queued at {}",
                            attempts + 1, MAX_RERUNS, pr.number(), what, top ? "top" : "tail");

                        // The PR comment appears as soon as the run finished and then keeps updating
                        // in place while the re-runs settle; the JIRA visa gets the same treatment,
                        // but is only touched on stage changes (watchers get mail on every edit).
                        Long eta = queuedEtaEpoch(tcToken.get(), queued);
                        if (e.ghComment())
                            upsertGhComment(e, ghToken.get(), pr.number(), buildId,
                                visas.composeMarkdown(pr.number(), res.get())
                                    + pendingLine(what, attempts + 1, history, eta, e.tz(), "**"));
                        if (e.autoVisa()) {
                            try {
                                upsertVisa(e, jiraToken.get(), m.group(), pr.number(), buildId,
                                    visas.compose(pr.number(), res.get())
                                        + pendingLine(what, attempts + 1, history, eta, e.tz(), "*"));
                            }
                            catch (RuntimeException vex) {
                                log.warn("interim visa for PR {} failed: {}", pr.number(), vex.toString());
                            }
                        }

                        continue; // the final visa waits until the re-runs settle
                    }
                }

                Retry done = retries.get(pr.number());
                String note = done != null && done.buildId() == buildId ? done.note() : null;
                String settled = done != null && done.buildId() == buildId ? settledLine(done.history()) : null;

                Integer ahead = pending.countSince(tcToken.get(), pr.number(), buildId);

                if (e.autoVisa()) {
                    String body = visas.compose(pr.number(), res.get(), ahead);
                    if (settled != null)
                        body = body + "\n\n" + settled;
                    if (note != null)
                        body = body + "\n\n" + note;
                    String url = upsertVisa(e, jiraToken.get(), m.group(), pr.number(), buildId, body);
                    posted++;
                    postedTotal.incrementAndGet();
                    log.info("standing auto-visa posted for PR {} (build {}, by {}) -> {}", pr.number(), buildId, who,
                        url != null ? url : "updated in place");
                }
                if (e.ghComment()) {
                    String md = visas.composeMarkdown(pr.number(), res.get(), ahead);
                    if (settled != null)
                        md = md + "\n\n_" + settled + "_";
                    if (note != null)
                        md = md + "\n\n_" + note + "_";
                    upsertGhComment(e, ghToken.get(), pr.number(), buildId, md);
                }
                e.posted().put(pr.number(), buildId);
                retries.remove(pr.number());
                earlyReruns.remove(buildId); // this chain is settled; its mid-run memo is spent
            }
            catch (RuntimeException ex) {
                log.warn("standing auto-visa sweep: PR {} skipped: {}", pr.number(), ex.toString());
            }
        }

        lastSweepMs = System.currentTimeMillis() - t0;
        if (posted > 0)
            log.info("standing auto-visa sweep: {} visa(s) posted", posted);
    }

    /**
     * The whole run's story lives in ONE PR comment: created when the run first finishes, edited in
     * place as re-runs settle. A failure never breaks the sweep (the JIRA visa may already be out),
     * and a failed edit falls back to a fresh comment rather than losing the verdict.
     */
    private void upsertGhComment(Enrollment e, String ghToken, int pr, long buildId, String md) {
        try {
            GhThread t = e.ghThreads().get(pr);
            if (t != null && t.buildId() == buildId) {
                try {
                    github.updatePrComment(ghToken, t.commentId(), md);
                    log.info("standing GitHub comment updated for PR {} (build {})", pr, buildId);

                    return;
                }
                catch (RuntimeException editEx) {
                    log.warn("editing GitHub comment {} for PR {} failed ({}), posting fresh",
                        t.commentId(), pr, editEx.toString());
                }
            }
            GithubClient.PostedComment posted = github.addPrComment(ghToken, pr, md);
            e.ghThreads().put(pr, new GhThread(buildId, posted.id()));
            log.info("standing GitHub comment posted for PR {} (build {}) -> {}", pr, buildId, posted.htmlUrl());
        }
        catch (RuntimeException ghEx) {
            log.warn("standing GitHub comment for PR {} failed: {}", pr, ghEx.toString());
        }
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
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        enrolled.forEach((u, e) -> snap.add(new Persisted(u, e.tcToken(), e.jiraToken(), e.ghToken(), e.ghLogin(),
            e.tz(), e.enabledAt(), new HashMap<>(e.posted()), new HashMap<>(e.ghThreads()),
            new HashMap<>(e.jiraThreads()),
            e.autoVisa(), e.autoRerun(), e.ghComment(), e.styleFix())));
        Map<Long, List<String>> early = new HashMap<>();
        earlyReruns.forEach((build, suites) -> early.put(build, List.copyOf(suites)));
        Snapshots.writeAtomic(mapper, file, new Snapshot(snap, new HashMap<>(retries), early));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted[] enrollments;
        try {
            Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
            enrollments = s.enrollments() == null ? new Persisted[0] : s.enrollments().toArray(new Persisted[0]);
            if (s.retries() != null)
                retries.putAll(s.retries());
            if (s.earlyReruns() != null)
                s.earlyReruns().forEach((build, suites) -> earlyReruns
                    .computeIfAbsent(build, id -> ConcurrentHashMap.newKeySet()).addAll(suites));
        }
        catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
            // The pre-retries snapshot was a bare enrollment array — read it once, save in the new shape.
            enrollments = mapper.readValue(file.toFile(), Persisted[].class);
        }

        for (Persisted p : enrollments) {
            ConcurrentMap<Integer, Long> posted = new ConcurrentHashMap<>();
            if (p.posted() != null)
                posted.putAll(p.posted());
            ConcurrentMap<Integer, GhThread> ghThreads = new ConcurrentHashMap<>();
            if (p.ghThreads() != null)
                ghThreads.putAll(p.ghThreads());
            ConcurrentMap<Integer, JiraThread> jiraThreads = new ConcurrentHashMap<>();
            if (p.jiraThreads() != null)
                jiraThreads.putAll(p.jiraThreads());
            enrolled.put(p.username(), new Enrollment(p.tcToken(), p.jiraToken(), p.ghToken(), p.ghLogin(),
                p.tz(), p.enabledAt(), posted, ghThreads, jiraThreads,
                p.autoVisa() == null || p.autoVisa(), p.autoRerun(), p.ghComment() != null && p.ghComment(),
                p.styleFix() != null && p.styleFix()));
        }
    }

    private record Enrollment(String tcToken, String jiraToken, String ghToken, String ghLogin, String tz,
        long enabledAt, ConcurrentMap<Integer, Long> posted, ConcurrentMap<Integer, GhThread> ghThreads,
        ConcurrentMap<Integer, JiraThread> jiraThreads,
        boolean autoVisa, boolean autoRerun, boolean ghComment, boolean styleFix) {
    }

    /** The one living visa comment of a run in the JIRA ticket: which build it narrates and where to edit it. */
    private record JiraThread(long buildId, String commentId) {
    }

    /** The one living PR comment of a run: which build it narrates and where to edit it. */
    private record GhThread(long buildId, long commentId) {
    }

    /** An enrolled user resolved from a GitHub login, tokens decrypted and ready to act with. */
    public record GhActor(String username, String tcToken, String ghToken, String tz) {
    }

    /** One PR's auto-rerun bookkeeping: the build being settled, attempts spent, what was re-queued,
     * and a note for the visa. */
    private record Retry(long buildId, int attempts, String what, List<String> history, String note) {
    }

    /** The snapshot on disk: enrollments plus the auto-rerun attempt bookkeeping (so a restart can't
     * grant extra attempts or freeze the ⏳ line's context). */
    private record Snapshot(List<Persisted> enrollments, Map<Integer, Retry> retries,
        Map<Long, List<String>> earlyReruns) {
    }

    /** The ⏳ status line of the living comment while re-runs settle, numbered, with the waves so far.
     * {@code b} is the bold marker of the target markup: {@code **} for GitHub, {@code *} for JIRA. */
    private static String pendingLine(String what, int attempt, List<String> history, Long etaEpochSec, String tz,
        String b) {
        return "\n\n⏳ _Auto re-run " + b + "#" + (history == null || history.isEmpty() ? attempt : history.size())
            + b + " in progress — " + what + " re-queued (attempt " + attempt + "/" + MAX_RERUNS + ")"
            + (etaEpochSec == null ? "" : ", " + b + "≈ settled by " + wallClock(etaEpochSec, tz) + b)
            + ". This comment updates when they settle._"
            + earlierWaves(history);
    }

    /**
     * The living visa in the ticket: created on the run's finish, edited in place as the stage
     * changes. A failed edit falls back to a fresh comment; the fresh-post URL is returned (null
     * when an edit sufficed).
     */
    private String upsertVisa(Enrollment e, String jiraToken, String issueKey, int pr, long buildId, String body) {
        JiraThread t = e.jiraThreads().get(pr);
        if (t != null && t.buildId() == buildId && t.commentId() != null) {
            try {
                jira.updateComment(jiraToken, issueKey, t.commentId(), body);
                log.info("standing visa updated in place for PR {} (build {})", pr, buildId);

                return null;
            }
            catch (RuntimeException editEx) {
                log.warn("editing visa comment {} for PR {} failed ({}), posting fresh",
                    t.commentId(), pr, editEx.toString());
            }
        }
        JiraClient.PostedComment posted = jira.addCommentWithId(jiraToken, issueKey, body);
        e.jiraThreads().put(pr, new JiraThread(buildId, posted.id()));

        return posted.url();
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

    /** e.g. {@code "2 blocker suite(s)"}, {@code "2 blocker + 1 watch + 3 broken suite(s)"}. */
    private static String suitesLabel(int blockers, int watch, int broken) {
        List<String> parts = new ArrayList<>();
        if (blockers > 0)
            parts.add(blockers + " blocker");
        if (watch > 0)
            parts.add(watch + " watch");
        if (broken > 0)
            parts.add(broken + " broken");

        return parts.isEmpty() ? "0 suite(s)" : String.join(" + ", parts) + " suite(s)";
    }

    /** Max estimated finish across the just-queued builds (epoch seconds), or null when TC has none yet. */
    private Long queuedEtaEpoch(String tcToken, List<Long> buildIds) {
        // TeamCity computes a fresh queued build's estimates asynchronously — right after the trigger
        // they are often still empty. One short retry catches most of them, so the very first version
        // of the ⏳ line already tells when the re-runs should settle.
        for (int attempt = 0; ; attempt++) {
            long max = -1;
            for (Long id : buildIds) {
                try {
                    TcModel.Build b = tc.getBuildState(tcToken, id);
                    max = Math.max(max, b == null ? -1 : TcDates.epochSeconds(b.finishEstimate()));
                }
                catch (RuntimeException ignored) {
                    // no estimate for this one — the others still bound the ETA
                }
            }
            if (max > 0)
                return max;
            if (attempt >= 1)
                return null;

            try {
                Thread.sleep(7_000);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();

                return null;
            }
        }
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

    private record Persisted(String username, String tcToken, String jiraToken, String ghToken, String ghLogin,
        String tz, long enabledAt, Map<Integer, Long> posted, Map<Integer, GhThread> ghThreads,
        Map<Integer, JiraThread> jiraThreads,
        Boolean autoVisa, boolean autoRerun, Boolean ghComment, Boolean styleFix) {
    }
}
