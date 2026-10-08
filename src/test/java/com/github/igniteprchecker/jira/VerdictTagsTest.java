package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.analysis.model.TestVerdict.Doubt;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * On prod all 31 blockers of PRs 13655, 13566, 13637, 13632 and 12584 rested on one run, nine of them with no master
 * history at all, and the visa and the PR comment listed them exactly like proven ones; on PR 13566 they stood for two
 * weeks. The page tags such tests since core-4; the visa and the comment now carry the same tags, say how many
 * blockers rest on one run, and say what the tags mean.
 */
class VerdictTagsTest {
    private static final int PR = 13655;

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void aBlockerFromOneRunOfANewTestSaysSo() {
        AnalysisResult r = result(List.of(test("GridCommandHandlerTest.testCacheIdle", true, "F", 1, Doubt.ONE_RUN,
            Doubt.NO_MASTER_HISTORY)), List.of());

        String md = visas.composeMarkdown(PR, r, null);
        String wiki = visas.compose(PR, r, null);

        assertThat(md).contains("❌ **1 blocker(s) in 1 suite(s), all on 1 run:**\n"
            + "- Control Utility · `GridCommandHandlerTest.testCacheIdle` — 1 run; new test / no master history · [TC]")
            .endsWith("\n\n<sub>**1 run**: only one failure on this branch backs it: a re-run of its suite confirms or "
                + "clears it · **new test / no master history**: master has no runs of the test on this JDK to compare "
                + "with: a new test, or one master does not run.</sub>\n\n<sub>What each label means and what to do: "
                + "[verdict glossary](" + VisaService.GLOSSARY + ").</sub>");
        assertThat(wiki).contains("(x) *1 blocker(s) in 1 suite(s), all on 1 run:*\n"
            + "- Control Utility · {{GridCommandHandlerTest.testCacheIdle}} — 1 run; new test / no master history · [TC|")
            .endsWith("\n\n_1 run: only one failure on this branch backs it: a re-run of its suite confirms or clears it"
                + " · new test / no master history: master has no runs of the test on this JDK to compare with: a new "
                + "test, or one master does not run._\n\n_What each label means and what to do: [verdict glossary|"
                + VisaService.GLOSSARY + "]._");
    }

    @Test
    void aClassOfBlockersCountsWhatItsTestsRestOn() {
        AnalysisResult r = result(List.of(
            test("GridCommandHandlerTest.testCacheIdle", true, "F", 1, Doubt.ONE_RUN),
            test("GridCommandHandlerTest.testState", true, "F", 1, Doubt.ONE_RUN),
            test("GridCommandHandlerTest.testBaseline", true, "FFF", 3)), List.of());

        assertThat(visas.composeMarkdown(PR, r, null))
            .contains("❌ **3 blocker(s) in 1 suite(s), 2 of them on 1 run:**\n- Control Utility · "
                + "`GridCommandHandlerTest` — 3 tests (1 run: 2 of 3): `testCacheIdle`, `testState`, `testBaseline`");
        assertThat(visas.compose(PR, r, null))
            .contains("- Control Utility · {{GridCommandHandlerTest}} — 3 tests (1 run: 2 of 3): {{testCacheIdle}}");
    }

    /** A test to watch whose check against other PRs TeamCity errors kept from being made. */
    @Test
    void aTestToWatchSaysItWasNotVerified() {
        AnalysisResult r = result(List.of(), List.of(test("TxRecoveryTest.testRecovery", false, "PPF", 1, Doubt.ONE_RUN,
            Doubt.UNCHECKED)));

        String md = visas.composeMarkdown(PR, r, null);

        assertThat(md).contains("- Control Utility · `TxRecoveryTest.testRecovery` — 1 run; unverified · [TC]")
            .endsWith("<sub>**1 run**: only one failure on this branch backs it: a re-run of its suite confirms or "
                + "clears it · **unverified**: a TeamCity error kept the check of other PRs' runs of the test from "
                + "being made.</sub>\n\n<sub>What each label means and what to do: [verdict glossary]("
                + VisaService.GLOSSARY + ").</sub>");
    }

    @Test
    void aBlockerBackedByEveryRunOfItsCodeCarriesNoTag() {
        AnalysisResult r = result(List.of(test("GridCommandHandlerTest.testBaseline", true, "FFF", 3)), List.of());

        String md = visas.composeMarkdown(PR, r, null);

        assertThat(md).contains("❌ **1 blocker(s) in 1 suite(s):**\n"
            + "- Control Utility · `GridCommandHandlerTest.testBaseline` — failed 3 of 3 runs of this code · [TC]")
            .doesNotContain("<sub>**");
    }

    private static TestVerdict test(String name, boolean blocker, String runs, int codeRuns, Doubt... doubts) {
        return new TestVerdict(name.hashCode(), "org.apache.ignite.util." + name, "IgniteTests24Java8_ControlUtility",
            9390050L, "Control Utility", null, blocker, !blocker, "failed the only run on this branch", runs, codeRuns,
            List.of(doubts));
    }

    private static AnalysisResult result(List<TestVerdict> blockers, List<TestVerdict> watch) {
        return new AnalysisResult(PR, 9390000L, "pull/13655/head", System.currentTimeMillis(), blockers, watch,
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
