package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The checker's page shows the verdict of PR #69 of apache/ignite-python-thin-client: the newest run of Thin client:
 * Python on {@code pull/69} that tested the PR's own commit. Someone ran the suite on the branch from TeamCity, which
 * checked out the client's master: that run is not the PR's. An auto re-run on the same commits counts with its run,
 * as in the verdict comment. Opening the page again asks TeamCity nothing for two minutes.
 */
class ClientVerdictsTest {
    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final int PR = 69;

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final String NEWER_CLIENT = "b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4e5";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    private final ClientVerdicts clients = new ClientVerdicts(tc, analyzer, github,
        new PythonClientProperties(null, null, null, null),
        new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null), now::get);

    @BeforeEach
    void setUp() {
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong()))
            .thenAnswer(inv -> verdict(inv.getArgument(3, Long.class), 0));
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong(), anyLong()))
            .thenAnswer(inv -> verdict(inv.getArgument(3, Long.class), 0));
    }

    @Test
    void theVerdictIsOfTheNewestRunThatTestedThePrsOwnCommit() {
        runs(run(9424300L, "bob", CLIENT, IGNITE, "master"), run(9424200L, "alice", CLIENT, IGNITE, CLIENT));

        ClientVerdicts.Verdict v = clients.verdict("tc", PR).orElseThrow();

        assertThat(v.run()).isEqualTo(new ClientVerdicts.Run(9424200L, 0, "Thin client: Python", "alice", CLIENT,
            IGNITE));
        verify(analyzer).analyzeSuiteRun("tc", PR, "pull/69", 9424200L);
        verify(analyzer, never()).analyzeSuiteRun("tc", PR, "pull/69", 9424300L);
    }

    @Test
    void anAutoRerunOnTheSameCommitsCountsWithItsRun() {
        runs(run(9424210L, "alice", CLIENT, IGNITE, CLIENT), run(9424200L, "alice", CLIENT, IGNITE, CLIENT));

        ClientVerdicts.Verdict v = clients.verdict("tc", PR).orElseThrow();

        assertThat(v.run().buildId()).isEqualTo(9424200L);
        assertThat(v.run().rerunId()).isEqualTo(9424210L);
        verify(analyzer).analyzeSuiteRun("tc", PR, "pull/69", 9424200L, 9424210L);
    }

    @Test
    void aRunOfOtherCommitsIsJudgedAlone() {
        runs(run(9424210L, "alice", NEWER_CLIENT, IGNITE, NEWER_CLIENT), run(9424200L, "alice", CLIENT, IGNITE,
            CLIENT));

        ClientVerdicts.Verdict v = clients.verdict("tc", PR).orElseThrow();

        assertThat(v.run().rerunId()).isZero();
        verify(analyzer).analyzeSuiteRun("tc", PR, "pull/69", 9424210L);
    }

    @Test
    void aRunTeamCityNamesNoCheckoutOfIsTakenByItsCommits() {
        runs(run(9424200L, null, CLIENT, IGNITE, null));

        assertThat(clients.verdict("tc", PR).orElseThrow().run().clientSha()).isEqualTo(CLIENT);
    }

    @Test
    void aPrWithoutARunOfItsOwnCodeHasNoVerdict() {
        runs(run(9424300L, "bob", CLIENT, IGNITE, "master"));

        assertThat(clients.verdict("tc", PR)).isEmpty();
        verify(analyzer, never()).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong());
    }

    @Test
    void anotherViewWithinTwoMinutesAsksTeamCityNothing() {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));

        clients.verdict("tc", PR);
        now.addAndGet(ClientVerdicts.LOOK_EVERY_MS - 1);
        clients.verdict("tc", PR);

        verify(tc, times(1)).finishedRuns(anyString(), anyString(), anyString(), anyString());
        verify(analyzer, times(1)).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong());

        now.addAndGet(1);
        clients.verdict("tc", PR);

        verify(tc, times(2)).finishedRuns(anyString(), anyString(), anyString(), anyString());
        verify(analyzer, times(1)).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong());
    }

    /** Each warm cycle asks which run is the newest, and a view right after it asks nothing. */
    @Test
    void theWarmCycleComputesANewRunOnlyOnce() {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));

        assertThat(clients.warm("tc", PR)).isTrue();
        assertThat(clients.warm("tc", PR)).isFalse();
        assertThat(clients.verdict("tc", PR)).isPresent();

        verify(analyzer, times(1)).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong());
        verify(tc, times(2)).finishedRuns("tc", SUITE, "pull/69", "OVERRIDDEN_BRANCH");
    }

    @Test
    void anIncompleteVerdictIsComputedAgainAfterTwoMinutes() {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong()))
            .thenAnswer(inv -> verdict(9424200L, now.get()));

        clients.warm("tc", PR);
        now.addAndGet(ClientVerdicts.RETRY_WAIT_MS - 1);
        assertThat(clients.warm("tc", PR)).isFalse();
        now.addAndGet(1);
        assertThat(clients.warm("tc", PR)).isTrue();
    }

    @Test
    void theListBadgesTheRunAgainstThePrsHeadByTheClientsCommit() {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));
        clients.warm("tc", PR);

        assertThat(clients.glance(PR).against(CLIENT)).isEqualTo(Caveats.Standing.CLEAN);
        assertThat(clients.glance(PR).against(NEWER_CLIENT)).isEqualTo(Caveats.Standing.OLD_CODE);
        assertThat(clients.starter(PR)).isEqualTo("alice");
        assertThat(clients.glance(70)).isNull();
    }

    @Test
    void theHeadComesFromTheClientsOpenPrs() {
        when(github.openPrs("apache/ignite-python-thin-client")).thenReturn(List.of(
            new PrSummary(PR, "IGNITE-29117 Fix test", "https://github.com/apache/ignite-python-thin-client/pull/69",
                null, null, null, NEWER_CLIENT, null)));

        assertThat(clients.head(PR)).isEqualTo(NEWER_CLIENT);
        assertThat(clients.head(70)).isNull();
    }

    /**
     * Two people look at the PR right after its run finished: one opens the page, the other presses ↻. Each began a
     * whole compute of the same run on ci2. The second now waits for the first, and the warm cycle yields while they
     * wait.
     */
    @Test
    void aViewAndTheRefreshOfTheSameRunShareOneCompute() throws Exception {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger computes = new AtomicInteger();
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong())).thenAnswer(inv -> {
            computes.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);

            return verdict(9424200L, 0);
        });

        Thread view = new Thread(() -> clients.verdict("tc-alice", PR));
        view.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> computes.get() == 1);
        Thread refresh = new Thread(() -> clients.fresh("tc-bob", PR));
        refresh.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> refresh.getState() == Thread.State.WAITING
            || refresh.getState() == Thread.State.TIMED_WAITING);
        boolean waiting = clients.userWaiting();
        release.countDown();
        view.join(10_000);
        refresh.join(10_000);

        assertThat(computes.get()).isEqualTo(1);
        assertThat(waiting).isTrue();
        assertThat(clients.userWaiting()).isFalse();
        assertThat(clients.kept(PR)).isPresent();
    }

    /**
     * TeamCity answered 401 to the token of the view whose compute the warm cycle waited for. The cycle must not take
     * that 401 for an answer to its own token and drop it from the pool.
     */
    @Test
    void aSharedComputeDoesNotPassOnTeamCitysAnswerToAnotherToken() throws Exception {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger computes = new AtomicInteger();
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong())).thenAnswer(inv -> {
            computes.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null);
        });

        Thread view = new Thread(() -> {
            try {
                clients.verdict("tc-expired", PR);
            }
            catch (RuntimeException expected) {
                // the view's own 401
            }
        });
        view.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> computes.get() == 1);
        AtomicReference<Throwable> warmGot = new AtomicReference<>();
        Thread warm = new Thread(() -> {
            try {
                clients.warm("tc-pool", PR);
            }
            catch (RuntimeException e) {
                warmGot.set(e);
            }
        });
        warm.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> warm.getState() == Thread.State.WAITING
            || warm.getState() == Thread.State.TIMED_WAITING);
        release.countDown();
        view.join(10_000);
        warm.join(10_000);

        assertThat(computes.get()).isEqualTo(1);
        assertThat(warmGot.get()).isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(RestClientResponseException.class);
    }

    /** The warm cycle is no view: it does not make itself yield. */
    @Test
    void theWarmCycleWaitsForNobody() {
        runs(run(9424200L, "alice", CLIENT, IGNITE, CLIENT));
        AtomicInteger waitingWhileWarm = new AtomicInteger();
        when(analyzer.analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong())).thenAnswer(inv -> {
            if (clients.userWaiting())
                waitingWhileWarm.incrementAndGet();

            return verdict(9424200L, 0);
        });

        clients.warm("tc", PR);

        assertThat(waitingWhileWarm.get()).isZero();
    }

    private void runs(TcModel.BranchRun... runs) {
        when(tc.finishedRuns(anyString(), eq(SUITE), eq("pull/69"), eq("OVERRIDDEN_BRANCH")))
            .thenReturn(List.of(runs));
    }

    private static TcModel.BranchRun run(long id, String starter, String client, String ignite, String checkout) {
        return new TcModel.BranchRun(id, new TcModel.BuildType(SUITE, "Thin client: Python"),
            starter == null ? null : new TcModel.Triggered("user", new TcModel.User(starter)),
            new TcModel.RootedRevisions(List.of(
                new TcModel.RootedRevision(ignite, new TcModel.RootInstance("335", "GitHubApacheIgnite")),
                new TcModel.RootedRevision(client, new TcModel.RootInstance("314",
                    "GitHubApacheIgnitePythonThinClient")))),
            checkout == null ? null : new TcModel.Properties(List.of(new TcModel.Property("OVERRIDDEN_BRANCH",
                checkout))));
    }

    private AnalysisResult verdict(long buildId, long incompleteSince) {
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get", SUITE, buildId, "Thin client: Python",
            "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        return new AnalysisResult(PR, buildId, "pull/69", now.get(), List.of(), List.of(), List.of(shared), List.of(),
            List.of(), 1, 0, false, 0, false, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), incompleteSince);
    }
}
