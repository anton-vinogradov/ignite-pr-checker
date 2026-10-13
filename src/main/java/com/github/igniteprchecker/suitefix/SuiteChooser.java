package com.github.igniteprchecker.suitefix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the suite for a test class no suite runs, by a rule: the suite that lists the class of the module nearest to
 * it, and that class as the neighbour to stand next to. Nearest is the longest package in common, then the longest
 * start of the simple name in common, as IgniteCacheQueryNodeRestartTxSelfTest is to
 * IgniteCacheQueryNodeRestartSelfTest2. Ignite spreads one package over numbered suites (IgniteCacheTestSuite to
 * IgniteCacheTestSuite18), and the suite of the closest name is the right one more often than the suite that lists
 * the most classes of the package; the most classes only break a tie, then the suite's path.
 */
final class SuiteChooser {
    private static final Comparator<Nearest> NEAREST_FIRST = Comparator.comparingInt(Nearest::depth)
        .thenComparingInt(Nearest::name).thenComparingInt(Nearest::count).reversed()
        .thenComparing(n -> n.suite().path());

    private SuiteChooser() {
    }

    /** The suite for class {@code fqcn} among {@code suites}; empty when none lists a class of a package near it. */
    static Optional<Choice> choose(String fqcn, List<TestSuites.Suite> suites) {
        List<Nearest> found = new ArrayList<>();
        for (TestSuites.Suite s : suites)
            nearestIn(s, fqcn).ifPresent(found::add);
        found.sort(NEAREST_FIRST);
        if (found.isEmpty() || found.get(0).depth() == 0)
            return Optional.empty();

        Nearest pick = found.get(0);
        boolean byName = found.stream().anyMatch(n -> n.depth() == pick.depth() && n.count() > pick.count());

        return Optional.of(new Choice(pick.suite(), pick.neighbour(), reason(fqcn, pick, byName)));
    }

    /**
     * The class {@code suite} lists nearest to {@code fqcn}, by package and then by name; among names as near, the
     * one just before it in name order, where the suite would have it if it is sorted.
     */
    private static Optional<Nearest> nearestIn(TestSuites.Suite suite, String fqcn) {
        List<String> pkg = packageOf(fqcn);
        String simple = simpleName(fqcn);
        int depth = -1;
        List<String> near = new ArrayList<>();
        for (TestSuites.Member m : suite.members()) {
            if (m.fqcn() == null)
                continue;

            int d = common(pkg, packageOf(m.fqcn()));
            if (d > depth) {
                depth = d;
                near.clear();
            }
            if (d == depth)
                near.add(m.name());
        }
        if (near.isEmpty())
            return Optional.empty();

        int name = near.stream().mapToInt(n -> prefix(simple, n)).max().orElseThrow();
        List<String> closest = near.stream().filter(n -> prefix(simple, n) == name).sorted().toList();
        String neighbour = closest.stream().filter(n -> n.compareTo(simple) < 0).reduce((a, b) -> b)
            .orElse(closest.get(0));

        return Optional.of(new Nearest(suite, neighbour, depth, name, near.size(), pkg.subList(0, depth)));
    }

    private static String reason(String fqcn, Nearest pick, boolean byName) {
        String classes = pick.count() == 1 ? "1 class" : pick.count() + " classes";
        String where = pick.depth() == packageOf(fqcn).size() ? "it lists " + classes + " of the same package"
            : "no suite lists a class of the same package, and it lists " + classes + " under `"
                + String.join(".", pick.pkg()) + "`, the nearest";

        return byName ? where + ", and `" + pick.neighbour() + "` has the closest name" : where;
    }

    private static List<String> packageOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');

        return dot < 0 ? List.of() : List.of(fqcn.substring(0, dot).split("\\."));
    }

    private static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    /** How many leading parts of two packages are the same. */
    private static int common(List<String> a, List<String> b) {
        int n = 0;
        while (n < a.size() && n < b.size() && a.get(n).equals(b.get(n)))
            n++;

        return n;
    }

    /** How many leading characters of two names are the same. */
    private static int prefix(String a, String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n))
            n++;

        return n;
    }

    /** The pick: the suite, the class it lists to put the new one next to, and why, in the words of the rule. */
    record Choice(TestSuites.Suite suite, String neighbour, String reason) {
    }

    /**
     * A suite's class nearest to the new one: the packages' parts in common ({@code depth}, the shared package
     * {@code pkg}), the names' characters in common, and how many classes of the suite share that much package.
     */
    private record Nearest(TestSuites.Suite suite, String neighbour, int depth, int name, int count,
        List<String> pkg) {
    }
}
