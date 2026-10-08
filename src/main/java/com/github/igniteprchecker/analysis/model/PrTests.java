package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/**
 * How the PR's own new and changed test classes ran in its RunAll: TcpDiscoveryClientTopologyGapTest came in with
 * PR 13327 taking 298 s, passed there once, and now fails 18 of 100 master runs. {@code note} says what is missing
 * from the answer and why, null when nothing is. {@code suiteCheck} is what Ignite's own check says of the classes no
 * test suite holds, null when it was not asked.
 */
public record PrTests(long buildId, List<TestClass> classes, String note, SuiteCheck suiteCheck) {
    public PrTests(long buildId, List<TestClass> classes, String note) {
        this(buildId, classes, note, null);
    }

    /**
     * One test class the PR adds ({@code added}) or changes, as its file names it, with its tests' runs in the
     * RunAll; none when no suite of the RunAll ran it. {@code notInSuite}: Ignite's check finds it in no test suite,
     * so CI never runs it. {@code atRun}: what the RunAll's revision tells of a class with no runs; null when not
     * asked or not known.
     */
    public record TestClass(String name, String path, boolean added, List<Run> runs, boolean notInSuite,
        AtRun atRun) {
        public TestClass(String name, String path, boolean added, List<Run> runs) {
            this(name, path, added, runs, false, null);
        }
    }

    /**
     * A class with no runs as the RunAll's revision had it, by GitHub's comparison of that revision with the head and
     * by Ignite's check of it: not there under this name yet ({@code ABSENT}), in no test suite ({@code IN_NO_SUITE}),
     * or passed by the check, so in a suite that did not run it, or a base or {@code @Ignore} class
     * ({@code PASSED_CHECK}).
     */
    public enum AtRun {
        ABSENT, IN_NO_SUITE, PASSED_CHECK
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

    /**
     * Ignite's abandoned-tests check of the PR's head {@code sha}, the job on GitHub at {@code url}: whether every test
     * class is in a test suite. {@code reason} says why it is SKIPPED or UNKNOWN. {@code elsewhere} are the classes it
     * finds in no suite that are not among {@code classes}.
     */
    public record SuiteCheck(State state, String sha, String url, String reason, List<String> elsewhere) {
        /** FAILED names classes in no suite; RUNNING, SKIPPED and UNKNOWN tell nothing of any class. */
        public enum State {
            PASSED, FAILED, RUNNING, SKIPPED, UNKNOWN
        }
    }
}
