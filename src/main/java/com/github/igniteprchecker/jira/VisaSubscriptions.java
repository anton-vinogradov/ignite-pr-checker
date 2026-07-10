package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ConcurrentMap<Integer, Sub> subs = new ConcurrentHashMap<>();

    /** Posting waits for the (potentially heavy) analysis; one background thread is plenty. */
    private final ExecutorService poster = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-visa");
        t.setDaemon(true);
        return t;
    });

    public VisaSubscriptions(ObjectMapper mapper, SessionCodec codec, JiraClient jira, VisaService visas,
        BlockerAnalyzer analyzer, Warmer warmer) {
        this.mapper = mapper;
        this.codec = codec;
        this.jira = jira;
        this.visas = visas;
        this.analyzer = analyzer;
        this.warmer = warmer;
    }

    /** Arms the one-shot subscription: the next finished RunAll of this PR posts the visa to {@code issue}. */
    public void arm(int pr, String issue, String jiraToken, String username) {
        subs.put(pr, new Sub(issue, codec.encryptString(jiraToken), username, System.currentTimeMillis()));
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
        Optional<String> token = codec.decryptString(sub.token());
        if (token.isEmpty()) {
            subs.remove(pr);
            log.warn("auto-visa for PR {} dropped: token undecryptable (secret rotated?)", pr);
            return;
        }

        String tcToken = warmer.borrowToken();
        if (tcToken == null) {
            log.info("auto-visa for PR {} postponed: no pooled TeamCity token to compute the verdict", pr);
            return; // keep the subscription; the next finished run (or the user's next visit) retries
        }

        try {
            Optional<AnalysisResult> res = analyzer.analyze(tcToken, pr);
            if (res.isEmpty()) {
                log.info("auto-visa for PR {} postponed: no analysable run", pr);
                return;
            }

            String url = jira.addComment(token.get(), sub.issue(), visas.compose(pr, res.get()));
            subs.remove(pr); // one-shot: the token leaves the disk with it
            log.info("auto-visa posted for PR {} -> {} ({})", pr, sub.issue(), url);
        }
        catch (RuntimeException e) {
            log.warn("auto-visa for PR {} failed (kept armed): {}", pr, e.toString());
        }
    }

    @Override
    public String fileName() {
        return "visa-subs.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = new ArrayList<>();
        subs.forEach((pr, s) -> snap.add(new Persisted(pr, s.issue(), s.token(), s.username(), s.armedAt())));
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class))
            subs.put(p.pr(), new Sub(p.issue(), p.token(), p.username(), p.armedAt()));
    }

    private record Sub(String issue, String token, String username, long armedAt) {
    }

    private record Persisted(int pr, String issue, String token, String username, long armedAt) {
    }
}
