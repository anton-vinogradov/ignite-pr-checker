package com.github.igniteprchecker.analysis.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The broken and never-run suites of a verdict grouped by what broke them, as the PR page, the PR comment, the visa
 * and the auto re-runs all tell them. Listed one by one, they hid the cause: PR 13655 showed 62 broken suites, 60 of
 * them one ci2 glitch (the artifacts of a Build that had passed could not be fetched, written two ways), and PR
 * 13583, whose Build failed, listed the Build fifth among its victims and read "147 suites ran"; the auto re-run
 * re-queued such victims, each pulling a new Build.
 *
 * <p>{@code root} is the failed run of an {@link Kind#UPSTREAM} group while it is still broken, null once a re-run
 * of it passed; {@code upstream} names that run, null when the suites did not say which run they needed.
 * {@code suites} are the group's broken suites, the root aside, {@code cancelled} the suites it kept from
 * running. {@code rerun} is what a re-run of the group re-queues: the root alone for a failed upstream, and every
 * suite of the other groups; never a suite that failed to compile, as the same code fails the same way.
 */
public record BrokenGroup(Kind kind, String title, BrokenSuite root, Upstream upstream, List<BrokenSuite> suites,
    List<CancelledSuite> cancelled, List<String> rerun) {
    /** "build #30372 [id 9391124]" and "build with id: 9391124" name the same run in two of TeamCity's messages. */
    private static final Pattern RUN_REF = Pattern.compile("build #\\d+ \\[id \\d+]|build with id: \\d+");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final String RUN_ALL = "/run-all";

    /** What broke the suites of a group. */
    public enum Kind {
        /** A run the other suites need failed (the build step): they did not run. */
        UPSTREAM,

        /** ci2 could not hand the suites the artifacts of a run that passed: a re-run usually gets them. */
        ARTIFACTS,

        /** Suites that broke the same way, run numbers aside. */
        PROBLEM
    }

    /**
     * The groups of a verdict: failed upstream runs first, those still broken before those a re-run has passed
     * since, then the artifact glitch, then the other problems, the most common first.
     */
    public static List<BrokenGroup> of(AnalysisResult r) {
        List<BrokenGroup> out = new ArrayList<>();
        Set<BrokenSuite> placed = new LinkedHashSet<>();
        Set<CancelledSuite> kept = new LinkedHashSet<>();

        Map<String, Upstream> upstreams = new LinkedHashMap<>();
        Stream.concat(r.brokenSuites().stream().filter(BrokenSuite::keptFromRunning).map(BrokenSuite::failedUpstream),
                r.cancelledSuites().stream().map(CancelledSuite::failedUpstream))
            .filter(u -> u != null && u.suite() != null)
            .forEach(u -> upstreams.putIfAbsent(u.suite(), u));

        for (Upstream u : upstreams.values()) {
            BrokenSuite root = r.brokenSuites().stream().filter(s -> u.suite().equals(s.suite())).findFirst()
                .orElse(null);
            List<BrokenSuite> victims = r.brokenSuites().stream()
                .filter(s -> s != root && s.keptFromRunning() && s.failedUpstream() != null
                    && u.suite().equals(s.failedUpstream().suite()))
                .toList();
            List<CancelledSuite> neverRan = r.cancelledSuites().stream()
                .filter(c -> c.failedUpstream() != null && u.suite().equals(c.failedUpstream().suite()))
                .toList();
            if (root != null)
                placed.add(root);
            placed.addAll(victims);
            kept.addAll(neverRan);
            out.add(upstream(r, u, root, victims, neverRan));
        }
        out.sort(Comparator.comparing(g -> g.root() == null));

        // A verdict kept by an older release does not say which run its suites needed.
        List<BrokenSuite> unnamed = unplaced(r, placed, BrokenSuite::keptFromRunning);
        if (!unnamed.isEmpty()) {
            placed.addAll(unnamed);
            out.add(new BrokenGroup(Kind.UPSTREAM, suites(unnamed.size()) + " failed only because a run "
                + (unnamed.size() == 1 ? "it needs" : "they need") + " failed", null, null, unnamed, List.of(),
                List.of()));
        }

        List<BrokenSuite> artifacts = unplaced(r, placed, BrokenSuite::artifactsUnavailable);
        if (!artifacts.isEmpty()) {
            placed.addAll(artifacts);
            out.add(new BrokenGroup(Kind.ARTIFACTS, "ci2 glitch: artifacts unavailable (" + suites(artifacts.size())
                + ")", null, null, artifacts, List.of(), suitesOf(artifacts)));
        }

        Map<String, List<BrokenSuite>> byProblem = new LinkedHashMap<>();
        for (BrokenSuite s : unplaced(r, placed, s -> true))
            byProblem.computeIfAbsent(problemKey(s), k -> new ArrayList<>()).add(s);
        byProblem.values().stream()
            .sorted(Comparator.comparingInt((List<BrokenSuite> l) -> l.size()).reversed())
            .forEach(l -> out.add(new BrokenGroup(Kind.PROBLEM, titleOf(l), null, null, l, List.of(), suitesOf(l))));

        return out;
    }

    /** What the groups re-queue between them, each suite once, in the groups' order. */
    public static List<String> rerunSuites(List<BrokenGroup> groups) {
        return groups.stream().flatMap(g -> g.rerun().stream()).distinct().toList();
    }

    /** The group among the reasons a run cannot prove a PR clean: its title without the advice. */
    public String caveat() {
        int advice = title.indexOf("; ");

        return advice < 0 ? title : title.substring(0, advice);
    }

    /** How a suite is named in a sentence: TeamCity sorts the build step first by naming it "> Build". */
    public static String nameOf(String suiteName, String suite) {
        String name = suiteName == null ? "" : suiteName.replaceFirst("^[>\\s]+", "");

        return name.isBlank() ? suite : name;
    }

    private static BrokenGroup upstream(AnalysisResult r, Upstream u, BrokenSuite root, List<BrokenSuite> victims,
        List<CancelledSuite> neverRan) {
        String name = root != null ? nameOf(root.suiteName(), root.suite()) : nameOf(u.name(), u.suite());
        int n = victims.size() + neverRan.size();
        String title;
        if (root == null) {
            title = name + " failed in this run and passed on a re-run since — " + suites(n) + thatNeedIt(n)
                + " never ran; " + RUN_ALL + " to run them";
        }
        else if (nothingElseRan(r, n)) {
            title = name + " failed — nothing else ran; fix the build, then " + RUN_ALL;
        }
        else
            title = name + " failed — " + suites(n) + thatNeedIt(n) + " did not run; fix the build, then " + RUN_ALL;

        List<String> rerun = root != null && !root.compileError() ? List.of(root.suite()) : List.of();

        return new BrokenGroup(Kind.UPSTREAM, title, root, u, victims, neverRan, rerun);
    }

    /**
     * Whether the failed run and the {@code victims} suites it kept from running are all the chain had: no test
     * result, no other broken, shrunk or reused suite, and no suite that ran besides it.
     */
    private static boolean nothingElseRan(AnalysisResult r, int victims) {
        boolean noResults = Stream.of(r.blockers(), r.watch(), r.filtered(), r.unverified(), r.shrunkSuites(),
            r.unstableSuites()).allMatch(List::isEmpty);
        long others = r.brokenSuites().size() + r.cancelledSuites().size() - 1 - victims;

        return noResults && others == 0 && r.suitesRan() <= 1 && r.suitesReused() == 0;
    }

    private static List<BrokenSuite> unplaced(AnalysisResult r, Set<BrokenSuite> placed, Predicate<BrokenSuite> which) {
        return r.brokenSuites().stream().filter(s -> !placed.contains(s) && which.test(s)).toList();
    }

    /** The problems with the run numbers left out, and the two ways TeamCity names a run made one. */
    private static String problemKey(BrokenSuite s) {
        return DIGITS.matcher(runsMadeOne(problemsOf(s))).replaceAll("#");
    }

    private static String problemsOf(BrokenSuite s) {
        return s.problems() == null ? "" : String.join(" · ", s.problems());
    }

    /**
     * What the suites of a group broke with: the text of one when they all say the same, and else their shared text
     * with "N" for each number they do not share. The numbers of one suite are wrong for the others: the shortfall of
     * a hung suite, the run TeamCity compared it with.
     */
    private static String titleOf(List<BrokenSuite> suites) {
        String first = problemsOf(suites.get(0));
        if (suites.stream().allMatch(s -> problemsOf(s).equals(first)))
            return first;

        List<List<String>> numbers = suites.stream().map(s -> numbersIn(runsMadeOne(problemsOf(s)))).toList();
        Matcher m = DIGITS.matcher(runsMadeOne(first));
        StringBuilder title = new StringBuilder();
        for (int i = 0; m.find(); i++) {
            int at = i;
            boolean shared = numbers.stream().allMatch(n -> n.size() > at && n.get(at).equals(m.group()));
            m.appendReplacement(title, shared ? m.group() : "N");
        }
        m.appendTail(title);

        return title.toString();
    }

    /** The text with each of the two ways TeamCity names a run made "build". */
    private static String runsMadeOne(String text) {
        return RUN_REF.matcher(text).replaceAll("build");
    }

    private static List<String> numbersIn(String text) {
        return DIGITS.matcher(text).results().map(MatchResult::group).toList();
    }

    /** The suites a re-run of a group re-queues. */
    private static List<String> suitesOf(List<BrokenSuite> suites) {
        return suites.stream().filter(s -> !s.compileError()).map(BrokenSuite::suite)
            .filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private static String suites(int n) {
        return n + (n == 1 ? " suite" : " suites");
    }

    /** "1 suite that needs it", "2 suites that need it". */
    private static String thatNeedIt(int n) {
        return n == 1 ? " that needs it" : " that need it";
    }
}
