package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A pull request as shown in the left-hand list: number, title, link to its GitHub page, the TeamCity
 * username that triggered its latest RunAll (so the UI can tag "my run" the PRs you launched CI for),
 * and (best-effort) the blocker count from its last analysis — {@code null} if not analysed/known yet —
 * plus whether that verdict is clean for the PR's current code ({@code proven}), so a zero off an interrupted
 * or broken run, with tests to watch, or of older code isn't badged as clean. {@code headSha} is the PR's head
 * commit as GitHub listed it, null when unknown; {@code standing} names how the verdict stands
 * ({@code Caveats.Standing}), null if not analysed. {@code run} is the step the PR's run under way is at, null when
 * none is, or for a list that does not tell it.
 */
public record PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven,
    String headSha, String standing, @JsonInclude(JsonInclude.Include.NON_NULL) RunStep run) {
    /** A PR with no word of a run under way. */
    public PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven,
        String headSha, String standing) {
        this(number, title, url, triggeredBy, blockers, proven, headSha, standing, null);
    }

    /** A PR without its head or standing, in the shape callers used before they were listed. */
    public PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven) {
        this(number, title, url, triggeredBy, blockers, proven, null, null);
    }

    /**
     * Where a run under way stands among the four steps the PR page shows: 1 the queue, 2 RunAll, 3 auto re-runs; the
     * fourth, the final verdict, ends the run. {@code leftSec} is how long the step still takes by TeamCity's
     * estimates: until a queued RunAll starts, a running one finishes, or the last auto re-run does; null when there
     * is no estimate.
     */
    public record RunStep(int step, Long leftSec) {
    }
}
