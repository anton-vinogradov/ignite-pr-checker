package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One-shot "auto visa" subscriptions: when a PR's RunAll chain finishes, post the verdict to the
 * ticket automatically, so nobody has to keep the tool open. Each user arms and cancels their own;
 * their JIRA PAT is stored encrypted (same key as the session cookie) only until the visa is posted,
 * then the subscription is removed. A chain whose starter's standing auto-visa posts to the same
 * ticket is left to it: the subscription waits until that visa is in, and posts itself if it never
 * comes. Several users armed on one ticket get one visa between them. A visa that could not be posted
 * is tried again, for an hour, across restarts.
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
    private final TcClient tc;
    private final StandingVisas standing;
    private final ConcurrentMap<Key, Sub> subs = new ConcurrentHashMap<>();
    /** The next try for a PR whose chain's visa could not be posted yet. */
    private final ConcurrentMap<Integer, Retry> retries = new ConcurrentHashMap<>();
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
    private final ExecutorService poster;

    @Autowired
    public VisaSubscriptions(ObjectMapper mapper, SessionCodec codec, JiraClient jira, VisaService visas,
        BlockerAnalyzer analyzer, Warmer warmer, PendingCommits pending, TcClient tc, StandingVisas standing,
        @Qualifier("visaPosterExecutor") ExecutorService poster) {
        this.mapper = mapper;
        this.codec = codec;
        this.jira = jira;
        this.visas = visas;
        this.analyzer = analyzer;
        this.warmer = warmer;
        this.pending = pending;
        this.tc = tc;
        this.standing = standing;
        this.poster = poster;
    }

    /** Subscriptions whose visas are posted on a daemon thread of their own. */
    public VisaSubscriptions(ObjectMapper mapper, SessionCodec codec, JiraClient jira, VisaService visas,
        BlockerAnalyzer analyzer, Warmer warmer, PendingCommits pending, TcClient tc, StandingVisas standing) {
        this(mapper, codec, jira, visas, analyzer, warmer, pending, tc, standing, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "auto-visa");
            t.setDaemon(true);

            return t;
        }));
    }

    /** Arms the user's one-shot subscription: the next finished RunAll of this PR posts the visa to {@code issue}. */
    public void arm(int pr, String issue, String jiraToken, String username) {
        subs.put(new Key(pr, username), new Sub(issue, codec.encryptString(jiraToken), username,
            System.currentTimeMillis(), null));
        log.info("auto-visa armed for PR {} -> {} (by {})", pr, issue, username);
    }

    /** Cancels the user's own subscription for the PR; anyone else's stays armed. */
    public void cancel(int pr, String username) {
        if (subs.remove(new Key(pr, username)) != null)
            log.info("auto-visa cancelled for PR {} by {}", pr, username);
    }

    /** The PR's subscriptions as the user sees them: their own issue, if armed, and who else armed one. */
    public Armed armed(int pr, String username) {
        Sub own = subs.get(new Key(pr, username));
        List<String> others = armedOn(pr).stream().map(Map.Entry::getValue).map(Sub::username)
            .filter(u -> u != null && !u.equals(username)).toList();

        return new Armed(own == null ? null : own.issue(), others);
    }

    /** What {@link #armed} answers: {@code issue} is null when the user has no subscription of their own. */
    public record Armed(String issue, List<String> others) {
    }

    /**
     * A PR's chain finished: its verdict goes out to everyone armed on the PR. A cancelled chain has no
     * verdict of its own, so the subscriptions wait for the next one.
     */
    @EventListener
    public void onChainFinished(RerunTracker.ChainFinished ev) {
        if (armedOn(ev.pr()).isEmpty())
            return;

        if (ev.cancelled()) {
            log.info("auto-visa for PR {} kept armed: RunAll {} was cancelled", ev.pr(), ev.chainBuildId());

            return;
        }

        long seenAt = System.currentTimeMillis();
        poster.execute(() -> settle(ev.pr(), ev.chainBuildId(), seenAt));
    }

    /**
     * Posts the verdict of the chain that finished to the tickets armed on the PR, or leaves them armed.
     * Tries left for an earlier chain end here: this chain's verdict is the one owed now.
     */
    void settle(int pr, long chainBuildId, long seenAt) {
        Retry earlier = retries.get(pr);
        if (earlier != null && earlier.chain().buildId() < chainBuildId)
            retries.remove(pr, earlier);

        attempt(new Chain(pr, chainBuildId, seenAt), true);
    }

    /**
     * Posts the chain's verdict to the subscriptions armed on the PR by the time it was seen finished: one
     * armed since waits for the next chain, and does not take the verdict of the chain before it.
     */
    private void attempt(Chain chain, boolean justFinished) {
        List<Map.Entry<Key, Sub>> due = armedOn(chain.pr()).stream()
            .filter(en -> en.getValue().armedAt() <= chain.seenAt()).toList();
        if (due.isEmpty())
            return;

        String tcToken = warmer.borrowToken();
        if (tcToken == null) {
            log.info("auto-visa for PR {} postponed: no pooled TeamCity token to compute the verdict", chain.pr());
            retryLater(chain);
            return;
        }

        try {
            String owner = tc.buildTriggeredBy(tcToken, chain.buildId()).orElse(null);
            deliver(tcToken, chain, owner, due, justFinished);
        }
        catch (RuntimeException e) {
            log.warn("auto-visa for PR {} failed (kept armed): {}", chain.pr(), e.toString());
            retryLater(chain);
        }
    }

    /**
     * Subscriptions left to a standing auto-visa wait for it on the poster's thread, so a finished
     * chain and this check never post the same subscription twice.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    void recheckLeftToStanding() {
        poster.execute(this::settleLeftToStanding);
    }

    /**
     * Drops the subscriptions whose standing auto-visa is in, and posts the verdict for those whose
     * standing visa no longer comes: its owner's JIRA token was refused, the option went off, or the
     * PR left the sweep's list.
     */
    void settleLeftToStanding() {
        Map<Handover, List<Map.Entry<Key, Sub>>> waiting = subs.entrySet().stream()
            .filter(en -> en.getValue().leftTo() != null)
            .collect(Collectors.groupingBy(en -> en.getValue().leftTo()));
        if (waiting.isEmpty())
            return;

        String tcToken = warmer.borrowToken();
        if (tcToken == null)
            return; // nothing can be computed or posted now; the subscriptions keep waiting

        waiting.forEach((h, due) -> {
            try {
                deliver(tcToken, h.chain(), h.owner(), due, false);
            }
            catch (RuntimeException e) {
                log.warn("auto-visa for PR {} failed (kept armed): {}", h.chain().pr(), e.toString());
                retryLater(h.chain());
            }
        });
    }

    /**
     * The chain's verdict for each subscription in {@code due}: left to the standing auto-visa of the
     * chain's starter while that one is still to post it to the same ticket, dropped once it is in, and
     * posted here otherwise, tried again while it cannot be. Once one of those armed on a ticket posts it,
     * the ticket has the visa, and the others on it are served, even one whose own post JIRA failed.
     */
    private void deliver(String tcToken, Chain chain, String owner, List<Map.Entry<Key, Sub>> due,
        boolean justFinished) {
        int pr = chain.pr();
        long chainBuildId = chain.buildId();
        List<Map.Entry<Key, Sub>> own = new ArrayList<>();
        for (Map.Entry<Key, Sub> en : due) {
            Key key = en.getKey();
            Sub sub = en.getValue();
            switch (standing.visaCover(owner, pr, chainBuildId, sub.issue())) {
                case POSTED -> {
                    if (subs.remove(key, sub))
                        log.info("auto-visa of {} for PR {} served: the standing auto-visa of {} posted RunAll {}",
                            sub.username(), pr, owner, chainBuildId);
                }
                case PENDING -> {
                    Sub left = sub.handedTo(new Handover(chain, owner));
                    if (!left.equals(sub) && subs.replace(key, sub, left))
                        log.info("auto-visa of {} for PR {} waits: the standing auto-visa of {} posts RunAll {}",
                            sub.username(), pr, owner, chainBuildId);
                }
                case NONE -> {
                    Sub mine = sub.takenBack();
                    if (mine.equals(sub) || subs.replace(key, sub, mine))
                        own.add(Map.entry(key, mine));
                }
            }
        }
        if (own.isEmpty())
            return;

        Optional<AnalysisResult> res = verdict(tcToken, chain, justFinished);
        if (res.isPresent() && res.get().buildId() > chainBuildId) {
            log.info("auto-visa for PR {} kept armed: RunAll {} finished after {}", pr, res.get().buildId(),
                chainBuildId);
            return;
        }
        if (res.isEmpty() || res.get().buildId() != chainBuildId) {
            log.info("auto-visa for PR {} postponed: no verdict of the finished RunAll {}", pr, chainBuildId);
            retryLater(chain);
            return;
        }
        if (analyzer.stillRetrying(res.get())) {
            log.info("auto-visa for PR {} postponed: TeamCity errors left part of the verdict unchecked", pr);
            retryLater(chain);
            return;
        }

        Set<String> postedTo = new HashSet<>();
        List<Map.Entry<Key, Sub>> failed = new ArrayList<>();
        for (Map.Entry<Key, Sub> en : own) {
            if (!post(pr, en.getKey(), en.getValue(), res.get(), tcToken, postedTo))
                failed.add(en);
        }

        boolean owed = false;
        for (Map.Entry<Key, Sub> en : failed) {
            if (postedTo.contains(en.getValue().issue()))
                served(pr, en.getKey(), en.getValue());
            else
                owed = true;
        }
        if (owed)
            retryLater(chain);
    }

    /**
     * The PR's verdict once the chain finished. Right after the finish, the PR's build lookup is cached for
     * half a minute and still named the chain before it, whose verdict then went out as this one's: that try
     * looks the build up afresh and recomputes. A later one takes the verdict as any action does, cached until
     * something changes or an incomplete one is due another compute, so a JIRA or TeamCity outage does not
     * recompute the PR on every try.
     */
    private Optional<AnalysisResult> verdict(String tcToken, Chain chain, boolean justFinished) {
        if (!justFinished)
            return analyzer.analyzeForAction(tcToken, chain.pr());

        Optional<AnalysisResult> res = analyzer.forceRefresh(tcToken, chain.pr());
        // A compute under way since before the finish is shared, and it saw the chain unfinished.
        if (res.isPresent() && res.get().buildId() == chain.buildId() && res.get().finishedAt() == 0)
            res = analyzer.analyzeAfterNow(tcToken, chain.pr());

        return res;
    }

    /** Posts the visa for one subscription; false when JIRA failed it and it is still to be posted. */
    private boolean post(int pr, Key key, Sub sub, AnalysisResult res, String tcToken, Set<String> postedTo) {
        if (postedTo.contains(sub.issue())) {
            served(pr, key, sub);
            return true;
        }

        Optional<String> token = codec.decryptString(sub.token());
        if (token.isEmpty()) {
            subs.remove(key, sub);
            log.warn("auto-visa of {} for PR {} dropped: token undecryptable (secret rotated?)", sub.username(), pr);
            return true;
        }

        try {
            String url = jira.addComment(token.get(), sub.issue(), visas.compose(pr, res,
                pending.countSince(tcToken, pr, res.buildId()), revision(tcToken, res.buildId())));
            subs.remove(key, sub); // one-shot: the token leaves the disk with it (a re-armed one stays)
            postedTo.add(sub.issue());
            posted.incrementAndGet();
            lastPostedAt = System.currentTimeMillis();
            log.info("auto-visa posted for PR {} -> {} by {} ({})", pr, sub.issue(), sub.username(), url);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("auto-visa of {} for PR {} failed (kept armed): {}", sub.username(), pr, e.toString());
            return false;
        }
    }

    /** The commit the build tested, for the visa to name; null when TeamCity does not say. */
    private String revision(String tcToken, long buildId) {
        try {
            return tc.buildRevision(tcToken, buildId).orElse(null);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** Ends a subscription whose ticket got the chain's visa from another one armed on it. */
    private void served(int pr, Key key, Sub sub) {
        if (subs.remove(key, sub))
            log.info("auto-visa of {} for PR {} served: the visa is already in {}", sub.username(), pr, sub.issue());
    }

    /**
     * Tries again after a while, for an hour from the first try that did not post. One later try used to be
     * all a held verdict got: a 502 on it, or a restart before it, left the visa to the next finished RunAll,
     * which may never come. The next try is saved with the PR's subscriptions, so a restart does not lose it.
     */
    private void retryLater(Chain chain) {
        int pr = chain.pr();
        long now = System.currentTimeMillis();
        Retry prev = retries.get(pr);
        if (prev != null && prev.chain().buildId() > chain.buildId())
            return; // the tries for the newer chain serve the same subscriptions

        boolean again = prev != null && prev.chain().buildId() == chain.buildId();
        long since = again ? prev.since() : now;
        if (now - since >= RETRY_FOR_MS) {
            retries.remove(pr, prev);
            log.warn("auto-visa for PR {} not posted for an hour; the next finished RunAll tries again", pr);
            return;
        }

        Retry next = new Retry(again ? prev.chain() : chain, now + retryDelayMs, since);
        retries.put(pr, next);
        schedule(next);
    }

    /**
     * The try stays recorded while it runs, so a failure in it keeps counting the hour from the first one; a
     * try superseded since, by another retry, a newer chain or the hour's end, does nothing.
     */
    private void schedule(Retry r) {
        int pr = r.chain().pr();
        long wait = Math.max(0, r.at() - System.currentTimeMillis());
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS, poster).execute(() -> {
            if (!r.equals(retries.get(pr)))
                return;

            attempt(r.chain(), false);
            retries.remove(pr, r);
        });
    }

    /** The PR's subscriptions, the earliest armed first: it is the one that posts when several share a ticket. */
    private List<Map.Entry<Key, Sub>> armedOn(int pr) {
        return subs.entrySet().stream().filter(en -> en.getKey().pr() == pr)
            .sorted(Comparator.comparingLong(en -> en.getValue().armedAt())).toList();
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
        subs.forEach((k, s) -> snap.add(Persisted.of(k, s, retries.get(k.pr()))));
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Map<Integer, Retry> tries = new HashMap<>();
        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class)) {
            subs.put(new Key(p.pr(), p.username()), p.sub());
            Retry r = p.retry();
            if (r != null)
                tries.putIfAbsent(p.pr(), r);
        }
        tries.forEach((pr, r) -> {
            retries.put(pr, r);
            schedule(r);
        });
    }

    /** Whose subscription for which PR. */
    private record Key(int pr, String username) {
    }

    /**
     * One armed subscription. {@code leftTo} is the standing auto-visa that is to post the chain's verdict to
     * the same ticket; null while nothing was handed over.
     */
    private record Sub(String issue, String token, String username, long armedAt, Handover leftTo) {
        Sub handedTo(Handover h) {
            return new Sub(issue, token, username, armedAt, h);
        }

        Sub takenBack() {
            return leftTo == null ? this : new Sub(issue, token, username, armedAt, null);
        }
    }

    /** A PR's chain that finished, and when it was seen finished: a subscription armed later waits for the next. */
    private record Chain(int pr, long buildId, long seenAt) {
    }

    /**
     * The next try of the chain's verdict, due {@code at}; {@code since} is when the first try that did not post
     * was.
     */
    private record Retry(Chain chain, long at, long since) {
    }

    /** The standing visa subscriptions wait for: of which chain, from whom. */
    private record Handover(Chain chain, String owner) {
    }

    /**
     * A subscription on disk. A handover and a try due are written only when there is one, so a file
     * without them reads, and is written, as before.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Persisted(int pr, String issue, String token, String username, long armedAt, Long chainBuildId,
        Long chainSeenAt, String leftTo, Long retryChain, Long retryChainSeenAt, Long retryAt, Long retryingSince) {
        static Persisted of(Key k, Sub s, Retry r) {
            Chain left = s.leftTo() == null ? null : s.leftTo().chain();
            Chain tried = r == null ? null : r.chain();

            return new Persisted(k.pr(), s.issue(), s.token(), s.username(), s.armedAt(),
                left == null ? null : left.buildId(), left == null ? null : left.seenAt(),
                s.leftTo() == null ? null : s.leftTo().owner(), tried == null ? null : tried.buildId(),
                tried == null ? null : tried.seenAt(), r == null ? null : r.at(), r == null ? null : r.since());
        }

        Sub sub() {
            boolean handed = leftTo != null && chainBuildId != null && chainSeenAt != null;

            return new Sub(issue, token, username, armedAt,
                handed ? new Handover(new Chain(pr, chainBuildId, chainSeenAt), leftTo) : null);
        }

        Retry retry() {
            if (retryChain == null || retryChainSeenAt == null || retryAt == null || retryingSince == null)
                return null;

            return new Retry(new Chain(pr, retryChain, retryChainSeenAt), retryAt, retryingSince);
        }
    }
}
