package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/**
 * How the PR's own new and changed test classes ran in its RunAll: TcpDiscoveryClientTopologyGapTest came in with
 * PR 13327 taking 298 s, passed there once, and now fails 18 of 100 master runs. {@code note} says what is missing
 * from the answer and why, null when nothing is.
 */
public record PrTests(long buildId, List<TestClass> classes, String note) {
    /**
     * One test class the PR adds ({@code added}) or changes, as its file names it, with its tests' runs in the
     * RunAll; none when no suite of the RunAll ran it.
     */
    public record TestClass(String name, String path, boolean added, List<Run> runs) {
    }

    /**
     * One run of a test: TeamCity's status (SUCCESS, FAILURE, or UNKNOWN for an ignored test), how long it took,
     * and how many master runs of it there are on record, null when not looked up.
     */
    public record Run(
        // A TeamCity test-name id is a 64-bit hash that overflows JS number precision; serialize as a string.
        @JsonFormat(shape = JsonFormat.Shape.STRING) long testId,
        String name,
        String suite,
        long suiteBuildId,
        String suiteName,
        String occurrenceId,
        String status,
        long durationMs,
        Integer masterRuns
    ) {
    }
}
