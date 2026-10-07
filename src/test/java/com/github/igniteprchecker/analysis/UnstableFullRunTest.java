package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * PR 13644, Snapshots 6: the suite ran all 233 of master's tests, failed one and then hit a JVM crash.
 * The crash made it a broken suite whose failed test was never asked for, so the page said "JVM crash"
 * without naming the test, a flake master shares held the PR on "not proven", and the suite went into
 * every re-run wave. A suite that crashed after running all its tests has its failures classified.
 */
class UnstableFullRunTest {
    private static final String TOK = "t";

    private static final int PR = 13644;

    private static final long CHAIN = 9391000L;

    private static final String SNAPSHOTS6 = "IgniteTests24Java8_Snapshots6";

    private static final long SNAPSHOTS6_RUN = 9391066L;

    private static final long TEST = 4242L;

    private static final String NAME = "IgniteSnapshotTestSuite: SnapshotRestoreTest.testRestore";

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final ChainCollector collector = new ChainCollector(tc, baseline);

    @Test
    void theFailuresOfASuiteThatCrashedAfterRunningAllItsTestsAreCollected() {
        chainWith(snapshots6(233, "TC_JVM_CRASH"), 233);
        testFails();

        ChainCollector.Chain chain = collect();

        assertThat(chain.failedTests()).extracting(FailedTest::testId, FailedTest::suite, FailedTest::suiteBuildId)
            .containsExactly(tuple(TEST, SNAPSHOTS6, SNAPSHOTS6_RUN));
        assertThat(chain.brokenSuites()).as("whether it is broken waits for its tests' verdicts").isEmpty();
        assertThat(chain.unstableSuites()).extracting(BrokenSuite::suite, BrokenSuite::problems)
            .containsExactly(tuple(SNAPSHOTS6, List.of("JVM crash / out of memory")));
    }

    @Test
    void aCrashedSuiteWhoseFailuresAreAllKnownIsANoteNotABrokenSuite() {
        chainWith(snapshots6(233, "TC_JVM_CRASH"), 233);
        testFails();
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS6)).thenReturn(master(12, 100));

        AnalysisResult r = analyze();

        assertThat(r.filtered()).extracting(TestVerdict::name, TestVerdict::reason)
            .containsExactly(tuple(NAME, "pre-existing: fails 12/100 on master"));
        assertThat(r.brokenSuites()).isEmpty();
        assertThat(r.unstableSuites()).extracting(BrokenSuite::suite).containsExactly(SNAPSHOTS6);
        assertThat(Caveats.proven(r)).as("every test ran, and nothing it failed is this PR's").isTrue();
    }

    /** The PR may be what crashed the JVM: a suite with a blocker of its own stays broken. */
    @Test
    void aCrashedSuiteWithABlockerStaysBroken() {
        chainWith(snapshots6(233, "TC_OOME"), 233);
        testFails();
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS6)).thenReturn(master(0, 100));

        AnalysisResult r = analyze();

        assertThat(r.blockers()).extracting(TestVerdict::name).containsExactly(NAME);
        assertThat(r.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(SNAPSHOTS6);
        assertThat(r.unstableSuites()).isEmpty();
    }

    /** The real shape of Cache (Failover) 5 on PR 13434: timed out after 33 of master's 67 tests. */
    @Test
    void aSuiteThatCrashedBeforeRunningAllItsTestsIsBrokenAndNotMined() {
        chainWith(snapshots6(33, "TC_EXECUTION_TIMEOUT"), 67);

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(SNAPSHOTS6);
        assertThat(chain.failedTests()).isEmpty();
        verify(tc, never()).getFailedTests(TOK, SNAPSHOTS6_RUN);
    }

    @Test
    void withoutMastersCountACrashedSuiteIsNotTrustedToHaveRunEverything() {
        chainWith(snapshots6(233, "TC_JVM_CRASH"), null);

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(SNAPSHOTS6);
        verify(tc, never()).getFailedTests(TOK, SNAPSHOTS6_RUN);
    }

    /** Nothing failed but the crash itself: then the crash is all there is to say about the suite. */
    @Test
    void aCrashedFullRunWithoutFailedTestsIsBroken() {
        chainWith(snapshots6(233, "TC_JVM_CRASH"), 233);

        ChainCollector.Chain chain = collect();

        assertThat(chain.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(SNAPSHOTS6);
        assertThat(chain.unstableSuites()).isEmpty();
    }

    private ChainCollector.Chain collect() {
        return collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
    }

    private AnalysisResult analyze() {
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, collector, cfg, Executors.newFixedThreadPool(2),
            Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), new AnalysisCache(cfg, mapper),
            new RunDeltaStore(mapper));
        when(tc.findRunAllBuildForAnalysis(TOK, PR)).thenReturn(Optional.of(chainBuild(List.of())));
        when(tc.prBranchRuns(TOK, PR, TEST, SNAPSHOTS6)).thenReturn(List.of(new TcModel.TestOccurrence(
            "id:" + TEST, NAME, "FAILURE", null, new TcModel.BuildRef(SNAPSHOTS6_RUN, null, "finished", "FAILURE",
                SNAPSHOTS6, null, null, null), null)));

        return analyzer.analyze(TOK, PR).orElseThrow();
    }

    private void chainWith(TcModel.Build dep, Integer masterTests) {
        when(baseline.counts(anyString())).thenReturn(masterTests == null ? Map.of() : Map.of(SNAPSHOTS6, masterTests));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(List.of(dep)));
    }

    private void testFails() {
        when(tc.getFailedTests(TOK, SNAPSHOTS6_RUN)).thenReturn(List.of(new TcModel.TestOccurrence(
            "id:" + TEST + ",build:(id:" + SNAPSHOTS6_RUN + ")", NAME, "FAILURE", new TcModel.TestRef(TEST), null, null)));
    }

    private static List<TcModel.TestOccurrence> master(int fails, int runs) {
        List<TcModel.TestOccurrence> out = new ArrayList<>(Collections.nCopies(runs - fails, occurrence("SUCCESS")));
        out.addAll(Collections.nCopies(fails, occurrence("FAILURE")));

        return out;
    }

    private static TcModel.TestOccurrence occurrence(String status) {
        return new TcModel.TestOccurrence(null, null, status, null, null, null);
    }

    private static TcModel.Build chainBuild(List<TcModel.Build> deps) {
        return new TcModel.Build(CHAIN, "FAILURE", "finished", "pull/" + PR + "/head", "IgniteTests24Java8_RunAll", null,
            null, null, null, null, null, new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }

    private static TcModel.Build snapshots6(int tests, String problem) {
        return new TcModel.Build(SNAPSHOTS6_RUN, "FAILURE", "finished", "pull/" + PR + "/head", SNAPSHOTS6, null, null,
            null, null, null, null, new TcModel.BuildType(SNAPSHOTS6, "Snapshots 6"), null, null, null,
            new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(problem, null))), null,
            new TcModel.TestOccurrences(tests, List.of()));
    }
}
