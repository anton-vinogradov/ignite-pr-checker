package com.github.igniteprchecker.tc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Tracks queued/running builds launched for PRs (suite re-runs and whole RunAll chains), so the UI
 * can tag a suite with a live "queued/running" chip linking to that very build. A tracked RunAll
 * chain is expanded into its per-suite dependency states, so every suite of the chain gets its own
 * chip while it is queued/running.
 *
 * <p>Entries come from the tool's own trigger endpoints and are also re-seeded from the current-runs
 * listing, and they survive restarts (persisted without any tokens). State refreshes on a timer with
 * one cheap call per active build, using a token borrowed from the warmer's pool; entries are dropped
 * as soon as their build finishes or disappears, so the map stays small and self-cleaning.
 */
@Component
public class RerunTracker implements SnapshotCache {
    /** Hide an entry whose live state we could not confirm for this long (no tokens to poll with). */
    private static final long STALE_MS = 45 * 60 * 1000L;

    private final TcClient tc;
    private final Warmer warmer;
    private final VisaSubscriptions visaSubs;
    private final String runAllBuildType;
    private final ObjectMapper mapper;
    private final Map<Long, Tracked> tracked = new ConcurrentHashMap<>();

    private volatile long lastRefreshAt;
    private volatile long lastRefreshMs;
    private final java.util.concurrent.atomic.AtomicInteger chainFinishes = new java.util.concurrent.atomic.AtomicInteger();

    public RerunTracker(TcClient tc, Warmer warmer, VisaSubscriptions visaSubs, AnalysisProperties cfg,
        ObjectMapper mapper) {
        this.tc = tc;
        this.warmer = warmer;
        this.visaSubs = visaSubs;
        this.runAllBuildType = cfg.runAllBuildType();
        this.mapper = mapper;
    }

    /** Remember a queued/running build; the UI starts showing its chip immediately. Idempotent by build id. */
    public void record(int pr, TcModel.Build b) {
        if (b == null || b.buildTypeId() == null || "finished".equalsIgnoreCase(b.state()))
            return;

        String suiteName = b.buildType() != null && b.buildType().name() != null ? b.buildType().name() : b.buildTypeId();
        Tracked t = tracked.computeIfAbsent(b.id(),
            id -> new Tracked(pr, b.buildTypeId(), suiteName, b.id(), b.webUrl() == null ? "" : b.webUrl()));
        t.state = b.state() == null ? "queued" : b.state();
    }

    /** Refreshes each active build's state (chains expand to per-suite states); drops finished/gone ones. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 10_000)
    void refresh() {
        long t0 = System.currentTimeMillis();
        lastRefreshAt = t0; // the watcher looked, even if there was nothing to track or no token
        if (tracked.isEmpty())
            return;

        String token = warmer.borrowToken();
        if (token == null)
            return; // nobody logged in recently: entries older than STALE_MS drop out of active()

        List<TcModel.Build> queued = null; // one queue scan per cycle, shared by every tracked chain

        for (Tracked t : tracked.values()) {
            try {
                if (runAllBuildType.equals(t.buildTypeId)) {
                    if (queued == null)
                        queued = tc.queuedBuilds(token);
                    refreshChain(token, t, queued);
                }
                else
                    refreshSingle(token, t);
            }
            catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404)
                    tracked.remove(t.buildId); // cancelled in queue / cleaned up
                // other statuses (401/5xx): transient or a bad pooled token — retry next cycle
            }
            catch (RuntimeException ignore) {
                // transient network error: keep the last known state and retry next cycle
            }
        }
        lastRefreshMs = System.currentTimeMillis() - t0;
    }

    /** Telemetry for the status page. */
    public Map<String, Object> stats() {
        int chains = 0;
        for (Tracked t : tracked.values())
            if (runAllBuildType.equals(t.buildTypeId))
                chains++;

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("tracked", tracked.size());
        out.put("chains", chains);
        out.put("suites", tracked.size() - chains);
        out.put("lastRefreshAt", lastRefreshAt);
        out.put("lastRefreshMs", lastRefreshMs);
        out.put("chainFinishes", chainFinishes.get());
        return out;
    }

    private void refreshSingle(String token, Tracked t) {
        TcModel.Build b = tc.getBuildState(token, t.buildId);
        if (b == null || "finished".equalsIgnoreCase(b.state())) {
            tracked.remove(t.buildId);
            // A finished re-run is a new branch run for its tests: recompute the verdict so a
            // passed re-run clears its blockers without waiting for a viewer or the TTL.
            if (b != null)
                warmer.refreshPr(t.pr);
            return;
        }

        t.state = b.state();
        t.pct = pct(b);
        t.leftSec = leftSeconds(b);
        t.startSec = startSeconds(b);
        t.lastVerified = System.currentTimeMillis();
    }

    private void refreshChain(String token, Tracked t, List<TcModel.Build> queued) {
        TcModel.Build b = tc.getChainDepStates(token, t.buildId);
        if (b == null || "finished".equalsIgnoreCase(b.state())) {
            tracked.remove(t.buildId);
            // The result is analysable this very second: pre-compute it before the first viewer
            // arrives, instead of waiting for the next warm cycle or making a user pay cold.
            if (b != null) {
                chainFinishes.incrementAndGet();
                warmer.warmPr(t.pr);
                visaSubs.onRunFinished(t.pr);
            }
            return;
        }

        t.state = b.state();

        // Started suites come from the chain's snapshot-dependencies; the not-yet-started rest sit in
        // the build queue (a running chain's deps aren't listed until they start), so merge both to
        // cover every suite of the RunAll.
        String branch = "pull/" + t.pr + "/head";
        Map<Long, ActiveRerun> kids = new LinkedHashMap<>();
        if (b.snapshotDependencies() != null && b.snapshotDependencies().build() != null)
            for (TcModel.Build dep : b.snapshotDependencies().build())
                addChild(kids, t.pr, dep);
        for (TcModel.Build dep : queued)
            if (branch.equals(dep.branchName()))
                addChild(kids, t.pr, dep);

        t.children = List.copyOf(kids.values());
        t.pct = pct(b);
        // The composite's own estimate lies in both directions (no queue awareness; duration
        // history skewed by interrupted runs). The slowest child knows the queue and the actual
        // per-suite progress — prefer it whenever known, fall back to the composite's own.
        Long own = leftSeconds(b);
        long slowestKid = kids.values().stream()
            .map(ActiveRerun::leftSec)
            .filter(l -> l != null)
            .mapToLong(Long::longValue)
            .max().orElse(-1);
        t.leftSec = slowestKid >= 0 ? slowestKid : own;
        t.startSec = startSeconds(b);
        t.lastVerified = System.currentTimeMillis();
    }

    private static void addChild(Map<Long, ActiveRerun> kids, int pr, TcModel.Build dep) {
        if (dep.buildTypeId() == null || "finished".equalsIgnoreCase(dep.state()))
            return;

        String name = dep.buildType() != null && dep.buildType().name() != null
            ? dep.buildType().name() : dep.buildTypeId();
        kids.putIfAbsent(dep.id(), new ActiveRerun(pr, dep.buildTypeId(), name, dep.id(),
            dep.state() == null ? "queued" : dep.state(), dep.webUrl() == null ? "" : dep.webUrl(),
            pct(dep), leftSeconds(dep), startSeconds(dep)));
    }

    /** The currently queued/running builds (chains flattened into their suites), running first, deduped.
     * Entries whose state could not be re-verified recently (no pooled token, e.g. overnight) are hidden
     * rather than shown with a possibly long-finished "running". */
    public List<ActiveRerun> active() {
        long now = System.currentTimeMillis();
        Map<Long, ActiveRerun> out = new LinkedHashMap<>();
        for (Tracked t : tracked.values()) {
            if (now - t.lastVerified > STALE_MS)
                continue;
            out.putIfAbsent(t.buildId, new ActiveRerun(t.pr, t.buildTypeId, t.suiteName, t.buildId, t.state, t.webUrl,
                t.pct, t.leftSec, t.startSec));
            for (ActiveRerun kid : t.children)
                out.putIfAbsent(kid.buildId(), kid);
        }

        List<ActiveRerun> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparing((ActiveRerun r) -> !"running".equalsIgnoreCase(r.state()))
            .thenComparingLong(ActiveRerun::buildId));

        return list;
    }

    // --- persistence: the tracked builds only (never tokens); children re-derive on the next refresh ---

    @Override
    public String fileName() {
        return "reruns.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Persisted> snap = tracked.values().stream()
            .map(t -> new Persisted(t.pr, t.buildTypeId, t.suiteName, t.buildId, t.webUrl, t.state))
            .toList();
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Persisted p : mapper.readValue(file.toFile(), Persisted[].class)) {
            Tracked t = new Tracked(p.pr(), p.buildTypeId(), p.suiteName(), p.buildId(), p.webUrl());
            t.state = p.state();
            tracked.put(p.buildId(), t);
        }
    }

    private static final class Tracked {
        final int pr;
        final String buildTypeId;
        final String suiteName;
        final long buildId;
        final String webUrl;
        volatile String state = "queued";
        volatile Integer pct;
        volatile Long leftSec;
        volatile Long startSec;
        volatile long lastVerified = System.currentTimeMillis();
        volatile List<ActiveRerun> children = List.of();

        Tracked(int pr, String buildTypeId, String suiteName, long buildId, String webUrl) {
            this.pr = pr;
            this.buildTypeId = buildTypeId;
            this.suiteName = suiteName;
            this.buildId = buildId;
            this.webUrl = webUrl;
        }
    }

    private record Persisted(int pr, String buildTypeId, String suiteName, long buildId, String webUrl, String state) {
    }

    /** Seconds until TeamCity expects the build to finish: running-info for running builds, the
     * finish estimate for queued ones; null when it has no estimate. */
    private static Long leftSeconds(TcModel.Build b) {
        TcModel.RunningInfo ri = b.runningInfo();
        if (ri != null && ri.estimatedTotalSeconds() != null && ri.elapsedSeconds() != null)
            return Math.max(0, ri.estimatedTotalSeconds() - ri.elapsedSeconds());

        long finish = TcDates.epochSeconds(b.finishEstimate());
        if (finish > 0)
            return Math.max(0, finish - System.currentTimeMillis() / 1000);

        return null;
    }

    /** Seconds until a queued build is expected to start, when only that much is known; else null. */
    private static Long startSeconds(TcModel.Build b) {
        if (b.finishEstimate() != null && !b.finishEstimate().isBlank())
            return null; // the finish estimate is strictly more useful

        long start = TcDates.epochSeconds(b.startEstimate());
        return start > 0 ? Math.max(0, start - System.currentTimeMillis() / 1000) : null;
    }

    private static Integer pct(TcModel.Build b) {
        return b.runningInfo() == null ? null : b.runningInfo().percentageComplete();
    }

    /** A queued/running build for a PR: a suite re-run, a RunAll chain, or one of a chain's suites. */
    public record ActiveRerun(int pr, String buildTypeId, String suiteName, long buildId, String state, String webUrl,
        Integer pct, Long leftSec, Long startSec) {
    }
}
