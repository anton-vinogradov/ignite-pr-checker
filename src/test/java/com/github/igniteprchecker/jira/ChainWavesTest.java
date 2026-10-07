package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR 13653 had two chains at once. The auto re-run count was kept per PR, so a suite of the newer chain failing
 * mid-run took over the count of the older one, which was still settling, and the older one could get a third wave;
 * and a wave went in for the older chain while the newer one was already running over it.
 */
class ChainWavesTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13653;

    private static final long OLDER = 9399000L;

    private static final long NEWER = 9399500L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29001 Two chains", null, null, null,
            null)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(OLDER, "FAILURE",
            "finished", "pull/13653/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.buildTriggeredBy("tc", NEWER)).thenReturn(Optional.of(USER));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9399900L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
    }

    @Test
    void aChainStartedWhileTheLastOneSettlesKeepsItsOwnWaves() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(OLDER, false, blocker("Cache1", 1L))));
        standing.sweep();

        failMidRun(NEWER, "Cache2", 2L);

        assertThat(standing.waveStatus(PR, OLDER)).hasValueSatisfying(w -> {
            assertThat(w.wave()).isEqualTo(1);
            assertThat(w.what()).isEqualTo("1 blocker suite(s)");
        });
        assertThat(standing.waveStatus(PR, NEWER)).hasValueSatisfying(
            w -> assertThat(w.what()).isEqualTo("1 suite that failed mid-run"));
    }

    @Test
    void noWaveGoesInForAChainANewerOneIsRunningOver() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(OLDER, true, blocker("Cache1", 1L))));
        when(tc.getBuildState("tc", NEWER)).thenReturn(new TcModel.Build(NEWER, null, "running", null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null));

        standing.sweep();

        verify(tc, never()).triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString());
    }

    /** Each chain's waves survive a restart; an older release reads the PR's newest one, as it always did. */
    @Test
    void theWavesOfEachChainAreSaved(@TempDir Path dir) throws Exception {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(OLDER, false, blocker("Cache1", 1L))));
        standing.sweep();
        failMidRun(NEWER, "Cache2", 2L);
        Path file = dir.resolve("standing-visas.json");

        standing.saveTo(file);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);

        assertThat(restarted.waveStatus(PR, OLDER)).isPresent();
        assertThat(restarted.waveStatus(PR, NEWER)).isPresent();
        JsonNode retries = mapper.readTree(file.toFile()).get("retries");
        assertThat(retries.size()).isEqualTo(1);
        assertThat(retries.get(String.valueOf(PR)).get("buildId").asLong()).isEqualTo(NEWER);
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, mock(JiraClient.class),
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }

    private void failMidRun(long chain, String suite, long suiteBuildId) {
        TestVerdict failed = new TestVerdict(suiteBuildId, suite + "Test.test", suite, suiteBuildId, suite, "o2", true,
            false, "not seen failing in 100 master run(s)", "F", 1);
        when(analyzer.analyze("tc", PR)).thenReturn(Optional.of(verdict(chain, false, failed)));

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, chain, suite, suiteBuildId, suite));
    }

    private static TestVerdict blocker(String suite, long suiteBuildId) {
        return new TestVerdict(suiteBuildId, suite + "Test.test", suite, suiteBuildId, suite, "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(long buildId, boolean newerGoing, TestVerdict... blockers) {
        return new AnalysisResult(PR, buildId, "pull/13653/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, newerGoing, newerGoing ? NEWER : 0, 0, 0, 1,
            0);
    }
}
