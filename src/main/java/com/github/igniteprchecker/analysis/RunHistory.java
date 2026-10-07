package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test's latest runs in one suite, newest first, cut down to what classification reads: whether each
 * passed or failed, the conditions it ran under and, for a run on a PR branch, which PR. One character
 * per run keeps the cache and its disk snapshot small; the few distinct conditions are stored once.
 *
 * <p>Only runs with a result count. TeamCity lists a run where the test was ignored with status UNKNOWN:
 * the test did not run there, so it is no evidence either way. Counted, a test ignored for most of the
 * window read "not seen failing in 100 master run(s)", and one ignored in all of it never got "no master
 * history".
 *
 * @param results 'P' or 'F' per run.
 * @param envOf   per run, the position of its conditions in {@code envs}, as {@code 'a' + position}.
 * @param envs    the distinct conditions the runs were made under.
 * @param prs     per run, the PR whose branch it ran on; empty for master runs.
 */
record RunHistory(String results, String envOf, List<RunEnv> envs, List<Integer> prs) {
    private static final Pattern PR_BRANCH = Pattern.compile("pull/(\\d+)/head");

    /** Master runs, as TeamCity lists them newest first. */
    static RunHistory ofMaster(List<TcModel.TestOccurrence> newestFirst) {
        return of(newestFirst, false);
    }

    /** Runs on PR branches, newest first; a run on any other branch is left out. */
    static RunHistory ofPrBranches(List<TcModel.TestOccurrence> newestFirst) {
        return of(newestFirst, true);
    }

    private static RunHistory of(List<TcModel.TestOccurrence> runs, boolean onPrBranches) {
        StringBuilder results = new StringBuilder();
        StringBuilder envOf = new StringBuilder();
        List<RunEnv> envs = new ArrayList<>();
        List<Integer> prs = new ArrayList<>();

        for (TcModel.TestOccurrence o : runs) {
            boolean failed = "FAILURE".equals(o.status());
            if (!failed && !"SUCCESS".equals(o.status()))
                continue;

            int pr = onPrBranches ? prOf(o.build()) : 0;
            if (onPrBranches && pr == 0)
                continue;

            RunEnv env = RunEnv.of(o.build());
            if (!envs.contains(env))
                envs.add(env);

            results.append(failed ? 'F' : 'P');
            envOf.append((char) ('a' + envs.indexOf(env)));
            if (onPrBranches)
                prs.add(pr);
        }

        return new RunHistory(results.toString(), envOf.toString(), List.copyOf(envs), List.copyOf(prs));
    }

    private static int prOf(TcModel.BuildRef build) {
        if (build == null || build.branchName() == null)
            return 0;

        Matcher m = PR_BRANCH.matcher(build.branchName());

        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Every run, whatever it ran under. */
    HistoryStats all() {
        return stats(i -> true);
    }

    /** How many of the newest runs in a row failed, whatever they ran under. */
    int failStreak() {
        int streak = 0;
        while (streak < results.length() && results.charAt(streak) == 'F')
            streak++;

        return streak;
    }

    /** The runs made on the same JDK as {@code env}. */
    HistoryStats onJdkOf(RunEnv env) {
        return stats(i -> envAt(i).sameJdk(env));
    }

    /** Runs on other PRs' branches than {@code pr}'s, made under the same conditions as {@code env}. */
    HistoryStats otherPrsAs(RunEnv env, int pr) {
        return stats(i -> prs.get(i) != pr && envAt(i).sameAs(env));
    }

    /**
     * The scale factor the failing runs on {@code env}'s JDK ran at, when every one of them ran at a
     * known scale factor other than {@code env}'s; otherwise null.
     */
    String failingOnlyAtScaleOtherThan(RunEnv env) {
        String scale = null;
        for (int i = 0; i < results.length(); i++) {
            RunEnv run = envAt(i);
            if (results.charAt(i) != 'F' || !run.sameJdk(env))
                continue;
            if (!run.otherScaleThan(env))
                return null;
            if (scale == null)
                scale = run.scale();
        }

        return scale;
    }

    /** Whether TeamCity named the JDK of any run, so that a selection by JDK actually selected something. */
    boolean knowsJdk() {
        return envs.stream().anyMatch(e -> e.jdk() != null);
    }

    private RunEnv envAt(int run) {
        return envs.get(envOf.charAt(run) - 'a');
    }

    private HistoryStats stats(IntPredicate counted) {
        int runs = 0;
        int fails = 0;
        int greenStreak = 0;
        boolean green = true;
        Set<Integer> failingPrs = new HashSet<>();

        for (int i = 0; i < results.length(); i++) {
            if (!counted.test(i))
                continue;

            boolean failed = results.charAt(i) == 'F';
            runs++;
            if (failed) {
                fails++;
                green = false;
                if (!prs.isEmpty())
                    failingPrs.add(prs.get(i));
            }
            else if (green)
                greenStreak++;
        }

        return new HistoryStats(runs, fails, greenStreak, failingPrs.size());
    }
}
