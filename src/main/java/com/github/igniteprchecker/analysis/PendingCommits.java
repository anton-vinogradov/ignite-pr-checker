package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import org.springframework.stereotype.Component;

/**
 * Whether a PR's head has moved past the run being reported on. A verdict for code the author has
 * already replaced is not a verdict on the PR, so every surface that states one asks here first.
 * Two calls, and any failure answers "can't tell" rather than raising a false alarm.
 */
@Component
public class PendingCommits {
    private final TcClient tc;
    private final GithubClient github;

    public PendingCommits(TcClient tc, GithubClient github) {
        this.tc = tc;
        this.github = github;
    }

    /** The head's distance from the analysed run, or null when unchanged or unknowable. */
    public Ahead since(String tcToken, int pr, long buildId) {
        try {
            String built = tc.buildRevision(tcToken, buildId).orElse(null);
            String head = github.prHead(pr).headSha();
            if (built == null || head == null || built.equals(head))
                return null;

            GithubClient.Ahead ahead = github.compareAhead(built, head);

            return new Ahead(ahead.ahead(), built.substring(0, Math.min(7, built.length())), ahead.headShort());
        }
        catch (RuntimeException e) {
            return null; // PR gone/merged, or a TeamCity/GitHub blip
        }
    }

    /** Just the commit count, for the verdict text; null when the run still describes the head. */
    public Integer countSince(String tcToken, int pr, long buildId) {
        Ahead a = since(tcToken, pr, buildId);

        // A moved head with an uncountable distance (force-push, rebase) still supersedes the run.
        return a == null ? null : Math.max(a.commits(), 1);
    }

    /** How far the PR head is ahead of the analysed run, with both revisions short-formatted. */
    public record Ahead(int commits, String builtShort, String headShort) {
    }
}
