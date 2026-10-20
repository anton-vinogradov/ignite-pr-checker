package com.github.igniteprchecker.analysis.model;

/**
 * A run on a PR's branch and the commit it tested: {@code suite} is its build type, {@code suiteName} what TeamCity
 * calls it, {@code buildId} the run, {@code revision} the commit, null when TeamCity named none.
 */
public record RunRef(String suite, String suiteName, long buildId, String revision) {
}
