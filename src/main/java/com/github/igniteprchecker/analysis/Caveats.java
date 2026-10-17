package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import java.util.ArrayList;
import java.util.List;
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

    private static final String NEWER_RUN_GOING = "a newer run is still going — its unfinished suites can still fail";

    private Caveats() {
    }

    /**
     * Reasons this run cannot prove the PR clean, as plain sentences; empty when it can.
     * {@code commitsAhead} is null when nobody checked whether the PR head has moved.
     */
    public static List<String> of(AnalysisResult r, Integer commitsAhead) {
        Gaps g = Gaps.of(r);
        List<String> out = new ArrayList<>(g.upstream());
        if (g.neverRan() > 0)
            out.add("the RunAll was interrupted — " + suites(g.neverRan()) + " never ran");
        if (g.broken() > 0) {
            out.add(suites(g.broken()) + (g.broken() == 1 ? " has" : " have") + " no reliable result ("
                + causesOf(g.causes()) + ")");
        }
        if (g.shrunk() > 0) {
            out.add(suites(g.shrunk()) + " ran fewer tests than the same " + (g.shrunk() == 1 ? "suite" : "suites")
                + " on master");
        }
        if (g.unchecked() > 0) {
            out.add(g.unchecked() + " failed test" + (g.unchecked() == 1 ? "" : "s")
                + " could not be checked (TeamCity errors)");
        }
        if (g.live())
            out.add(NEWER_RUN_GOING);
        if (commitsAhead != null && commitsAhead > 0)
            out.add(pushedSince(commitsAhead));

        return out;
    }

    /**
     * The caveats as a key telling two verdicts of one revision apart, in the words of the first release that posted
     * visas under such keys: the key never follows the words {@link #of} shows, so a visa an older release posted
     * still matches, and no second visa of the same verdict goes out when the words change. The broken suites'
     * causes are left out, as TeamCity words them with the numbers of each run.
     */
    public static List<String> keyOf(AnalysisResult r) {
        Gaps g = Gaps.of(r);
        List<String> out = new ArrayList<>(g.upstream());
        if (g.neverRan() > 0)
            out.add("the RunAll was interrupted — " + g.neverRan() + " suite(s) never ran");
        if (g.broken() > 0)
            out.add(g.broken() + " suite(s) have no reliable result (" + UNNAMED_CAUSES + ")");
        if (g.shrunk() > 0)
            out.add(g.shrunk() + " suite(s) ran far fewer tests than the same suites on master");
        if (g.unchecked() > 0)
            out.add(g.unchecked() + " failed test(s) could not be checked (TeamCity errors)");
        if (g.live())
            out.add(NEWER_RUN_GOING);

        return out;
    }

    private static String suites(int n) {
        return n + (n == 1 ? " suite" : " suites");
    }

    /**
     * What a run left uncovered, counted once for {@link #of} and {@link #keyOf}: the caveats of the failed runs
     * other suites needed, how many suites never ran for other reasons, how many are broken and why, how many ran
     * far fewer tests than master, how many failed tests went unchecked, and whether a newer run is still going.
     */
    private record Gaps(List<String> upstream, int neverRan, int broken, List<BrokenGroup> causes, int shrunk,
        int unchecked, boolean live) {
        static Gaps of(AnalysisResult r) {
            List<BrokenGroup> groups = BrokenGroup.of(r);
            List<BrokenGroup> upstreams = groups.stream().filter(g -> g.upstream() != null).toList();
            int keptFromRunning = upstreams.stream().mapToInt(g -> g.cancelled().size()).sum();
            List<BrokenGroup> causes = groups.stream().filter(g -> g.upstream() == null).toList();

            return new Gaps(upstreams.stream().map(BrokenGroup::caveat).toList(),
                r.interrupted() ? Math.max(0, r.canceledSuites() - keptFromRunning) : 0,
                causes.stream().mapToInt(g -> g.suites().size()).sum(), causes, r.shrunkSuites().size(),
                r.unverified().size(), r.live());
        }
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

    /** The caveat of a run the PR's head has since moved past by {@code commits} commits. */
    public static String pushedSince(int commits) {
        return commits + (commits == 1 ? " commit" : " commits") + " pushed since this run — it tested older code";
    }

    /** The caveat of a run the PR's head has since moved past by a count not known yet. */
    public static String headMoved() {
        return "new commits pushed since this run — it tested older code";
    }

    /** Whether the run covers enough for an empty blocker list to mean something. */
    public static boolean proven(AnalysisResult r) {
        return of(r, null).isEmpty();
    }

    /**
     * How the verdict stands, by the one rule the PR list's tick, the page's head, the visa and the PR comment follow.
     * {@code commitsAhead} is how far the PR's head has moved past the run; null when unknown, and the run is then
     * taken for the PR's code.
     */
    public static Standing standing(AnalysisResult r, Integer commitsAhead) {
        return standing(r.blockers().size(), r.watch().size(), proven(r),
            commitsAhead != null && commitsAhead > 0 ? Code.OLD : Code.CURRENT);
    }

    private static Standing standing(int blockers, int watch, boolean covered, Code code) {
        if (blockers > 0)
            return Standing.BLOCKERS;
        if (watch > 0)
            return Standing.WATCH;
        if (!covered)
            return Standing.UNPROVEN;

        return switch (code) {
            case CURRENT -> Standing.CLEAN;
            case OLD -> Standing.OLD_CODE;
            case UNKNOWN -> Standing.UNKNOWN_CODE;
        };
    }

    /** Whether the run tested the code the PR has now, as far as the caller knows. */
    private enum Code {
        CURRENT, OLD, UNKNOWN
    }

    /** How a verdict stands at a glance: the PR list's badge, the page's head, the visa and the PR comment say it. */
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
            Code code = revision == null || head == null ? Code.UNKNOWN
                : revision.equalsIgnoreCase(head) ? Code.CURRENT : Code.OLD;

            return standing(blockers, watch, covered, code);
        }
    }
}
