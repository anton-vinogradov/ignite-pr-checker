package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Composes the verdict ("visa") in JIRA wiki markup — tcbot style: green when clean, red with the
 * list. "No blockers" is only ever green when the run behind it actually covered the PR; an
 * interrupted run, suites without a reliable result, or commits pushed since are stated instead.
 */
@Component
public class VisaService {
    /** How the line that marks a verdict comment superseded starts. */
    private static final String SUPERSEDED = "🔁 _Superseded";

    /** How the last line of a comment whose re-runs a newer run cut short starts, after its emoji. */
    private static final String REPLACED = "Re-runs stopped: RunAll ";

    /** How many suites a line of a broken group names before "and N more". */
    private static final int NAMES_SHOWN = 5;

    private final TeamcityProperties tc;
    private final String publicUrl;
    private final String repo;

    @Autowired
    public VisaService(TeamcityProperties tc,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl, GithubProperties github) {
        this.tc = tc;
        this.publicUrl = publicUrl;
        this.repo = github.repo();
    }

    public VisaService(TeamcityProperties tc, String publicUrl) {
        this(tc, publicUrl, new GithubProperties(null, null, null));
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
     * the commit the run tested, named in the head line; null when TeamCity did not say.
     */
    public String composeMarkdown(int pr, AnalysisResult r, Integer commitsAhead, String sha) {
        String base = tcBase();
        TestGroups tests = new TestGroups(base, page(pr));
        StringBuilder b = new StringBuilder();
        b.append("**[Ignite PR Checker](").append(page(pr))
            .append(")** verdict · RunAll build [").append(r.buildId()).append("](").append(base)
            .append("build/").append(r.buildId()).append(")")
            .append(sha == null ? "" : " · tested [" + shortSha(sha) + "](" + commitUrl(pr, sha) + ")")
            .append(" · ").append(r.suitesRan()).append(" suites ran, ").append(r.suitesReused())
            .append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<TestVerdict> watch = r.watch();
        List<String> caveats = Caveats.of(r, commitsAhead);
        // Green only when nothing at all needs attention — the same bar the web page uses. Tests that
        // just started failing on this code, or a run that couldn't cover the PR, make "No blockers" a lie.
        if (blockers.isEmpty() && watch.isEmpty() && caveats.isEmpty()) {
            b.append("✅ **No blockers** — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        Broken broken = Broken.of(r, caveats);
        broken.roots().forEach(g -> b.append("🛑 **").append(g.title()).append("**\n")
            .append(rootLines(g)).append('\n'));
        if (broken.allSaid(r))
            return b.toString().stripTrailing();

        if (!broken.caveats().isEmpty()) {
            b.append("⚠️ **This run doesn't cover the PR fully:**\n");
            broken.caveats().forEach(c -> b.append("- ").append(c).append('\n'));
            b.append("\nEverything below is what it did manage to say.\n\n");
        }

        if (!broken.others().isEmpty()) {
            b.append("⚠️ **Broken suites** (no reliable run):\n");
            broken.others().forEach(g -> b.append("- ").append(groupLine(g)).append('\n'));
            b.append('\n');
        }

        if (!r.shrunkSuites().isEmpty()) {
            b.append("🔍 **").append(r.shrunkSuites().size())
                .append(" suite(s) ran fewer tests than on master** (tests that never ran can't fail):\n");
            r.shrunkSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(s.tests()).append(" tests vs ").append(s.baseline())
                .append(" on master (**−").append(s.dropPct()).append("%**)\n"));
            b.append('\n');
        }

        if (!watch.isEmpty()) {
            // Never say the re-runs are under way: this composes from the analysis alone and has no
            // idea whether anyone will re-run anything (a manual visa, a subscription, or auto-rerun
            // switched off). StandingVisas appends the real re-run state when there is one.
            b.append("👀 **").append(watch.size()).append(" test(s) started failing on this code** — not proven ")
                .append("blockers yet: too few runs of this revision to tell a break from a flake, so a re-run ")
                .append("of the suite decides it.\n");
            b.append(tests.markdown(watch, true)).append('\n');
        }

        if (!r.unverified().isEmpty()) {
            b.append("❔ **").append(r.unverified().size()).append(" failed test(s) could not be checked** — ")
                .append("TeamCity errors kept them from being compared with master and the branch; not counted as ")
                .append("blockers.\n");
            b.append(tests.markdown(r.unverified(), false)).append('\n');
        }

        if (blockers.isEmpty() && !watch.isEmpty())
            b.append("⚠️ **No proven blocker yet** — this is not an all-clear: see the tests above. ")
                .append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
        else if (blockers.isEmpty())
            b.append("🔎 **No blockers found — but the run above can't prove the PR is clean.** ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out. ")
                .append("Re-run once the above is sorted out.");
        else {
            long suites = blockers.stream().map(TestVerdict::suiteBuildId).distinct().count();
            b.append("❌ **").append(blockers.size()).append(" blocker(s) in ").append(suites).append(" suite(s):**\n")
                .append(tests.markdown(blockers, true));
        }

        return b.toString();
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
        return publicUrl + "/?pr=" + pr;
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
        return s.tests() > 0 && s.baseline() > s.tests()
            ? " — ran " + s.tests() + " of master's " + s.baseline() + " tests" : "";
    }

    /**
     * Under a failed run the others need: what it failed with while it stays broken, and the suites it kept from
     * running, in one line.
     */
    private static String rootLines(BrokenGroup g) {
        List<String> victims = Stream.concat(
            g.suites().stream().map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())),
            g.cancelled().stream().map(c -> BrokenGroup.nameOf(c.suiteName(), c.suite()))).toList();

        return (g.root() == null ? "" : "- " + suiteLine(g.root()) + "\n")
            + (victims.isEmpty() ? "" : "- Did not run: " + names(victims) + "\n");
    }

    /** One line for a group of broken suites: its cause and the suites it holds. */
    private static String groupLine(BrokenGroup g) {
        List<String> suites = g.suites().stream().map(s -> BrokenGroup.nameOf(s.suiteName(), s.suite())).toList();

        return switch (g.kind()) {
            case UPSTREAM, ARTIFACTS -> g.title() + ": " + names(suites);
            case PROBLEM -> suites.size() == 1 ? suiteLine(g.suites().get(0))
                : g.title() + " (" + suites.size() + " suites): " + names(suites);
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

    /**
     * A verdict's broken groups as the PR comment and the visa tell them: the failed runs the others need first, on
     * their own, then the {@code caveats} they do not already say, and the other groups.
     */
    private record Broken(List<BrokenGroup> roots, List<BrokenGroup> others, List<String> caveats) {
        static Broken of(AnalysisResult r, List<String> caveats) {
            List<BrokenGroup> groups = BrokenGroup.of(r);
            List<BrokenGroup> roots = groups.stream().filter(g -> g.upstream() != null).toList();
            List<String> rest = new ArrayList<>(caveats);
            roots.forEach(g -> rest.remove(g.caveat()));

            return new Broken(roots, groups.stream().filter(g -> g.upstream() == null).toList(), rest);
        }

        /** Whether the failed runs are all there is to say: nothing else ran, so nothing else can be told. */
        boolean allSaid(AnalysisResult r) {
            return !roots.isEmpty() && others.isEmpty() && caveats.isEmpty()
                && Stream.of(r.blockers(), r.watch(), r.filtered(), r.unverified(), r.shrunkSuites())
                    .allMatch(List::isEmpty);
        }
    }

    public String compose(int pr, AnalysisResult r) {
        return compose(pr, r, null);
    }

    public String compose(int pr, AnalysisResult r, Integer commitsAhead) {
        return compose(pr, r, commitsAhead, null);
    }

    /** The same verdict in JIRA wiki markup; see {@link #composeMarkdown(int, AnalysisResult, Integer, String)}. */
    public String compose(int pr, AnalysisResult r, Integer commitsAhead, String sha) {
        String base = tcBase();
        TestGroups tests = new TestGroups(base, page(pr));
        StringBuilder b = new StringBuilder();
        b.append("[Ignite PR Checker|").append(page(pr)).append("] verdict for PR ")
            .append(pr).append(" · RunAll build [").append(r.buildId()).append('|').append(base).append("build/")
            .append(r.buildId()).append(']')
            .append(sha == null ? "" : " · tested [" + shortSha(sha) + "|" + commitUrl(pr, sha) + "]")
            .append(" · ").append(r.suitesRan()).append(" suites ran, ").append(r.suitesReused())
            .append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<TestVerdict> watch = r.watch();
        List<String> caveats = Caveats.of(r, commitsAhead);
        // Green only when nothing at all needs attention — the same bar the web page uses. Tests that
        // just started failing on this code, or a run that couldn't cover the PR, make "No blockers" a lie.
        if (blockers.isEmpty() && watch.isEmpty() && caveats.isEmpty()) {
            b.append("(/) *No blockers* — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        Broken broken = Broken.of(r, caveats);
        broken.roots().forEach(g -> b.append("(x) *").append(g.title()).append("*\n")
            .append(rootLines(g)).append('\n'));
        if (broken.allSaid(r))
            return b.toString().stripTrailing();

        if (!broken.caveats().isEmpty()) {
            b.append("(!) *This run doesn't cover the PR fully:*\n");
            broken.caveats().forEach(c -> b.append("- ").append(c).append('\n'));
            b.append("\nEverything below is what it did manage to say.\n\n");
        }

        if (!broken.others().isEmpty()) {
            b.append("(!) *Broken suites* (failed without a reliable run):\n");
            broken.others().forEach(g -> b.append("- ").append(groupLine(g)).append('\n'));
            b.append('\n');
        }

        if (!r.shrunkSuites().isEmpty()) {
            b.append("(?) *").append(r.shrunkSuites().size())
                .append(" suite(s) ran fewer tests than on master* (tests that never ran can't fail):\n");
            r.shrunkSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(s.tests()).append(" tests vs ").append(s.baseline())
                .append(" on master (*-").append(s.dropPct()).append("%*)\n"));
            b.append('\n');
        }

        if (!watch.isEmpty()) {
            // Never say the re-runs are under way — see composeMarkdown.
            b.append("(!) *").append(watch.size()).append(" test(s) started failing on this code* — not proven ")
                .append("blockers yet: too few runs of this revision to tell a break from a flake, so a re-run ")
                .append("of the suite decides it.\n");
            b.append(tests.wiki(watch, true)).append('\n');
        }

        if (!r.unverified().isEmpty()) {
            b.append("(?) *").append(r.unverified().size()).append(" failed test(s) could not be checked* — ")
                .append("TeamCity errors kept them from being compared with master and the branch; not counted as ")
                .append("blockers.\n");
            b.append(tests.wiki(r.unverified(), false)).append('\n');
        }

        if (blockers.isEmpty() && !watch.isEmpty())
            b.append("(!) *No proven blocker yet* — this is not an all-clear: see the tests above. ")
                .append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
        else if (blockers.isEmpty())
            b.append("(?) *No blockers found — but the run above can't prove the PR is clean.* ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out. ")
                .append("Re-run once the above is sorted out.");
        else {
            long suites = blockers.stream().map(TestVerdict::suiteBuildId).distinct().count();
            b.append("(x) *").append(blockers.size()).append(" blocker(s) in ").append(suites).append(" suite(s):*\n")
                .append(tests.wiki(blockers, true));
        }

        return b.toString();
    }
}
