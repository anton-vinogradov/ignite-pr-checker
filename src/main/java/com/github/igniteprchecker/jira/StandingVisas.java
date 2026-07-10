package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
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
    private final ConcurrentMap<String, Enrollment> enrolled = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger postedTotal = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastSweepAt;
    private volatile long lastSweepMs;

    public StandingVisas(ObjectMapper mapper, SessionCodec codec, TcClient tc, GithubClient github,
        BlockerAnalyzer analyzer, JiraClient jira, VisaService visas) {
        this.mapper = mapper;
        this.codec = codec;
        this.tc = tc;
        this.github = github;
        this.analyzer = analyzer;
        this.jira = jira;
        this.visas = visas;
    }

    /** Enrols the user: both tokens go to encrypted-at-rest storage until {@link #disable}. */
    public void enable(String username, String tcToken, String jiraToken) {
        enrolled.put(username, new Enrollment(
            codec.encryptString(tcToken), codec.encryptString(jiraToken),
            System.currentTimeMillis(), new ConcurrentHashMap<>()));
        log.info("standing auto-visa enabled for {}", username);
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
                    continue; // this run is already visa'd

                Optional<String> tcToken = codec.decryptString(e.tcToken());
                Optional<String> jiraToken = codec.decryptString(e.jiraToken());
                if (tcToken.isEmpty() || jiraToken.isEmpty()) {
                    enrolled.remove(who);
                    log.warn("standing auto-visa for {} dropped: tokens undecryptable (secret rotated?)", who);
                    continue;
                }

                Optional<AnalysisResult> res = analyzer.analyze(tcToken.get(), pr.number());
                if (res.isEmpty() || res.get().buildId() != buildId)
                    continue; // raced with a newer run; the next sweep settles it

                String url = jira.addComment(jiraToken.get(), m.group(), visas.compose(pr.number(), res.get()));
                e.posted().put(pr.number(), buildId);
                posted++;
                postedTotal.incrementAndGet();
                log.info("standing auto-visa posted for PR {} (build {}, by {}) -> {}", pr.number(), buildId, who, url);
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
        enrolled.forEach((u, e) -> snap.add(new Persisted(u, e.tcToken(), e.jiraToken(), e.enabledAt(),
            new HashMap<>(e.posted()))));
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
            enrolled.put(p.username(), new Enrollment(p.tcToken(), p.jiraToken(), p.enabledAt(), posted));
        }
    }

    private record Enrollment(String tcToken, String jiraToken, long enabledAt, ConcurrentMap<Integer, Long> posted) {
    }

    private record Persisted(String username, String tcToken, String jiraToken, long enabledAt,
        Map<Integer, Long> posted) {
    }
}
