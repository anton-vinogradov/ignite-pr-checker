package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
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

    /** PR #13421: the whole point — a red RunAll whose findings are all watch items is not an all-clear. */
    @Test
    void watchItemsAreNamedAndKeepTheVisaOutOfTheGreenBranch() {
        AnalysisResult r = result(List.of(), List.of(verdict(1L, "CalciteSql2: T.testJoin")), false);

        String md = visas.composeMarkdown(42, r);
        assertThat(md)
            .doesNotContain("No blockers")
            .contains("👀 **1 test(s) started failing on this code**")
            .contains("Calcite SQL 2: `CalciteSql2: T.testJoin`")
            .contains("⚠️ **No proven blocker yet**");

        assertThat(visas.compose(42, r))
            .doesNotContain("No blockers")
            .contains("(!) *1 test(s) started failing on this code*")
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
            .contains("the RunAll was interrupted — 7 suite(s) never ran")
            .contains("can't prove the PR is clean");
        assertThat(visas.compose(42, r))
            .doesNotContain("(/) *No blockers*")
            .contains("(!) *This run doesn't cover the PR fully:*")
            .contains("the RunAll was interrupted — 7 suite(s) never ran");
    }

    @Test
    void blockersStillWinTheHeadline() {
        AnalysisResult r = result(List.of(verdict(1L, "S: T.broke")), List.of(verdict(2L, "S: T.maybe")), false);

        assertThat(visas.composeMarkdown(42, r))
            .contains("❌ **1 blocker(s) in 1 suite(s):**")
            .contains("👀 **1 test(s) started failing on this code**")
            .doesNotContain("No proven blocker yet");
    }

    private static AnalysisResult result(List<TestVerdict> blockers, List<TestVerdict> watch, boolean interrupted) {
        return new AnalysisResult(42, 9244731L, "pull/42/head", System.currentTimeMillis(),
            blockers, watch, List.of(), List.of(), List.of(), 120, 9, interrupted, interrupted ? 7 : 0, false, 0, 0, 0, 0);
    }

    private static TestVerdict verdict(long testId, String name) {
        return new TestVerdict(testId, name, "CalciteSql2", 9244697L, "Calcite SQL 2", "o1",
            false, false, "reason", "PF", 1);
    }
}
