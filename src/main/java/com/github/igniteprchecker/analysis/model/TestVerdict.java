package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/**
 * Classification of one failed test.
 *
 * @param blocker    true if the failure is attributed to the PR (fails consistently, never on master).
 * @param watch      true if the test recently started failing on the branch but passed earlier (a fresh
 *                   break to watch, not yet a hard blocker); never true together with {@code blocker}.
 * @param reason     human-readable explanation of the verdict.
 * @param branchRuns pass/fail history in the test's suite on the PR branch, oldest → newest ('P'/'F' per
 *                   finished run; a run where the test was ignored is left out); "" if none.
 * @param codeRuns   how many of the trailing {@code branchRuns} ran on the same revision as the latest one —
 *                   the runs the verdict is based on, the rest having run on other code. Equals the whole
 *                   strip when TeamCity gave no revisions; 0 when the verdict never needed to look.
 * @param doubts     what a blocker or watch verdict rests on short of proof (see {@link Doubt}); empty otherwise.
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
    int codeRuns,
    List<Doubt> doubts
) {
    /**
     * Which classification rules produced these verdicts. Bump it whenever a change makes the same
     * run classify differently: snapshots on disk hold verdicts, and reviving old-rule ones after a
     * deploy would show a stale verdict, or report the rule change as "new blockers since your last
     * run". Snapshot stores drop what does not match.
     */
    public static final int RULES = 10;

    public TestVerdict {
        doubts = doubts == null ? List.of() : List.copyOf(doubts);
    }

    /** A verdict that rests on nothing short of proof, in the shape callers used before doubts were stated. */
    public TestVerdict(long testId, String name, String suite, long suiteBuildId, String suiteName, String occurrenceId,
        boolean blocker, boolean watch, String reason, String branchRuns, int codeRuns) {
        this(testId, name, suite, suiteBuildId, suiteName, occurrenceId, blocker, watch, reason, branchRuns, codeRuns,
            List.of());
    }

    /**
     * Why a blocker or a test to watch may not be what it looks like. On prod all 31 blockers of five PRs rested on
     * one run, nine of them with no master history at all, and they looked exactly like proven ones.
     */
    public enum Doubt {
        /** It failed once on this branch, and nothing else backs it: a re-run confirms or clears it. */
        ONE_RUN,

        /** Master has no runs of the test on the PR's JDK to compare with: a new test, or one master does not run. */
        NO_MASTER_HISTORY,

        /** A TeamCity error kept part of the check from being made. */
        UNCHECKED
    }
}
