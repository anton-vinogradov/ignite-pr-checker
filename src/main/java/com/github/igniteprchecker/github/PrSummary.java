package com.github.igniteprchecker.github;

/**
 * A pull request as shown in the left-hand list: number, title, link to its GitHub page, the TeamCity
 * username that triggered its latest RunAll (so the UI can flag "My?" PRs — ones you launched CI for),
 * and (best-effort) the blocker count from its last analysis — {@code null} if not analysed/known yet —
 * plus whether that verdict is clean for the PR's current code ({@code proven}), so a zero off an interrupted
 * or broken run, with tests to watch, or of older code isn't badged as clean. {@code headSha} is the PR's head
 * commit as GitHub listed it, null when unknown; {@code standing} names how the verdict stands
 * ({@code Caveats.Standing}), null if not analysed.
 */
public record PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven,
    String headSha, String standing) {
    /** A PR without its head or standing, in the shape callers used before they were listed. */
    public PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven) {
        this(number, title, url, triggeredBy, blockers, proven, null, null);
    }
}
