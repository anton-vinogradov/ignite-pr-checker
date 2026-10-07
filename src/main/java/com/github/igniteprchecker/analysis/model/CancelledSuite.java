package com.github.igniteprchecker.analysis.model;

/**
 * A suite of the analysed chain that was cancelled and has not run on the branch since: it has no result
 * at all, so the verdict says nothing about it. {@code reason} is TeamCity's cancellation comment (null
 * when it gave none). {@code cancelledBy} is the user who cancelled it, null when TeamCity cancelled it by
 * itself: only such a suite is worth an automatic re-run, as a person meant theirs not to run.
 */
public record CancelledSuite(String suite, long suiteBuildId, String suiteName, String reason, String cancelledBy) {
}
