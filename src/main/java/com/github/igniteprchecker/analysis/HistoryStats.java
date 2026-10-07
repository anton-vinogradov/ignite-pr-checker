package com.github.igniteprchecker.analysis;

/**
 * What the classifier reads from a selection of a test's runs (see {@link RunHistory}).
 *
 * @param runs        how many runs had a result.
 * @param fails       how many of them failed.
 * @param greenStreak how many of the newest runs in a row passed.
 * @param failingPrs  for runs on PR branches, how many different PRs failed the test; 0 for master.
 */
record HistoryStats(int runs, int fails, int greenStreak, int failingPrs) {
    /** The share of runs that failed; 0 when there were none. */
    double failRate() {
        return runs == 0 ? 0 : (double) fails / runs;
    }
}
