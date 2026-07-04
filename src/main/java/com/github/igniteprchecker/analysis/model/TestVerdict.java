package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Classification of one failed test.
 *
 * @param blocker    true if the failure is attributed to the PR (the test never fails on master).
 * @param reason     human-readable explanation of the verdict.
 * @param branchRuns pass/fail history on the PR branch, oldest → newest ('P'/'F' per finished run); "" if none.
 */
public record TestVerdict(
    // A TeamCity test-name id is a 64-bit hash that overflows JS number precision; serialize as a string.
    @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
    String name,
    String suite,
    long suiteBuildId,
    String suiteName,
    String occurrenceId,
    boolean blocker,
    String reason,
    String branchRuns
) {
}
