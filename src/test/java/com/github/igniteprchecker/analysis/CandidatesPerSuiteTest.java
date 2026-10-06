package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * One test id runs in several suites of a chain: the C++ thin-client tests of apache/ignite#13335 run on
 * Windows, Linux and Clang. Each suite's failure of it is judged by that suite's own runs and master
 * history, so each must reach the verdict as a candidate of its own; within one suite it stays one.
 */
class CandidatesPerSuiteTest {
    private static final String TOK = "t";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String CHAIN_QUEUED = "20261005T202556+0000";

    private static final long RECONNECT = 5272433775095107011L;

    private static final String NAME = "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientReconnect";

    private static final String WIN = "IgniteTests24Java8_PlatformCCMakeWinX64Release";

    private static final String LINUX = "IgniteTests24Java8_PlatformCPPCMakeLinux";

    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector collector = new ChainCollector(tc, mock(SuiteBaseline.class));

    /**
     * The case from the issue: Windows comes first among the chain's suites and its re-run passed, while
     * Linux fails steadily. Keyed by test id, only the Windows failure was classified, filed as "passed on
     * re-run", and the steady Linux break appeared nowhere.
     */
    @Test
    void aSteadyFailureInOneSuiteIsNotHiddenByAPassingRerunInAnother() {
        chainWith(suite(9388972L, WIN), suite(9388971L, LINUX));
        reconnectFails(9388972L, 9388971L);
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, collector, cfg, Executors.newFixedThreadPool(2),
            Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), new AnalysisCache(cfg, mapper),
            new RunDeltaStore(mapper));
        when(tc.findRunAllBuildForAnalysis(TOK, PR)).thenReturn(Optional.of(chainBuild(CHAIN, List.of())));
        for (String suite : List.of(WIN, LINUX))
            when(tc.getBaseBranchHistory(TOK, RECONNECT, suite)).thenReturn(Collections.nCopies(33, pass()));
        when(tc.prBranchRuns(TOK, PR, RECONNECT, WIN)).thenReturn(List.of(
            run("FAILURE", 9388972L, WIN), run("SUCCESS", 9389300L, WIN)));
        when(tc.prBranchRuns(TOK, PR, RECONNECT, LINUX)).thenReturn(List.of(
            run("FAILURE", 9388971L, LINUX), run("FAILURE", 9389301L, LINUX), run("FAILURE", 9389302L, LINUX)));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).as("Linux fails steadily on a clean master").extracting(TestVerdict::suite)
            .containsExactly(LINUX);
        assertThat(r.filtered()).extracting(TestVerdict::suite, TestVerdict::reason)
            .containsExactly(tuple(WIN, "not failing in the last finished run (passed on re-run)"));
    }

    @Test
    void aTestFailingInTwoSuitesOfTheChainIsACandidateInEach() {
        chainWith(suite(9388970L, CLANG), suite(9388971L, LINUX));
        reconnectFails(9388970L, 9388971L);

        assertThat(collect().failedTests()).extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId)
            .containsExactlyInAnyOrder(tuple(RECONNECT, CLANG, 9388970L), tuple(RECONNECT, LINUX, 9388971L));
    }

    @Test
    void aNewerChainFailingTheTestInAnotherSuiteAddsThatSuitesCandidate() {
        chainWith(suite(9388971L, LINUX), suite(9388970L, CLANG));
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(chainBuild(9390500L, List.of())));
        when(tc.getBuildWithDeps(TOK, 9390500L)).thenReturn(chainBuild(9390500L, List.of(suite(9390470L, CLANG))));
        reconnectFails(9388971L, 9390470L);
        failing(9388970L, 1001L, "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientSsl");

        assertThat(collect().failedTests()).extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId)
            .containsExactlyInAnyOrder(tuple(RECONNECT, LINUX, 9388971L), tuple(1001L, CLANG, 9388970L),
                tuple(RECONNECT, CLANG, 9390470L));
    }

    @Test
    void aRerunFailingTheTestInAnotherSuiteAddsThatSuitesCandidate() {
        chainWith(suite(9388971L, LINUX), suite(9388970L, CLANG));
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(suite(9389219L, CLANG)));
        reconnectFails(9388971L, 9389219L);
        failing(9388970L, 1001L, "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientSsl");

        assertThat(collect().failedTests()).extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId)
            .containsExactlyInAnyOrder(tuple(RECONNECT, LINUX, 9388971L), tuple(1001L, CLANG, 9388970L),
                tuple(RECONNECT, CLANG, 9389219L));
    }

    /** A re-run of the same suite failing the test again is one more run of the chain's candidate, not a second one. */
    @Test
    void aRerunFailingTheTestInTheSameSuiteKeepsOneCandidate() {
        chainWith(suite(9388971L, LINUX));
        when(tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED)).thenReturn(List.of(suite(9389219L, LINUX)));
        reconnectFails(9388971L, 9389219L);

        assertThat(collect().failedTests()).extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId)
            .containsExactly(tuple(RECONNECT, LINUX, 9388971L));
    }

    private ChainCollector.Chain collect() {
        return collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
    }

    private void chainWith(TcModel.Build... deps) {
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(CHAIN, Arrays.asList(deps)));
    }

    private void reconnectFails(long... suiteBuildIds) {
        for (long id : suiteBuildIds)
            failing(id, RECONNECT, NAME);
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

    private static TcModel.Build suite(long id, String buildTypeId) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/" + PR + "/head", buildTypeId, null, null, null, null,
            null, null, new TcModel.BuildType(buildTypeId, buildTypeId), null, null, null, null, null,
            new TcModel.TestOccurrences(100, List.of()));
    }

    /** A finished branch run of the test in a suite build, all on the one revision under review. */
    private static TcModel.TestOccurrence run(String status, long buildId, String suite) {
        return new TcModel.TestOccurrence("id:1,build:(id:" + buildId + ")", NAME, status, null,
            new TcModel.BuildRef(buildId, null, "finished", status, suite, null,
                new TcModel.Revisions(List.of(new TcModel.Revision("ec2c458")))), null);
    }

    private static TcModel.TestOccurrence pass() {
        return new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null);
    }
}
