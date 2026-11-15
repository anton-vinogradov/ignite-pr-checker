package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.IgnitePrRuns;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.mirror.GitMirror;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MirroredRerunWavesTest {
    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String HEAD = "a".repeat(40);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = mock(GithubClient.class);

    private final GitMirror mirror = mock(GitMirror.class);

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class), mock(ExecutorService.class), mock(ExecutorService.class),
        new SettingsJournal(mapper), new IgnitePrRuns(github, tc, mirror,
            new AnalysisProperties(null, "RunAll", null, null, null, null, null)));

    private final TestVerdict cache = blocker("Cache", 11L);

    private final TestVerdict query = blocker("Query", 12L);

    @BeforeEach
    void anEnrolledUsersFailedRun() {
        when(mirror.enabled()).thenReturn(true);
        when(github.prHeadSha(PR)).thenReturn(HEAD);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Example", null, null, null, null)));
        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(tc.buildTriggeredBy("tc", RUN_ALL)).thenReturn(Optional.of("author"));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", "RunAll", null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User("author")), null, null, null, null, null)));
        AnalysisResult verdict = new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(),
            List.of(cache, query), List.of(), List.of(), List.of(), List.of(), 2, 1, false, 0, false, 0, 0, 0, 0, 0);
        when(analyzer.analyze("tc", PR)).thenReturn(Optional.of(verdict));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict));
        when(tc.queuePinnedReplacingQueued(eq("tc"), any(), anyBoolean(), anyString())).thenReturn(
            new TcModel.Build(9400000L, null, "queued", "pull/13335/head", "Cache", null, null, null, null, null,
                null, null, null, null, null, null, null, null));
    }

    /** A settled wave fetches the head once, so all its suites test the same commit even if the PR moves later. */
    @Test
    void settledWaveUsesOneFreshHeadForEverySuite() {
        standing.sweep();

        ArgumentCaptor<PinnedRun> runs = ArgumentCaptor.forClass(PinnedRun.class);
        verify(tc, times(2)).queuePinnedReplacingQueued(eq("tc"), runs.capture(), eq(true), anyString());
        assertThat(runs.getAllValues()).extracting(PinnedRun::suite).containsExactly("Cache", "Query");
        assertThat(runs.getAllValues()).allSatisfy(run -> assertThat(run.revisions()).singleElement()
            .satisfies(pin -> assertThat(pin.version()).isEqualTo(HEAD)));
        verify(github).prHeadSha(PR);
        verify(mirror).ensurePr("ignite", PR, HEAD);
    }

    /** Each early failure event is a separate launch action and reads the head again after a push. */
    @Test
    void earlyActionsReadFreshHeads() {
        String pushed = "b".repeat(40);
        when(github.prHeadSha(PR)).thenReturn(HEAD, pushed);

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, "Cache", 11L, "Cache"));
        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, "Query", 12L, "Query"));

        ArgumentCaptor<PinnedRun> runs = ArgumentCaptor.forClass(PinnedRun.class);
        verify(tc, times(2)).queuePinnedReplacingQueued(eq("tc"), runs.capture(), eq(true), anyString());
        assertThat(runs.getAllValues()).extracting(run -> run.revisions().get(0).version())
            .containsExactly(HEAD, pushed);
    }

    /** A failed mirror refresh starts no replacement and consumes no auto-rerun attempt. */
    @Test
    void failedPreparationLeavesTheWaveUnstarted() {
        doThrow(new GitMirror.NotReadyException("PR head changed while fetching"))
            .when(mirror).ensurePr("ignite", PR, HEAD);

        standing.sweep();

        verify(mirror).ensurePr("ignite", PR, HEAD);
        verify(tc, never()).queuePinnedReplacingQueued(anyString(), any(), anyBoolean(), anyString());
        assertThat(standing.waveStatus(PR, RUN_ALL)).isEmpty();
    }

    private static TestVerdict blocker(String suite, long id) {
        return new TestVerdict(id, suite + "Test.test", suite, id, suite, "o" + id, true, false,
            "not seen on master", "F", 1);
    }
}
