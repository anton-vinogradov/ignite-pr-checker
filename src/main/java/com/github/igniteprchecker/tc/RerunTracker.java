package com.github.igniteprchecker.tc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
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
    private final TcClient tc;
    private final Warmer warmer;
    private final String runAllBuildType;
    private final ObjectMapper mapper;
    private final Map<Long, Tracked> tracked = new ConcurrentHashMap<>();

    public RerunTracker(TcClient tc, Warmer warmer, AnalysisProperties cfg, ObjectMapper mapper) {
        this.tc = tc;
        this.warmer = warmer;
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
        if (tracked.isEmpty())
            return;

        String token = warmer.borrowToken();
        if (token == null)
            return; // nobody logged in recently: keep the last known states until a token appears

        for (Tracked t : tracked.values()) {
            try {
                if (runAllBuildType.equals(t.buildTypeId))
                    refreshChain(token, t);
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
    }

    private void refreshSingle(String token, Tracked t) {
        TcModel.Build b = tc.getBuildState(token, t.buildId);
        if (b == null || "finished".equalsIgnoreCase(b.state()))
            tracked.remove(t.buildId);
        else
            t.state = b.state();
    }

    private void refreshChain(String token, Tracked t) {
        TcModel.Build b = tc.getChainDepStates(token, t.buildId);
        if (b == null || "finished".equalsIgnoreCase(b.state())) {
            tracked.remove(t.buildId);
            return;
        }

        t.state = b.state();

        // Started suites come from the chain's snapshot-dependencies; the not-yet-started rest sit in
        // the branch's build queue (a running chain's deps aren't listed until they start), so merge
        // both to cover every suite of the RunAll.
        Map<Long, ActiveRerun> kids = new LinkedHashMap<>();
        if (b.snapshotDependencies() != null && b.snapshotDependencies().build() != null)
            for (TcModel.Build dep : b.snapshotDependencies().build())
                addChild(kids, t.pr, dep);
        for (TcModel.Build dep : tc.queuedBranchBuilds(token, t.pr))
            addChild(kids, t.pr, dep);

        t.children = List.copyOf(kids.values());
    }

    private static void addChild(Map<Long, ActiveRerun> kids, int pr, TcModel.Build dep) {
        if (dep.buildTypeId() == null || "finished".equalsIgnoreCase(dep.state()))
            return;

        String name = dep.buildType() != null && dep.buildType().name() != null
            ? dep.buildType().name() : dep.buildTypeId();
        kids.putIfAbsent(dep.id(), new ActiveRerun(pr, dep.buildTypeId(), name, dep.id(),
            dep.state() == null ? "queued" : dep.state(), dep.webUrl() == null ? "" : dep.webUrl()));
    }

    /** The currently queued/running builds (chains flattened into their suites), running first, deduped. */
    public List<ActiveRerun> active() {
        Map<Long, ActiveRerun> out = new LinkedHashMap<>();
        for (Tracked t : tracked.values()) {
            out.putIfAbsent(t.buildId, new ActiveRerun(t.pr, t.buildTypeId, t.suiteName, t.buildId, t.state, t.webUrl));
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

    /** A queued/running build for a PR: a suite re-run, a RunAll chain, or one of a chain's suites. */
    public record ActiveRerun(int pr, String buildTypeId, String suiteName, long buildId, String state, String webUrl) {
    }
}
