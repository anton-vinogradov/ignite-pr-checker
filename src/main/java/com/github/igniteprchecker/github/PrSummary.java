package com.github.igniteprchecker.github;

/**
 * A pull request as shown in the left-hand list: number, title, link to its GitHub page, the TeamCity
 * username that triggered its latest RunAll (so the UI can flag "My?" PRs — ones you launched CI for),
 * and (best-effort) the blocker count from its last analysis — {@code null} if not analysed/known yet.
 */
public record PrSummary(int number, String title, String url, String triggeredBy, Integer blockers) {
}
