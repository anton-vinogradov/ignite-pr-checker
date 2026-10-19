package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.PrTests;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * On PR 13335 "This PR's tests" came late, after the verdict, with "master history was looked up for 20 of 67
 * tests of changed classes". The server asked TeamCity for those 20 histories one after another, though the card
 * shows master history only for a failed test, and the analysis behind the verdict has already looked up every
 * failure. One answer now asks TeamCity once when the analysis knows the failures, and what does not wait for
 * anything else is asked at the same time.
 */
class PrTestsCallsTest {
    private static final String TOK = "tok";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String HEAD = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    private static final int OTHER_PR = 13575;

    private static final long OTHER_CHAIN = 9391002L;

    private static final String OTHER_HEAD = "1f2e3d4c5b6a7980f1e2d3c4b5a6978801f2e3d4";

    private static final String SECURITY = "IgniteTests24Java8_Security";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final AnalysisCache cache = new AnalysisCache(
        new AnalysisProperties(null, "RunAll", null, null, null, null, null), Jackson2Defaults.mapper());

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void oneAnswerAsksTeamCityOnceWhenTheAnalysisKnowsTheFailures() {
        when(github.prTestFiles(PR)).thenReturn(List.of(
            new GithubClient.PrFile("modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java",
                "added"),
            new GithubClient.PrFile("modules/core/src/test/java/org/apache/ignite/internal/ssl/SslContextTest.java",
                "modified")));
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(false, false, 0, null, HEAD,
            "IGNITE-28867 SSL certificates reload"));
        when(github.checkRuns(HEAD)).thenReturn(List.of(new GithubClient.CheckRun(51234567890L,
            "Check java code on JDK 17", "completed", "success", null)));
        when(github.job(51234567890L)).thenReturn(new GithubClient.Job("completed", List.of(
            new GithubClient.Job.Step("Run abandoned tests checks.", "completed", "success"))));
        List<TcModel.TestOccurrence> runs = new ArrayList<>();
        IntStream.range(0, 27).forEach(i -> runs.add(run(100 + i, "SslRenewalTest.testRenew" + i, "SUCCESS", 900)));
        IntStream.range(0, 38).forEach(i -> runs.add(run(200 + i, "SslContextTest.testContext" + i, "SUCCESS",
            61_000 + i)));
        runs.add(run(301, "SslContextTest.testReloadExpired", "FAILURE", 3_000));
        runs.add(run(302, "SslContextTest.testReloadWrongPassword", "FAILURE", 2_000));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(runs));
        cache.history(301, SECURITY, () -> RunHistory.ofMaster(masterRuns(40)));
        cache.history(302, SECURITY, () -> RunHistory.ofMaster(List.of()));
        clearInvocations(github, tc);

        PrTests answer = new PrTestRuns(github, tc, cache).of(TOK, PR, CHAIN, false);

        assertThat(mockingDetails(tc).getInvocations()).as("TeamCity: the runs of the classes").hasSize(1);
        assertThat(mockingDetails(github).getInvocations())
            .as("GitHub: the PR's files, its head, Ignite's check and its job").hasSize(4);
        assertThat(answer.note()).isNull();
        assertThat(answer.classes().get(1).runs().stream().filter(r -> r.masterRuns() != null))
            .extracting(PrTests.Run::testId, PrTests.Run::masterRuns)
            .containsExactlyInAnyOrder(tuple(301L, 40), tuple(302L, 0));
    }

    /** GitHub names the PR's head while TeamCity says how the classes ran: neither waits for the other's answer. */
    @Test
    void githubAndTeamCityAreAskedAtTheSameTime() {
        pr();
        CountDownLatch headAsked = new CountDownLatch(1);
        CountDownLatch runsAsked = new CountDownLatch(1);
        AtomicBoolean together = new AtomicBoolean(true);
        when(github.prOutcome(PR)).thenAnswer(inv -> {
            headAsked.countDown();
            if (!runsAsked.await(2, TimeUnit.SECONDS))
                together.set(false);

            return new GithubClient.PrOutcome(false, false, 0, null, HEAD, "IGNITE-28867 SSL certificates reload");
        });
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenAnswer(inv -> {
            runsAsked.countDown();
            if (!headAsked.await(2, TimeUnit.SECONDS))
                together.set(false);

            return Optional.of(List.of(run(301, "SslContextTest.testReloadExpired", "SUCCESS", 3_000)));
        });

        PrTests answer = new PrTestRuns(github, tc, cache, pool, System::currentTimeMillis).of(TOK, PR, CHAIN, false);

        assertThat(together).as("each was asked before the other answered").isTrue();
        assertThat(answer.suiteCheck().sha()).isEqualTo(HEAD);
    }

    /** The master histories the analysis has not looked up yet are asked for side by side. */
    @Test
    void theFailuresHistoriesAreAskedSideBySide() {
        pr();
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of(
            run(301, "SslContextTest.testReloadExpired", "FAILURE", 3_000),
            run(302, "SslContextTest.testReloadWrongPassword", "FAILURE", 2_000))));
        CountDownLatch asked = new CountDownLatch(2);
        AtomicBoolean together = new AtomicBoolean(true);
        when(tc.getBaseBranchHistory(eq(TOK), anyLong(), eq(SECURITY))).thenAnswer(inv -> {
            asked.countDown();
            if (!asked.await(2, TimeUnit.SECONDS))
                together.set(false);

            return masterRuns(40);
        });

        PrTests answer = new PrTestRuns(github, tc, cache, pool, System::currentTimeMillis).of(TOK, PR, CHAIN, false);

        assertThat(together).as("each history was asked before the other one came").isTrue();
        assertThat(answer.classes()).singleElement().satisfies(c -> assertThat(c.runs())
            .extracting(PrTests.Run::masterRuns).containsExactly(40, 40));
    }

    /**
     * Two PRs are opened at once. TeamCity takes long on each master history of PR 13335's 20 failed tests, which the
     * analysis cache does not have. PR 13575 has one passed test, so its answer needs only GitHub's word on its head.
     * With every thread of the application's pool on PR 13335's lookups, PR 13575's answer does not wait for them.
     */
    @Test
    void anAnswerDoesNotWaitForAnotherPrsLookups() throws Exception {
        ExecutorService shared = new AnalysisConfig().prTestsExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            pr();
            when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(IntStream.range(0, 20)
                .mapToObj(i -> run(400 + i, "SslContextTest.testReload" + i, "FAILURE", 3_000)).toList()));
            CountDownLatch busy = new CountDownLatch(4);
            when(tc.getBaseBranchHistory(eq(TOK), anyLong(), eq(SECURITY))).thenAnswer(inv -> {
                busy.countDown();
                release.await(10, TimeUnit.SECONDS);

                return masterRuns(40);
            });
            when(github.prTestFiles(OTHER_PR)).thenReturn(List.of(new GithubClient.PrFile(
                "modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java", "modified")));
            when(github.prOutcome(OTHER_PR)).thenReturn(new GithubClient.PrOutcome(false, false, 0, null, OTHER_HEAD,
                "IGNITE-29049 Move MDC to JUnit"));
            when(tc.testRunsOfClasses(eq(TOK), eq(OTHER_CHAIN), any())).thenReturn(Optional.of(List.of(
                run(501, "SslRenewalTest.testRenew", "SUCCESS", 900))));
            PrTestRuns prTests = new PrTestRuns(github, tc, cache, shared, System::currentTimeMillis);

            CompletableFuture<PrTests> slow = CompletableFuture.supplyAsync(() -> prTests.of(TOK, PR, CHAIN, false));
            assertThat(busy.await(5, TimeUnit.SECONDS)).as("PR 13335's lookups hold the pool's threads").isTrue();
            CompletableFuture<PrTests> quick = CompletableFuture.supplyAsync(
                () -> prTests.of(TOK, OTHER_PR, OTHER_CHAIN, false));

            assertThat(quick).as("PR 13575's answer while PR 13335's lookups wait")
                .succeedsWithin(Duration.ofSeconds(2))
                .satisfies(answer -> assertThat(answer.suiteCheck().sha()).isEqualTo(OTHER_HEAD));
            release.countDown();
            assertThat(slow).succeedsWithin(Duration.ofSeconds(5)).satisfies(answer -> assertThat(answer.classes())
                .singleElement().satisfies(c -> assertThat(c.runs()).extracting(PrTests.Run::masterRuns)
                    .hasSize(20).containsOnly(40)));
        }
        finally {
            release.countDown();
            shared.shutdown();
        }
    }

    /**
     * A PR that fixes a flaky test TeamCity has muted: the test still fails in the RunAll, and the verdict leaves a
     * muted failure out. Its master history would show nowhere, so TeamCity is not asked it.
     */
    @Test
    void aMutedFailureIsSaidAndNotLookedUp() {
        pr();
        TcModel.TestOccurrence muted = run(301, "SslContextTest.testReloadExpired", "FAILURE", 3_000);
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of(
            new TcModel.TestOccurrence(muted.id(), muted.name(), muted.status(), muted.test(), muted.build(), null,
                muted.duration(), true))));

        PrTests answer = new PrTestRuns(github, tc, cache).of(TOK, PR, CHAIN, false);

        assertThat(answer.classes()).singleElement().satisfies(c -> assertThat(c.runs())
            .extracting(PrTests.Run::muted, PrTests.Run::masterRuns).containsExactly(tuple(true, null)));
        verify(tc, never()).getBaseBranchHistory(eq(TOK), anyLong(), any());
    }

    /** PR 13335 changes SslContextTest; its head passed Ignite's check. */
    private void pr() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(
            "modules/core/src/test/java/org/apache/ignite/internal/ssl/SslContextTest.java", "modified")));
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(false, false, 0, null, HEAD,
            "IGNITE-28867 SSL certificates reload"));
    }

    private static TcModel.TestOccurrence run(long testId, String test, String status, long durationMs) {
        return new TcModel.TestOccurrence("build:(id:9389100),id:" + testId,
            "SecurityTestSuite: org.apache.ignite.internal.ssl." + test, status, new TcModel.TestRef(testId),
            new TcModel.BuildRef(9389100L, null, null, null, SECURITY, new TcModel.BuildType(SECURITY, "Security"),
                null, null), null, durationMs);
    }

    private static List<TcModel.TestOccurrence> masterRuns(int passes) {
        return IntStream.range(0, passes).mapToObj(i -> new TcModel.TestOccurrence(null, null, "SUCCESS", null, null,
            null)).toList();
    }
}
