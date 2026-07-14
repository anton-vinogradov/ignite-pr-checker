package com.github.igniteprchecker.analysis.model;

/**
 * A suite that ran far FEWER tests in this PR than the same suite runs on master. Tests that never
 * ran can't fail, so such a suite can look green while silently skipping half its coverage — a
 * broken suite class, an excluded package, a hang that ended the run early. Not automatically a
 * defect (a PR may remove tests on purpose), but never something to discover after the merge.
 */
public record ShrunkSuite(String suite, String suiteName, long suiteBuildId, int tests, int baseline, int dropPct) {
}
