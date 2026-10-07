package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * apache/ignite#13654: {@code testSnapshotCheckMetricsLesserTopology[encryption=true, onlyPrimary=true,
 * snpThrdPoolSz=4]} failed all four runs of Snapshots 8 on the PR's revision. It had failed once in its
 * last 100 master runs, 22 runs back, and was filtered as "pre-existing: fails 1/100 on master", while six
 * sibling parametrizations with the same four failures were blockers. A rare, old master failure no
 * longer outweighs a failure that keeps repeating on the PR's code.
 */
class RareMasterFailureTest {
    private static final String TOK = "tok";

    private static final int PR = 13654;

    private static final long TEST = -1661331956011831017L;

    private static final String SNAPSHOTS8 = "IgniteTests24Java8_Snapshots8";

    private static final String HEAD = "2ff3f44";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @Test
    void fourFailuresOnThePrsCodeOutweighOneMasterFailureLongAgo() {
        failing(master(100, 22), "FFFF");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason())
            .isEqualTo("rare on master: fails 1/100, passed the last 21; failed all 4 runs on this branch");
    }

    /** One failure is no repeat: it stays pre-existing, so no re-run wave is spent on it. */
    @Test
    void aSingleFailureStaysPreExisting() {
        failing(master(100, 22), "F");

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(r.watch()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("pre-existing: fails 1/100 on master");
    }

    /** A master failure among the last ten master runs is current, however rare. */
    @Test
    void aRecentMasterFailureStaysPreExisting() {
        failing(master(100, 6), "FFFF");

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("pre-existing: fails 1/100 on master");
    }

    /** At 2% on master, two failures in a row happen by chance once in 2,500 tries; three are needed. */
    @Test
    void twoPercentOnMasterTakesThreeFailuresInARow() {
        failing(master(100, 22, 40), "FF");
        assertThat(analyzer.analyze(TOK, PR).orElseThrow().blockers()).isEmpty();

        failing(master(100, 22, 40), "FFF");
        TestVerdict v = only(analyzer.forceRefresh(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason())
            .isEqualTo("rare on master: fails 2/100, passed the last 21; failed all 3 runs on this branch");
    }

    /** The branch rules still decide between a blocker and a test to watch. */
    @Test
    void twoFailuresAfterTwoPassesOnTheSameCodeAreWatched() {
        failing(master(100, 22), "PPFF");

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.watch()).reason()).startsWith("started failing in the last 2 of 4 runs on revision " + HEAD);
    }

    /** A pass in the last finished run clears the failure before master is even asked about. */
    @Test
    void aPassingReRunClearsAFailureThatAlsoFailsOnMaster() {
        failing(master(50, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), "FP");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().filtered());

        assertThat(v.reason()).isEqualTo("not failing in the last finished run (passed on re-run)");
    }

    /** Wires the PR's chain failing the test, with these master runs and these branch runs on {@link #HEAD}. */
    private void failing(List<TcModel.TestOccurrence> master, String branchRuns) {
        FailedTest t = new FailedTest(TEST, "IgniteSnapshotTestSuite8: IgniteClusterSnapshotCheckTest."
            + "testSnapshotCheckMetricsLesserTopology[encryption=true, onlyPrimary=true, snpThrdPoolSz=4]", SNAPSHOTS8,
            9392784L, "Snapshots 8", "build:(id:9392784),id:2000000223");
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(9391879L));
        when(chains.findBuildIdFresh(TOK, PR)).thenReturn(Optional.of(9391879L));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(9391879L), any())).thenReturn(new ChainCollector.Chain(9391879L,
            "pull/" + PR + "/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS8)).thenReturn(master);

        List<TcModel.TestOccurrence> runs = new ArrayList<>();
        for (int i = 0; i < branchRuns.length(); i++) {
            String status = branchRuns.charAt(i) == 'F' ? "FAILURE" : "SUCCESS";
            runs.add(new TcModel.TestOccurrence("o" + i, null, status, null, new TcModel.BuildRef(9392700L + i,
                "pull/" + PR + "/head", "finished", status, SNAPSHOTS8, null,
                new TcModel.Revisions(List.of(new TcModel.Revision(HEAD))), null), null));
        }
        when(tc.prBranchRuns(TOK, PR, TEST, SNAPSHOTS8)).thenReturn(runs);
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

    private static TestVerdict only(List<TestVerdict> verdicts) {
        assertThat(verdicts).hasSize(1);

        return verdicts.get(0);
    }
}
