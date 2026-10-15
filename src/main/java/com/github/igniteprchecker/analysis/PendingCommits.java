package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
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
            return movedSince(tcToken, pr, buildId);
        }
        catch (RuntimeException e) {
            return null; // PR gone/merged, or a TeamCity/GitHub blip
        }
    }

    /** The head's distance from the analysed run, null when the run tested the head; throws when nobody can tell. */
    public Ahead movedSince(String tcToken, int pr, long buildId) {
        String built = tc.buildRevision(tcToken, buildId)
            .orElseThrow(() -> new IllegalStateException("TeamCity gave no revision of build " + buildId));
        String head = github.prHeadSha(pr);
        if (head == null)
            throw new IllegalStateException("GitHub gave no head of PR " + pr);
        if (built.equals(head))
            return null;

        GithubClient.Ahead ahead = github.compareAhead(built, head);

        return new Ahead(ahead.ahead(), shortSha(built), ahead.headShort(), built, ahead.rewritten());
    }

    /** Just the commit count, for the verdict text; null when the run still describes the head. */
    public Integer countSince(String tcToken, int pr, long buildId) {
        Ahead a = since(tcToken, pr, buildId);

        // A moved head with an uncountable distance (force-push, rebase) still supersedes the run.
        return a == null ? null : Math.max(a.commits(), 1);
    }

    /**
     * The head the open-PR list shows past the run, without a call; the commit count is then unknown (-1). Null when
     * the list names the run's own revision, or was fetched before the run started: such a list may name a head older
     * than the run's.
     */
    public Ahead listedPast(AnalysisResult r) {
        long started = Math.max(r.queuedAt(), r.startedAt());
        if (r.revision() == null || started == 0)
            return null;

        String head = github.listedHead(r.prNumber(), started);
        if (head == null || head.equalsIgnoreCase(r.revision()))
            return null;

        return new Ahead(-1, shortSha(r.revision()), shortSha(head), r.revision(), false);
    }

    private static String shortSha(String sha) {
        return sha.substring(0, Math.min(7, sha.length()));
    }

    /**
     * How far the PR head is ahead of the analysed run, with both revisions short-formatted, the run's whole
     * revision (a short one cannot be fetched), and whether a rebase or force-push rewrote the branch since.
     */
    public record Ahead(int commits, String builtShort, String headShort, String builtRevision, boolean rewritten) {
    }
}
