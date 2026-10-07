package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.config.TeamcityProperties;
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
            Platform .NET (Windows) and 140 more""");
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

    @Test
    void theArtifactGlitchIsOneLineAndTheCaveatNamesTheCauses() {
        String md = visas.composeMarkdown(BrokenRuns.PR_13655, BrokenRuns.artifactsGlitch());

        assertThat(md)
            .contains("- 64 suite(s) have no reliable result (60× ci2 glitch: artifacts unavailable; 2× non-zero "
                + "exit code; execution timeout; …)\n")
            .contains("⚠️ **Broken suites** (no reliable run):\n"
                + "- ci2 glitch: artifacts unavailable (60 suites): Suite 1, Suite 2, Suite 3, Suite 4, Suite 5 and 55 "
                + "more\n"
                + "- non-zero exit code (2 suites): PDS 5, PDS 6\n"
                + "- Cache 5: execution timeout — ran 12 of master's 35 tests\n"
                + "- Basic 3: Failed to create temporary custom script file")
            .doesNotContain("Suite 6,");
    }
}
