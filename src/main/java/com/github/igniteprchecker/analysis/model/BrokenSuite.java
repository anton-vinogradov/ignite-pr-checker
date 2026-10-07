package com.github.igniteprchecker.analysis.model;

import java.util.List;

/**
 * A suite that FAILED without producing a single failed test — the run broke before/without testing
 * (compilation error, execution timeout, agent crash, failed snapshot dependency). Such suites are
 * invisible to the failed-test analysis, so they are surfaced separately: an all-green verdict with
 * a broken suite would be a lie.
 *
 * <p>{@code tests}/{@code baseline} are how many tests it did run against master's count for the
 * same suite (0 when unknown). A hung or crashed suite usually runs a fraction of them, and that
 * shortfall belongs under its cause — on its own it would read as "tests disappeared" and hide the
 * timeout that actually caused it.
 *
 * <p>{@code problemTypes} are TeamCity's types of its problems, as {@code problems} describe them.
 * {@code failedUpstream} is the run it needed that failed, null when every run it needed passed: such a suite
 * never got to run, and only that run tells why.
 */
public record BrokenSuite(String suite, long suiteBuildId, String suiteName, List<String> problems,
    int tests, int baseline, List<String> problemTypes, Upstream failedUpstream) {
    /** TeamCity's problem type of a suite it could not hand the artifacts of a run it needs. */
    private static final String ARTIFACTS = "ARTIFACT_DEPENDENCY_ERROR";

    public BrokenSuite {
        problemTypes = problemTypes == null ? List.of() : problemTypes;
    }

    /** A broken suite whose problem types and upstream are not known, in the shape callers used before they were. */
    public BrokenSuite(String suite, long suiteBuildId, String suiteName, List<String> problems, int tests,
        int baseline) {
        this(suite, suiteBuildId, suiteName, problems, tests, baseline, List.of(), null);
    }

    /**
     * Whether TeamCity could not hand it the artifacts of a run it needs. A verdict kept by an older release has
     * no types: TeamCity's own message tells then.
     */
    public boolean artifactsUnavailable() {
        return problemTypes.contains(ARTIFACTS)
            || problems != null && problems.stream().anyMatch(p -> p.startsWith("Failed to resolve artifacts"));
    }

    /** Whether it failed to compile: no re-run of the same code gets past that. */
    public boolean compileError() {
        return problemTypes.contains("TC_COMPILATION_ERROR")
            || problems != null && problems.contains("compilation error");
    }

    /** Whether it says it failed only because a run it needed failed, which run not known. */
    public boolean failedDependency() {
        return problems != null && problems.contains("failed dependency");
    }
}
