package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * RunAll 9384769 of PR 13583: the Build failed with a non-zero exit code, eight suites failed on it and the rest were
 * cancelled. The page read "147 suites ran" and the Build was one broken suite among its victims. Every suite run says
 * which runs it needed: a suite that failed or was cancelled because the Build failed carries that Build, and ran
 * nothing. A suite whose Build passed but which could not fetch its artifacts carries no failed run.
 */
class FailedBuildChainTest {
    private static final String TOK = "t";

    private static final int PR = 13583;

    private static final long CHAIN = 9384769L;

    private static final String QUEUED = "20260915T101500+0000";

    private static final String AFTER = "20260915T101600+0000";

    private static final String BUILD = "IgniteTests24Java8_BuildApacheIgnite";

    private static final long BUILD_RUN = 9384622L;

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    @Test
    void suitesTheFailedBuildKeptFromRunningCarryItAndRanNothing() {
        TcModel.Build build = run(BUILD_RUN, BUILD, "> Build", "FAILURE", "TC_EXIT_CODE", List.of());
        TcModel.Build failedBuild = new TcModel.Build(BUILD_RUN, "FAILURE", null, null, BUILD, null, null, null, null,
            null, null, new TcModel.BuildType(BUILD, "> Build"), null, null, null, null, null, null);
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain(List.of(
            build,
            run(9384701L, "IgniteTests24Java8_ThinClientNodeJs", "Thin client: Node.js", "FAILURE",
                "SNAPSHOT_DEPENDENCY_ERROR", List.of(failedBuild)),
            cancelled(9384801L, "IgniteTests24Java8_Cache1", "Cache 1", List.of(failedBuild)),
            run(9384802L, "IgniteTests24Java8_Basic1", "Basic 1", "SUCCESS", null, List.of()))));

        ChainCollector.Chain chain = new ChainCollector(tc, baseline)
            .collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(chain.brokenSuites())
            .extracting(BrokenSuite::suite, s -> s.failedUpstream() == null ? null : s.failedUpstream().suite(),
                BrokenSuite::problemTypes)
            .containsExactlyInAnyOrder(tuple(BUILD, null, List.of("TC_EXIT_CODE")),
                tuple("IgniteTests24Java8_ThinClientNodeJs", BUILD, List.of("SNAPSHOT_DEPENDENCY_ERROR")));
        assertThat(chain.cancelledSuites()).singleElement()
            .extracting(CancelledSuite::suite, c -> c.failedUpstream().buildId(), c -> c.failedUpstream().name())
            .containsExactly("IgniteTests24Java8_Cache1", BUILD_RUN, "> Build");
        assertThat(chain.suitesRan()).as("the Build and Basic 1 ran; Thin client: Node.js only failed on the Build")
            .isEqualTo(2);
    }

    @Test
    void aSuiteThatCouldNotFetchThePassedBuildsArtifactsCarriesNoFailedRun() {
        TcModel.Build passedBuild = new TcModel.Build(BUILD_RUN, "SUCCESS", null, null, BUILD, null, null, null, null,
            null, null, new TcModel.BuildType(BUILD, "> Build"), null, null, null, null, null, null);
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain(List.of(
            run(BUILD_RUN, BUILD, "> Build", "SUCCESS", null, List.of()),
            run(9391167L, "IgniteTests24Java8_ControlUtilityZookeeper", "Control Utility (Zookeeper)", "FAILURE",
                "ARTIFACT_DEPENDENCY_ERROR", List.of(passedBuild)))));

        ChainCollector.Chain chain = new ChainCollector(tc, baseline)
            .collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(chain.brokenSuites()).singleElement().satisfies(s -> {
            assertThat(s.failedUpstream()).isNull();
            assertThat(s.artifactsUnavailable()).isTrue();
        });
    }

    private static TcModel.Build chain(List<TcModel.Build> deps) {
        return new TcModel.Build(CHAIN, "FAILURE", "finished", "pull/" + PR + "/head", "IgniteTests24Java8_RunAll",
            null, QUEUED, null, null, null, null, new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }

    private static TcModel.Build run(long id, String suite, String name, String status, String problem,
        List<TcModel.Build> needs) {
        return new TcModel.Build(id, status, "finished", "pull/" + PR + "/head", suite, null, AFTER, null, null, null,
            null, new TcModel.BuildType(suite, name), null, new TcModel.SnapshotDeps(needs.size(), needs), null,
            problem == null ? null : new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(problem,
                problem.startsWith("ARTIFACT") ? "Failed to resolve artifacts from <[Apache Ignite 2.x / Tests] / > "
                    + "Build, build #30372 [id 9384622]>" : null))),
            null, new TcModel.TestOccurrences(0, List.of()));
    }

    private static TcModel.Build cancelled(long id, String suite, String name, List<TcModel.Build> needs) {
        return new TcModel.Build(id, "UNKNOWN", "finished", "pull/" + PR + "/head", suite, null, AFTER, null, null,
            null, null, new TcModel.BuildType(suite, name), null, new TcModel.SnapshotDeps(needs.size(), needs), null,
            null, null, null, new TcModel.CanceledInfo("Snapshot dependency failed", null));
    }
}
