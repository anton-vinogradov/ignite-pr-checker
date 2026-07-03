package com.github.igniteprchecker.github;

/** A pull request as shown in the left-hand list: number, title, and link to its GitHub page. */
public record PrSummary(int number, String title, String url) {
}
