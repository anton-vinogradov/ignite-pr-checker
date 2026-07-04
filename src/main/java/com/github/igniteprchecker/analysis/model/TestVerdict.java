package com.github.igniteprchecker.analysis.model;

/**
 * Classification of one failed test.
 *
 * @param blocker true if the failure is attributed to the PR (the test never fails on master).
 * @param reason  human-readable explanation of the verdict.
 */
public record TestVerdict(
    long testId,
    String name,
    String suite,
    boolean blocker,
    String reason
) {
}
