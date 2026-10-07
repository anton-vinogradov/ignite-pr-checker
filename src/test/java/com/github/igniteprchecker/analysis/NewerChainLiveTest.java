package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * apache/ignite#13654: the verdict is for RunAll 9391879, and the newer RunAll 9392337 was cancelled.
 * The checker kept calling the verdict live, pointing at the cancelled chain: the PR never got its tick,
 * the visa said "a newer run is still going", and every view recomputed. A cancelled chain's finished
 * suites still count; only a chain that is actually running makes the verdict live.
 */
class NewerChainLiveTest {
    private static final String TOK = "t";

    private static final int PR = 13654;

    private static final long CHAIN = 9391879L;

    private static final long NEWER = 9392337L;

    private static final String SNAPSHOTS8 = "IgniteTests24Java8_Snapshots8";

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final ChainCollector collector = new ChainCollector(tc, baseline);

    @Test
    void aCancelledNewerChainIsFoldedInButNotLive() {
        newerChain("finished", "UNKNOWN");

        ChainCollector.Chain chain = collect();

        assertThat(chain.live()).isFalse();
        assertThat(chain.liveBuildId()).isZero();
        assertThat(chain.failedTests()).as("what the cancelled chain did run still counts")
            .extracting(FailedTest::suiteBuildId).containsExactly(9392300L);
    }

    @Test
    void aRunningNewerChainIsLive() {
        newerChain("running", "SUCCESS");

        ChainCollector.Chain chain = collect();

        assertThat(chain.live()).isTrue();
        assertThat(chain.liveBuildId()).isEqualTo(NEWER);
    }

    private void newerChain(String state, String status) {
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild(CHAIN, "finished", "SUCCESS", List.of()));
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(
            chainBuild(NEWER, state, status, null),
            chainBuild(CHAIN, "finished", "SUCCESS", null)));
        TcModel.Build snapshots8 = new TcModel.Build(9392300L, "FAILURE", "finished", "pull/" + PR + "/head", SNAPSHOTS8,
            null, null, null, null, null, null, new TcModel.BuildType(SNAPSHOTS8, "Snapshots 8"), null, null, null, null,
            null, new TcModel.TestOccurrences(120, List.of()));
        when(tc.getBuildWithDeps(TOK, NEWER)).thenReturn(chainBuild(NEWER, state, status, List.of(snapshots8)));
        when(tc.getFailedTests(TOK, 9392300L)).thenReturn(List.of(new TcModel.TestOccurrence(
            "id:1,build:(id:9392300)", "IgniteClusterSnapshotCheckTest.testSnapshotCheckMetricsLesserTopology", "FAILURE",
            new TcModel.TestRef(-1661331956011831017L), null, null)));
    }

    private ChainCollector.Chain collect() {
        return collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
    }

    private static TcModel.Build chainBuild(long id, String state, String status, List<TcModel.Build> deps) {
        return new TcModel.Build(id, status, state, "pull/" + PR + "/head", "IgniteTests24Java8_RunAll", null, null, null,
            null, null, null, new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            deps == null ? null : new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }
}
