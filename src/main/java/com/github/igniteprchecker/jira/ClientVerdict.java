package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.CauseClusters;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import java.util.List;
import java.util.stream.Stream;

/**
 * The verdict of a run of the Python thin client's suite for a PR of the client, in GitHub's markdown: the commits it
 * ran, then what failed, and why each failure is or is not blamed on the PR, by the rules of a suite of a RunAll. Its
 * links go to TeamCity and GitHub only: the checker's page has no PRs of the client.
 */
public final class ClientVerdict {
    /** Tests a list names; the rest are counted, with a link to the run's tests in TeamCity. */
    static final int LISTED = 20;

    /** The longest test name a line shows. */
    private static final int NAME_CHARS = 200;

    private ClientVerdict() {
    }

    /**
     * What the verdict says of the run: its build and suite, the PR, and the commits of Ignite and of the client it
     * ran on.
     */
    public record Run(long buildId, String suiteName, String igniteRepo, String igniteSha, String clientRepo, int pr,
        String clientSha) {
    }

    /**
     * The verdict comment. {@code login} is the GitHub login of the commander, mentioned so GitHub tells them; null
     * when the checker does not know it. {@code tcBase} ends with a slash.
     */
    public static String markdown(String tcBase, String login, Run run, AnalysisResult r) {
        StringBuilder b = new StringBuilder();
        String build = "[build " + run.buildId() + "](" + tcBase + "build/" + run.buildId() + ")";
        b.append(login == null || login.isBlank() ? "The verdict of a `/run-all`" : "@" + login
            + " the verdict of your `/run-all`").append(": **").append(Markup.text(run.suiteName())).append("** ")
            .append(build).append(" ran this PR's head ")
            .append(commit("https://github.com/" + run.clientRepo() + "/pull/" + run.pr() + "/commits/",
                run.clientSha()))
            .append(" against Ignite master at ")
            .append(commit("https://github.com/" + run.igniteRepo() + "/commit/", run.igniteSha())).append(".\n\n");

        r.brokenSuites().forEach(s -> b.append("⚠️ **The suite has no reliable result:** ")
            .append(Markup.text(problems(s))).append(".\n\n"));
        r.unstableSuites().forEach(s -> b.append("ℹ️ **Crashed near the end of the run:** ")
            .append(Markup.text(String.join(" · ", s.problems())))
            .append(". It ran nearly all of master's tests, so its results stand.\n\n"));
        r.shrunkSuites().forEach(s -> b.append("🔍 **The suite ran fewer tests than on master:** ").append(ranOf(s))
            .append(". Tests that never ran can't fail.\n\n"));

        String tests = tcBase + "build/" + run.buildId() + "?buildTab=tests";
        if (!r.blockers().isEmpty()) {
            b.append("❌ **").append(count(r.blockers().size(), "blocker")).append(":**\n")
                .append(list(tcBase, r.blockers(), tests)).append('\n');
        }
        if (!r.watch().isEmpty()) {
            b.append("👀 **").append(count(r.watch().size(), "test")).append(" started failing on this code** — not"
                + " proven blockers yet. There are too few runs of this code to tell a break from a flake. A re-run of"
                + " the suite on the same commits decides it.\n").append(list(tcBase, r.watch(), tests)).append('\n');
        }
        if (!r.unverified().isEmpty()) {
            b.append("❔ **").append(count(r.unverified().size(), "failed test")).append(" could not be checked** —"
                + " TeamCity errors kept them from being compared with master and the branch; not counted as"
                + " blockers.\n").append(list(tcBase, r.unverified(), tests)).append('\n');
        }

        Caveats.Standing standing = Caveats.standing(r, null);
        if (standing == Caveats.Standing.CLEAN)
            b.append(r.filtered().isEmpty() ? "✅ **No blockers** — no test failed.\n\n"
                : "✅ **No blockers** — nothing in this run looks caused by this PR.\n\n");
        else if (standing == Caveats.Standing.WATCH)
            b.append("⚠️ **No proven blockers yet** — this does not mean the PR is clean: see the tests above.\n\n");
        else if (standing != Caveats.Standing.BLOCKERS)
            b.append("🔎 **No blockers found — but this run can't prove the PR is clean:** see above.\n\n");

        if (!r.filtered().isEmpty()) {
            int n = r.filtered().size();
            b.append("**").append(count(n, "failing test")).append(n == 1 ? " is" : " are")
                .append(" not blamed on this PR:**\n").append(list(tcBase, r.filtered(), tests)).append('\n');
        }

        String legend = new TestGroups(tcBase, null).markdownLegend(Stream.concat(r.blockers().stream(),
            r.watch().stream()).toList());
        if (!legend.isEmpty())
            b.append(legend).append("\n\n");

        return b.append("<sub>Ignite is master at the commit above, so a client change that needs an Ignite change is"
            + " not tested with it. What each label means and what to do: [verdict glossary](")
            .append(VisaService.GLOSSARY).append(").</sub>").toString();
    }

    /** One line per test, with why it is listed and a link to it in TeamCity; past {@link #LISTED}, how many more. */
    private static String list(String tcBase, List<TestVerdict> tests, String allTests) {
        StringBuilder b = new StringBuilder();
        tests.stream().limit(LISTED).forEach(t -> {
            String tags = TestGroups.tags(t);
            String url = TestGroups.rowUrl(tcBase, t);
            b.append("- ").append(Markup.code(shown(t.name()))).append(" — ").append(Markup.text(t.reason()))
                .append(tags.isEmpty() ? "" : " (" + tags + ")").append(url == null ? "" : " · [TC](" + url + ")")
                .append('\n');
        });
        if (tests.size() > LISTED)
            b.append("- … and ").append(tests.size() - LISTED).append(" more in [TeamCity](").append(allTests)
                .append(")\n");

        return b.toString();
    }

    /** A test's name without the suite TeamCity puts before it and without the agent's work directory. */
    private static String shown(String name) {
        String s = CauseClusters.AGENT_WORK_DIR.matcher(name.contains(": ") ? name.substring(name.indexOf(": ") + 2)
            : name).replaceAll("");

        return s.length() > NAME_CHARS ? s.substring(0, NAME_CHARS - 1) + "…" : s;
    }

    /** "execution timeout — ran 33 of master's 67 tests". */
    private static String problems(BrokenSuite s) {
        String ran = s.shortfall();

        return String.join(" · ", s.problems()) + (ran == null ? "" : " — " + ran);
    }

    /** "ran 467 of master's 861 tests (**−46%**)". */
    private static String ranOf(ShrunkSuite s) {
        return "ran " + s.tests() + " of master's " + s.baseline() + " tests (**−" + s.dropPct() + "%**)";
    }

    private static String commit(String url, String sha) {
        return sha == null ? "an unknown commit" : "[`" + sha.substring(0, Math.min(7, sha.length())) + "`](" + url
            + sha + ")";
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
