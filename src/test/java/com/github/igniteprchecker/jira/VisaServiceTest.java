package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.ArrayList;
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

    /** PR #13421: the whole point — a red RunAll whose findings are all watch items is not an all-clear. */
    @Test
    void watchItemsAreNamedAndKeepTheVisaOutOfTheGreenBranch() {
        AnalysisResult r = result(List.of(), List.of(verdict(1L, "CalciteSql2: T.testJoin")), false);

        String md = visas.composeMarkdown(42, r);
        assertThat(md)
            .doesNotContain("No blockers")
            .contains("👀 **1 test started failing on this code**")
            .contains("- Calcite SQL 2 · `T.testJoin` — one run of this code")
            .contains("⚠️ **No proven blocker yet**");

        assertThat(visas.compose(42, r))
            .doesNotContain("No blockers")
            .contains("(!) *1 test started failing on this code*")
            .contains("(!) *No proven blocker yet*");
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
            .contains("⚠️ **This run doesn't cover the PR fully:**")
            .contains("the RunAll was interrupted — 7 suites never ran")
            .contains("can't prove the PR is clean");
        assertThat(visas.compose(42, r))
            .doesNotContain("(/) *No blockers*")
            .contains("(!) *This run doesn't cover the PR fully:*")
            .contains("the RunAll was interrupted — 7 suites never ran");
    }

    @Test
    void blockersStillWinTheHeadline() {
        AnalysisResult r = result(List.of(verdict(1L, "S: T.broke")), List.of(verdict(2L, "S: T.maybe")), false);

        assertThat(visas.composeMarkdown(42, r))
            .contains("❌ **1 blocker in 1 suite:**")
            .contains("👀 **1 test started failing on this code**")
            .doesNotContain("No proven blocker yet");
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

        assertThat(visas.composeMarkdown(42, r)).contains("❌ **2 blockers in 1 suite:**");
        assertThat(visas.compose(42, r)).contains("(x) *2 blockers in 1 suite:*");
    }

    /** The page calls the tests it sets aside "Not blamed on this PR"; the comment and the visa said "filtered out". */
    @Test
    void theTestsSetAsideAreNamedAsThePageNamesThem() {
        AnalysisResult clean = withFiltered(List.of(), false, 3);
        AnalysisResult watched = withFiltered(List.of(verdict(1L, "T.testJoin")), false, 1);
        AnalysisResult unproven = withFiltered(List.of(), true, 0);

        assertThat(visas.composeMarkdown(42, clean)).endsWith("3 failing tests not blamed on this PR.\n\n"
            + "<sub>What each label means and what to do: [verdict glossary](" + VisaService.GLOSSARY + ").</sub>");
        assertThat(visas.composeMarkdown(42, watched)).contains("see the tests above. 1 failing test not blamed on this "
            + "PR.");
        assertThat(visas.composeMarkdown(42, unproven)).contains("can't prove the PR is clean.** 0 failing tests not "
            + "blamed on this PR. Re-run once the above is sorted out.");
        assertThat(visas.compose(42, clean)).contains("(/) *No blockers* — nothing in this run looks caused by this PR. "
            + "3 failing tests not blamed on this PR.");
        for (AnalysisResult r : List.of(clean, watched, unproven))
            assertThat(visas.composeMarkdown(42, r) + visas.compose(42, r)).doesNotContain("filtered out");
    }

    private static AnalysisResult withFiltered(List<TestVerdict> watch, boolean interrupted, int filtered) {
        List<TestVerdict> setAside = new ArrayList<>();
        for (int i = 0; i < filtered; i++)
            setAside.add(verdict(100 + i, "T.testFlaky" + i));

        return new AnalysisResult(42, 9244731L, "pull/42/head", System.currentTimeMillis(), List.of(), watch, setAside,
            List.of(), List.of(), 120, 9, interrupted, interrupted ? 7 : 0, false, 0, 0, 0, 0, 0);
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
