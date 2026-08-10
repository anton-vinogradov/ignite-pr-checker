package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A suite failing mid-chain is only re-run when the analysis blames it for something a re-run can
 * settle. Re-running a suite that failed on master's own flakes would burn CI to learn nothing.
 */
class EarlyRerunTest {
    @Test
    void onlyFindingsARerunCanSettleAreWorthIt() {
        assertThat(StandingVisas.worthRerunning(result(verdict(true, false), List.of()), "Cache1")).isTrue();
        assertThat(StandingVisas.worthRerunning(result(verdict(false, true), List.of()), "Cache1")).isTrue();
        assertThat(StandingVisas.worthRerunning(
            result(null, List.of(new BrokenSuite("Cache1", 5L, "Cache 1", List.of("execution timeout"), 0, 0))),
            "Cache1")).isTrue();
    }

    @Test
    void aSuiteThatOnlyFailedOnKnownNoiseIsLeftAlone() {
        assertThat(StandingVisas.worthRerunning(result(verdict(false, false), List.of()), "Cache1")).isFalse();
    }

    @Test
    void anotherSuitesFindingsNeverTriggerThisOne() {
        assertThat(StandingVisas.worthRerunning(result(verdict(true, false), List.of()), "Cache2")).isFalse();
    }

    private static TestVerdict verdict(boolean blocker, boolean watch) {
        return new TestVerdict(1L, "T.test", "Cache1", 5L, "Cache 1", "o1", blocker, watch, "r", "F", 1);
    }

    private static AnalysisResult result(TestVerdict v, List<BrokenSuite> broken) {
        List<TestVerdict> blockers = v != null && v.blocker() ? List.of(v) : List.of();
        List<TestVerdict> watch = v != null && v.watch() ? List.of(v) : List.of();
        List<TestVerdict> filtered = v != null && !v.blocker() && !v.watch() ? List.of(v) : List.of();

        return new AnalysisResult(42, 100L, "pull/42/head", System.currentTimeMillis(),
            blockers, watch, filtered, broken, List.of(), 1, 0, false, 0, true, 100L, 0, 0, 0, 0);
    }
}
