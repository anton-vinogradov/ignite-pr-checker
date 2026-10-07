package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * The auto re-run put the victims of a failed Build, as on PR 13583, at the top of the queue, each pulling a new Build
 * of its own; of the 60 suites one ci2 glitch broke on PR 13655, the first ten would go to the top while the chain was
 * still running. A failed Build is re-run alone, and not at all when it failed to compile; the early re-runs stop at
 * the bar the settled waves stop at.
 */
class FailedBuildWaveTest {
    private static final String USER = "avinogradov";

    private static final String TOK = "tc";

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final long now = System.currentTimeMillis() / 1000;

    @Test
    void aFailedBuildIsReRunAloneAndTheSuitesItKeptFromRunningAreNot() {
        StandingVisas.WaveSuites w = StandingVisas.WaveSuites.of(BrokenRuns.buildFailed(false));

        assertThat(w.all()).containsExactly(BrokenRuns.BUILD);
        assertThat(StandingVisas.worthRerunning(BrokenRuns.buildFailed(false), BrokenRuns.BUILD)).isTrue();
        assertThat(StandingVisas.worthRerunning(BrokenRuns.buildFailed(false), "IgniteTests24Java8_ThinClientNodeJs"))
            .isFalse();
    }

    @Test
    void aBuildThatFailedToCompileIsNotReRunAtAll() {
        assertThat(StandingVisas.WaveSuites.of(BrokenRuns.buildFailed(true)).all()).isEmpty();
        assertThat(StandingVisas.worthRerunning(BrokenRuns.buildFailed(true), BrokenRuns.BUILD)).isFalse();
    }

    @Test
    void theSuitesHitByTheArtifactGlitchAreAllWorthARerun() {
        assertThat(StandingVisas.WaveSuites.of(BrokenRuns.artifactsGlitch()).broken()).hasSize(64)
            .contains("IgniteTests24Java8_Suite1", "IgniteTests24Java8_Suite60", "IgniteTests24Java8_Cache5");
    }

    @Test
    void theSettledWaveQueuesTheFailedBuildOnly(@TempDir Path dir) throws Exception {
        AnalysisResult r = BrokenRuns.buildFailed(false);
        enrolledBeforeTheRun(dir);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(r.prNumber(), "IGNITE-28890 Fix it", null, null, null,
            null)));
        when(tc.findRunAllBuildForPr(TOK, r.prNumber())).thenReturn(Optional.of(new TcModel.Build(r.buildId(),
            "FAILURE", "finished", r.branchName(), null, null, null, null, TcDates.format(now - 600), null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(TOK, r.prNumber())).thenReturn(Optional.of(r));
        when(tc.triggerBuildReplacingQueued(eq(TOK), anyString(), eq(r.prNumber()), anyBoolean(), anyString()))
            .thenAnswer(inv -> new TcModel.Build(9384990L, null, "queued", null, inv.getArgument(1), null, null, null,
                null, null, null, null, null, null, null, null, null, null));
        when(tc.getBuildState(eq(TOK), anyLong())).thenReturn(new TcModel.Build(9384990L, null, "queued", null, null,
            null, null, null, null, null, TcDates.format(now + 1800), null, null, null, null, null, null, null));

        standing.sweep();

        ArgumentCaptor<String> suites = ArgumentCaptor.forClass(String.class);
        verify(tc, atLeastOnce()).triggerBuildReplacingQueued(eq(TOK), suites.capture(), eq(r.prNumber()),
            eq(true), anyString());
        assertThat(suites.getAllValues()).containsExactly(BrokenRuns.BUILD);
    }

    @Test
    void noSuiteIsReRunEarlyWhileTheVerdictAsksForMoreThanAWaveTakes() {
        AnalysisResult r = BrokenRuns.artifactsGlitch();
        standing.change(USER, TOK, null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(tc.buildTriggeredBy(anyString(), eq(r.buildId()))).thenReturn(Optional.of(USER));
        when(analyzer.analyze(anyString(), eq(r.prNumber()))).thenReturn(Optional.of(r));

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(r.prNumber(), r.buildId(), "IgniteTests24Java8_Suite1",
            r.brokenSuites().get(0).suiteBuildId(), "Suite 1"));

        verify(tc, never()).triggerBuildReplacingQueued(anyString(), anyString(), anyInt(), anyBoolean(), anyString());
    }

    /** Restores the user, auto re-run on, from a snapshot taken a day before the run. */
    private void enrolledBeforeTheRun(Path dir) throws Exception {
        standing.change(USER, TOK, null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) snap.get("enrollments").get(0)).put("enabledAt", (now - 86_400) * 1000);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }
}
