package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Why an empty blocker list may still not mean "this PR is fine": the run behind it never covered
 * everything (aborted, suites without a reliable result, suites that ran far fewer tests than
 * master, a newer run still going), or it no longer describes the PR's code (commits pushed since).
 * Every surface states these next to the verdict instead of showing a bare green tick.
 */
public final class Caveats {
    private Caveats() {
    }

    /**
     * Reasons this run cannot prove the PR clean, as plain sentences; empty when it can.
     * {@code commitsAhead} is null when nobody checked whether the PR head has moved.
     */
    public static List<String> of(AnalysisResult r, Integer commitsAhead) {
        List<String> out = new ArrayList<>();

        if (r.interrupted())
            out.add("the RunAll was interrupted — " + r.canceledSuites() + " suite(s) never ran");

        if (!r.brokenSuites().isEmpty())
            out.add(r.brokenSuites().size() + " suite(s) have no reliable result (compilation error, timeout, crash)");

        if (!r.shrunkSuites().isEmpty())
            out.add(r.shrunkSuites().size() + " suite(s) ran far fewer tests than the same suites on master");

        if (r.live())
            out.add("a newer run is still going — its unfinished suites can still fail");

        if (commitsAhead != null && commitsAhead > 0)
            out.add(commitsAhead + " commit(s) pushed since this run — it tested older code");

        return out;
    }

    /** Whether the run covers enough for an empty blocker list to mean something. */
    public static boolean proven(AnalysisResult r) {
        return of(r, null).isEmpty();
    }
}
