package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The sweep, the early re-runs and the watch of running chains all borrowed the TeamCity token of
 * whichever participant the map listed first. Had that token expired, everyone's visas, re-runs and
 * comments stopped with nothing but "PR skipped" in the log, and logging in again with a fresh token
 * did not replace the dead one.
 */
class TcTokenRefusalTest {
    /** Listed first by the enrollment map, so the old code always looked builds up with this token. */
    private static final String DEAD = "nsamelchev";

    private static final String LIVE = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StandingVisas standing = new StandingVisas(new ObjectMapper(),
        new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()), tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        standing.change(DEAD, "dead-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        standing.change(LIVE, "live-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(tc.runningRunAllChains("dead-tc")).thenThrow(refused());
        when(tc.findRunAllBuildForPr("dead-tc", PR)).thenThrow(refused());
        when(tc.triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(build(9389100L, null));
        when(tc.getBuildState(anyString(), anyLong())).thenReturn(new TcModel.Build(9389100L, null, "queued", null,
            null, null, null, null, null, null, "20991231T000000+0000", null, null, null, null, null, null, null));
    }

    @Test
    void oneDeadTokenDoesNotStopTheSweepForEveryoneElse() {
        runOf(LIVE);

        standing.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("live-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
        assertThat(standing.tcTokenRejected(DEAD)).isTrue();
        assertThat(standing.tcTokenRejected(LIVE)).isFalse();
    }

    @Test
    void aRefusedOwnerIsPausedUntilALoginBringsAWorkingToken() {
        runOf(DEAD);

        standing.sweep();

        verify(analyzer, never()).analyzeForAction(eq("dead-tc"), anyInt());
        assertThat(standing.rerunOn(DEAD)).as("paused, not switched off").isTrue();

        standing.tcTokenAccepted(DEAD, "fresh-tc");
        when(analyzer.analyzeForAction("fresh-tc", PR)).thenReturn(Optional.of(verdict()));
        standing.sweep();

        assertThat(standing.tcTokenRejected(DEAD)).isFalse();
        verify(tc).triggerBuildReplacingQueued(eq("fresh-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
    }

    /** The lookups worked, but the run's owner's own token was refused when the verdict was computed. */
    @Test
    void aRefusalOfTheOwnersOwnCallPausesThem() {
        runOf(LIVE);
        when(analyzer.analyzeForAction("live-tc", PR)).thenThrow(refused());

        standing.sweep();
        standing.sweep();

        verify(analyzer, times(1)).analyzeForAction("live-tc", PR);
        assertThat(standing.tcTokenRejected(LIVE)).isTrue();
    }

    /** A browser still carrying an old token must not swap a working stored token for it. */
    @Test
    void aSessionTokenReplacesOnlyARefusedOne() {
        standing.tcTokenOffered(LIVE, "old-browser-tc");

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("live-tc"));

        standing.markTcRejected(LIVE);
        standing.tcTokenOffered(LIVE, "new-browser-tc");

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("new-browser-tc"));
        assertThat(standing.tcTokenRejected(LIVE)).isFalse();
    }

    @Test
    void aRefusalSurvivesARestart(@TempDir Path dir) throws Exception {
        standing.markTcRejected(DEAD);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = new StandingVisas(new ObjectMapper(),
            new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()), tc, github, analyzer,
            mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
            mock(PendingCommits.class));

        restarted.loadFrom(file);

        assertThat(restarted.tcTokenRejected(DEAD)).isTrue();
        assertThat(restarted.tcTokenRejected(LIVE)).isFalse();
    }

    @Test
    void anEarlyRerunFindsTheChainOwnerWithAnotherToken() {
        when(tc.buildTriggeredBy("dead-tc", RUN_ALL)).thenThrow(refused());
        when(tc.buildTriggeredBy("live-tc", RUN_ALL)).thenReturn(Optional.of(LIVE));
        when(analyzer.analyze("live-tc", PR)).thenReturn(Optional.of(verdict()));

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, "Cache1", 9389012L, "Cache 1"));

        verify(tc).triggerBuildReplacingQueued(eq("live-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
    }

    private void runOf(String user) {
        when(tc.findRunAllBuildForPr("live-tc", PR)).thenReturn(Optional.of(build(RUN_ALL, user)));
        when(tc.findRunAllBuildForPr("fresh-tc", PR)).thenReturn(Optional.of(build(RUN_ALL, user)));
        when(analyzer.analyzeForAction("live-tc", PR)).thenReturn(Optional.of(verdict()));
    }

    private static TcModel.Build build(long id, String triggeredBy) {
        return new TcModel.Build(id, "FAILURE", triggeredBy == null ? "queued" : "finished", "pull/13335/head",
            null, null, null, null, null, null, null, null,
            triggeredBy == null ? null : new TcModel.Triggered("user", new TcModel.User(triggeredBy)),
            null, null, null, null, null);
    }

    private static AnalysisResult verdict() {
        TestVerdict blocker = new TestVerdict(1L, "GridCacheTest.testPut", "Cache1", 9389012L, "Cache 1", "o1",
            true, false, "not seen failing in 100 master run(s)", "F", 1);

        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
    }

    private static HttpClientErrorException refused() {
        return HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
            new byte[0], null);
    }
}
