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
 * Master history of {@code IgniteClusterSnapshotSelfTest.testRecoveryClusterSnapshotJvmHalted} in Snapshots,
 * as ci2 gave it on 2026-10-06: green in all 85 runs on JDK 17, failing in all 15 nightly runs on JDK 21.
 * The checker's own chains run on JDK 17, so it called a break of this test "pre-existing: fails 15/100 on
 * master" in every PR. Master is now compared on the JDK the PR's run used.
 */
class MasterJdkTest {
    private static final String TOK = "tok";

    private static final int PR = 13654;

    private static final long TEST = 8882040372364155986L;

    private static final String SNAPSHOTS = "IgniteTests24Java8_Snapshots";

    /** Newest first: the status of each master run, and the JDK it ran on ('7' for 17, '1' for 21). */
    private static final String MASTER = "PFPPPPPFPPPPPPPPPFPPPPPPPPFPPPPPFPPPPPFPPPPPPFPPPPFPPPPPPPPFPPPPPPPPPFPPPPPFPPPPPFPPPPPFPPPPPFPPPPPF";

    private static final String MASTER_JDK = "7177777177777777717777777717777717777717777771777717777777717777777771777771777771777771777771777771";

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @Test
    void aJdk17FailureIsJudgedByMastersJdk17Runs() {
        failingOn("/opt/java/jdk-open-17");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("not seen failing in 85 master run(s) on JDK 17; failed the only run on this branch");
    }

    @Test
    void aJdk21FailureIsPreExistingOnMastersJdk21Runs() {
        failingOn("/opt/java/jdk-open-21");

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).isEmpty();
        assertThat(only(r.filtered()).reason()).isEqualTo("pre-existing: fails 15/15 on master on JDK 21");
    }

    /** Fewer than ten master runs on the PR's JDK are thin evidence, and the reason says so. */
    @Test
    void fewMasterRunsOnThePrsJdkAreCalledOut() {
        failingOn("/opt/java/jdk-open-11");
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS)).thenReturn(List.of(
            master('P', "/opt/java/jdk-open-11", 9390120L),
            master('F', "/opt/java/jdk-open-21", 9389909L),
            master('P', "/opt/java/jdk-open-11", 9389748L)));

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("not seen failing in only 2 master run(s) on JDK 11; failed the only run on this branch");
    }

    /** No master run on the PR's JDK at all is no master history, whatever ran on other JDKs. */
    @Test
    void noMasterRunOnThePrsJdkIsNoMasterHistory() {
        failingOn("/usr/lib/jvm/java-11-openjdk-amd64");

        TestVerdict v = only(analyzer.analyze(TOK, PR).orElseThrow().blockers());

        assertThat(v.reason()).isEqualTo("no master history on JDK 11 (can't prove pre-existing); failed the only run on this branch");
    }

    @Test
    void theJdkIsTheMajorVersionInJavaHome() {
        assertThat(RunEnv.jdkOf("/opt/java/jdk-open-21")).isEqualTo("21");
        assertThat(RunEnv.jdkOf("%env.JDK_OPEN_17%")).as("as TeamCity reports it for older builds").isEqualTo("17");
        assertThat(RunEnv.jdkOf("/usr/lib/jvm/jdk-17.0.8.1+1")).isEqualTo("17");
        assertThat(RunEnv.jdkOf("/usr/lib/jvm/java-8-openjdk-amd64")).isEqualTo("8");
        assertThat(RunEnv.jdkOf("/usr/lib/jvm/jdk1.8.0_202")).isEqualTo("8");
        assertThat(RunEnv.jdkOf("/opt/temurin")).isEqualTo("/opt/temurin");
    }

    /** Wires PR 13654's chain failing the test once, in a Snapshots run on the given JDK. */
    private void failingOn(String javaHome) {
        FailedTest t = new FailedTest(TEST, "IgniteSnapshotTestSuite: IgniteClusterSnapshotSelfTest."
            + "testRecoveryClusterSnapshotJvmHalted[encryption=false, onlyPrimay=false]", SNAPSHOTS, 9392300L,
            "Snapshots", "build:(id:9392300),id:1");
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(9391879L));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(9391879L), any())).thenReturn(new ChainCollector.Chain(9391879L,
            "pull/" + PR + "/head", List.of(t), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0));
        when(tc.getBaseBranchHistory(TOK, TEST, SNAPSHOTS)).thenReturn(ci2MasterHistory());
        when(tc.prBranchRuns(TOK, PR, TEST, SNAPSHOTS)).thenReturn(List.of(new TcModel.TestOccurrence("1", null,
            "FAILURE", null, new TcModel.BuildRef(9392300L, "pull/" + PR + "/head", "finished", "FAILURE", SNAPSHOTS, null,
                new TcModel.Revisions(List.of(new TcModel.Revision("2ff3f44"))), conditions(javaHome, "0.1")), null)));
    }

    private static List<TcModel.TestOccurrence> ci2MasterHistory() {
        List<TcModel.TestOccurrence> runs = new ArrayList<>();
        for (int i = 0; i < MASTER.length(); i++) {
            String javaHome = MASTER_JDK.charAt(i) == '1' ? "/opt/java/jdk-open-21" : "/opt/java/jdk-open-17";
            runs.add(master(MASTER.charAt(i), javaHome, 9390120L - i));
        }

        return runs;
    }

    private static TcModel.TestOccurrence master(char status, String javaHome, long buildId) {
        return new TcModel.TestOccurrence(null, null, status == 'F' ? "FAILURE" : "SUCCESS", null,
            new TcModel.BuildRef(buildId, null, null, null, null, null, null, conditions(javaHome, "1.0")), null);
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
