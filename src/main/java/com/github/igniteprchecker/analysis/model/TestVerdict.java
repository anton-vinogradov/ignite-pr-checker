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
 * @param codeRuns   how many of the trailing {@code branchRuns} ran on the same revision as the latest one —
 *                   the runs the verdict is based on, the rest having run on other code. Equals the whole
 *                   strip when TeamCity gave no revisions; 0 when the verdict never needed to look.
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
    String branchRuns,
    int codeRuns
) {
    /**
     * Which classification rules produced these verdicts. Bump it whenever a change makes the same
     * run classify differently: snapshots on disk hold verdicts, and reviving old-rule ones after a
     * deploy would show a stale verdict, or report the rule change as "new blockers since your last
     * run". Snapshot stores drop what does not match.
     */
    public static final int RULES = 2;
}
