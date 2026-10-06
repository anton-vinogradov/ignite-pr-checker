package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;

/**
 * All the classifier needs from a test's master-branch history, kept instead of the raw occurrences
 * so the cache stays tiny: how many times it ran and how many of those failed. A test that never
 * failed on master ({@code fails == 0}) but failed in the PR is a blocker.
 *
 * <p>Only runs with a result count. TeamCity lists a master run where the test was ignored with status
 * UNKNOWN: the test did not run there, so it is no evidence the test is clean on master. Counted, a test
 * ignored for most of the window read "not seen failing in 100 master run(s)", and one ignored in all of
 * it never got "no master history".
 */
record HistoryStats(int runs, int fails) {
    static HistoryStats of(List<TcModel.TestOccurrence> history) {
        int runs = 0;
        int fails = 0;

        for (TcModel.TestOccurrence occ : history) {
            if ("FAILURE".equals(occ.status())) {
                runs++;
                fails++;
            }
            else if ("SUCCESS".equals(occ.status()))
                runs++;
        }

        return new HistoryStats(runs, fails);
    }
}
