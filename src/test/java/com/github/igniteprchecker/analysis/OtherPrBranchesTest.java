package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * The nightly master RunAll runs at TEST_SCALE_FACTOR 1.0, PR chains at 0.1, so master is not always the
 * baseline a PR failure should be judged by. The cases are ci2's answers of 2026-10-06 for two tests of
 * IgniteClusterSnapshotSelfTest: {@code testClientHandlesSnapshotFailOnStartStage} is green in all 101 master
 * runs and failed 14 of 112 runs on the branches of 13 PRs, a false blocker in each of them;
 * {@code testRestoreSnapshotProgress} fails all 101 master runs and passes 94 of 95 runs on the branches of
 * other PRs, so a break of it in a PR was always "pre-existing". Other PRs' branches are now the second
 * signal.
 */
class OtherPrBranchesTest {
    private static final String TOK = "tok";

    private static final int PR = 13654;

    private static final long TEST = 4242L;

    private static final String SNAPSHOTS = "IgniteTests24Java8_Snapshots";

    private static final String HEAD = "2ff3f44";

    private static final String JDK17 = "/opt/java/jdk-open-17";

    /** The PRs whose branches failed testClientHandlesSnapshotFailOnStartStage, 13614 twice. */
    private static final int[] FAILED_ON_START_STAGE = {13653, 13554, 13644, 13583, 13641, 13574, 13622, 13614, 13614,
        13604, 13598, 13602, 13596, 13592};

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @Test
    void aTestFailingInThreeOtherPrsIsFlakyUnderPrConditions() {
        failing(PR, "F", master(101, 0, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(98, FAILED_ON_START_STAGE));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("flaky on other PR branches: fails 14/112 in 13 other PRs; "
            + "not seen failing in 101 master run(s) on JDK 17");
    }

    /** The PR's own runs are its evidence, never its baseline: 13614's failures are not "another PR". */
    @Test
    void thePrsOwnFailuresDoNotMakeItFlaky() {
        failing(13614, "F", master(101, 0, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(98, 13614, 13614, 13653, 13554));

        assertThat(only(analyzer.analyze(TOK, 13614).orElseThrow().blockers()).reason())
            .isEqualTo("not seen failing in 101 master run(s) on JDK 17; failed the only run on this branch");
    }

    /** Runs under other conditions than the PR's own are not compared: here three PRs ran at scale 1.0. */
    @Test
    void otherPrsRunUnderOtherConditionsAreNotCompared() {
        failing(PR, "F", master(101, 0, "1.0"));
        List<TcModel.TestOccurrence> others = new ArrayList<>(onOtherPrs(98));
        for (int pr : new int[] {13653, 13554, 13644})
            others.add(run(pr, "FAILURE", JDK17, "1.0", 9390000L + pr));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(others);

        assertThat(analyzer.analyze(TOK, PR).orElseThrow().blockers()).hasSize(1);
    }

    /** Three failures in a row on the PR's code outweigh a test failing 1.5% of the time on other PRs. */
    @Test
    void aRepeatingFailureOutweighsRareFailuresOnOtherPrs() {
        failing(PR, "FFF", master(101, 0, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(197, 13653, 13554, 13644));

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("not seen failing in 101 master run(s) on JDK 17; fails 3/200 in 3 other PRs; "
            + "failed all 3 runs on this branch");
    }

    @Test
    void aFailureMasterHasOnlyAtAnotherScaleBlocksWhenOtherPrsPassIt() {
        failing(PR, "FFF", master(101, 101, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(94, 13644));

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("fails 101/101 on master at TEST_SCALE_FACTOR=1.0, but 1/95 on other PR "
            + "branches at 0.1; failed all 3 runs on this branch");
    }

    /** At 1 in 95 on other PRs, two failures in a row are not enough to set master aside. */
    @Test
    void twoFailuresDoNotSetMasterAsideAgainstOneIn95() {
        failing(PR, "FF", master(101, 101, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(94, 13644));

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().filtered()).reason())
            .isEqualTo("pre-existing: fails 101/101 on master on JDK 17");
    }

    /**
     * A test master broke recently also passes on PR branches made before the break. Only a master
     * failure in at least half the runs is set aside.
     */
    @Test
    void aMasterFailureInFewerThanHalfTheRunsStaysPreExisting() {
        failing(PR, "FFF", master(101, 30, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(94, 13644));

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().filtered()).reason())
            .isEqualTo("pre-existing: fails 30/101 on master on JDK 17");
    }

    /** One failure asks TeamCity nothing about other PRs: no answer from them could change the verdict. */
    @Test
    void aSingleFailureOfAPreExistingTestDoesNotAskAboutOtherPrs() {
        failing(PR, "F", master(101, 101, "1.0"));

        analyzer.analyze(TOK, PR);

        verify(tc, never()).otherBranchRuns(anyString(), anyLong(), anyString());
    }

    /** One answer about other PRs serves every PR the test fails in. */
    @Test
    void otherPrBranchesAreAskedOncePerTestAndSuite() {
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenReturn(onOtherPrs(98));
        failing(PR, "F", master(101, 0, "1.0"));
        analyzer.analyze(TOK, PR);
        failing(13653, "F", master(101, 0, "1.0"));
        analyzer.analyze(TOK, 13653);

        verify(tc, times(1)).otherBranchRuns(TOK, TEST, SNAPSHOTS);
    }

    /** Other PRs only ever override master; when TeamCity can't say, master's verdict stands. */
    @Test
    void aTeamCityErrorAboutOtherPrsLeavesMastersVerdict() {
        failing(PR, "F", master(101, 0, "1.0"));
        when(tc.otherBranchRuns(TOK, TEST, SNAPSHOTS)).thenThrow(new IllegalStateException("ci2 timeout"));

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().blockers()).reason())
            .isEqualTo("not seen failing in 101 master run(s) on JDK 17; failed the only run on this branch");
    }

    /** Wires a chain of {@code pr} failing the test, with these runs on its branch on {@link #HEAD}, JDK 17, scale 0.1. */
    private void failing(int pr, String branchRuns, List<TcModel.TestOccurrence> master) {
        long chain = 9391879L + pr - PR;
        FailedTest t = new FailedTest(TEST, "IgniteSnapshotTestSuite: IgniteClusterSnapshotSelfTest.test", SNAPSHOTS,
            9392300L, "Snapshots", "build:(id:9392300),id:1");
        when(chains.findBuildId(TOK, pr)).thenReturn(Optional.of(chain));
        when(chains.collectForBuild(eq(TOK), eq(pr), eq(chain), any())).thenReturn(new ChainCollector.Chain(chain,
            "pull/" + pr + "/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS)).thenReturn(master);

        List<TcModel.TestOccurrence> runs = new ArrayList<>();
        for (int i = 0; i < branchRuns.length(); i++) {
            TcModel.TestOccurrence r = run(pr, branchRuns.charAt(i) == 'F' ? "FAILURE" : "SUCCESS", JDK17, "0.1",
                9392300L + i);
            runs.add(r);
        }
        when(tc.prBranchRuns(TOK, pr, TEST, SNAPSHOTS)).thenReturn(runs);
    }

    /** {@code runs} master runs on JDK 17 at the given scale, newest first, the newest {@code fails} failing. */
    private static List<TcModel.TestOccurrence> master(int runs, int fails, String scale) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            out.add(new TcModel.TestOccurrence(null, null, i < fails ? "FAILURE" : "SUCCESS", null,
                new TcModel.BuildRef(9390120L - i, "refs/heads/master", "finished", "SUCCESS", SNAPSHOTS, null, null,
                    conditions(JDK17, scale)), null));
        }

        return out;
    }

    /**
     * Runs on other PRs' branches at the PR conditions: {@code passes} passes on PRs that never failed the
     * test, then a failure on each of {@code failedOn}, and a failure on a release branch, which is no PR.
     */
    private static List<TcModel.TestOccurrence> onOtherPrs(int passes, int... failedOn) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        long build = 9392000L;
        for (int pr : failedOn)
            out.add(run(pr, "FAILURE", JDK17, "0.1", build--));
        for (int i = 0; i < passes; i++)
            out.add(run(13000 + i, "SUCCESS", JDK17, "0.1", build--));
        out.add(new TcModel.TestOccurrence(null, null, "FAILURE", null, new TcModel.BuildRef(build, "ignite-2.18",
            "finished", "FAILURE", SNAPSHOTS, null, null, conditions(JDK17, "0.1")), null));

        return out;
    }

    private static TcModel.TestOccurrence run(int pr, String status, String javaHome, String scale, long buildId) {
        return new TcModel.TestOccurrence("o" + buildId, null, status, null, new TcModel.BuildRef(buildId,
            "pull/" + pr + "/head", "finished", status, SNAPSHOTS, null,
            new TcModel.Revisions(List.of(new TcModel.Revision(HEAD))), conditions(javaHome, scale)), null);
    }

    private static TcModel.Properties conditions(String javaHome, String scale) {
        return new TcModel.Properties(List.of(new TcModel.Property(TcModel.JAVA_HOME, javaHome),
            new TcModel.Property(TcModel.TEST_SCALE_FACTOR, scale)));
    }

    private static TestVerdict only(List<TestVerdict> verdicts) {
        assertThat(verdicts).hasSize(1);

        return verdicts.get(0);
    }
}
