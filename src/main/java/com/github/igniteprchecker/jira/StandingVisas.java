package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ConcurrentMap<String, Enrollment> enrolled = new ConcurrentHashMap<>();
    /** Auto-rerun attempts per PR for the build being settled; in-memory (a restart forfeits a retry). */
    private final ConcurrentMap<Integer, Retry> retries = new ConcurrentHashMap<>();

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
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas, RerunTracker rerunTracker) {
        this.mapper = mapper;
        this.codec = codec;
        this.tc = tc;
        this.github = github;
        this.analyzer = analyzer;
        this.jira = jira;
        this.visas = visas;
        this.rerunTracker = rerunTracker;
    }

    /**
     * Enrols the user with two independent switches: auto-visa (needs the JIRA token) and
     * auto-rerun (TC token only). Tokens stay encrypted at rest until {@link #disable}.
     */
    public void enable(String username, String tcToken, String jiraToken, String ghToken,
        boolean autoVisa, boolean autoRerun, boolean ghComment) {
        String ghLogin = ghToken == null ? null : github.ghUser(ghToken).orElse(null);
        enrolled.put(username, new Enrollment(
            codec.encryptString(tcToken), jiraToken == null ? null : codec.encryptString(jiraToken),
            ghToken == null ? null : codec.encryptString(ghToken), ghLogin,
            System.currentTimeMillis(), new ConcurrentHashMap<>(), autoVisa, autoRerun, ghComment));
        log.info("standing options for {}: autoVisa={}, autoRerun={}, ghComment={} (gh login {})",
            username, autoVisa, autoRerun, ghComment, ghLogin);
    }

    /**
     * The user behind a GitHub login, with decrypted tokens — the PR command poll resolves the
     * comment's author through this. Only users with the GitHub option on are addressable.
     */
    public Optional<GhActor> actorByGhLogin(String login) {
        for (Map.Entry<String, Enrollment> en : enrolled.entrySet()) {
            Enrollment e = en.getValue();
            if (e.ghComment() && login.equals(e.ghLogin())) {
                Optional<String> tcToken = codec.decryptString(e.tcToken());
                Optional<String> ghToken = e.ghToken() == null ? Optional.empty() : codec.decryptString(e.ghToken());
                if (tcToken.isPresent() && ghToken.isPresent())
                    return Optional.of(new GhActor(en.getKey(), tcToken.get(), ghToken.get()));
            }
        }

        return Optional.empty();
    }

    /** Whether anyone with the GitHub option is enrolled — gates the PR command poll entirely. */
    public boolean anyGhEnrolled() {
        return enrolled.values().stream().anyMatch(Enrollment::ghComment);
    }

    /**
     * Backfills {@code ghLogin} for enrollments made before logins were recorded (one GitHub
     * call per such user, once); no-op when every GitHub-enabled enrollment already has one.
     */
    public void ensureGhLogins() {
        enrolled.replaceAll((u, e) -> {
            if (!e.ghComment() || e.ghLogin() != null || e.ghToken() == null)
                return e;

            String login = codec.decryptString(e.ghToken()).flatMap(github::ghUser).orElse(null);
            if (login == null)
                return e;

            log.info("gh login for {} resolved: {}", u, login);

            return new Enrollment(e.tcToken(), e.jiraToken(), e.ghToken(), login, e.enabledAt(),
                e.posted(), e.autoVisa(), e.autoRerun(), e.ghComment());
        });
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
     * Sweep: for every open PR whose latest finished RunAll was triggered by an enrolled user and
     * hasn't been visa'd yet, compute the verdict and post it to the PR's IGNITE ticket.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    void sweep() {
        long t0 = System.currentTimeMillis();
        lastSweepAt = t0;
        lastSweepMs = 0;
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
                    if (rerunTracker.hasActive(pr.number()))
                        continue; // re-runs (or the next chain) still running — settle later

                    Retry r = retries.get(pr.number());
                    int attempts = r != null && r.buildId() == buildId ? r.attempts() : 0;
                    List<String> suites = res.get().blockers().stream()
                        .map(v -> v.suite()).filter(x -> x != null && !x.isBlank())
                        .distinct().toList();
                    if (!suites.isEmpty() && suites.size() > MAX_SUITES_PER_RERUN && attempts == 0) {
                        // Systemic breakage: re-running dozens of suites would only hammer the shared CI.
                        retries.put(pr.number(), new Retry(buildId, MAX_RERUNS,
                            "(i) Auto re-run skipped: " + suites.size() + " blocker suites is too many — "
                                + "this looks systemic; fix the cause and re-trigger RunAll."));
                    }
                    else if (!suites.isEmpty() && attempts < MAX_RERUNS) {
                        // <= TOP_QUEUE_LIMIT suites jump the queue; more go in normally (tail) so the
                        // re-run doesn't shove everyone else's builds back.
                        boolean top = suites.size() <= TOP_QUEUE_LIMIT;
                        for (String suite : suites)
                            rerunTracker.record(pr.number(),
                                tc.triggerBuildReplacingQueued(tcToken.get(), suite, pr.number(), top));
                        String note = top ? (r != null ? r.note() : null)
                            : "(i) " + suites.size() + " blocker suites were re-queued at the TAIL of the queue "
                                + "(too many to jump it without disturbing others) — this may need a real fix "
                                + "and a fresh RunAll rather than re-runs.";
                        retries.put(pr.number(), new Retry(buildId, attempts + 1, note));
                        log.info("auto-rerun {}/{} for PR {}: {} blocker suite(s) re-queued at {}",
                            attempts + 1, MAX_RERUNS, pr.number(), suites.size(), top ? "top" : "tail");
                        continue; // the visa waits until the re-runs settle
                    }
                }

                Retry done = retries.get(pr.number());
                String note = done != null && done.buildId() == buildId ? done.note() : null;

                if (e.autoVisa()) {
                    String body = visas.compose(pr.number(), res.get());
                    if (note != null)
                        body = body + "\n\n" + note;
                    String url = jira.addComment(jiraToken.get(), m.group(), body);
                    posted++;
                    postedTotal.incrementAndGet();
                    log.info("standing auto-visa posted for PR {} (build {}, by {}) -> {}", pr.number(), buildId, who, url);
                }
                if (e.ghComment()) {
                    try {
                        String md = visas.composeMarkdown(pr.number(), res.get());
                        if (note != null)
                            md = md + "\n\n_" + note + "_";
                        String url = github.addPrComment(ghToken.get(), pr.number(), md);
                        log.info("standing GitHub comment posted for PR {} (by {}) -> {}", pr.number(), who, url);
                    }
                    catch (RuntimeException ghEx) {
                        // The JIRA visa (if any) already landed; don't re-post it next sweep over a GitHub blip.
                        log.warn("standing GitHub comment for PR {} failed: {}", pr.number(), ghEx.toString());
                    }
                }
                e.posted().put(pr.number(), buildId);
                retries.remove(pr.number());
            }
            catch (RuntimeException ex) {
                log.warn("standing auto-visa sweep: PR {} skipped: {}", pr.number(), ex.toString());
            }
        }

        lastSweepMs = System.currentTimeMillis() - t0;
        if (posted > 0)
            log.info("standing auto-visa sweep: {} visa(s) posted", posted);
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
            e.enabledAt(), new HashMap<>(e.posted()), e.autoVisa(), e.autoRerun(), e.ghComment())));
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class)) {
            ConcurrentMap<Integer, Long> posted = new ConcurrentHashMap<>();
            if (p.posted() != null)
                posted.putAll(p.posted());
            enrolled.put(p.username(), new Enrollment(p.tcToken(), p.jiraToken(), p.ghToken(), p.ghLogin(),
                p.enabledAt(), posted,
                p.autoVisa() == null || p.autoVisa(), p.autoRerun(), p.ghComment() != null && p.ghComment()));
        }
    }

    private record Enrollment(String tcToken, String jiraToken, String ghToken, String ghLogin, long enabledAt,
        ConcurrentMap<Integer, Long> posted, boolean autoVisa, boolean autoRerun, boolean ghComment) {
    }

    /** An enrolled user resolved from a GitHub login, tokens decrypted and ready to act with. */
    public record GhActor(String username, String tcToken, String ghToken) {
    }

    /** One PR's auto-rerun bookkeeping: the build being settled, attempts spent, and a note for the visa. */
    private record Retry(long buildId, int attempts, String note) {
    }

    private record Persisted(String username, String tcToken, String jiraToken, String ghToken, String ghLogin,
        long enabledAt, Map<Integer, Long> posted, Boolean autoVisa, boolean autoRerun, Boolean ghComment) {
    }
}
