package com.github.igniteprchecker.analysis.model;

/**
 * A run that a suite of the chain needed and that failed: the build step every suite of a RunAll depends on.
 * {@code suite} is its build type, {@code buildId} the failed run, {@code name} what TeamCity calls it.
 */
public record Upstream(String suite, long buildId, String name) {
}
