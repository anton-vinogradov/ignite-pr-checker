package com.github.igniteprchecker.github;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GitProxyProperties;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Prepares one action's Ignite PR revisions before any existing builds are replaced. */
@Component
public class IgnitePrRuns {
    private static final String ROOT = "GitHubApacheIgnite";

    private static final String COMMENT = "Triggered by Ignite PR Checker";

    private final GithubClient github;

    private final TcClient tc;

    private final GitProxyProperties proxy;

    private final String runAll;

    @Autowired
    public IgnitePrRuns(GithubClient github, TcClient tc, GitProxyProperties proxy, AnalysisProperties analysis) {
        this.github = github;
        this.tc = tc;
        this.proxy = proxy;
        this.runAll = analysis.runAllBuildType();
    }

    private IgnitePrRuns(TcClient tc) {
        this.github = null;
        this.tc = tc;
        this.proxy = null;
        this.runAll = null;
    }

    public static IgnitePrRuns unpinned(TcClient tc) {
        return new IgnitePrRuns(tc);
    }

    public boolean enabled() {
        return proxy != null && proxy.enabled();
    }

    public Prepared prepareRunAll(int pr) {
        return prepare(pr, runAll == null ? List.of() : List.of(runAll));
    }

    /** Reads the fresh head once and prepares every suite on that commit; failures leave existing runs alone. */
    public Prepared prepare(int pr, List<String> suites) {
        if (!enabled())
            return new Prepared(pr, Map.of());

        String sha = github.prHeadSha(pr);
        if (sha == null || !sha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("GitHub did not name a full head commit for PR " + pr);

        Map<String, PinnedRun> runs = new LinkedHashMap<>();
        for (String suite : suites) {
            runs.put(suite, new PinnedRun(suite, "pull/" + pr + "/head",
                List.of(PinnedRun.Pin.forRoot(ROOT, "refs/pull/" + pr + "/head", sha)), Map.of()));
        }

        return new Prepared(pr, runs);
    }

    public TcModel.Build queueRunAll(String token, Prepared prepared, boolean top) {
        return prepared.runs().isEmpty() ? tc.triggerRunAll(token, prepared.pr(), top)
            : tc.queuePinned(token, prepared.runs().get(runAll), top, COMMENT);
    }

    public TcModel.Build queueOwnReplacingQueued(String token, String username, Prepared prepared, String suite,
        boolean top) {
        return prepared.runs().isEmpty()
            ? tc.triggerOwnBuildReplacingQueued(token, username, suite, prepared.pr(), top)
            : tc.queuePinnedOwnReplacingQueued(token, username, prepared.runs().get(suite), top, COMMENT);
    }

    public TcModel.Build queueReplacingQueued(String token, Prepared prepared, String suite, boolean top,
        String comment) {
        return prepared.runs().isEmpty() ? tc.triggerBuildReplacingQueued(token, suite, prepared.pr(), top, comment)
            : tc.queuePinnedReplacingQueued(token, prepared.runs().get(suite), top, comment);
    }

    /** All suites of one action, prepared on the same head; an empty map retains branch-based queueing. */
    public record Prepared(int pr, Map<String, PinnedRun> runs) {
        public Prepared {
            runs = Map.copyOf(runs);
        }
    }
}
