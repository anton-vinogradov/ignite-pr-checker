package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;

/**
 * All the classifier needs from a test's master-branch history, kept instead of the raw occurrences
 * so the cache stays tiny: how many times it ran and how many of those failed. A test that never
 * failed on master ({@code fails == 0}) but failed in the PR is a blocker.
 */
record HistoryStats(int runs, int fails) {
    static HistoryStats of(List<TcModel.TestOccurrence> history) {
        int fails = 0;

        for (TcModel.TestOccurrence occ : history) {
            if ("FAILURE".equals(occ.status()))
                fails++;
        }

        return new HistoryStats(history.size(), fails);
    }
}
