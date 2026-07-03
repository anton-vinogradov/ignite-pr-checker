package com.github.igniteprchecker.analysis.model;

/**
 * Classification of one failed test.
 *
 * @param blocker         true if the failure is attributed to the PR (broke by this change).
 * @param reason          human-readable explanation of the verdict.
 * @param baseFailRatePct fail rate of this test in the base branch, percent.
 * @param statusChanges   pass/fail flips in the base branch on builds without code changes.
 * @param historyRuns     number of base-branch runs examined.
 */
public record TestVerdict(
    long testId,
    String name,
    String suite,
    boolean blocker,
    String reason,
    double baseFailRatePct,
    int statusChanges,
    int historyRuns
) {
}
