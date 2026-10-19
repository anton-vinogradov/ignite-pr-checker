package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PR #69 of apache/ignite-python-thin-client ran Thin client: Python as build 9424005, the PR's head against Ignite
 * master. Its failures are judged by the rules of a suite of a RunAll: master's runs of the suite, other PRs' runs of
 * it, and the suite's runs on the client's branch {@code pull/69}, never on Ignite's {@code pull/69/head}. A run on the
 * same Ignite commit but another commit of the client is other code.
 */
class ClientSuiteRunTest {
    private static final String TOK = "tok";

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final long BUILD = 9424005L;

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final long CONFIG_TEST = 501L;

    private static final long SQL_TEST = 502L;

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, new ChainCollector(tc, baseline), cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, Jackson2Defaults.mapper()), new RunDeltaStore(Jackson2Defaults.mapper()));

    @BeforeEach
    void setUp() {
        when(baseline.counts(TOK)).thenReturn(Map.of(SUITE, 400));
        when(tc.suiteRun(TOK, BUILD)).thenReturn(run("FAILURE", 398, null));
        when(tc.getFailedTests(TOK, BUILD)).thenReturn(List.of(failed("o1", CONFIG_TEST, "test_cache_config.test_get"),
            failed("o2", SQL_TEST, "test_sql.test_sql_fields_query")));
        when(tc.getBaseBranchHistory(TOK, CONFIG_TEST, SUITE)).thenReturn(master(100, 3, 30, 60));
        when(tc.getBaseBranchHistory(TOK, SQL_TEST, SUITE)).thenReturn(master(100));
        when(tc.otherBranchRuns(anyString(), anyLong(), anyString())).thenReturn(List.of());
        when(tc.branchRuns(TOK, "pull/69", CONFIG_TEST, SUITE)).thenReturn(List.of(branchRun(BUILD, CLIENT)));
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(branchRun(BUILD, CLIENT)));
    }

    @Test
    void aFailureMasterSharesIsNotBlamedAndOneMasterNeverHadIs() {
        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.filtered()).extracting(TestVerdict::name, TestVerdict::reason)
            .containsExactly(tuple("test_cache_config.test_get",
                "pre-existing: fails 3/100 on master"));
        assertThat(r.blockers()).extracting(TestVerdict::name, TestVerdict::reason)
            .containsExactly(tuple("test_sql.test_sql_fields_query",
                "not seen failing in 100 master runs; failed the only run on this branch"));
        assertThat(r.brokenSuites()).isEmpty();
        assertThat(r.buildId()).isEqualTo(BUILD);
        verify(tc, never()).prBranchRuns(anyString(), anyInt(), anyLong(), anyString());
    }

    /**
     * Two runs on the same Ignite commit are not the same code when the client moved: the earlier run, which passed,
     * tested another commit of the PR. Taken by Ignite's commit alone, the pass made a flake of a test the PR's new
     * code failed, and the test was not blamed.
     */
    @Test
    void aRunOfAnotherClientCommitIsOtherCode() {
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(9423000L, "c0ffee0c0ffee0c0ffee0c0ffee0c0ffee0c0ffe", "SUCCESS"), branchRun(BUILD, CLIENT)));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.filtered()).extracting(TestVerdict::name).containsExactly("test_cache_config.test_get");
        assertThat(r.watch()).extracting(TestVerdict::reason).containsExactly("failed the only run on commits 1f2e3d4"
            + " and a1b2c3d; the 1 earlier run was on other code");
    }

    /** Build of Ignite master failed, so the suite ran nothing: no test is judged, the suite has no result. */
    @Test
    void aSuiteKeptFromRunningByItsBuildIsBroken() {
        when(tc.suiteRun(TOK, BUILD)).thenReturn(run("FAILURE", 0, "SNAPSHOT_DEPENDENCY_ERROR"));
        when(tc.getFailedTests(TOK, BUILD)).thenReturn(List.of());

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.brokenSuites()).extracting(BrokenSuite::problems).containsExactly(List.of("failed dependency"));
        assertThat(r.blockers()).isEmpty();
        assertThat(r.filtered()).isEmpty();
    }

    private static TcModel.Build run(String status, int tests, String problem) {
        return new TcModel.Build(BUILD, status, "finished", "pull/69", SUITE, null, "20261019T120000+0000",
            "20261019T121000+0000", "20261019T124000+0000", null, null, new TcModel.BuildType(SUITE,
                "Thin client: Python"), null, null, null, problem == null ? null
                : new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(problem, null))),
            revisions(CLIENT), new TcModel.TestOccurrences(tests, null));
    }

    /** The suite's commits as TeamCity lists them: Ignite's root first, then the client's. */
    private static TcModel.Revisions revisions(String client) {
        return new TcModel.Revisions(List.of(new TcModel.Revision(IGNITE), new TcModel.Revision(client)));
    }

    private static TcModel.TestOccurrence failed(String id, long testId, String name) {
        return new TcModel.TestOccurrence(id, name, "FAILURE", new TcModel.TestRef(testId), null, null);
    }

    private static TcModel.TestOccurrence branchRun(long build, String client) {
        return branchRun(build, client, "FAILURE");
    }

    private static TcModel.TestOccurrence branchRun(long build, String client, String status) {
        return new TcModel.TestOccurrence("b" + build, null, status, null, new TcModel.BuildRef(build, "pull/69",
            "finished", status, SUITE, null, revisions(client), null), null);
    }

    /** {@code runs} master runs, newest first, failing at the given 1-based positions counted from the newest. */
    private static List<TcModel.TestOccurrence> master(int runs, int... failingAt) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            boolean failed = false;
            for (int f : failingAt)
                failed |= f == i;
            out.add(new TcModel.TestOccurrence(null, null, failed ? "FAILURE" : "SUCCESS", null, null, null));
        }

        return out;
    }
}
