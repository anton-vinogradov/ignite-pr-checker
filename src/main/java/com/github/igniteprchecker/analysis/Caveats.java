package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Why an empty blocker list may still not mean "this PR is fine": the run behind it never covered
 * everything (aborted, suites without a reliable result, suites that ran far fewer tests than
 * master, failed tests TeamCity errors kept from being checked, a newer run still going), or it no
 * longer describes the PR's code (commits pushed since).
 * Every surface states these next to the verdict instead of showing a bare green tick.
 */
public final class Caveats {
    /** How many causes a caveat about broken suites names before "…". */
    private static final int CAUSES_SHOWN = 3;

    /** How much of a TeamCity message a caveat quotes. */
    private static final int CAUSE_CHARS = 80;

    /** What the caveat about broken suites said of their causes before it named them; see {@link #keyOf}. */
    private static final String UNNAMED_CAUSES = "compilation error, timeout, crash";

    private Caveats() {
    }

    /**
     * Reasons this run cannot prove the PR clean, as plain sentences; empty when it can.
     * {@code commitsAhead} is null when nobody checked whether the PR head has moved.
     */
    public static List<String> of(AnalysisResult r, Integer commitsAhead) {
        return of(r, commitsAhead, Caveats::causesOf);
    }

    /**
     * The caveats as a key telling two verdicts of one revision apart. The broken suites' causes are left out, as
     * TeamCity words them with the numbers of each run, and the caveat reads as it did before it named them, so the
     * key of a visa an older release posted still matches.
     */
    public static List<String> keyOf(AnalysisResult r) {
        return of(r, null, causes -> UNNAMED_CAUSES);
    }

    private static List<String> of(AnalysisResult r, Integer commitsAhead,
        Function<List<BrokenGroup>, String> causesText) {
        List<String> out = new ArrayList<>();
        List<BrokenGroup> groups = BrokenGroup.of(r);
        List<BrokenGroup> upstreams = groups.stream().filter(g -> g.upstream() != null).toList();
        upstreams.forEach(g -> out.add(g.caveat()));

        int keptFromRunning = upstreams.stream().mapToInt(g -> g.cancelled().size()).sum();
        if (r.interrupted() && r.canceledSuites() > keptFromRunning)
            out.add("the RunAll was interrupted — " + (r.canceledSuites() - keptFromRunning) + " suite(s) never ran");

        List<BrokenGroup> causes = groups.stream().filter(g -> g.upstream() == null).toList();
        int broken = causes.stream().mapToInt(g -> g.suites().size()).sum();
        if (broken > 0)
            out.add(broken + " suite(s) have no reliable result (" + causesText.apply(causes) + ")");

        if (!r.shrunkSuites().isEmpty())
            out.add(r.shrunkSuites().size() + " suite(s) ran far fewer tests than the same suites on master");

        if (!r.unverified().isEmpty())
            out.add(r.unverified().size() + " failed test(s) could not be checked (TeamCity errors)");

        if (r.live())
            out.add("a newer run is still going — its unfinished suites can still fail");

        if (commitsAhead != null && commitsAhead > 0)
            out.add(commitsAhead + " commit(s) pushed since this run — it tested older code");

        return out;
    }

    /** "60× ci2 glitch: artifacts unavailable; execution timeout; …": the groups' causes, the most common first. */
    private static String causesOf(List<BrokenGroup> groups) {
        String shown = groups.stream().limit(CAUSES_SHOWN)
            .map(g -> (g.suites().size() > 1 ? g.suites().size() + "× " : "") + causeOf(g))
            .collect(Collectors.joining("; "));

        return groups.size() > CAUSES_SHOWN ? shown + "; …" : shown;
    }

    private static String causeOf(BrokenGroup g) {
        String cause = switch (g.kind()) {
            case ARTIFACTS -> "ci2 glitch: artifacts unavailable";
            case UPSTREAM -> "a run they need failed";
            case PROBLEM -> g.title();
        };

        return cause.length() > CAUSE_CHARS ? cause.substring(0, CAUSE_CHARS - 1) + "…" : cause;
    }

    /** Whether the run covers enough for an empty blocker list to mean something. */
    public static boolean proven(AnalysisResult r) {
        return of(r, null).isEmpty();
    }

    /** How a PR's verdict stands at a glance, as its badge in the PR list shows it. */
    public enum Standing {
        /** It has blockers. */
        BLOCKERS,

        /** No blockers, but tests started failing on its code: a re-run decides. */
        WATCH,

        /** No blockers or tests to watch, but the run cannot prove the PR clean (see {@link #of}). */
        UNPROVEN,

        /** A clean run of code the PR has since moved on from. */
        OLD_CODE,

        /** A clean run, but whether it ran the PR's current code is not known. */
        UNKNOWN_CODE,

        /** What the page calls "No blockers": the only standing that earns the green tick. */
        CLEAN
    }

    /**
     * What the PR list keeps of a verdict: enough to badge it. {@code covered} is whether the run covers enough for
     * an empty blocker list to mean something, {@code revision} the code it ran, null when unknown.
     */
    public record Glance(int blockers, int watch, boolean covered, String revision) {
        public static Glance of(AnalysisResult r) {
            return new Glance(r.blockers().size(), r.watch().size(), proven(r), r.revision());
        }

        /**
         * How the verdict stands for a PR whose head is {@code head}, null when unknown. The green tick in the list
         * once ignored tests to watch (PRs 13583, 13577 and 13389) and commits pushed since the run (7 of 13 ticks
         * on prod).
         */
        public Standing against(String head) {
            if (blockers > 0)
                return Standing.BLOCKERS;
            if (watch > 0)
                return Standing.WATCH;
            if (!covered)
                return Standing.UNPROVEN;
            if (revision == null || head == null)
                return Standing.UNKNOWN_CODE;

            return revision.equalsIgnoreCase(head) ? Standing.CLEAN : Standing.OLD_CODE;
        }
    }
}
