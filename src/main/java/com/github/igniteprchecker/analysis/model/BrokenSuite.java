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
 */
public record BrokenSuite(String suite, long suiteBuildId, String suiteName, List<String> problems,
    int tests, int baseline) {
}
