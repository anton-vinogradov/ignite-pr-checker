package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The checker does not look for test classes outside suites itself: Ignite's GitHub Actions job "Check java code on
 * JDK 17" does, in its step "Run abandoned tests checks.". Its check of a PR's head is read as GitHub reports it: the
 * check run, the job's steps, and the log when the step failed.
 */
class AbandonedTestsCheckTest {
    private static final String SHA = "9389046a1b2c3d4e5f60718293a4b5c6d7e8f901";

    private static final long JOB = 51234567890L;

    private static final String JOB_URL = "https://github.com/apache/ignite/actions/runs/18001/job/" + JOB;

    private final GithubClient github = mock(GithubClient.class);

    private final AbandonedTestsCheck check = new AbandonedTestsCheck(github);

    @Test
    void aStepThatPassedSaysEveryClassIsInASuite() {
        finishedJob("success");

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.PASSED);
        assertThat(outcome.url()).isEqualTo(JOB_URL);
        assertThat(outcome.settled()).isTrue();
        verify(github, never()).jobLog(anyLong(), any());
    }

    @Test
    void aStepThatFailedNamesTheClassesFromTheLog() throws IOException {
        finishedJob("failure");
        String log = Files.readString(Path.of("src/test/resources/actions/abandoned-tests-failed.log"),
            StandardCharsets.UTF_8);
        when(github.jobLog(eq(JOB), any())).thenAnswer(inv -> {
            Function<Stream<String>, ?> reader = inv.getArgument(1);

            return Optional.ofNullable(reader.apply(log.lines()));
        });

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.FAILED);
        assertThat(outcome.classes()).containsExactly("org.apache.ignite.ssl.SslContextReloadTest",
            "org.apache.ignite.internal.ssl.SslRenewalTest");
    }

    /** A finished check of a commit does not change: the page and the verdict of one settle ask GitHub once. */
    @Test
    void aFinishedCheckOfACommitIsAskedOnce() {
        finishedJob("success");

        check.of(SHA);
        check.of(SHA);

        verify(github, times(1)).checkRuns(SHA);
        verify(github, times(1)).job(JOB);
    }

    @Test
    void aStepNotFinishedYetIsStillRunning() {
        job("in_progress", null, "in_progress");

        assertThat(check.of(SHA).state()).isEqualTo(State.RUNNING);
    }

    /** The log of a job comes once the job ends; the step that failed is only the start of its end. */
    @Test
    void aFailedStepOfAJobStillGoingWaitsForTheLog() {
        job("completed", "failure", "in_progress");

        assertThat(check.of(SHA).state()).isEqualTo(State.RUNNING);
        verify(github, never()).jobLog(anyLong(), any());
    }

    /** Checkstyle failed first: the abandoned-tests step never ran, and says nothing of any class. */
    @Test
    void aSkippedStepSaysWhy() {
        finishedJob("skipped");

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.SKIPPED);
        assertThat(outcome.reason()).isEqualTo("an earlier step of its job failed");
    }

    @Test
    void noJobOnTheCommitYetIsSaid() {
        when(github.checkRuns(SHA)).thenReturn(List.of(new GithubClient.CheckRun(1, "Сheck .NET code", "completed",
            "success", null)));

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.UNKNOWN);
        assertThat(outcome.reason()).isEqualTo("GitHub shows no Check java code job for it yet");
        assertThat(outcome.settled()).isFalse();
    }

    /** GitHub gives a job's log only to a signed-in caller; without the app token the classes can't be named. */
    @Test
    void withoutATokenTheLogIsNotRead() {
        finishedJob("failure");
        when(github.jobLog(eq(JOB), any())).thenReturn(Optional.empty());

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.UNKNOWN);
        assertThat(outcome.reason()).isEqualTo("reading its log takes the checker's GitHub token");
    }

    @Test
    void aLogThatCouldNotBeReadIsAskedAgain() {
        finishedJob("failure");
        when(github.jobLog(eq(JOB), any())).thenThrow(new IllegalStateException("GitHub: 502"));

        AbandonedTestsCheck.Outcome outcome = check.of(SHA);

        assertThat(outcome.state()).isEqualTo(State.UNKNOWN);
        assertThat(outcome.reason()).isEqualTo("GitHub did not give its log");
        assertThat(outcome.settled()).isFalse();
    }

    private void finishedJob(String stepConclusion) {
        job("completed", stepConclusion, "completed");
    }

    private void job(String stepStatus, String stepConclusion, String jobStatus) {
        when(github.checkRuns(SHA)).thenReturn(List.of(
            new GithubClient.CheckRun(JOB - 1, "Check ducktape on py38", "completed", "success", null),
            new GithubClient.CheckRun(JOB, "Check java code on JDK 17", jobStatus, null, JOB_URL)));
        when(github.job(JOB)).thenReturn(new GithubClient.Job(jobStatus, List.of(
            new GithubClient.Job.Step("Run codestyle and licenses checks", "completed", "success"),
            new GithubClient.Job.Step("Run abandoned tests checks.", stepStatus, stepConclusion),
            new GithubClient.Job.Step("Check javadocs.", "queued", null))));
    }
}
