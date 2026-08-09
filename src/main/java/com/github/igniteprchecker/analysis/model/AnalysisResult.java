package com.github.igniteprchecker.analysis.model;

import java.util.List;

/**
 * Result of analysing a PR's RunAll chain: the real blockers vs. the failures filtered out as
 * pre-existing or flaky (each with the reason it was filtered). {@code computedAt} is the epoch-ms
 * moment the result was produced, so the UI can show how fresh it is.
 */
public record AnalysisResult(
    int prNumber,
    long buildId,
    String branchName,
    long computedAt,
    List<TestVerdict> blockers,
    List<TestVerdict> watch,
    List<TestVerdict> filtered,
    List<BrokenSuite> brokenSuites,
    List<ShrunkSuite> shrunkSuites,
    int suitesRan,
    int suitesReused,
    boolean interrupted,
    int canceledSuites,
    boolean live,
    long liveBuildId,
    /** Epoch seconds of the analysed chain's queue/start/finish; 0 when TeamCity didn't say. */
    long queuedAt,
    long startedAt,
    long finishedAt,
    /**
     * The newest finished build on the branch when this was computed. A later suite re-run makes the
     * verdict wrong without changing the chain build id, so the warmer compares this rather than
     * trusting "same chain, same answer". 0 when it couldn't be read.
     */
    long branchWatermark
) {
}
