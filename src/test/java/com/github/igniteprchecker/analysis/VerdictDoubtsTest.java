package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.analysis.model.TestVerdict.Doubt;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * On prod all 31 blockers of PRs 13655, 13566, 13637, 13632 and 12584 rested on one run, nine of them on a test
 * master had never run, and they looked exactly like proven blockers: on PRs without auto re-run they stayed for
 * weeks. A blocker or a test to watch now says what it rests on short of proof.
 */
class VerdictDoubtsTest {
    private static final String TOK = "tok";

    private static final int PR = 13566;

    private static final long TEST = 4_242_424_242L;

    private static final String SUITE = "IgniteTests24Java8_ControlUtility";

    private static final String HEAD = "5be1c0d";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @Test
    void aBlockerFromItsOnlyRunSaysSo() {
        failing(masterRuns(85), "F");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("not seen failing in 85 master run(s); failed the only run on this branch");
        assertThat(v.doubts()).containsExactly(Doubt.ONE_RUN);
    }

    @Test
    void threeFailuresInARowAreProof() {
        failing(masterRuns(85), "FFF");

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().blockers()).doubts()).isEmpty();
    }

    @Test
    void aTestMasterNeverRanHasNothingToBeComparedWith() {
        failing(List.of(), "F");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).startsWith("no master history (can't prove pre-existing)");
        assertThat(v.doubts()).containsExactly(Doubt.ONE_RUN, Doubt.NO_MASTER_HISTORY);
    }

    /** TeamCity listed no run of the test on the branch: the chain's own failure is all there is. */
    @Test
    void aBlockerWithNoRunOnTheBranchRestsOnTheChainsFailure() {
        failing(masterRuns(85), "");

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().blockers()).doubts()).containsExactly(Doubt.ONE_RUN);
    }

    @Test
    void aFirstFailureOnNewCodeIsWatchedOnOneRun() {
        failing(masterRuns(85), "PPF", "0ld0ld0", "0ld0ld0", HEAD);

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().watch());

        assertThat(v.reason()).startsWith("first failure on revision " + HEAD + " — watch");
        assertThat(v.doubts()).containsExactly(Doubt.ONE_RUN);
    }

    @Test
    void twoFailuresAfterPassesOnTheSameCodeAreNotOneRun() {
        failing(masterRuns(85), "PPFF");

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().watch()).doubts()).isEmpty();
    }

    /** Whether the test fails on other PRs could not be asked: the blocker stands, unchecked. */
    @Test
    void aBlockerOtherPrsCouldNotBeCheckedForIsUnverified() {
        failing(masterRuns(85), "FFF");
        when(tc.otherBranchRuns(TOK, TEST, SUITE)).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().blockers()).doubts()).containsExactly(Doubt.UNCHECKED);
    }

    @Test
    void aTestTeamCityErrorsKeptFromBeingCheckedIsUnverified() {
        failing(masterRuns(85), "F");
        when(tc.prBranchRuns(anyString(), eq(PR), anyLong(), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.unverified()).doubts()).containsExactly(Doubt.UNCHECKED);
    }

    @Test
    void aFilteredTestHasNoDoubts() {
        failing(masterRuns(85), "FP");

        assertThat(only(analyzer.analyze(TOK, PR).orElseThrow().filtered()).doubts()).isEmpty();
    }

    /** A verdict saved before doubts were stated reads as one with none. */
    @Test
    void aVerdictSavedWithoutDoubtsReads() throws Exception {
        TestVerdict v = new ObjectMapper().readValue("""
            {"testId":"4242424242","name":"t","suite":"S","suiteBuildId":1,"suiteName":"S","occurrenceId":"o",
             "blocker":true,"watch":false,"reason":"r","branchRuns":"F","codeRuns":1}""", TestVerdict.class);

        assertThat(v.doubts()).isEmpty();
    }

    private void failing(List<TcModel.TestOccurrence> master, String branchRuns) {
        String[] revisions = new String[branchRuns.length()];
        Arrays.fill(revisions, HEAD);
        failing(master, branchRuns, revisions);
    }

    /** Wires the PR's chain failing the test, with these master runs and these branch runs on these revisions. */
    private void failing(List<TcModel.TestOccurrence> master, String branchRuns, String... revisions) {
        FailedTest t = new FailedTest(TEST, "IgniteControlUtilityTestSuite: GridCommandHandlerTest.testCacheIdle",
            SUITE, 9392784L, "Control Utility", "build:(id:9392784),id:2000000223");
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(9391879L));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(9391879L), any())).thenReturn(new ChainCollector.Chain(9391879L,
            "pull/" + PR + "/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, TEST, SUITE)).thenReturn(master);

        List<TcModel.TestOccurrence> runs = new ArrayList<>();
        for (int i = 0; i < branchRuns.length(); i++) {
            String status = branchRuns.charAt(i) == 'F' ? "FAILURE" : "SUCCESS";
            runs.add(new TcModel.TestOccurrence("o" + i, null, status, null, new TcModel.BuildRef(9392700L + i,
                "pull/" + PR + "/head", "finished", status, SUITE, null,
                new TcModel.Revisions(List.of(new TcModel.Revision(revisions[i]))), null), null));
        }
        when(tc.prBranchRuns(TOK, PR, TEST, SUITE)).thenReturn(runs);
    }

    private static List<TcModel.TestOccurrence> masterRuns(int passes) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 0; i < passes; i++)
            out.add(new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null));

        return out;
    }

    private static TestVerdict only(List<TestVerdict> verdicts) {
        assertThat(verdicts).hasSize(1);

        return verdicts.get(0);
    }
}
