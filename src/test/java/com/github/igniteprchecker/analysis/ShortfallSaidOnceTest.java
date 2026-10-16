package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Cache (Failover) 5 of PR 13434 hung on the execution timeout after 33 of master's 67 tests, and TeamCity added
 * "Number of tests 33 is 51% less than 67 in build #1141": the page, the PR comment and the visa said the shortfall
 * twice, in TeamCity's words and as "ran 33 of master's 67 tests". TeamCity's count of the same numbers is left out,
 * and when it was all TeamCity named, the shortfall is the suite's one problem. A count of other numbers stays:
 * TeamCity compared with a run of its own choosing.
 */
class ShortfallSaidOnceTest {
    private static final String TOK = "t";

    private static final String SUITE = "CacheFailover5";

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    @Test
    void teamCitysCountOfTheSameShortfallIsNotASecondProblem() {
        BrokenSuite s = broken(problem("TC_EXECUTION_TIMEOUT", "Execution timeout"),
            problem("BUILD_FAILURE_ON_METRIC", "Number of tests 33 is 51% less than 67 in build #1141"));

        assertThat(s.problems()).containsExactly("execution timeout");
        assertThat(s.tests()).isEqualTo(33);
        assertThat(s.baseline()).isEqualTo(67);
    }

    @Test
    void theShortfallIsTheOneProblemWhenTheCountWasAllTeamCityNamed() {
        BrokenSuite s = broken(problem("BUILD_FAILURE_ON_METRIC", "Number of tests 33 is 51% less than 67 in build #1141"));

        assertThat(s.problems()).containsExactly("ran 33 of master's 67 tests");
    }

    @Test
    void aCountOfOtherNumbersStays() {
        BrokenSuite s = broken(problem("BUILD_FAILURE_ON_METRIC", "Number of tests 30 is 55% less than 67 in build #1141"));

        assertThat(s.problems()).containsExactly("Number of tests 30 is 55% less than 67 in build #1141");
    }

    private BrokenSuite broken(TcModel.ProblemOccurrence... problems) {
        when(baseline.counts(anyString())).thenReturn(Map.of(SUITE, 67));
        TcModel.Build suite = new TcModel.Build(9253651L, "FAILURE", "finished", "pull/42/head", SUITE, null, null, null,
            null, null, null, new TcModel.BuildType(SUITE, "Cache (Failover) 5"), null, null, null,
            new TcModel.ProblemOccurrences(List.of(problems)), null, new TcModel.TestOccurrences(33, List.of()));
        when(tc.getBuildWithDeps(TOK, 999L)).thenReturn(new TcModel.Build(999, "FAILURE", "finished", "pull/42/head",
            "RunAll", null, null, null, null, null, null, null, null, new TcModel.SnapshotDeps(1, List.of(suite)), null,
            null, null, null));
        when(tc.recentChains(TOK, 42, 3)).thenReturn(List.of());

        List<BrokenSuite> broken = new ChainCollector(tc, baseline)
            .collectForBuild(TOK, 42, 999L, Executors.newSingleThreadExecutor()).brokenSuites();

        assertThat(broken).hasSize(1);

        return broken.get(0);
    }

    private static TcModel.ProblemOccurrence problem(String type, String details) {
        return new TcModel.ProblemOccurrence(type, details);
    }
}
