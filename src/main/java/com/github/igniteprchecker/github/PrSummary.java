package com.github.igniteprchecker.github;

/**
 * A pull request as shown in the left-hand list: number, title, link to its GitHub page, the TeamCity
 * username that triggered its latest RunAll (so the UI can flag "My?" PRs — ones you launched CI for),
 * and (best-effort) the blocker count from its last analysis — {@code null} if not analysed/known yet —
 * plus whether the run behind that count covered the PR ({@code proven}), so a zero off an interrupted
 * or broken run isn't badged as clean.
 */
public record PrSummary(int number, String title, String url, String triggeredBy, Integer blockers, Boolean proven) {
}
