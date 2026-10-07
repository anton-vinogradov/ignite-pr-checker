package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot "auto visa" subscriptions: when a PR's RunAll chain finishes, post the verdict to the
 * ticket automatically, so nobody has to keep the tool open. The user's JIRA PAT is stored encrypted
 * (same key as the session cookie) only until the visa is posted, then the subscription is removed.
 */
@Component
public class VisaSubscriptions implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(VisaSubscriptions.class);

    private final ObjectMapper mapper;
    private final SessionCodec codec;
    private final JiraClient jira;
    private final VisaService visas;
    private final BlockerAnalyzer analyzer;
    private final Warmer warmer;
    private final PendingCommits pending;
    private final ConcurrentMap<Integer, Sub> subs = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger posted = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastPostedAt;

    /** How long a visa that could not be posted when its chain finished waits before the next try: one sweep period. */
    long retryDelayMs = 600_000;

    /**
     * How long after the first try that did not post the visa is tried again: past that, a cause that has
     * not gone away is not worth a try every 10 minutes, and the next finished RunAll tries again.
     */
    private static final long RETRY_FOR_MS = 3_600_000;

    /** Posting waits for the (potentially heavy) analysis; one background thread is plenty. */
    private final ExecutorService poster = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-visa");
        t.setDaemon(true);
        return t;
    });

    public VisaSubscriptions(ObjectMapper mapper, SessionCodec codec, JiraClient jira, VisaService visas,
        BlockerAnalyzer analyzer, Warmer warmer, PendingCommits pending) {
        this.mapper = mapper;
        this.codec = codec;
        this.jira = jira;
        this.visas = visas;
        this.analyzer = analyzer;
        this.warmer = warmer;
        this.pending = pending;
    }

    /** Arms the one-shot subscription: the next finished RunAll of this PR posts the visa to {@code issue}. */
    public void arm(int pr, String issue, String jiraToken, String username) {
        subs.put(pr, new Sub(issue, codec.encryptString(jiraToken), username, System.currentTimeMillis(), 0, 0));
        log.info("auto-visa armed for PR {} -> {} (by {})", pr, issue, username);
    }

    public void cancel(int pr) {
        if (subs.remove(pr) != null)
            log.info("auto-visa cancelled for PR {}", pr);
    }

    /** The armed issue key for a PR, if any. */
    public Optional<String> armedIssue(int pr) {
        Sub s = subs.get(pr);

        return s == null ? Optional.empty() : Optional.of(s.issue());
    }

    /** Called by the rerun tracker the moment a PR's chain finishes. No-op without a subscription. */
    public void onRunFinished(int pr) {
        Sub sub = subs.get(pr);
        if (sub == null)
            return;

        poster.execute(() -> post(pr, sub));
    }

    private void post(int pr, Sub sub) {
        if (!sub.equals(subs.get(pr)))
            return; // posted, cancelled, armed anew or tried again since this try was queued

        Optional<String> token = codec.decryptString(sub.token());
        if (token.isEmpty()) {
            subs.remove(pr);
            log.warn("auto-visa for PR {} dropped: token undecryptable (secret rotated?)", pr);
            return;
        }

        String tcToken = warmer.borrowToken();
        if (tcToken == null) {
            log.info("auto-visa for PR {} postponed: no pooled TeamCity token to compute the verdict", pr);
            retryLater(pr, sub);
            return;
        }

        try {
            Optional<AnalysisResult> res = analyzer.analyzeForAction(tcToken, pr);
            if (res.isEmpty()) {
                log.info("auto-visa for PR {} postponed: no analysable run", pr);
                retryLater(pr, sub);
                return;
            }
            if (analyzer.stillRetrying(res.get())) {
                log.info("auto-visa for PR {} postponed: TeamCity errors left part of the verdict unchecked", pr);
                retryLater(pr, sub);
                return;
            }

            String url = jira.addComment(token.get(), sub.issue(), visas.compose(pr, res.get(), pending.countSince(tcToken, pr, res.get().buildId())));
            subs.remove(pr); // one-shot: the token leaves the disk with it
            posted.incrementAndGet();
            lastPostedAt = System.currentTimeMillis();
            log.info("auto-visa posted for PR {} -> {} ({})", pr, sub.issue(), url);
        }
        catch (RuntimeException e) {
            log.warn("auto-visa for PR {} failed (kept armed): {}", pr, e.toString());
            retryLater(pr, sub);
        }
    }

    /**
     * Tries again after a while, for an hour from the first try that did not post. One later try used to be
     * all a held verdict got: a 502 on it, or a restart before it, left the visa to the next finished RunAll,
     * which may never come. The next try is saved with the subscription, so a restart does not lose it.
     */
    private void retryLater(int pr, Sub sub) {
        long now = System.currentTimeMillis();
        long since = sub.retryingSince() > 0 ? sub.retryingSince() : now;
        if (now - since >= RETRY_FOR_MS) {
            if (subs.replace(pr, sub, sub.retrying(0, 0)))
                log.warn("auto-visa for PR {} not posted for an hour; the next finished RunAll tries again", pr);
            return;
        }

        Sub next = sub.retrying(now + retryDelayMs, since);
        if (subs.replace(pr, sub, next))
            schedule(pr, next);
    }

    private void schedule(int pr, Sub sub) {
        long wait = Math.max(0, sub.retryAt() - System.currentTimeMillis());
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS, poster).execute(() -> post(pr, sub));
    }

    public int armedCount() {
        return subs.size();
    }

    public int postedCount() {
        return posted.get();
    }

    public long lastPostedAt() {
        return lastPostedAt;
    }

    @Override
    public String fileName() {
        return "visa-subs.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        subs.forEach((pr, s) -> snap.add(new Persisted(pr, s.issue(), s.token(), s.username(), s.armedAt(),
            s.retryAt(), s.retryingSince())));
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class)) {
            Sub sub = new Sub(p.issue(), p.token(), p.username(), p.armedAt(), p.retryAt(), p.retryingSince());
            subs.put(p.pr(), sub);
            if (sub.retryAt() > 0)
                schedule(p.pr(), sub);
        }
    }

    /**
     * An armed subscription. {@code retryAt} is when the next try of a visa that could not be posted once its
     * chain finished is due, and {@code retryingSince} when the first such try was; both are 0 while it waits
     * for a finished run.
     */
    private record Sub(String issue, String token, String username, long armedAt, long retryAt, long retryingSince) {
        Sub retrying(long at, long since) {
            return new Sub(issue, token, username, armedAt, at, since);
        }
    }

    /** A subscription on disk; one saved before retries were kept has no retry due. */
    private record Persisted(int pr, String issue, String token, String username, long armedAt, long retryAt,
        long retryingSince) {
    }
}
