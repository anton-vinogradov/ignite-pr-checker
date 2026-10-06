package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * A suite re-run on its own belongs to no RunAll chain, yet what it fails is as much a branch failure
 * as anything a chain fails. Built on the real shape of RunAll 9389046 for apache/ignite#13335: the
 * checker re-ran Queries 5 as 9389219, which cleared the chain's failure and failed {@code testRestarts}
 * instead — a test that never reached the verdict.
 */
class SingleSuiteRerunTest {
    private static final String TOK = "t";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String CHAIN_QUEUED = "20261005T202556+0000";

    private static final String QUERIES5 = "IgniteTests24Java8_Queries5";

    private static final String QUERIES6 = "IgniteTests24Java8_Queries6";

    private static final String FAILOVER5 = "IgniteTests24Java8_CacheFailover5";

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final ChainCollector collector = new ChainCollector(tc, baseline);

    @Test
    void aTestOnlyTheSingleSuiteRerunFailedBecomesACandidate() {
        chainWith(
            suite(9389028L, QUERIES5, "Queries 5", "FAILURE"),
            suite(9389029L, QUERIES6, "Queries 6", "FAILURE"));
        failing(9389028L, 1001L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing");
        failing(9389029L, 1002L, "IndexingSpiQuerySelfTest.testIndexingSpi");
        failing(9389219L, 1003L, "IgniteCacheQueryNodeRestartSelfTest.testRestarts");
        // What ci2 answers for this chain: the re-run plus the chain's own failed suites.
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(
            suite(9389219L, QUERIES5, "Queries 5", "FAILURE"),
            suite(9389029L, QUERIES6, "Queries 6", "FAILURE"),
            suite(9389028L, QUERIES5, "Queries 5", "FAILURE")));

        ChainCollector.Chain chain = collect();

        assertThat(chain.failedTests())
            .as("the failure only the re-run 9389219 saw must be classified like any other")
            .extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId, FailedTest::suiteName)
            .contains(tuple(1003L, QUERIES5, 9389219L, "Queries 5"));
        assertThat(chain.failedTests()).extracting(FailedTest::testId).containsExactlyInAnyOrder(1001L, 1002L, 1003L);
        verify(tc, times(1)).getFailedTests(TOK, 9389028L);
        verify(tc, times(1)).getFailedTests(TOK, 9389029L);
    }

    /**
     * Only a build newer than the chain's own run of that suite is a re-run of it. Suites of a known
     * newer chain are that chain's business, and the RunAll itself is not a suite of it at all.
     */
    @Test
    void onlyRerunsNewerThanTheChainsOwnRunOfTheSuiteAreFetched() {
        TcModel.Build newerQueries5 = suite(9390400L, QUERIES5, "Queries 5", "FAILURE");
        chainWith(
            suite(9389028L, QUERIES5, "Queries 5", "FAILURE"),
            suite(9389029L, QUERIES6, "Queries 6", "FAILURE"));
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(
            new TcModel.Build(9390500L, "FAILURE", "finished", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null),
            chainBuild(CHAIN, List.of())));
        when(tc.getBuildWithDeps(TOK, 9390500L)).thenReturn(chainBuild(9390500L, List.of(newerQueries5)));
        failing(9389028L, 1001L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing");
        failing(9389029L, 1002L, "IndexingSpiQuerySelfTest.testIndexingSpi");
        failing(9390400L, 1004L, "IgniteCacheQueryNodeRestartSelfTest.testRestarts");
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(
            suite(9390500L, "IgniteTests24Java8_RunAll", "Run All", "FAILURE"),
            newerQueries5,
            suite(9389010L, QUERIES6, "Queries 6", "FAILURE"),
            suite(9389029L, QUERIES6, "Queries 6", "FAILURE")));

        ChainCollector.Chain chain = collect();

        assertThat(chain.failedTests()).extracting(FailedTest::testId).containsExactlyInAnyOrder(1001L, 1002L, 1004L);
        assertThat(chain.brokenSuites()).as("nothing here broke").isEmpty();
        verify(tc, times(1)).getFailedTests(TOK, 9390400L);
        verify(tc, never()).getFailedTests(TOK, 9390500L);
        verify(tc, never()).getFailedTests(TOK, 9389010L);
    }

    /**
     * A re-run is judged by the same suite rules as a chain dependency, so one that broke is a broken
     * suite. Re-running a suite because it broke, and having it break again, is still one broken suite
     * — the count reaches the visa — anchored at the newest run, the one the verdict is about.
     */
    @Test
    void aRerunThatBrokeIsABrokenSuiteAndSupersedesTheChainsEntryForIt() {
        chainWith(
            suite(9389028L, QUERIES5, "Queries 5", "FAILURE"),
            suite(9389033L, FAILOVER5, "Cache (Failover) 5", "FAILURE", "TC_EXECUTION_TIMEOUT"));
        failing(9389028L, 1001L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing");
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(
            suite(9389250L, FAILOVER5, "Cache (Failover) 5", "FAILURE", "TC_EXECUTION_TIMEOUT"),
            suite(9389219L, QUERIES5, "Queries 5", "FAILURE", "TC_COMPILATION_ERROR")));

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites())
            .extracting(BrokenSuite::suite, BrokenSuite::suiteBuildId, BrokenSuite::problems)
            .containsExactlyInAnyOrder(
                tuple(FAILOVER5, 9389250L, List.of("execution timeout")),
                tuple(QUERIES5, 9389219L, List.of("compilation error")));
        assertThat(chain.failedTests()).extracting(FailedTest::testId).containsExactly(1001L);
    }

    /**
     * A chain run that broke is not a shrunk suite as well: its own break explains its missing tests.
     * That holds after a re-run that broke again has taken its place in the broken list, or one suite
     * would read twice in the visa, as "no reliable result" and as "ran far fewer tests".
     */
    @Test
    void aChainRunThatBrokeStaysOutOfTheShrunkSuitesAfterItsRerunBrokeToo() {
        chainWith(
            suite(9389028L, QUERIES5, "Queries 5", "FAILURE"),
            run(9389033L, FAILOVER5, "Cache (Failover) 5", 30, "TC_EXECUTION_TIMEOUT"));
        when(baseline.counts(anyString())).thenReturn(Map.of(FAILOVER5, 100));
        failing(9389028L, 1001L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing");
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(
            run(9389250L, FAILOVER5, "Cache (Failover) 5", 25, "TC_EXECUTION_TIMEOUT")));

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).extracting(BrokenSuite::suite, BrokenSuite::suiteBuildId)
            .containsExactly(tuple(FAILOVER5, 9389250L));
        assertThat(chain.shrunkSuites())
            .as("the chain's run 9389033 timed out, so its 30 of 100 tests are that timeout's doing")
            .isEmpty();
    }

    /** A re-run between the chain and a newer chain that broke the same suite leaves the newer entry alone. */
    @Test
    void aRerunOlderThanANewerChainsBrokenRunDoesNotReplaceIt() {
        chainWith(run(9389033L, FAILOVER5, "Cache (Failover) 5", 30, "TC_EXECUTION_TIMEOUT"));
        TcModel.Build newerFailover5 = run(9390400L, FAILOVER5, "Cache (Failover) 5", 20, "TC_EXECUTION_TIMEOUT");
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(
            new TcModel.Build(9390500L, "FAILURE", "finished", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null),
            chainBuild(CHAIN, List.of())));
        when(tc.getBuildWithDeps(TOK, 9390500L)).thenReturn(chainBuild(9390500L, List.of(newerFailover5)));
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(
            newerFailover5,
            run(9389250L, FAILOVER5, "Cache (Failover) 5", 25, "TC_EXECUTION_TIMEOUT")));

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).extracting(BrokenSuite::suite, BrokenSuite::suiteBuildId)
            .containsExactly(tuple(FAILOVER5, 9390400L));
    }

    private ChainCollector.Chain collect() {
        return collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
    }

    private void chainWith(TcModel.Build... deps) {
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(CHAIN, Arrays.asList(deps)));
    }

    private void failing(long suiteBuildId, long testId, String name) {
        when(tc.getFailedTests(TOK, suiteBuildId)).thenReturn(List.of(new TcModel.TestOccurrence(
            "id:" + testId + ",build:(id:" + suiteBuildId + ")", name, "FAILURE", new TcModel.TestRef(testId), null, null)));
    }

    private static TcModel.Build chainBuild(long id, List<TcModel.Build> deps) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/" + PR + "/head", "IgniteTests24Java8_RunAll", null,
            id == CHAIN ? CHAIN_QUEUED : null, null, null, null, null,
            new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }

    private static TcModel.Build suite(long id, String buildTypeId, String name, String status, String... problems) {
        return run(id, buildTypeId, name, status, 100, problems);
    }

    /** A FAILURE run that got through {@code tests} tests. */
    private static TcModel.Build run(long id, String buildTypeId, String name, int tests, String... problems) {
        return run(id, buildTypeId, name, "FAILURE", tests, problems);
    }

    private static TcModel.Build run(long id, String buildTypeId, String name, String status, int tests,
        String... problems) {
        TcModel.ProblemOccurrences occurred = new TcModel.ProblemOccurrences(Arrays.stream(problems)
            .map(type -> new TcModel.ProblemOccurrence(type, null)).toList());

        return new TcModel.Build(id, status, "finished", "pull/" + PR + "/head", buildTypeId, null, null, null, null,
            null, null, new TcModel.BuildType(buildTypeId, name), null, null, null, occurred, null,
            new TcModel.TestOccurrences(tests, List.of()));
    }
}
