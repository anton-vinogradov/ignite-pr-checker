package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.PrTests.AtRun;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Ignite's workflow does not run on a PR that conflicts with master, so GitHub shows no job of its abandoned-tests
 * check there, and the check never settles. The runs of a finished RunAll do not change: TeamCity is asked them again
 * only after 15 minutes, while the check is asked again every 2.
 */
class PrTestsAskedAgainTest {
    private static final String TOK = "tok";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String BUILT = "5be1c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8";

    private static final String HEAD = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    private static final long JOB = 51234567890L;

    private static final String RENEWAL =
        "modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private final PrTestRuns prTests = new PrTestRuns(github, tc,
        new AnalysisCache(new AnalysisProperties(null, "RunAll", null, null, null, null, null), new ObjectMapper()),
        now::get);

    @Test
    void aFinishedRunAllIsNotAskedAgainWhileIgnitesCheckIsUnsettled() {
        pr();
        when(github.checkRuns(HEAD)).thenReturn(List.of());

        assertThat(prTests.of(TOK, PR, CHAIN, false).suiteCheck().state()).isEqualTo(State.UNKNOWN);
        now.addAndGet(3 * 60_000L);
        prTests.of(TOK, PR, CHAIN, false);

        verify(github, times(2)).checkRuns(HEAD);
        verify(tc, times(1)).testRunsOfClasses(eq(TOK), eq(CHAIN), any());

        now.addAndGet(13 * 60_000L);
        prTests.of(TOK, PR, CHAIN, false);

        verify(tc, times(2)).testRunsOfClasses(eq(TOK), eq(CHAIN), any());
    }

    /** The page opened while Ignite's job was going: a couple of minutes later it says how the job ended. */
    @Test
    void aCheckStillGoingIsAskedAgainAfterTwoMinutes() {
        pr();
        job("in_progress", null);

        assertThat(prTests.of(TOK, PR, CHAIN, false).suiteCheck().state()).isEqualTo(State.RUNNING);

        job("completed", "success");
        now.addAndGet(3 * 60_000L);

        assertThat(prTests.of(TOK, PR, CHAIN, false).suiteCheck().state()).isEqualTo(State.PASSED);
    }

    /** A chain's revision does not change, and the analysis already keeps it. */
    @Test
    void theRunsRevisionIsAskedOnce() {
        pr();
        job("completed", "success");
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(BUILT));
        when(github.changesBetween(BUILT, HEAD)).thenReturn(new GithubClient.Changes(Map.of(RENEWAL, "renamed"),
            false, true));

        assertThat(prTests.of(TOK, PR, CHAIN, false).classes().get(0).atRun()).isEqualTo(AtRun.ABSENT);
        now.addAndGet(3 * 60_000L);
        assertThat(prTests.of(TOK, PR, CHAIN, false).classes().get(0).atRun()).isEqualTo(AtRun.ABSENT);

        verify(tc, times(1)).buildRevision(TOK, CHAIN);
        verify(github, times(1)).changesBetween(BUILT, HEAD);
    }

    /** PR 13335's SslRenewalTest, with no runs in the RunAll. */
    private void pr() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(RENEWAL, "added")));
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(false, false, 0, null, HEAD,
            "IGNITE-28867 SSL certificates reload"));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any()))
            .thenReturn(Optional.of(List.<TcModel.TestOccurrence>of()));
    }

    /** Ignite's job on the head, as far as it got. */
    private void job(String status, String conclusion) {
        when(github.checkRuns(HEAD)).thenReturn(List.of(new GithubClient.CheckRun(JOB, "Check java code on JDK 17",
            status, conclusion, null)));
        when(github.job(JOB)).thenReturn(new GithubClient.Job(status, List.of(
            new GithubClient.Job.Step("Run abandoned tests checks.", status, conclusion))));
    }
}
