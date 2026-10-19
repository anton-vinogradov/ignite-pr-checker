package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.springframework.stereotype.Component;

/**
 * Whether a PR's head has moved past the run being reported on. A verdict for code the author has
 * already replaced is not a verdict on the PR, so every surface that states one asks here first.
 * Two calls, and any failure answers "can't tell" rather than raising a false alarm.
 */
@Component
public class PendingCommits {
    /** How many comparisons of two commits are kept at most. */
    static final int COMPARED = 500;

    private final TcClient tc;
    private final GithubClient github;

    /** GitHub's comparisons by their two commits, the least recently asked first: a pair compares the same for good. */
    private final Map<Pair, GithubClient.Ahead> compared = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Pair, GithubClient.Ahead> eldest) {
            return size() > COMPARED;
        }
    };

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

    /**
     * The head's distance from the analysed run, null when the run tested the head; throws when nobody can tell.
     * GitHub is asked however few calls the token has left: the page leaves the last ones for this.
     */
    public Ahead movedSince(String tcToken, int pr, long buildId) {
        String built = tc.buildRevision(tcToken, buildId)
            .orElseThrow(() -> new IllegalStateException("TeamCity gave no revision of build " + buildId));
        String head = github.prHeadSha(pr);
        if (head == null)
            throw new IllegalStateException("GitHub gave no head of PR " + pr);

        return between(built, head, () -> true);
    }

    /**
     * The same for the page, which may name any build and any PR: a build of another branch than the PR's gets no
     * answer, and the PR's head comes from {@code heads}, null when it cannot name one. GitHub is asked to compare a
     * new pair of commits only while {@code spare} says the checker's token has calls to spare. Throws when nobody can
     * tell.
     */
    public Ahead movedSince(String tcToken, int pr, long buildId, IntFunction<String> heads, BooleanSupplier spare) {
        String built = tc.prBuildRevision(tcToken, pr, buildId).orElseThrow(
            () -> new IllegalStateException("TeamCity gave no revision of build " + buildId + " of PR " + pr));
        String head = heads.apply(pr);
        if (head == null)
            throw new IllegalStateException("no head of PR " + pr);

        return between(built, head, spare);
    }

    private Ahead between(String built, String head, BooleanSupplier spare) {
        if (built.equals(head))
            return null;

        GithubClient.Ahead ahead = compare(built, head, spare);

        return new Ahead(ahead.ahead(), shortSha(built), ahead.headShort(), built, ahead.rewritten());
    }

    /**
     * GitHub's comparison of the two commits; one that failed to count is asked again next time. Throws when GitHub
     * has not compared the pair yet and {@code spare} says not to ask it.
     */
    private GithubClient.Ahead compare(String built, String head, BooleanSupplier spare) {
        Pair pair = new Pair(built, head);
        synchronized (compared) {
            GithubClient.Ahead known = compared.get(pair);
            if (known != null)
                return known;
        }
        if (!spare.getAsBoolean())
            throw new IllegalStateException("GitHub not asked to compare " + built + " with " + head);

        GithubClient.Ahead ahead = github.compareAhead(built, head);
        if (ahead.ahead() >= 0) {
            synchronized (compared) {
                compared.put(pair, ahead);
            }
        }

        return ahead;
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

    private record Pair(String built, String head) {
    }

    /**
     * How far the PR head is ahead of the analysed run, with both revisions short-formatted, the run's whole
     * revision (a short one cannot be fetched), and whether a rebase or force-push rewrote the branch since.
     */
    public record Ahead(int commits, String builtShort, String headShort, String builtRevision, boolean rewritten) {
    }
}
