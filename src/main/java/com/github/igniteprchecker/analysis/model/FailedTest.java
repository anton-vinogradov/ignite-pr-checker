package com.github.igniteprchecker.analysis.model;

/** A test that failed in the PR chain, with the suite (buildType) it failed in. */
public record FailedTest(long testId, String name, String suite) {
}
