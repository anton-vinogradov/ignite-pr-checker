package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The PR comment of 13583, whose Build failed, read "8 suite(s) have no reliable result (compilation error, timeout,
 * crash)" and listed the Build fifth among its victims, as "- > Build", which GitHub draws as a quote; the comment of
 * 13655 listed 60 suites hit by one ci2 glitch line by line. The verdict now starts with the failed Build and folds
 * its victims into one line, and every other group of broken suites is one line too.
 */
class BrokenGroupsVerdictTest {
    private final VisaService visas = new VisaService(
        new TeamcityProperties("https://ci2.ignite.apache.org/"), "https://ignite-pr-checker.is-a.dev");

    @Test
    void aFailedBuildIsTheWholeVerdict() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13583, BrokenRuns.buildFailed(false));

        assertThat(md).isEqualTo("""
            🛑 **No verdict: Build failed** · [full verdict](https://ignite-pr-checker.is-a.dev/ignite?pr=13583)

            - Build: non-zero exit code
            - Did not run: Platform .NET (Core Linux), Thin client: Node.js, Thin client: PHP, Thin client: Python, \
            Platform .NET (Windows) and 140 more

            **Next:** fix the build and run RunAll again.

            <sub>RunAll [9384769](https://ci2.ignite.apache.org/build/9384769) · [glossary](%s)</sub>"""
            .formatted(VisaService.GLOSSARY));
        assertThat(md).doesNotContain("- > Build", "compilation error, timeout, crash", "never ran", "No blockers");
    }

    @Test
    void theVisaStartsWithTheFailedBuildToo() {
        String wiki = visas.compose(BrokenRuns.PR_13583, BrokenRuns.buildFailed(true));

        assertThat(wiki).startsWith("(x) *No verdict: Build failed* — [Ignite PR Checker|https://ignite-pr-checker.is-a.dev/"
            + "ignite?pr=13583] verdict for PR 13583\n\n- Build: compilation error\n- Did not run: ");
    }

    @Test
    void aBuildThatPassedOnARerunLeavesARunAllToDo() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13583, BrokenRuns.buildPassedOnRerun());

        assertThat(md).startsWith("⚠️ **No blockers found, 145 suites never ran** · ")
            .contains("\n\nBuild failed in this run and passed on a re-run since — 145 suites that need it never ran:\n"
                + "- Did not run: Platform .NET (Core Linux), ")
            .contains("**Next:** run RunAll again: the suites that never ran need it.")
            .doesNotContain("- Build:", "RunAll was interrupted");
    }

    /** More suites than a list names one by one go by cause: the 60 of the glitch are one line. */
    @Test
    void theArtifactGlitchIsOneLine() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.artifactsGlitch());

        assertThat(visas.compose(BrokenRuns.PR_13655, BrokenRuns.artifactsGlitch()))
            .startsWith("(!) *No blockers found, 64 suites need a re-run* — ")
            .contains("\n\n- ci2 glitch: artifacts unavailable (60 suites): ");

        assertThat(md)
            .startsWith("⚠️ **No blockers found, 64 suites need a re-run** · ")
            .contains("**Next:** re-run the 64 suites above.\n")
            .contains("\n\n- ci2 glitch: artifacts unavailable (60 suites): Suite 1, Suite 2, Suite 3, Suite 4, Suite 5 and 55 "
                + "more\n"
                + "- non-zero exit code (2 suites): PDS 5, PDS 6\n"
                + "- Cache 5: execution timeout — ran 12 of master's 35 tests\n"
                + "- Basic 3: Failed to create temporary custom script file")
            .doesNotContain("Suite 6,");
    }

    /** TeamCity named only its count of the shortfall, so the shortfall is the suite's one problem: said once. */
    @Test
    void aSuiteWhoseOnlyProblemIsItsShortfallSaysItOnce() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(),
            List.of(new BrokenSuite("IgniteTests24Java8_Ml", 9392102L, "Machine Learning",
                List.of("ran 12 of master's 35 tests"), 12, 35))));

        assertThat(md).contains("- [Machine Learning](https://ci2.ignite.apache.org/build/9392102) — ran 12 of master's 35"
            + " tests\n\n");
    }

    /**
     * TeamCity failed Machine Learning and Cache 5 only for their test counts, 12 of master's 35 and 30 of master's
     * 80: the comment said "ran N of master's N tests (2 suites): Machine Learning, Cache 5", with no count anywhere.
     */
    @Test
    void suitesShortOnlyOfTestsKeepTheirOwnCountsInAGroup() {
        AnalysisResult r = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), six(i -> new BrokenSuite(
            "IgniteTests24Java8_Ml" + i, i, "Machine Learning " + i, List.of("ran " + (10 + i) + " of master's 35 tests"),
            10 + i, 35)));

        String group = "ran fewer tests than on master (6 suites): Machine Learning 1 (ran 11 of master's 35 tests), "
            + "Machine Learning 2 (ran 12 of master's 35 tests), ";
        int pr = BrokenRuns.PR_13655;
        for (String body : List.of(visas.composeMarkdown(pr, r), visas.compose(pr, r)))
            assertThat(body).contains("- " + group).doesNotContain("ran N");
    }

    /** Two suites short by the same count: the group's line says the count, and the names do not repeat it. */
    @Test
    void aShortfallTheGroupNamesIsNotRepeatedAfterEachSuite() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(),
            six(i -> new BrokenSuite("IgniteTests24Java8_Ml" + i, i, "Machine Learning " + i,
                List.of("ran 12 of master's 35 tests"), 12, 35))));

        assertThat(md).contains("- ran 12 of master's 35 tests (6 suites): Machine Learning 1, Machine Learning 2, ");
    }

    /** Two suites hung the same way, each after its own share of master's tests: neither's numbers fit the other. */
    @Test
    void aGroupLineGivesEachSuiteItsOwnShortfall() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(),
            six(i -> new BrokenSuite("IgniteTests24Java8_CacheFailover" + i, i, "Cache (Failover) " + i, List.of(
                "execution timeout", "Number of tests " + 5 * i + " is " + (90 - i) + "% less than " + (60 + i)
                    + " in build #114" + i), 5 * i, 60 + i))));

        assertThat(md)
            .contains("- execution timeout · Number of tests N is N% less than N in build #N (6 suites): Cache "
                + "(Failover) 1 (ran 5 of master's 61 tests), Cache (Failover) 2 (ran 10 of master's 62 tests), ")
            .doesNotContain("#1141", "#1142");
    }

    private static List<BrokenSuite> six(IntFunction<BrokenSuite> suite) {
        return IntStream.rangeClosed(1, 6).mapToObj(suite).toList();
    }
}
