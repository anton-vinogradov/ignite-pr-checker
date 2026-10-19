package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PR #69 of apache/ignite-python-thin-client ran Thin client: Python as build 9424005, the PR's head against Ignite
 * master. Its failures are judged by the rules of a suite of a RunAll: master's runs of the suite, other PRs' runs of
 * it, and the suite's runs on the client's branch {@code pull/69}, never on Ignite's {@code pull/69/head}. A run on the
 * same Ignite commit but another commit of the client is other code, and a run on {@code pull/69} started without the
 * PR's commit in OVERRIDDEN_BRANCH tested the client's master.
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

    /** The suite's finished builds on pull/69 as TeamCity lists them, with the OVERRIDDEN_BRANCH each ran with. */
    private final Map<Long, String> checkouts = new LinkedHashMap<>();

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
        checkouts.put(BUILD, CLIENT);
        when(tc.finishedRunsParameter(TOK, SUITE, "pull/69", "OVERRIDDEN_BRANCH")).thenAnswer(inv ->
            new LinkedHashMap<>(checkouts));
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
        checkouts.put(9423000L, "c0ffee0c0ffee0c0ffee0c0ffee0c0ffee0c0ffe");
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(9423000L, "c0ffee0c0ffee0c0ffee0c0ffee0c0ffee0c0ffe", "SUCCESS"), branchRun(BUILD, CLIENT)));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.filtered()).extracting(TestVerdict::name).containsExactly("test_cache_config.test_get");
        assertThat(r.watch()).extracting(TestVerdict::reason).containsExactly("failed the only run on commits 1f2e3d4"
            + " and a1b2c3d; the 1 earlier run was on other code");
    }

    /**
     * Someone pressed Run on pull/69 in TeamCity before the /run-all. That run took the PR's two commits from the VCS
     * roots, but OVERRIDDEN_BRANCH stayed master, so it tested the client's master and passed. Taken for a run of the
     * PR's code, its pass made a flake of a failure master never had, and the PR was told "No blockers".
     */
    @Test
    void aRunThatTestedTheClientsMasterIsNoRunOfThePr() {
        checkouts.put(9423500L, "master");
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(9423500L, CLIENT, "SUCCESS"), branchRun(BUILD, CLIENT)));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.blockers()).extracting(TestVerdict::name, TestVerdict::reason, TestVerdict::branchRuns)
            .containsExactly(tuple("test_sql.test_sql_fields_query",
                "not seen failing in 100 master runs; failed the only run on this branch", "F"));
    }

    /** The same run of the client's master, finished after the PR's run: its pass does not clear the PR's failure. */
    @Test
    void aLaterRunOfTheClientsMasterDoesNotClearTheFailure() {
        checkouts.put(9424500L, "master");
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(BUILD, CLIENT), branchRun(9424500L, CLIENT, "SUCCESS")));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.blockers()).extracting(TestVerdict::name).containsExactly("test_sql.test_sql_fields_query");
    }

    /**
     * A run on pull/69 finished after the checker listed the suite's builds there, so the checker does not know what
     * the run checked the client out at: its pass does not count.
     */
    @Test
    void aRunMissingFromTheListOfBuildsDoesNotCount() {
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(BUILD, CLIENT), branchRun(9424700L, CLIENT, "SUCCESS")));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.blockers()).extracting(TestVerdict::name).containsExactly("test_sql.test_sql_fields_query");
    }

    /** TeamCity named no OVERRIDDEN_BRANCH of an earlier run: the run is placed by its commits alone. */
    @Test
    void aRunWithoutOverriddenBranchCountsByItsCommits() {
        checkouts.put(9423000L, null);
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(
            branchRun(9423000L, CLIENT, "SUCCESS"), branchRun(BUILD, CLIENT)));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.blockers()).isEmpty();
        assertThat(r.filtered()).extracting(TestVerdict::name, TestVerdict::branchRuns)
            .contains(tuple("test_sql.test_sql_fields_query", "PF"));
    }

    /**
     * ci2 failed a lookup, so the verdict is computed again two minutes later. The new try asks again only for what
     * the last one could not get: with 60 failed tests every try asked for the runs of all 60 on pull/69, while ci2
     * was failing.
     */
    @Test
    void aNewTryAsksOnlyForTheRunsTheLastTryCouldNotGet() {
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenThrow(new IllegalStateException("502 Bad Gateway"))
            .thenReturn(List.of(branchRun(BUILD, CLIENT)));

        AnalysisResult first = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);
        AnalysisResult next = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(first.incompleteSince()).isPositive();
        assertThat(next.incompleteSince()).isZero();
        assertThat(next.blockers()).extracting(TestVerdict::name).containsExactly("test_sql.test_sql_fields_query");
        verify(tc, times(1)).branchRuns(TOK, "pull/69", CONFIG_TEST, SUITE);
        verify(tc, times(2)).branchRuns(TOK, "pull/69", SQL_TEST, SUITE);
    }

    /** Between the two tries another run of the suite on pull/69 finished: the new try reads the branch again. */
    @Test
    void aNewTryReadsTheBranchAgainOnceARunFinishedThere() {
        when(tc.otherBranchRuns(TOK, SQL_TEST, SUITE)).thenThrow(new IllegalStateException("502 Bad Gateway"));
        analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);
        checkouts.put(9424600L, CLIENT);
        when(tc.branchRuns(TOK, "pull/69", SQL_TEST, SUITE)).thenReturn(List.of(branchRun(BUILD, CLIENT),
            branchRun(9424600L, CLIENT, "SUCCESS")));

        AnalysisResult next = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(next.filtered()).extracting(TestVerdict::name, TestVerdict::reason).contains(tuple(
            "test_sql.test_sql_fields_query", "not failing in the last finished run (passed on re-run)"));
        verify(tc, times(2)).branchRuns(TOK, "pull/69", CONFIG_TEST, SUITE);
    }

    /** TeamCity failed to list the suite's builds on pull/69: no failed test is judged without them. */
    @Test
    void withoutTheListOfBuildsOnTheBranchNoTestIsJudged() {
        when(tc.finishedRunsParameter(TOK, SUITE, "pull/69", "OVERRIDDEN_BRANCH"))
            .thenThrow(new IllegalStateException("502 Bad Gateway"));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.unverified()).extracting(TestVerdict::name).containsExactlyInAnyOrder(
            "test_cache_config.test_get", "test_sql.test_sql_fields_query");
        assertThat(r.incompleteSince()).isPositive();
        verify(tc, never()).branchRuns(anyString(), anyString(), anyLong(), anyString());
    }

    /**
     * The test fails in three other PRs of Ignite, run the way this one is, and one of them is Ignite's PR #69: it is
     * not the client's PR #69, so its failure counts like the others, and the PR is not blamed.
     */
    @Test
    void failuresInOtherIgnitePrsClearTheClientsPrIgnitesPrOfTheSameNumberIncluded() {
        when(tc.otherBranchRuns(TOK, SQL_TEST, SUITE)).thenReturn(List.of(otherPrRun(9420003L, 69, "FAILURE"),
            otherPrRun(9420002L, 13001, "FAILURE"), otherPrRun(9420001L, 13002, "FAILURE"),
            otherPrRun(9420000L, 13003, "SUCCESS")));

        AnalysisResult r = analyzer.analyzeSuiteRun(TOK, 69, "pull/69", BUILD);

        assertThat(r.blockers()).isEmpty();
        assertThat(r.filtered()).extracting(TestVerdict::name, TestVerdict::reason).contains(tuple(
            "test_sql.test_sql_fields_query", "flaky on other PR branches: fails 3/4 in 3 other PRs; not seen failing"
                + " in 100 master runs"));
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

    /** A run of the suite in Ignite's PR {@code pr}, which runs it in its RunAll. */
    private static TcModel.TestOccurrence otherPrRun(long build, int pr, String status) {
        return new TcModel.TestOccurrence("o" + build, null, status, null, new TcModel.BuildRef(build,
            "pull/" + pr + "/head", "finished", status, SUITE, null, null, null), null);
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
