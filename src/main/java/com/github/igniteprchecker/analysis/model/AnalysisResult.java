package com.github.igniteprchecker.analysis.model;

import java.util.List;

/**
 * Result of analysing a PR's RunAll chain: the real blockers vs. the failures filtered out as
 * pre-existing or flaky (each with the reason it was filtered).
 */
public record AnalysisResult(
    int prNumber,
    long buildId,
    String branchName,
    List<TestVerdict> blockers,
    List<TestVerdict> filtered
) {
}
