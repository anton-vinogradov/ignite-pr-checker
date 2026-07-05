package com.github.igniteprchecker.analysis.model;

import java.util.List;

/**
 * A suite that FAILED without producing a single failed test — the run broke before/without testing
 * (compilation error, execution timeout, agent crash, failed snapshot dependency). Such suites are
 * invisible to the failed-test analysis, so they are surfaced separately: an all-green verdict with
 * a broken suite would be a lie.
 */
public record BrokenSuite(String suite, long suiteBuildId, String suiteName, List<String> problems) {
}
