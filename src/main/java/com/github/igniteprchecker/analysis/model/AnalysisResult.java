package com.github.igniteprchecker.analysis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Result of analysing a PR's RunAll chain: the real blockers vs. the failures filtered out as
 * pre-existing or flaky (each with the reason it was filtered). {@code computedAt} is the epoch-ms
 * moment the result was produced, so the UI can show how fresh it is.
 *
 * <p>Snapshots from older releases carry {@code branchWatermark}, the id of the build TeamCity listed
 * first on the branch. Builds are listed by start, not finish, so that id proves nothing about what
 * the verdict saw: it is dropped on load, and such a result recomputes once.
 */
@JsonIgnoreProperties("branchWatermark")
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
     * Epoch seconds from which a build finishing on the branch may be missing from this verdict: the
     * moment the analysis started reading TeamCity, less a margin for clock skew. A later suite re-run
     * makes the verdict wrong without changing the chain build id, so the warmer asks TeamCity whether
     * anything finished after this rather than trusting "same chain, same answer". 0 when unknown.
     */
    long branchWatermarkAt,
    /**
     * Suites that hit a timeout, an out-of-memory error or a JVM crash, yet ran all of master's tests, and
     * failed only tests that are pre-existing or flaky: their results stand, so the problem is a note,
     * not a broken suite.
     */
    List<BrokenSuite> unstableSuites,
    /**
     * The chain's suites that were cancelled and have not run on the branch since; {@code canceledSuites}
     * counts them, and {@code interrupted} holds while there are any.
     */
    List<CancelledSuite> cancelledSuites
) {
    public AnalysisResult {
        unstableSuites = unstableSuites == null ? List.of() : unstableSuites;
        cancelledSuites = cancelledSuites == null ? List.of() : cancelledSuites;
    }

    /** A result with no unstable or listed cancelled suites, in the shape callers used before they were tracked. */
    public AnalysisResult(int prNumber, long buildId, String branchName, long computedAt, List<TestVerdict> blockers,
        List<TestVerdict> watch, List<TestVerdict> filtered, List<BrokenSuite> brokenSuites,
        List<ShrunkSuite> shrunkSuites, int suitesRan, int suitesReused, boolean interrupted, int canceledSuites,
        boolean live, long liveBuildId, long queuedAt, long startedAt, long finishedAt, long branchWatermarkAt) {
        this(prNumber, buildId, branchName, computedAt, blockers, watch, filtered, brokenSuites, shrunkSuites, suitesRan,
            suitesReused, interrupted, canceledSuites, live, liveBuildId, queuedAt, startedAt, finishedAt,
            branchWatermarkAt, List.of(), List.of());
    }
}
