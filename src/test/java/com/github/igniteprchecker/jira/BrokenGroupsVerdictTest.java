package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
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

        assertThat(md.substring(md.indexOf("\n\n") + 2)).isEqualTo("""
            🛑 **Build failed — nothing else ran; fix the build, then /run-all**
            - Build: non-zero exit code
            - Did not run: Platform .NET (Core Linux), Thin client: Node.js, Thin client: PHP, Thin client: Python, \
            Platform .NET (Windows) and 140 more

            <sub>What each label means and what to do: [verdict glossary](%s).</sub>""".formatted(VisaService.GLOSSARY));
        assertThat(md).doesNotContain("- > Build", "compilation error, timeout, crash", "never ran", "No blockers");
    }

    @Test
    void theVisaStartsWithTheFailedBuildToo() {
        String wiki = visas.compose(BrokenRuns.PR_13583, BrokenRuns.buildFailed(true));

        assertThat(wiki.substring(wiki.indexOf("\n\n") + 2))
            .startsWith("(x) *Build failed — nothing else ran; fix the build, then /run-all*\n"
                + "- Build: compilation error\n- Did not run: ");
    }

    @Test
    void aBuildThatPassedOnARerunLeavesARunAllToDo() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13583, BrokenRuns.buildPassedOnRerun());

        assertThat(md.substring(md.indexOf("\n\n") + 2))
            .startsWith("🛑 **Build failed in this run and passed on a re-run since — 145 suites that need it never "
                + "ran; /run-all to run them**\n- Did not run: Platform .NET (Core Linux), ")
            .doesNotContain("- Build:", "the RunAll was interrupted", "doesn't cover");
    }

    /**
     * The page heads these suites "Broken suites" and says they have no reliable result; the comment said "no reliable
     * run" and the visa "failed without a reliable run".
     */
    @Test
    void theArtifactGlitchIsOneLineAndTheCaveatNamesTheCauses() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.artifactsGlitch());

        assertThat(visas.compose(BrokenRuns.PR_13655, BrokenRuns.artifactsGlitch()))
            .contains("(!) *Broken suites* (no reliable result):\n- ci2 glitch: artifacts unavailable (60 suites): ");

        assertThat(md)
            .contains("- 64 suites have no reliable result (60× ci2 glitch: artifacts unavailable; 2× non-zero "
                + "exit code; execution timeout; …)\n")
            .contains("⚠️ **Broken suites** (no reliable result):\n"
                + "- ci2 glitch: artifacts unavailable (60 suites): Suite 1, Suite 2, Suite 3, Suite 4, Suite 5 and 55 "
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

        assertThat(md).contains("- Machine Learning: ran 12 of master's 35 tests\n")
            .contains("- 1 suite has no reliable result (ran 12 of master's 35 tests)\n")
            .doesNotContain("tests — ran 12");
    }

    /**
     * TeamCity failed Machine Learning and Cache 5 only for their test counts, 12 of master's 35 and 30 of master's
     * 80: the comment said "ran N of master's N tests (2 suites): Machine Learning, Cache 5", with no count anywhere.
     */
    @Test
    void suitesShortOnlyOfTestsKeepTheirOwnCountsInAGroup() {
        AnalysisResult r = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Ml", 1L, "Machine Learning", List.of("ran 12 of master's 35 tests"), 12,
                35),
            new BrokenSuite("IgniteTests24Java8_Cache5", 2L, "Cache 5", List.of("ran 30 of master's 80 tests"), 30,
                80)));

        String group = "ran fewer tests than on master (2 suites): Machine Learning (ran 12 of master's 35 tests), "
            + "Cache 5 (ran 30 of master's 80 tests)\n";
        int pr = BrokenRuns.PR_13655;
        for (String body : List.of(visas.composeMarkdown(pr, r), visas.compose(pr, r))) {
            assertThat(body).contains("- " + group)
                .contains("- 2 suites have no reliable result (2× ran fewer tests than on master)\n")
                .doesNotContain("ran N");
        }
    }

    /** Two suites short by the same count: the group's line says the count, and the names do not repeat it. */
    @Test
    void aShortfallTheGroupNamesIsNotRepeatedAfterEachSuite() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(),
            List.of(
                new BrokenSuite("IgniteTests24Java8_Ml", 1L, "Machine Learning", List.of("ran 12 of master's 35 tests"),
                    12, 35),
                new BrokenSuite("IgniteTests24Java8_Ml2", 2L, "Machine Learning 2",
                    List.of("ran 12 of master's 35 tests"), 12, 35))));

        assertThat(md).contains("- ran 12 of master's 35 tests (2 suites): Machine Learning, Machine Learning 2\n");
    }

    /** Two suites hung the same way, each after its own share of master's tests: neither's numbers fit the other. */
    @Test
    void aGroupLineGivesEachSuiteItsOwnShortfall() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(),
            List.of(
                new BrokenSuite("IgniteTests24Java8_CacheFailover5", 1L, "Cache (Failover) 5", List.of(
                    "execution timeout", "Number of tests 5 is 93% less than 67 in build #1141"), 5, 67),
                new BrokenSuite("IgniteTests24Java8_DiskPageCompressions8", 2L, "Disk Page Compressions 8", List.of(
                    "execution timeout", "Number of tests 50 is 77% less than 216 in build #2313"), 50, 216))));

        assertThat(md)
            .contains("- 2 suites have no reliable result (2× execution timeout · Number of tests N is N% less than "
                + "N in build #N)\n")
            .contains("- execution timeout · Number of tests N is N% less than N in build #N (2 suites): Cache "
                + "(Failover) 5 (ran 5 of master's 67 tests), Disk Page Compressions 8 (ran 50 of master's 216 "
                + "tests)\n")
            .doesNotContain("#1141", "#2313");
    }
}
