package com.github.igniteprchecker.analysis.model;

/** A test that failed in the PR chain: the suite (buildType) it failed in, plus that suite build's id and display name (for links). */
public record FailedTest(long testId, String name, String suite, long suiteBuildId, String suiteName) {
}
