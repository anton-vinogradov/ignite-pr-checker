package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The visa must read green only when the run really is: watch items and a cut-short chain are not. */
class VisaServiceTest {
    private final VisaService visas = new VisaService(
        new TeamcityProperties("https://ci2.ignite.apache.org/"), "https://ignite-pr-checker.is-a.dev");

    @Test
    void nothingToReportReadsGreen() {
        String md = visas.composeMarkdown(42, result(List.of(), List.of(), false));

        assertThat(md).contains("✅ **No blockers**");
        assertThat(visas.compose(42, result(List.of(), List.of(), false))).contains("(/) *No blockers*");
    }

    /** PR #13421: the whole point — a red RunAll whose findings are all watch items does not read clean. */
    @Test
    void watchItemsAreNamedAndKeepTheVisaOutOfTheGreenBranch() {
        AnalysisResult r = result(List.of(), List.of(verdict(1L, "CalciteSql2: T.testJoin")), false);

        String md = visas.composeMarkdown(42, r);
        assertThat(md)
            .doesNotContain("No blockers")
            .startsWith("⚠️ **No proven blockers yet, 1 test needs a re-run** · [full verdict](")
            .contains("- Calcite SQL 2 · `T.testJoin` — one run of this code")
            .contains("**Next:** re-run Calcite SQL 2.");

        assertThat(visas.compose(42, r))
            .doesNotContain("No blockers")
            .startsWith("(!) *No proven blockers yet, 1 test needs a re-run* — [Ignite PR Checker|")
            .contains("*Next:* re-run Calcite SQL 2.");
    }

    /**
     * The verdict is composed from the analysis alone, so it must not claim re-runs are happening: a
     * manual visa, a subscription, or auto-rerun switched off all reach this text with nothing queued —
     * and the settled/skipped re-run lines StandingVisas appends would contradict it outright.
     */
    @Test
    void theVisaNeverClaimsARerunIsUnderWay() {
        AnalysisResult r = result(List.of(), List.of(verdict(1L, "CalciteSql2: T.testJoin")), false);

        assertThat(visas.composeMarkdown(42, r)).doesNotContain("being re-run");
        assertThat(visas.compose(42, r)).doesNotContain("being re-run");
    }

    /** Same bar for a chain that never finished — it reaches the reader as a coverage caveat. */
    @Test
    void anInterruptedChainSaysSoInsteadOfClaimingAllClear() {
        AnalysisResult r = result(List.of(), List.of(), true);

        assertThat(visas.composeMarkdown(42, r))
            .doesNotContain("✅ **No blockers**")
            .startsWith("⚠️ **No blockers found, 7 suites never ran** · ")
            .contains("**Next:** run RunAll again to cover the suites that never ran.");
        assertThat(visas.compose(42, r))
            .doesNotContain("(/) *No blockers*")
            .startsWith("(!) *No blockers found, 7 suites never ran* — ");
    }

    @Test
    void blockersStillWinTheHeadline() {
        AnalysisResult r = result(List.of(verdict(1L, "S: T.broke")), List.of(verdict(2L, "S: T.maybe")), false);

        assertThat(visas.composeMarkdown(42, r))
            .startsWith("❌ **1 blocker in 1 suite** · ")
            .contains("1 test started failing on this code, not proven a blocker yet:\n- Calcite SQL 2 · `T.maybe`")
            .doesNotContain("No proven blockers yet");
    }

    /**
     * Two runs of Calcite SQL 2 in one chain, the second a re-run, each failing a test of its own: the page counts
     * one suite, and the comment counted two suite runs.
     */
    @Test
    void blockersAreCountedBySuiteAsThePageCountsThem() {
        TestVerdict first = new TestVerdict(1L, "T.testJoin", "CalciteSql2", 9244697L, "Calcite SQL 2", "o1", true,
            false, "reason", "FF", 2);
        TestVerdict rerun = new TestVerdict(2L, "T.testUnion", "CalciteSql2", 9244800L, "Calcite SQL 2", "o2", true,
            false, "reason", "FF", 2);
        AnalysisResult r = result(List.of(first, rerun), List.of(), false);

        assertThat(visas.composeMarkdown(42, r)).startsWith("❌ **2 blockers in 1 suite** · ");
        assertThat(visas.compose(42, r)).startsWith("(x) *2 blockers in 1 suite* — ");
    }

    /**
     * A suite of a RunAll ran 467 of master's 861 tests: the comment and the visa said "467 tests vs 861 on master",
     * the page "ran 467 tests · master runs 861", and a broken suite's line "ran 1 of master's 3 tests". All three use
     * the last form. A shrunk suite may have run no test at all, and its line still gives the count.
     */
    @Test
    void aSuiteWithFewerTestsReadsAsOnThePage() {
        AnalysisResult r = new AnalysisResult(42, 9244731L, "pull/42/head", System.currentTimeMillis(), List.of(),
            List.of(), List.of(), List.of(), List.of(new ShrunkSuite("Pds2", "PDS 2", 2L, 467, 861, 46),
                new ShrunkSuite("Cache3", "Cache 3", 3L, 0, 120, 100)), 120, 9, false, 0, false, 0, 0, 0, 0, 0);

        assertThat(visas.composeMarkdown(42, r)).contains("⚠️ **No blockers found, 2 suites ran fewer tests than on "
            + "master** · ", "\n\n- PDS 2: ran 467 of master's 861 tests (−46%)\n"
            + "- Cache 3: ran 0 of master's 120 tests (−100%)\n\n**Next:** find out why they ran fewer tests");
        assertThat(visas.compose(42, r)).contains("(!) *No blockers found, 2 suites ran fewer tests than on master* — ",
            "\n\n- PDS 2: ran 467 of master's 861 tests (-46%)\n- Cache 3: ran 0 of master's 120 tests (-100%)\n\n");
        assertThat(visas.composeMarkdown(42, r) + visas.compose(42, r)).doesNotContain("far fewer", " vs ");
    }

    private static AnalysisResult result(List<TestVerdict> blockers, List<TestVerdict> watch, boolean interrupted) {
        return new AnalysisResult(42, 9244731L, "pull/42/head", System.currentTimeMillis(),
            blockers, watch, List.of(), List.of(), List.of(), 120, 9, interrupted, interrupted ? 7 : 0, false, 0, 0, 0, 0, 0);
    }

    private static TestVerdict verdict(long testId, String name) {
        return new TestVerdict(testId, name, "CalciteSql2", 9244697L, "Calcite SQL 2", "o1",
            false, false, "reason", "PF", 1);
    }
}
