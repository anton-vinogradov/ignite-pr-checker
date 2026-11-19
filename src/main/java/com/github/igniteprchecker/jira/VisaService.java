package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.MasterSuites;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Composes the verdict of a run for the PR comment, in GitHub's markdown, and for the visa, in JIRA's wiki markup:
 * the same words in both. Its first line is the answer, with a link to the checker's page: "No blockers" only when
 * the run behind it covered the PR ({@link Caveats#standing}, which the PR list's tick and the page's head go by
 * too). Then comes what the answer rests on, each fact once: the tests, the suites that gave no result, each with
 * how it does on master, as a suite that ends the same way there says nothing of the PR. A line that says what to do
 * next closes it, and a small one names the run. The comment used to say the same thing three times over nine lines:
 * as a caveat, as a list of broken suites and as a verdict that pointed back at both.
 */
@Component
public class VisaService {
    /** What each label of a verdict means and what to do about it: the table at the top of the feature tour. */
    public static final String GLOSSARY = "https://github.com/" + GithubClient.SELF_REPO
        + "/blob/main/docs/features.md#verdict-glossary";

    /** How the line that marks a verdict comment superseded starts. */
    private static final String SUPERSEDED = "🔁 _Superseded";

    /** How the last line of a comment whose re-runs a newer run cut short starts, after its emoji. */
    private static final String REPLACED = "Re-runs stopped: RunAll ";

    /** How many suites a line names before "and N more", and a list names one by one before it goes by cause. */
    private static final int NAMES_SHOWN = 5;

    /** How many suites the last line names before it says "the suites above". */
    private static final int NEXT_NAMED = 3;

    private final TeamcityProperties tc;

    private final String publicUrl;

    private final String repo;

    /** Where Ignite's own check of the PR's test classes comes from; null when not asked. */
    private final PrTestRuns prTests;

    /** How a suite does on master; null when not asked, and a broken suite then says nothing of master. */
    private final MasterSuites masters;

    @Autowired
    public VisaService(TeamcityProperties tc,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl, GithubProperties github,
        PrTestRuns prTests, MasterSuites masters) {
        this.tc = tc;
        this.publicUrl = publicUrl;
        this.repo = github.repo();
        this.prTests = prTests;
        this.masters = masters;
    }

    /** A verdict that says nothing of the PR's test classes in no test suite, nor of master's runs of a suite. */
    public VisaService(TeamcityProperties tc, String publicUrl) {
        this(tc, publicUrl, new GithubProperties(null, null, null), null, null);
    }

    /** The verdict in GitHub markdown, for a PR comment mirror of the visa. */
    public String composeMarkdown(int pr, AnalysisResult r) {
        return composeMarkdown(pr, r, null);
    }

    public String composeMarkdown(int pr, AnalysisResult r, Integer commitsAhead) {
        return composeMarkdown(pr, r, commitsAhead, null);
    }

    /**
     * The verdict in GitHub markdown. {@code commitsAhead} is how many commits the PR head is ahead
     * of the analysed run (null when unknown) — a verdict for superseded code says so. {@code sha} is
     * the commit the run tested, named in the last line; null when TeamCity did not say.
     */
    public String composeMarkdown(int pr, AnalysisResult r, Integer commitsAhead, String sha) {
        return verdict(Ink.MARKDOWN, pr, r, commitsAhead, sha);
    }

    public String compose(int pr, AnalysisResult r) {
        return compose(pr, r, null);
    }

    public String compose(int pr, AnalysisResult r, Integer commitsAhead) {
        return compose(pr, r, commitsAhead, null);
    }

    /** The same verdict in JIRA wiki markup; see {@link #composeMarkdown(int, AnalysisResult, Integer, String)}. */
    public String compose(int pr, AnalysisResult r, Integer commitsAhead, String sha) {
        return verdict(Ink.WIKI, pr, r, commitsAhead, sha);
    }

    /**
     * The line on the test classes Ignite's own abandoned-tests check finds in no test suite, which CI never runs, in
     * both markups after an empty line; empty when it finds none. The verdict above it stays as it is.
     */
    Ending notInAnySuite(int pr) {
        Optional<AbandonedTestsCheck.Outcome> check = prTests == null ? Optional.empty() : prTests.notInAnySuite(pr);
        if (check.isEmpty())
            return new Ending("", "");

        List<String> classes = check.get().classes().stream().map(c -> c.substring(c.lastIndexOf('.') + 1)).toList();
        boolean one = classes.size() == 1;
        String what = one ? "1 test class is in no test suite" : classes.size() + " test classes are in no test suite";
        String says = ", so CI never runs " + (one ? "it" : "them") + ": ";
        String todo = ". Add " + (one ? "it" : "each") + " to a test suite or mark it ";
        String name = "Ignite's abandoned-tests check";
        String url = check.get().url();
        String sha = shortSha(check.get().sha());

        return new Ending(
            "\n\n⚠️ **" + what + "**" + says + names(classes.stream().map(c -> "`" + c + "`").toList()) + " ("
                + (url == null ? name : "[" + name + "](" + url + ")") + " on `" + sha + "`)" + todo + "`@Ignore`.",
            "\n\n(!) *" + what + "*" + says + names(classes.stream().map(c -> "{{" + c + "}}").toList()) + " ("
                + (url == null ? name : "[" + name + "|" + url + "]") + " on {{" + sha + "}})" + todo + "{{@Ignore}}.");
    }

    private String verdict(Ink ink, int pr, AnalysisResult r, Integer commitsAhead, String sha) {
        String base = tcBase();
        TestGroups tests = new TestGroups(base, page(pr));
        Broken broken = Broken.of(r, masters);
        Found found = found(r, broken);
        // A mixed run says of itself which commits it tested: its suites may have run on the PR's head.
        boolean old = r.otherCodeRuns().isEmpty() && commitsAhead != null && commitsAhead > 0;

        List<String> blocks = new ArrayList<>();
        if (old) {
            blocks.add(head(ink, pr, "⏩", "(!)", "Outdated: " + count(commitsAhead, "commit") + " pushed since this run"));
            blocks.add("Of the code it tested: " + found.text() + ".");
        }
        else
            blocks.add(head(ink, pr, found.icon(), found.wikiIcon(), found.text()));

        for (BrokenGroup g : broken.roots()) {
            String lines = rootLines(g, ink::text).stripTrailing();
            String title = found.said() == Said.ROOT ? "" : ink.text(g.caveat());
            if (!title.isEmpty() || !lines.isEmpty())
                blocks.add(title.isEmpty() || lines.isEmpty() ? title + lines : title + ":\n" + lines);
        }
        if (!r.blockers().isEmpty())
            blocks.add(list(ink, tests, r.blockers(), true));
        if (!r.watch().isEmpty()) {
            blocks.add(under(found.said() == Said.WATCH ? null : count(r.watch().size(), "test")
                + " started failing on this code, not proven " + (r.watch().size() == 1 ? "a blocker" : "blockers")
                + " yet", list(ink, tests, r.watch(), true)));
        }
        if (!r.unverified().isEmpty()) {
            blocks.add(under(found.said() == Said.UNVERIFIED ? null : count(r.unverified().size(), "failed test")
                + " could not be checked (TeamCity errors)", list(ink, tests, r.unverified(), false)));
        }
        if (!broken.rerun().isEmpty()) {
            blocks.add(under(found.said() == Said.RERUN ? null : count(broken.rerun().size(), "suite")
                + " gave no result", suiteLines(ink, broken, broken.rerun())));
        }
        if (!broken.same().isEmpty()) {
            blocks.add(broken.rerun().isEmpty()
                ? under(count(broken.same().size(), "suite") + " gave no result, same as on master",
                    suiteLines(ink, broken, broken.same()))
                : "Also without a result, same as on master: " + ink.text(names(broken.same().stream()
                    .map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())).toList())) + ".");
        }
        if (!r.shrunkSuites().isEmpty()) {
            blocks.add(under(found.said() == Said.SHRUNK ? null : count(r.shrunkSuites().size(), "suite")
                + " ran fewer tests than on master", r.shrunkSuites().stream().map(s -> "- " + ink.text(s.suiteName())
                + ": " + ranOfMasters(s) + " (" + ink.icon("−", "-") + s.dropPct() + "%)")
                .reduce((a, b) -> a + "\n" + b).orElseThrow()));
        }
        int neverRan = Caveats.neverRan(r);
        if (neverRan > 0 && found.said() != Said.INTERRUPTED)
            blocks.add("The RunAll was interrupted: " + count(neverRan, "suite") + " never ran.");
        if (r.live() && found.said() != Said.GOING)
            blocks.add(going(r, true) + ": its unfinished suites can still fail.");
        if (!r.otherCodeRuns().isEmpty()) {
            String mixed = ink.text(Caveats.mixed(r));
            blocks.add(Character.toUpperCase(mixed.charAt(0)) + mixed.substring(1) + ".");
        }

        String next = next(ink, r, broken, old);
        if (next != null)
            blocks.add(ink.bold("Next:") + " " + next);

        Ending inNoSuite = notInAnySuite(pr);
        if (!inNoSuite.markdown().isEmpty())
            blocks.add(ink.icon(inNoSuite.markdown(), inNoSuite.wiki()).strip());

        List<TestVerdict> tagged = Stream.concat(r.blockers().stream(), r.watch().stream()).toList();
        String legend = ink == Ink.MARKDOWN ? tests.markdownLegend(tagged) : tests.wikiLegend(tagged);
        if (!legend.isEmpty())
            blocks.add(legend);

        String run = "RunAll " + ink.link(Long.toString(r.buildId()), base + "build/" + r.buildId())
            + (sha == null ? "" : " · " + ink.link(shortSha(sha), commitUrl(pr, sha)))
            + " · " + ink.link("glossary", GLOSSARY);
        blocks.add(ink.icon("<sub>" + run + "</sub>", "_" + run + "_"));

        return String.join("\n\n", blocks);
    }

    /**
     * The first line: the answer, and where the whole verdict is. A visa goes out under its author's own name, so it
     * says whose verdict it is; a PR comment comes from the checker's account.
     */
    private String head(Ink ink, int pr, String icon, String wikiIcon, String text) {
        return ink.icon(icon, wikiIcon) + " " + ink.bold(text) + (ink == Ink.MARKDOWN
            ? " · " + ink.link("full verdict", page(pr))
            : " — " + ink.link("Ignite PR Checker", page(pr)) + " verdict for PR " + pr);
    }

    /**
     * What the run found, in a line: the worst of it, and whether anyone has something to do. The magnifier is for a
     * run that blames nothing but proves nothing either while it leaves the author nothing to do: its suites broke
     * as they do on master, or it is still going.
     */
    private static Found found(AnalysisResult r, Broken broken) {
        Caveats.Standing standing = Caveats.standing(r, null);
        if (standing == Caveats.Standing.BLOCKERS)
            return new Found("❌", "(x)", blockersIn(r.blockers()) + onOneRun(r.blockers()), Said.NOTHING);
        if (standing == Caveats.Standing.WATCH) {
            return new Found("⚠️", "(!)", "No proven blockers yet, " + needsRerun(r.watch().size(), "test"),
                Said.WATCH);
        }
        if (standing == Caveats.Standing.CLEAN)
            return new Found("✅", "(/)", "No blockers", Said.NOTHING);
        if (broken.nothingElseRan(r) && broken.roots().get(0).root() != null)
            return new Found("🛑", "(x)", "No verdict: " + rootName(broken.roots().get(0)) + " failed", Said.ROOT);
        if (r.live()) {
            return new Found("🔎", "(?)", "No blockers so far, " + Character.toLowerCase(going(r, false).charAt(0))
                + going(r, false).substring(1), Said.GOING);
        }

        String said = "No blockers found, ";
        if (!broken.roots().isEmpty()) {
            BrokenGroup first = broken.roots().get(0);

            return new Found("⚠️", "(!)", said + (first.root() != null ? rootName(first) + " failed"
                : count(first.suites().size() + first.cancelled().size(), "suite") + " never ran"), Said.NOTHING);
        }
        if (!broken.rerun().isEmpty()) {
            int n = broken.rerun().size();

            return new Found("⚠️", "(!)", said + (broken.rerunnable().isEmpty() ? count(n, "suite") + " gave no result"
                : needsRerun(n, "suite")), Said.RERUN);
        }

        int neverRan = Caveats.neverRan(r);
        if (neverRan > 0)
            return new Found("⚠️", "(!)", said + count(neverRan, "suite") + " never ran", Said.INTERRUPTED);
        if (!r.shrunkSuites().isEmpty()) {
            return new Found("⚠️", "(!)", said + count(r.shrunkSuites().size(), "suite")
                + " ran fewer tests than on master", Said.SHRUNK);
        }
        if (!r.unverified().isEmpty()) {
            return new Found("⚠️", "(!)", said + count(r.unverified().size(), "failed test")
                + " could not be checked", Said.UNVERIFIED);
        }
        if (!r.otherCodeRuns().isEmpty())
            return new Found("⚠️", "(!)", "No blockers found in a mixed run", Said.NOTHING);

        return new Found("🔎", "(?)", "No blockers found", Said.NOTHING);
    }

    /**
     * What to do next, the most pressing thing alone; null for a clean verdict, which asks for nothing. A suite that
     * failed to compile is not re-run: the same code fails the same way.
     */
    private static String next(Ink ink, AnalysisResult r, Broken broken, boolean old) {
        if (old)
            return "run RunAll on the current head.";
        if (r.live())
            return "wait for " + (r.liveBuildId() == r.buildId() ? "this run" : "the newer run") + " to finish.";
        if (!r.blockers().isEmpty()) {
            return "fix the blockers, push, and run RunAll again" + ink.icon(" — a `/run-all` comment here does it"
                + " when PR commands are on in the checker's ⚙.", ".");
        }
        if (!broken.roots().isEmpty()) {
            return broken.roots().get(0).root() == null ? "run RunAll again: the suites that never ran need it."
                : "fix the build and run RunAll again.";
        }
        if (!r.watch().isEmpty()) {
            return "re-run " + named(ink, r.watch().stream().map(t -> t.suiteName() == null ? t.suite()
                : t.suiteName()).distinct().toList()) + ".";
        }
        if (!broken.rerun().isEmpty()) {
            List<BrokenSuite> again = broken.rerunnable();
            if (again.isEmpty())
                return "fix what fails to compile.";

            // Only a suite that is fine on master points at the PR when it breaks again.
            return "re-run " + named(ink, again.stream().map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())).toList())
                + (again.stream().allMatch(s -> broken.onMaster(s) == MasterSuites.OnMaster.FINE)
                    ? ". If " + (again.size() == 1 ? "it ends" : "they end") + " the same way again, look for the"
                        + " cause in this PR." : ".");
        }
        if (Caveats.neverRan(r) > 0)
            return "run RunAll again to cover the suites that never ran.";
        if (!r.shrunkSuites().isEmpty())
            return "find out why they ran fewer tests: a test that never ran can't fail.";
        if (!r.unverified().isEmpty())
            return "ask for the verdict again later, with ↻ on the checker's page.";
        if (!r.otherCodeRuns().isEmpty())
            return "run RunAll on the PR's head.";

        return broken.same().isEmpty() ? null : "nothing to do for this PR.";
    }

    /** "This run is still going", or "A newer run is still going". */
    private static String going(AnalysisResult r, boolean sentence) {
        String said = r.liveBuildId() == r.buildId() ? "this run is still going" : "a newer run is still going";

        return sentence ? Character.toUpperCase(said.charAt(0)) + said.substring(1) : said;
    }

    /** "1 suite needs a re-run", "2 tests need a re-run". */
    private static String needsRerun(int n, String noun) {
        return count(n, noun) + (n == 1 ? " needs" : " need") + " a re-run";
    }

    /** The failed run of a group of suites it kept from running, as a sentence names it. */
    private static String rootName(BrokenGroup g) {
        return g.root() != null ? BrokenGroup.nameOf(g.root().suiteName(), g.root().suite())
            : BrokenGroup.nameOf(g.upstream().name(), g.upstream().suite());
    }

    /** "Cache 1", "Cache 1 and Cache 2", "Cache 1, Cache 2 and Cache 3", "the 4 suites above". */
    private static String named(Ink ink, List<String> suites) {
        if (suites.size() > NEXT_NAMED)
            return "the " + suites.size() + " suites above";

        List<String> names = suites.stream().map(ink::text).toList();

        return names.size() == 1 ? names.get(0)
            : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** A list under the line that says what it lists; under nothing when the first line of the verdict said it. */
    private static String under(String what, String lines) {
        return what == null ? lines : what + ":\n" + lines;
    }

    private static String list(Ink ink, TestGroups tests, List<TestVerdict> listed, boolean why) {
        return (ink == Ink.MARKDOWN ? tests.markdown(listed, why) : tests.wiki(listed, why)).stripTrailing();
    }

    /**
     * The suites that gave no result, a line each with a link to the run; more than {@link #NAMES_SHOWN} of them go
     * by cause, as 60 suites that could not get their artifacts are one thing to say.
     */
    private String suiteLines(Ink ink, Broken broken, List<BrokenSuite> suites) {
        if (suites.size() > NAMES_SHOWN) {
            return broken.others().stream()
                .map(g -> new BrokenGroup(g.kind(), g.title(), null, null,
                    g.suites().stream().filter(suites::contains).toList(), List.of(), List.of()))
                .filter(g -> !g.suites().isEmpty())
                .map(g -> "- " + ink.text(groupLine(g))).reduce((a, b) -> a + "\n" + b).orElseThrow();
        }

        return suites.stream().map(s -> {
            String name = ink.text(BrokenGroup.nameOf(s.suiteName(), s.suite()));
            List<String> says = new ArrayList<>();
            if (s.problems() != null && !s.problems().isEmpty())
                says.add(ink.text(String.join(" · ", s.problems())));
            if (s.shortfall() != null)
                says.add(s.shortfall());
            if (broken.onMaster(s) == MasterSuites.OnMaster.FINE)
                says.add("fine on master");

            return "- " + (s.suiteBuildId() > 0 ? ink.link(name, tcBase() + "build/" + s.suiteBuildId()) : name)
                + (says.isEmpty() ? "" : " — " + String.join(", ", says));
        }).reduce((a, b) -> a + "\n" + b).orElseThrow();
    }

    /**
     * "ran 467 of master's 861 tests", as the page says it. Not {@link BrokenSuite#ran}, which gives null for a suite
     * that ran no test: a shrunk suite may have run none.
     */
    private static String ranOfMasters(ShrunkSuite s) {
        return "ran " + s.tests() + " of master's " + s.baseline() + " tests";
    }

    /** "33 blockers in 2 suites": the suites counted as the page counts them, by suite, not by suite run. */
    private static String blockersIn(List<TestVerdict> blockers) {
        long suites = blockers.stream().map(t -> t.suite() == null ? Long.toString(t.suiteBuildId()) : t.suite())
            .distinct().count();

        return count(blockers.size(), "blocker") + " in " + count((int)suites, "suite");
    }

    /** "1 suite", "2 suites". */
    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /**
     * The last line of a living comment whose run a newer RunAll replaced before its re-runs settled: the
     * verdict above stays as that run's last, and the newest one is on the page.
     */
    public Ending replaced(int pr, long newer) {
        String page = page(pr);
        String says = REPLACED + newer + " replaced this run before they settled. The verdict above"
            + " is this run's last; the newest is on ";

        return new Ending("🛑 _" + says + "[the checker's page](" + page + ")._", "_" + says + "[the checker's page|"
            + page + "]._");
    }

    /** The line added to a PR verdict comment once a newer RunAll of the PR has finished. */
    public String superseded(int pr, long newer) {
        return "\n\n" + SUPERSEDED + " by the newer RunAll [" + newer + "](" + tcBase() + "build/" + newer
            + ") — its verdict is on [the checker's page](" + page(pr) + ")._";
    }

    /** Whether a PR verdict comment already says a newer run took its place: marked superseded, or replaced. */
    public boolean saysSuperseded(String body) {
        return body.contains(SUPERSEDED) || body.contains(REPLACED);
    }

    /** The last line of a living comment whose PR was merged or closed before the run's re-runs settled. */
    public Ending prClosed(boolean merged) {
        String says = "The PR was " + (merged ? "merged" : "closed") + " before the re-runs settled: the verdict above"
            + " is the last known.";

        return new Ending("🏁 _" + says + "_", "_" + says + "_");
    }

    /** The last line of a living comment whose re-runs nobody follows any more: its owner switched them off. */
    public Ending notFollowed() {
        String says = "Re-runs no longer followed: the options that settle this run were switched off. The verdict"
            + " above is the last known.";

        return new Ending("⏹ _" + says + "_", "_" + says + "_");
    }

    /**
     * The same when the owner's options are on but none of them was on when the run finished: switched off and on
     * since, or switched on after a release that kept no time per option.
     */
    public Ending notFollowedAfterChange() {
        String says = "Re-runs no longer followed: the options that settle this run were changed after it finished. The"
            + " verdict above is the last known.";

        return new Ending("⏹ _" + says + "_", "_" + says + "_");
    }

    private String tcBase() {
        return tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
    }

    /** The checker's page of the PR. */
    private String page(int pr) {
        return publicUrl + "/ignite?pr=" + pr;
    }

    /** The commit as GitHub shows it among the PR's commits. */
    private String commitUrl(int pr, String sha) {
        return "https://github.com/" + repo + "/pull/" + pr + "/commits/" + sha;
    }

    private static String shortSha(String sha) {
        return sha.substring(0, Math.min(7, sha.length()));
    }

    /** One closing line in both markups: GitHub's markdown and JIRA's wiki markup. */
    public record Ending(String markdown, String wiki) {
    }

    /** The tests a broken suite never got to, stated under its cause rather than as a finding of its own. */
    private static String shortfall(BrokenSuite s) {
        String ran = s.shortfall();

        return ran == null ? "" : " — " + ran;
    }

    /**
     * "Cache 1 (ran 33 of master's 67 tests)": a suite among others broken the same way, with its own shortfall, as
     * the group's cause gives none; when the cause is that very shortfall, the name alone.
     */
    private static String nameAmongOthers(BrokenGroup g, BrokenSuite s) {
        String name = BrokenGroup.nameOf(s.suiteName(), s.suite());
        String ran = BrokenSuite.ran(s.tests(), s.baseline());

        return ran == null || ran.equals(g.title()) ? name : name + " (" + ran + ")";
    }

    /**
     * Under a failed run the others need: what it failed with while it stays broken, and the suites it kept from
     * running, in one line; TeamCity's words go through {@code text}, the markup's escape.
     */
    private static String rootLines(BrokenGroup g, UnaryOperator<String> text) {
        List<String> victims = Stream.concat(
            g.suites().stream().map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())),
            g.cancelled().stream().map(c -> BrokenGroup.nameOf(c.suiteName(), c.suite()))).toList();

        return (g.root() == null ? "" : "- " + text.apply(suiteLine(g.root())) + "\n")
            + (victims.isEmpty() ? "" : "- Did not run: " + text.apply(names(victims)) + "\n");
    }

    /** One line for a group of broken suites: its cause and the suites it holds. */
    private static String groupLine(BrokenGroup g) {
        List<String> suites = g.suites().stream().map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())).toList();

        return switch (g.kind()) {
            case UPSTREAM, ARTIFACTS -> g.title() + ": " + names(suites);
            case PROBLEM -> suites.size() == 1 ? suiteLine(g.suites().get(0))
                : g.title() + " (" + suites.size() + " suites): "
                    + names(g.suites().stream().map(s -> nameAmongOthers(g, s)).toList());
        };
    }

    /** "Cache 1: execution timeout — ran 33 of master's 67 tests". */
    private static String suiteLine(BrokenSuite s) {
        return BrokenGroup.nameOf(s.suiteName(), s.suite()) + ": " + String.join(" · ", s.problems()) + shortfall(s);
    }

    /** "Cache 1, Cache 2, Cache 3, Cache 4, Cache 5 and 55 more". */
    private static String names(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(NAMES_SHOWN, names.size())));

        return names.size() > NAMES_SHOWN ? shown + " and " + (names.size() - NAMES_SHOWN) + " more" : shown;
    }

    /** ", 3 of them on 1 run" when blockers rest on one failure: such a blocker is not proven yet. */
    private static String onOneRun(List<TestVerdict> blockers) {
        long k = TestGroups.oneRun(blockers);

        return k == 0 ? "" : k == blockers.size() ? ", all on 1 run" : ", " + k + " of them on 1 run";
    }

    /** What the first line of a verdict says: its mark in each markup, its words, and what they tell already. */
    private record Found(String icon, String wikiIcon, String text, Said said) {
    }

    /** What the first line tells in full, so that no line under it says it again. */
    private enum Said {
        NOTHING, ROOT, WATCH, RERUN, INTERRUPTED, GOING, SHRUNK, UNVERIFIED
    }

    /**
     * A verdict's broken suites as the PR comment and the visa tell them: the failed runs the others need, on their
     * own; of the {@code others}, the suites that broke the {@code same} way as their newest master run, which say
     * nothing of the PR, and those to {@code rerun}; {@code masters} is null when master is not asked about.
     */
    private record Broken(List<BrokenGroup> roots, List<BrokenGroup> others, List<BrokenSuite> rerun,
        List<BrokenSuite> same, MasterSuites masters) {
        static Broken of(AnalysisResult r, MasterSuites masters) {
            List<BrokenGroup> groups = BrokenGroup.of(r);
            List<BrokenGroup> others = groups.stream().filter(g -> g.upstream() == null).toList();
            List<BrokenSuite> rerun = new ArrayList<>();
            List<BrokenSuite> same = new ArrayList<>();
            for (BrokenGroup g : others) {
                for (BrokenSuite s : g.suites()) {
                    boolean asOnMaster = masters != null && masters.brokenLikeMaster(g, s);
                    (asOnMaster ? same : rerun).add(s);
                }
            }

            return new Broken(groups.stream().filter(g -> g.upstream() != null).toList(), others, rerun, same, masters);
        }

        MasterSuites.OnMaster onMaster(BrokenSuite s) {
            return masters == null ? MasterSuites.OnMaster.UNKNOWN : masters.onMaster(s.suite(), s.problemTypes());
        }

        /** The suites a re-run can tell more of: one that failed to compile fails the same way again. */
        List<BrokenSuite> rerunnable() {
            return rerun.stream().filter(s -> !s.compileError()).toList();
        }

        /** Whether a failed run and the suites it kept from running are all the chain had: nothing else to tell. */
        boolean nothingElseRan(AnalysisResult r) {
            return !roots.isEmpty() && others.isEmpty() && Caveats.neverRan(r) == 0 && !r.live()
                && r.otherCodeRuns().isEmpty()
                && Stream.of(r.blockers(), r.watch(), r.filtered(), r.unverified(), r.shrunkSuites())
                    .allMatch(List::isEmpty);
        }
    }
}
