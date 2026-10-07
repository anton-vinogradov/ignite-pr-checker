package com.github.igniteprchecker.jira;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A suite announced the moment it fails is judged by a verdict that has seen that failure. The cached
 * verdict of a running chain is computed minutes earlier, so the suite that has just failed is usually
 * absent from it: judged by that verdict, the suite looks innocent, the one-shot announcement is spent,
 * and the early re-run never happens (#219: RunAll 9389046, Queries 5 failed at 22:31 mid-chain).
 */
class EarlyRerunFreshnessTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final long QUERIES5_RUN = 9389028L;

    private static final String QUERIES5 = "IgniteTests24Java8_Queries5";

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = new StandingVisas(new ObjectMapper(),
        new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()),
        tc, github, analyzer, mock(JiraClient.class), mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final RerunTracker.SuiteFailedMidRun queries5Failed =
        new RerunTracker.SuiteFailedMidRun(PR, CHAIN, QUERIES5, QUERIES5_RUN, "Queries 5");

    EarlyRerunFreshnessTest() {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, true, false, false));
        when(tc.buildTriggeredBy(anyString(), eq(CHAIN))).thenReturn(Optional.of(USER));
        when(tc.triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389219L, null, "queued", "pull/13335/head", QUERIES5, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aCachedVerdictThatPredatesTheFailureIsNotTheJudge() {
        when(analyzer.analyze(anyString(), eq(PR))).thenReturn(Optional.of(verdict()));
        when(analyzer.analyzeAfterNow(anyString(), eq(PR))).thenReturn(Optional.of(verdict(queries5Watch())));

        standing.earlyRerun(queries5Failed);

        verify(tc).triggerBuildReplacingQueued(anyString(), eq(QUERIES5), eq(PR), eq(true), anyString());
    }

    @Test
    void aCachedVerdictThatSawTheFailureIsUsedAsIs() {
        when(analyzer.analyze(anyString(), eq(PR))).thenReturn(Optional.of(verdict(queries5Watch())));

        standing.earlyRerun(queries5Failed);

        verify(analyzer, never()).analyzeAfterNow(anyString(), eq(PR));
        verify(tc).triggerBuildReplacingQueued(anyString(), eq(QUERIES5), eq(PR), eq(true), anyString());
    }

    /**
     * TeamCity failed to answer for the test Queries 5 failed: the cached verdict saw the failure all the same,
     * and recomputing the whole PR in the middle of TeamCity's trouble would only re-run nothing again.
     */
    @Test
    void aCachedVerdictThatCouldNotCheckTheFailureIsUsedAsIs() {
        TestVerdict unchecked = new TestVerdict(5L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing",
            QUERIES5, QUERIES5_RUN, "Queries 5", "o5", false, false,
            "could not verify (TeamCity error: 502 Bad Gateway)", "", 0);
        when(analyzer.analyze(anyString(), eq(PR))).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13335/head", 0, List.of(), List.of(), List.of(), List.of(), List.of(), 140, 0, false, 0, true, CHAIN,
            0, 0, 0, 0, List.of(), List.of(), List.of(unchecked), System.currentTimeMillis())));

        standing.earlyRerun(queries5Failed);

        verify(analyzer, never()).analyzeAfterNow(anyString(), eq(PR));
        verify(tc, never()).triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString());
    }

    @Test
    void aFreshVerdictThatFindsOnlyMasterNoiseStillRerunsNothing() {
        when(analyzer.analyze(anyString(), eq(PR))).thenReturn(Optional.of(verdict()));
        when(analyzer.analyzeAfterNow(anyString(), eq(PR))).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13335/head", 0, List.of(), List.of(),
            List.of(new TestVerdict(7L, "IgniteCacheQueryNodeRestartSelfTest.testRestarts", QUERIES5, QUERIES5_RUN,
                "Queries 5", "o7", false, false, "pre-existing: fails 13/100 on master", "F", 1)),
            List.of(), List.of(), 140, 0, false, 0, true, CHAIN, 0, 0, 0, 0)));

        standing.earlyRerun(queries5Failed);

        verify(tc, never()).triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString());
    }

    private static TestVerdict queries5Watch() {
        return new TestVerdict(5L, "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing", QUERIES5,
            QUERIES5_RUN, "Queries 5", "o5", false, true, "first failure on revision ec2c458 — watch", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... watch) {
        return new AnalysisResult(PR, CHAIN, "pull/13335/head", 0, List.of(), List.of(watch), List.of(),
            List.of(), List.of(), 140, 0, false, 0, true, CHAIN, 0, 0, 0, 0);
    }
}
