package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Classification of one failed test.
 *
 * @param blocker    true if the failure is attributed to the PR (fails consistently, never on master).
 * @param watch      true if the test recently started failing on the branch but passed earlier (a fresh
 *                   break to watch, not yet a hard blocker); never true together with {@code blocker}.
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
    boolean watch,
    String reason,
    String branchRuns
) {
}
