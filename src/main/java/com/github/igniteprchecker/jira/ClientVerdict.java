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
 * The verdict of a run of the Python thin client's suite for a PR of the client: in GitHub's markdown for the PR, and
 * in JIRA's wiki markup for the visa. It names the commits the run tested, then what failed, and why each failure is or
 * is not blamed on the PR, by the rules of a suite of a RunAll. The comment only counts the tests not blamed: the
 * checker's page lists them. The visa opens by saying it is of this one suite: read as the visa of a RunAll, it would
 * vouch for suites that never ran. Its links go to TeamCity and GitHub, and the comment's also to the checker's page of
 * the PR.
 */
public final class ClientVerdict {
    /** Tests a list names; the rest are counted, with a link to the run's tests in TeamCity. */
    static final int LISTED = 20;

    /** How long the checker tries again a visa JIRA or GitHub failed; the verdict waits for it meanwhile. */
    public static final int VISA_TRIES_MINUTES = 10;

    /** The longest test name a line shows. */
    private static final int NAME_CHARS = 200;

    private static final String FOOTER = "This run uses Ignite master at the commit above. If this PR needs an Ignite"
        + " change that is not in that commit, the run tests this PR without that change. What each label means and"
        + " what to do: ";

    private ClientVerdict() {
    }

    /**
     * What the verdict says of the run: its build and suite, the PR, the commits of Ignite and of the client it ran on,
     * and the checker's page of the PR, null when the verdict does not link it.
     */
    public record Run(long buildId, String suiteName, String igniteRepo, String igniteSha, String clientRepo, int pr,
        String clientSha, String page) {
        /** A run whose verdict does not link the checker's page. */
        public Run(long buildId, String suiteName, String igniteRepo, String igniteSha, String clientRepo, int pr,
            String clientSha) {
            this(buildId, suiteName, igniteRepo, igniteSha, clientRepo, pr, clientSha, null);
        }
    }

    /**
     * The run's auto re-run on the same commits: its build, 0 when none was queued, whether the verdict counts its
     * results ({@code counted}), and why it does not, in words that follow the build: it was cancelled, or TeamCity no
     * longer has it. For a re-run never queued, {@code why} says what TeamCity answered. {@code standsIn}: TeamCity
     * cancelled the run by itself, so the run has no result, and the re-run gives the verdict. {@code auto}: the
     * checker queued it as the run's auto re-run; false for a later run of the same commits that the checker cannot
     * tell the origin of.
     */
    public record Rerun(long buildId, boolean counted, String why, boolean standsIn, boolean auto) {
        /** The run's auto re-run. */
        public Rerun(long buildId, boolean counted, String why, boolean standsIn) {
            this(buildId, counted, why, standsIn, true);
        }

        /** A re-run that finished: the verdict counts the failed tests of both runs. */
        public static Rerun counted(long buildId) {
            return new Rerun(buildId, true, null, false);
        }

        /**
         * A later run of the same commits that the verdict counts too, which may or may not be the run's auto re-run:
         * the verdict on the checker's page pairs the two newest runs of the same commits, whoever queued them.
         */
        public static Rerun sameCommits(long buildId) {
            return new Rerun(buildId, true, null, false, false);
        }

        /** A re-run that finished in place of a run TeamCity cancelled by itself: the verdict is the re-run's. */
        public static Rerun standsIn(long buildId) {
            return new Rerun(buildId, true, null, true);
        }

        public static Rerun cancelled(long buildId) {
            return new Rerun(buildId, false, "was cancelled", false);
        }

        public static Rerun gone(long buildId) {
            return new Rerun(buildId, false, "is gone from TeamCity", false);
        }

        public static Rerun notQueued(String why) {
            return new Rerun(0, false, why, false);
        }
    }

    /**
     * The verdict comment. {@code login} is the GitHub login of the commander, mentioned so GitHub tells them; null
     * when the checker does not know it. {@code tcBase} ends with a slash.
     */
    public static String markdown(String tcBase, String login, Run run, AnalysisResult r) {
        return markdown(tcBase, login, run, r, null, ClientVisa.NONE);
    }

    /**
     * The verdict comment, with what became of the run's auto re-run, null when none was due, and of its visa. A re-run
     * that stands in for a run TeamCity cancelled is the build the verdict names as the one that ran.
     */
    public static String markdown(String tcBase, String login, Run run, AnalysisResult r, Rerun rerun,
        ClientVisa visa) {
        return markdown(tcBase, login, run, r, rerun, visa, false);
    }

    /**
     * See {@link #markdown(String, String, Run, AnalysisResult, Rerun, ClientVisa)}: {@code fromPage}, the run was
     * queued on the checker's page rather than by a {@code /run-all}, and the comment's first words say so. A comment
     * that links the checker's page leaves the list of the tests not blamed on the PR to the page, as the verdict
     * comment of a RunAll does, and only counts them: one PR can have dozens of them.
     */
    public static String markdown(String tcBase, String login, Run run, AnalysisResult r, Rerun rerun,
        ClientVisa visa, boolean fromPage) {
        Ink ink = Ink.MARKDOWN;
        long ran = ran(run, rerun);
        StringBuilder b = new StringBuilder();
        b.append(opening(login, fromPage)).append(": ").append(ink.bold(ink.text(run.suiteName()))).append(' ')
            .append(ink.link("build " + ran, tcBase + "build/" + ran))
            .append(" ran this PR's head ").append(clientCommit(ink, run))
            .append(" against Ignite master at ").append(igniteCommit(ink, run)).append('.');
        pageLine(ink, run, r, b);
        b.append("\n\n");
        body(ink, tcBase, ran, r, run.page(), b);
        rerunLine(ink, tcBase, run, rerun, b);
        visaLine(visa, b);

        return b.append("<sub>").append(FOOTER).append(ink.link("verdict glossary", VisaService.GLOSSARY))
            .append(".</sub>").toString();
    }

    /**
     * The visa: the same verdict in JIRA's wiki markup, opened by what it is of: one suite, the PR's head against
     * Ignite master at a commit, and no other suite. {@code rerun}: the run's auto re-run, null when none was due.
     */
    public static String wiki(String tcBase, Run run, AnalysisResult r, Rerun rerun) {
        Ink ink = Ink.WIKI;
        long ran = ran(run, rerun);
        StringBuilder b = new StringBuilder();
        b.append("Ignite PR Checker verdict for ")
            .append(ink.link("PR " + run.pr() + " of " + run.clientRepo(), pullUrl(run)))
            .append(" · ").append(ink.bold("one suite only, not a RunAll")).append("\n\n")
            .append(ink.bold(ink.text(run.suiteName()))).append(' ')
            .append(ink.link("build " + ran, tcBase + "build/" + ran))
            .append(" tested the PR's head ").append(clientCommit(ink, run))
            .append(" against Ignite master at ").append(igniteCommit(ink, run)).append(". No other suite ran.\n\n");
        body(ink, tcBase, ran, r, null, b);
        rerunLine(ink, tcBase, run, rerun, b);

        return b.append('_').append(FOOTER).append(ink.link("verdict glossary", VisaService.GLOSSARY)).append("._")
            .toString();
    }

    /**
     * How the comment opens: whose run it is the verdict of, with a mention GitHub tells {@code login} of, and how the
     * run was queued.
     */
    private static String opening(String login, boolean fromPage) {
        boolean nobody = login == null || login.isBlank();
        if (fromPage) {
            return nobody ? "The verdict of a run queued on the checker's page"
                : "@" + login + " the verdict of the run you queued on the checker's page";
        }

        return nobody ? "The verdict of a `/run-all`" : "@" + login + " the verdict of your `/run-all`";
    }

    /** The build the verdict names as the one that ran: the run's, or the re-run that stands in for it. */
    private static long ran(Run run, Rerun rerun) {
        return rerun != null && rerun.standsIn() ? rerun.buildId() : run.buildId();
    }

    /**
     * What failed and why, the verdict's standing, the tests not blamed, and what the tags of the lists mean; past the
     * tests a list shows, it links those of build {@code ran}. With the checker's {@code page} of the PR, the tests not
     * blamed are only counted, and the page lists them; without it, as in the visa, they are listed.
     */
    private static void body(Ink ink, String tcBase, long ran, AnalysisResult r, String page, StringBuilder b) {
        r.brokenSuites().forEach(s -> b.append(ink.icon("⚠️", "(!)")).append(' ')
            .append(ink.bold("The suite has no reliable result:")).append(' ').append(ink.text(problems(s)))
            .append(".\n\n"));
        r.unstableSuites().forEach(s -> b.append(ink.icon("ℹ️", "(i)")).append(' ')
            .append(ink.bold("Crashed near the end of the run:")).append(' ')
            .append(ink.text(String.join(" · ", s.problems())))
            .append(". It ran nearly all of master's tests, so its results are reliable.\n\n"));
        r.shrunkSuites().forEach(s -> b.append(ink.icon("🔍", "(?)")).append(' ')
            .append(ink.bold("The suite ran fewer tests than on master:")).append(' ').append(ranOf(ink, s))
            .append(". Tests that never ran can't fail.\n\n"));

        String tests = tcBase + "build/" + ran + "?buildTab=tests";
        if (!r.blockers().isEmpty()) {
            b.append(ink.icon("❌", "(x)")).append(' ').append(ink.bold(count(r.blockers().size(), "blocker") + ":"))
                .append('\n').append(list(ink, tcBase, r.blockers(), tests)).append('\n');
        }
        if (!r.watch().isEmpty()) {
            b.append(ink.icon("👀", "(!)")).append(' ')
                .append(ink.bold(count(r.watch().size(), "test") + " started failing on this code"))
                .append(" — not proven blockers yet. There are too few runs of this code to tell a break from a flake."
                    + " A new ").append(ink == Ink.MARKDOWN ? "`/run-all`" : "{{/run-all}}")
                .append(" decides it while this PR's head and Ignite master stay at the commits above.\n")
                .append(list(ink, tcBase, r.watch(), tests)).append('\n');
        }
        if (!r.unverified().isEmpty()) {
            b.append(ink.icon("❔", "(?)")).append(' ')
                .append(ink.bold(count(r.unverified().size(), "failed test") + " could not be checked"))
                .append(" — TeamCity errors kept them from being compared with master and the branch; not counted as"
                    + " blockers.\n").append(list(ink, tcBase, r.unverified(), tests)).append('\n');
        }

        Caveats.Standing standing = Caveats.standing(r, null);
        if (standing == Caveats.Standing.CLEAN) {
            b.append(ink.icon("✅", "(/)")).append(' ').append(ink.bold("No blockers"))
                .append(r.filtered().isEmpty() ? " — no test failed.\n\n"
                    : " — nothing in this run looks caused by this PR.\n\n");
        }
        else if (standing == Caveats.Standing.WATCH) {
            b.append(ink.icon("⚠️", "(!)")).append(' ').append(ink.bold("No proven blockers yet"))
                .append(" — this does not mean the PR is clean: see the tests above.\n\n");
        }
        else if (standing != Caveats.Standing.BLOCKERS) {
            b.append(ink.icon("🔎", "(?)")).append(' ')
                .append(ink.bold("No blockers found — but this run can't prove the PR is clean:"))
                .append(" see above.\n\n");
        }

        if (!r.filtered().isEmpty()) {
            int n = r.filtered().size();
            String notBlamed = count(n, "failing test") + (n == 1 ? " is" : " are") + " not blamed on this PR";
            if (page == null) {
                b.append(ink.bold(notBlamed + ":")).append('\n').append(list(ink, tcBase, r.filtered(), tests))
                    .append('\n');
            }
            else {
                b.append(ink.bold(notBlamed + ".")).append(' ').append(ink.link("The checker's page", page))
                    .append(n == 1 ? " shows it with its reason.\n\n" : " lists them with their reasons.\n\n");
            }
        }

        List<TestVerdict> tagged = Stream.concat(r.blockers().stream(), r.watch().stream()).toList();
        TestGroups groups = new TestGroups(tcBase, null);
        String legend = ink == Ink.MARKDOWN ? groups.markdownLegend(tagged) : groups.wikiLegend(tagged);
        if (!legend.isEmpty())
            b.append(legend).append("\n\n");
    }

    /** What became of the run's auto re-run, when one was due; nothing when none was. */
    private static void rerunLine(Ink ink, String tcBase, Run run, Rerun rerun, StringBuilder b) {
        if (rerun == null)
            return;

        String build = rerun.buildId() == 0 ? null
            : ink.link("build " + rerun.buildId(), tcBase + "build/" + rerun.buildId());
        b.append("♻️ ");
        if (rerun.standsIn()) {
            b.append("TeamCity cancelled ").append(ink.link("build " + run.buildId(), tcBase + "build/"
                + run.buildId())).append(" by itself. The checker ran the suite again on the same commits. This"
                + " verdict comes from that re-run.");
        }
        else if (rerun.counted() && !rerun.auto())
            b.append("This verdict counts ").append(build)
                .append(" too: a later run of the suite on the same commits.");
        else if (rerun.counted())
            b.append("Settled after one auto re-run: ").append(build)
                .append(" ran the suite again on the same commits.");
        else if (build != null)
            b.append("The auto re-run ").append(build).append(' ').append(rerun.why())
                .append(", so this verdict counts the first run only.");
        else {
            b.append("The checker could not queue the auto re-run: ").append(ink.text(rerun.why()))
                .append(". This verdict counts the first run only.");
        }
        b.append("\n\n");
    }

    /** The sentence on where the checker's page of the PR shows the verdict; none when the verdict does not link it. */
    private static void pageLine(Ink ink, Run run, AnalysisResult r, StringBuilder b) {
        if (run.page() == null)
            return;

        boolean failed = Stream.of(r.blockers(), r.watch(), r.unverified(), r.filtered()).anyMatch(l -> !l.isEmpty());
        b.append(failed ? " The checker's page shows each failed test with its failure message: "
                : " The checker's page shows this verdict and the PR's runs: ")
            .append(ink.link("Ignite PR Checker", run.page())).append('.');
    }

    /** What became of the run's visa, for the commander; nothing when their option does not act on the run. */
    private static void visaLine(ClientVisa visa, StringBuilder b) {
        String line = switch (visa.state()) {
            case ClientVisa.POSTED -> "The JIRA visa is in [" + visa.issue() + "](" + visa.url() + ").";
            case ClientVisa.SAME -> "No new JIRA visa: your last one in [" + visa.issue() + "](" + visa.url()
                + ") has the same verdict for the same commits.";
            case ClientVisa.NO_TICKET -> "No JIRA visa: the PR title names no IGNITE ticket.";
            case ClientVisa.CLOSED -> "No JIRA visa: the PR was " + visa.why() + " before the run's verdict.";
            case ClientVisa.WAITS -> waits(visa.why());
            case ClientVisa.REFUSED -> "No JIRA visa: JIRA refused your JIRA token, so the checker dropped it and"
                + " switched off **Visa my runs in JIRA**. Save a new token in ⚙ to switch it on again.";
            case ClientVisa.FAILED -> "No JIRA visa: " + Markup.text(visa.why()) + ". The checker tried for "
                + VISA_TRIES_MINUTES + " minutes.";
            default -> null;
        };
        if (line != null)
            b.append("📝 ").append(line).append("\n\n");
    }

    /** Why the visa waits for a JIRA token the checker no longer has, and what gets the next run a visa. */
    private static String waits(String why) {
        if (StandingVisas.TOKEN_REMOVED.equals(why)) {
            return "No JIRA visa: the checker's state was restored from a backup older than 2 days, which keeps no"
                + " tokens. Save your JIRA token in ⚙ again, and your next run gets a visa.";
        }

        return "No JIRA visa: " + (SettingsJournal.Entry.KEY_CHANGED.equals(why)
            ? "the server key was changed, so the checker could no longer read your JIRA token."
            : "the checker could not read your JIRA token.")
            + " Save a new one in ⚙, and your next run gets a visa.";
    }

    /** One line per test, with why it is listed and a link to it in TeamCity; past {@link #LISTED}, how many more. */
    private static String list(Ink ink, String tcBase, List<TestVerdict> tests, String allTests) {
        StringBuilder b = new StringBuilder();
        tests.stream().limit(LISTED).forEach(t -> {
            String tags = TestGroups.tags(t);
            String url = TestGroups.rowUrl(tcBase, t);
            b.append("- ").append(ink.code(shown(t.name()))).append(" — ").append(ink.text(t.reason()))
                .append(tags.isEmpty() ? "" : " (" + tags + ")").append(url == null ? "" : " · " + ink.link("TC", url))
                .append('\n');
        });
        if (tests.size() > LISTED)
            b.append("- … and ").append(tests.size() - LISTED).append(" more in ")
                .append(ink.link("TeamCity", allTests)).append('\n');

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
    private static String ranOf(Ink ink, ShrunkSuite s) {
        return "ran " + s.tests() + " of master's " + s.baseline() + " tests ("
            + ink.bold(ink.icon("−", "-") + s.dropPct() + "%") + ")";
    }

    private static String clientCommit(Ink ink, Run run) {
        return commit(ink, pullUrl(run) + "/commits/", run.clientSha());
    }

    private static String igniteCommit(Ink ink, Run run) {
        return commit(ink, "https://github.com/" + run.igniteRepo() + "/commit/", run.igniteSha());
    }

    private static String pullUrl(Run run) {
        return "https://github.com/" + run.clientRepo() + "/pull/" + run.pr();
    }

    private static String commit(Ink ink, String url, String sha) {
        if (sha == null)
            return "an unknown commit";

        String shortSha = sha.substring(0, Math.min(7, sha.length()));

        return ink.link(ink == Ink.MARKDOWN ? "`" + shortSha + "`" : shortSha, url + sha);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The markup a verdict is written in: GitHub's markdown for the PR comment, JIRA's wiki markup for the visa. */
    private enum Ink {
        MARKDOWN, WIKI;

        String bold(String s) {
            return this == MARKDOWN ? "**" + s + "**" : "*" + s + "*";
        }

        /** Text from TeamCity or GitHub, written so that it reads as written. */
        String text(String s) {
            return this == MARKDOWN ? Markup.text(s) : Markup.wikiText(s);
        }

        String code(String s) {
            return this == MARKDOWN ? Markup.code(s) : "{{" + Markup.wikiText(s) + "}}";
        }

        String link(String text, String url) {
            return this == MARKDOWN ? "[" + text + "](" + url + ")" : "[" + text + "|" + url + "]";
        }

        /** The mark a line opens with: an emoji in GitHub, one of JIRA's icons in a visa. */
        String icon(String markdown, String wiki) {
            return this == MARKDOWN ? markdown : wiki;
        }
    }
}
