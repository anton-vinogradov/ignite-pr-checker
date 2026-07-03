package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;

/**
 * The only things the classifier needs from a test's base-branch history, kept instead of the raw
 * occurrences so the cache stays tiny: number of runs, failures, and pass/fail flips on builds that
 * carried no code changes.
 */
record HistoryStats(int runs, int fails, int flips) {
    static HistoryStats of(List<TcModel.TestOccurrence> history) {
        int fails = 0;
        int flips = 0;
        String prev = null;

        for (TcModel.TestOccurrence occ : history) {
            if ("FAILURE".equals(occ.status()))
                fails++;

            if (prev != null && !prev.equals(occ.status()) && noCodeChanges(occ))
                flips++;

            prev = occ.status();
        }

        return new HistoryStats(history.size(), fails, flips);
    }

    private static boolean noCodeChanges(TcModel.TestOccurrence occ) {
        return occ.build() != null && occ.build().changes() != null && occ.build().changes().count() == 0;
    }
}
