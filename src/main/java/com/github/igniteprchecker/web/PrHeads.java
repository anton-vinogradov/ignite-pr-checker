package com.github.igniteprchecker.web;

import com.github.igniteprchecker.github.GithubClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The head commit of a PR as the page asks for it: whether a running build tests it (/api/runs), whether the PR
 * moved past a run (/api/pending), and whether it has merge conflicts with its base branch (/api/trigger). Any
 * signed-in user can ask about any PR number, and every answer GitHub gives costs a call of the checker's own token,
 * which PR commands, comments and visas need too. When those calls run out for over an hour, the PR commands posted
 * meanwhile are skipped, and a visa goes out without saying that commits were pushed since. So the head comes from the
 * open-PR list while that is fresh, a head GitHub named is kept a while, and the page does not ask GitHub at all while
 * few of the token's hourly calls are left.
 */
@Component
public class PrHeads {
    /** How long a head GitHub named, or the open-PR list showed, is taken as the PR's head. */
    static final long KEEP_MS = 30_000L;

    /** How many PRs' heads are kept at most. */
    static final int KEPT = 1_000;

    /** The page asks GitHub while at least 1 / RESERVE_SHARE of the token's hourly calls are left: 1000 of 5000. */
    static final int RESERVE_SHARE = 5;

    private final GithubClient github;

    private final LongSupplier nowMs;

    /** The heads GitHub named, by PR, the least recently asked first. */
    private final Map<Integer, Kept> kept = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Kept> eldest) {
            return size() > KEPT;
        }
    };

    @Autowired
    public PrHeads(GithubClient github) {
        this(github, System::currentTimeMillis);
    }

    PrHeads(GithubClient github, LongSupplier nowMs) {
        this.github = github;
        this.nowMs = nowMs;
    }

    /** The PR's head commit; null when GitHub could not name it, or was not asked, to keep calls for the rest. */
    public String of(int pr) {
        long now = nowMs.getAsLong();
        String listed = github.listedHead(pr, (now - KEEP_MS) / 1000);
        if (listed != null)
            return listed;

        synchronized (kept) {
            Kept k = kept.get(pr);
            if (k != null && now - k.at() < KEEP_MS)
                return k.sha();
        }
        if (!spare())
            return null;

        String sha;
        try {
            sha = github.prHeadSha(pr);
        }
        catch (RuntimeException e) {
            return null;
        }
        if (sha != null) {
            synchronized (kept) {
                kept.put(pr, new Kept(sha, now));
            }
        }

        return sha;
    }

    /**
     * Whether GitHub reports merge conflicts between the PR and its base branch. Asked anew each time, as a push to
     * either side changes it; while the token has no calls to spare, GitHub is not asked and the answer is no.
     */
    public boolean conflicts(int pr) {
        return spare() && github.reportsConflicts(pr);
    }

    /** Whether the token has calls to spare for the page: GitHub said nothing else since its hour began. */
    public boolean spare() {
        GithubClient.Quota q = github.appQuota();

        return q == null || nowMs.getAsLong() / 1000 >= q.resetEpochSec()
            || q.remaining() >= q.limit() / RESERVE_SHARE;
    }

    /** A head GitHub named at {@code at}, epoch milliseconds. */
    private record Kept(String sha, long at) {
    }
}
