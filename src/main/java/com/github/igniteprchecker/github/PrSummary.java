package com.github.igniteprchecker.github;

/**
 * A pull request as shown in the left-hand list: number, title, link to its GitHub page, and (best-effort)
 * the blocker count from its last analysis — {@code null} if it hasn't been analysed yet.
 */
public record PrSummary(int number, String title, String url, Integer blockers) {
}
