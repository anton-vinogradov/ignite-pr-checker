package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Tracks the builds triggered through this tool (RunAll and suite re-runs) while they are queued or
 * running, so the UI can tag a suite with a live "queued/running" chip linking to that very build.
 * States refresh on a timer with one cheap by-id call per active build (using the token of whoever
 * triggered it — kept in memory only, like the warmer's token pool); an entry is dropped as soon as
 * its build finishes or disappears, so the map stays tiny and self-cleaning.
 */
@Component
public class RerunTracker {
    private final TcClient tc;
    private final Map<Long, Tracked> tracked = new ConcurrentHashMap<>();

    public RerunTracker(TcClient tc) {
        this.tc = tc;
    }

    /** Remember a just-triggered build; the UI starts showing its chip immediately (state "queued"). */
    public void record(int pr, String token, TcModel.Build b) {
        if (b == null || b.buildTypeId() == null)
            return;

        String suiteName = b.buildType() != null && b.buildType().name() != null ? b.buildType().name() : b.buildTypeId();
        Tracked t = new Tracked(pr, b.buildTypeId(), suiteName, b.id(), b.webUrl() == null ? "" : b.webUrl(), token);
        t.state = b.state() == null ? "queued" : b.state();
        tracked.put(b.id(), t);
    }

    /** Refreshes each active build's state; drops finished/gone ones. No-op when nothing is tracked. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    void refresh() {
        for (Tracked t : tracked.values()) {
            try {
                TcModel.Build b = tc.getBuildState(t.token, t.buildId);
                if (b == null || "finished".equalsIgnoreCase(b.state()))
                    tracked.remove(t.buildId);
                else
                    t.state = b.state();
            }
            catch (RestClientResponseException e) {
                // 404: cancelled-in-queue/cleaned-up; 401/403: the triggerer's token died — either way stop tracking.
                tracked.remove(t.buildId);
            }
            catch (RuntimeException ignore) {
                // transient network error: keep the last known state and retry next cycle
            }
        }
    }

    /** The currently queued/running tracked builds (never exposes tokens), running first. */
    public List<ActiveRerun> active() {
        List<ActiveRerun> out = new ArrayList<>();
        tracked.values().forEach(t ->
            out.add(new ActiveRerun(t.pr, t.buildTypeId, t.suiteName, t.buildId, t.state, t.webUrl)));
        out.sort(Comparator.comparing((ActiveRerun r) -> !"running".equalsIgnoreCase(r.state()))
            .thenComparingLong(ActiveRerun::buildId));

        return out;
    }

    private static final class Tracked {
        final int pr;
        final String buildTypeId;
        final String suiteName;
        final long buildId;
        final String webUrl;
        final String token;
        volatile String state = "queued";

        Tracked(int pr, String buildTypeId, String suiteName, long buildId, String webUrl, String token) {
            this.pr = pr;
            this.buildTypeId = buildTypeId;
            this.suiteName = suiteName;
            this.buildId = buildId;
            this.webUrl = webUrl;
            this.token = token;
        }
    }

    /** A tool-triggered build that is still queued or running. */
    public record ActiveRerun(int pr, String buildTypeId, String suiteName, long buildId, String state, String webUrl) {
    }
}
