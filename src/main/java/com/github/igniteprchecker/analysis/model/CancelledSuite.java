package com.github.igniteprchecker.analysis.model;

/**
 * A suite of the analysed chain that was cancelled and has not run on the branch since: it has no result
 * at all, so the verdict says nothing about it. {@code reason} is TeamCity's cancellation comment and
 * {@code cancelledBy} the user who cancelled it, each null when TeamCity gave none. {@code byTeamCity} is
 * true only when TeamCity says it cancelled the suite by itself, with no user: only such a suite is worth an
 * automatic re-run, as a person meant theirs not to run, and a suite with no word on who cancelled it may be
 * either.
 */
public record CancelledSuite(String suite, long suiteBuildId, String suiteName, String reason, String cancelledBy,
    boolean byTeamCity) {
}
