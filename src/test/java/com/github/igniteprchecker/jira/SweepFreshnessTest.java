package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ChainCollector;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * PR 13335, RunAll 9389046: the chain finished at 23:00:32 and the 23:08:42 sweep re-ran the suites
 * of a verdict cached while the chain was still running. Queries 5 had failed at 22:31, after that
 * verdict, so wave 1 left it out and it got one attempt instead of two. Whatever acts on a verdict —
 * the sweep or the one-shot auto visa — must have it computed after the chain finished.
 */
class SweepFreshnessTest {
    private static final String USER = "avinogradov";

    private static final String TOK = "tc";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final Suite DPC1 = new Suite("IgniteTests24Java8_DiskPageCompressions1", "Disk Page Compressions 1",
        9389012L, 1L, "IgnitePdsCompressionTest.testPageCompression");

    private static final Suite QUERIES5 = new Suite("IgniteTests24Java8_Queries5", "Queries 5", 9389028L, 2L,
        "DynamicEnableIndexingBasicSelfTest.testEnableDynamicIndexing[...]");

    private static final Suite QUERIES6 = new Suite("IgniteTests24Java8_Queries6", "Queries 6", 9389031L, 3L,
        "IndexingQueryEntityTest.testQueryEntity");

    private static final DateTimeFormatter TC_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final GithubClient github = mock(GithubClient.class);

    private final AnalysisProperties cfg =
        new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, mapper);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        cache, new RunDeltaStore(mapper));

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    /** The sweep runs at 23:08:42; the chain finished 8m10s earlier. */
    private final long now = System.currentTimeMillis() / 1000;

    private final long chainFinished = now - 490;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        enrolledLongBeforeTheRun(dir);

        when(github.openPrs()).thenReturn(List.of(
            new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates", null, null, null, null)));
        when(tc.findRunAllBuildForPr(TOK, PR)).thenReturn(Optional.of(build(RUN_ALL, null, "finished",
            TC_DATE.format(Instant.ofEpochSecond(chainFinished)), null, new TcModel.Triggered("user",
                new TcModel.User(USER)))));
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(RUN_ALL));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(RUN_ALL), any())).thenReturn(new ChainCollector.Chain(RUN_ALL,
            "pull/13335/head", List.of(DPC1.failed(), QUERIES5.failed(), QUERIES6.failed()), List.of(), List.of(),
            140, 12, false, 0, false, 0, now - 4 * 3600, now - 4 * 3600, chainFinished));

        for (Suite s : List.of(DPC1, QUERIES5, QUERIES6)) {
            when(tc.getBaseBranchHistory(TOK, s.testId(), s.id())).thenReturn(statuses("SUCCESS", 100)); // 0/100 on master
            when(tc.prBranchRuns(TOK, PR, s.testId(), s.id())).thenReturn(List.of(s.run("FAILURE")));
        }

        when(tc.triggerBuildReplacingQueued(eq(TOK), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenAnswer(inv -> build(9389100L, inv.getArgument(1), "queued", null, null, null));
        when(tc.getBuildState(eq(TOK), anyLong())).thenAnswer(inv -> build(inv.getArgument(1), null, "queued", null,
            TC_DATE.format(Instant.ofEpochSecond(now + 1800)), null));
    }

    @Test
    void waveOneRerunsTheSuitesThatFailedAfterTheCachedVerdict() {
        // Computed at 22:25 while the chain ran: Queries 5 had not failed yet.
        cache.putResult(RUN_ALL, verdict((now - 2580) * 1000, RUN_ALL, 0, List.of(DPC1, QUERIES6), now - 2640));

        standing.sweep();

        assertThat(rerunSuites())
            .as("Queries 5 failed after the cached verdict was computed; wave 1 must re-run it too")
            .containsExactlyInAnyOrder(DPC1.id(), QUERIES5.id(), QUERIES6.id());
    }

    /**
     * A newer RunAll that someone cancelled keeps the result {@code live} forever, so "not live" is not
     * the test: the verdict was read from the finished chain, and nothing finished on the branch since.
     */
    @Test
    void aVerdictComputedAfterTheChainFinishedIsActedOnAsIs() {
        cache.putResult(RUN_ALL, verdict((now - 60) * 1000, 9389300L, chainFinished,
            List.of(DPC1, QUERIES5, QUERIES6), now - 120));

        standing.sweep();

        assertThat(rerunSuites()).containsExactlyInAnyOrder(DPC1.id(), QUERIES5.id(), QUERIES6.id());
        verify(chains, never()).collectForBuild(any(), anyInt(), anyLong(), any());
    }

    /**
     * Only runs that finished after the options were switched on are acted on. Between the chain's
     * finish and the sweep its owner switched PR commands off, and every settings change used to move
     * that cutoff: the run was skipped as if it had finished before auto re-run was on.
     */
    @Test
    void aClickOnAnotherOptionAfterTheRunDoesNotHideItFromTheSweep() {
        cache.putResult(RUN_ALL, verdict((now - 60) * 1000, 9389300L, chainFinished,
            List.of(DPC1, QUERIES5, QUERIES6), now - 120));
        standing.linkGhLogin(USER, TOK, "anton-vinogradov");
        standing.change(USER, TOK, null, null, new StandingVisas.OptionChange(null, null, null, null, false));

        standing.sweep();

        assertThat(rerunSuites()).containsExactlyInAnyOrder(DPC1.id(), QUERIES5.id(), QUERIES6.id());
    }

    /**
     * Read from the finished chain, but the early re-run of Disk Page Compressions 1 passed after it:
     * acting on that verdict would spend an attempt on a suite that has already settled.
     */
    @Test
    void aRunThatFinishedAfterTheVerdictIsTakenInBeforeActing() {
        cache.putResult(RUN_ALL, verdict((now - 300) * 1000, 0, chainFinished, List.of(DPC1, QUERIES5, QUERIES6),
            now - 360));
        when(tc.branchFinishedAfter(TOK, PR, now - 360)).thenReturn(true);
        when(tc.prBranchRuns(TOK, PR, DPC1.testId(), DPC1.id()))
            .thenReturn(List.of(DPC1.run("FAILURE"), DPC1.rerun(9389150L, "SUCCESS")));

        standing.sweep();

        assertThat(rerunSuites()).containsExactlyInAnyOrder(QUERIES5.id(), QUERIES6.id());
    }

    /** When TeamCity can't say whether the branch moved, the sweep recomputes rather than trust the cache. */
    @Test
    void aVerdictThatCannotBeCheckedIsRecomputedBeforeActing() {
        cache.putResult(RUN_ALL, verdict((now - 300) * 1000, 0, chainFinished, List.of(DPC1, QUERIES6), now - 360));
        when(tc.branchFinishedAfter(TOK, PR, now - 360)).thenThrow(new IllegalStateException("ci2 timeout"));

        standing.sweep();

        assertThat(rerunSuites()).containsExactlyInAnyOrder(DPC1.id(), QUERIES5.id(), QUERIES6.id());
    }

    /**
     * The 23:07 warm cycle started recomputing the verdict cached mid-run; while it ran, the early
     * re-run of Disk Page Compressions 1 passed (23:08:30), and the 23:08:42 sweep found that compute
     * still under way. It had read the suite before the re-run passed, so sharing it would spend an
     * attempt on a suite that has already settled.
     */
    @Test
    void aComputeAlreadyUnderWayWhenTheSweepAsksIsNotActedOn() throws Exception {
        cache.putResult(RUN_ALL, verdict((now - 2580) * 1000, RUN_ALL, 0, List.of(DPC1, QUERIES6), now - 2640));
        when(tc.branchFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(true); // the chain, then the re-run
        AtomicBoolean rerunPassed = new AtomicBoolean();
        CountDownLatch warmReadDpc1 = new CountDownLatch(1);
        CountDownLatch warmMayFinish = new CountDownLatch(1);
        when(tc.prBranchRuns(TOK, PR, DPC1.testId(), DPC1.id())).thenAnswer(inv -> {
            if (rerunPassed.get())
                return List.of(DPC1.run("FAILURE"), DPC1.rerun(9389150L, "SUCCESS"));

            warmReadDpc1.countDown();
            warmMayFinish.await();

            return List.of(DPC1.run("FAILURE"));
        });
        CountDownLatch sweepAsked = new CountDownLatch(1);
        when(chains.findBuildId(TOK, PR)).thenAnswer(inv -> {
            if ("sweep".equals(Thread.currentThread().getName()))
                sweepAsked.countDown();

            return Optional.of(RUN_ALL);
        });

        Thread warm = new Thread(() -> analyzer.warm(TOK, PR), "warm");
        warm.start();
        assertThat(warmReadDpc1.await(10, TimeUnit.SECONDS)).as("the warm compute reads the suite").isTrue();
        rerunPassed.set(true);

        Thread sweep = new Thread(standing::sweep, "sweep");
        sweep.start();
        assertThat(sweepAsked.await(10, TimeUnit.SECONDS)).as("the sweep asks for the verdict").isTrue();
        awaitWaiting(sweep);
        warmMayFinish.countDown();
        sweep.join(10_000);
        warm.join(10_000);

        assertThat(rerunSuites())
            .as("Disk Page Compressions 1 passed its re-run before the sweep asked; a compute that read it earlier must not decide")
            .containsExactlyInAnyOrder(QUERIES5.id(), QUERIES6.id());
    }

    /** The one-shot auto visa fires the moment the chain finishes, so it meets the verdict cached mid-run too. */
    @Test
    void theOneShotAutoVisaPostsTheVerdictOfTheFinishedChain() {
        cache.putResult(RUN_ALL, verdict((now - 2580) * 1000, RUN_ALL, 0, List.of(DPC1, QUERIES6), now - 2640));
        Warmer warmer = mock(Warmer.class);
        when(warmer.borrowToken()).thenReturn(TOK);
        VisaService visas = mock(VisaService.class);
        when(visas.compose(eq(PR), any(), any())).thenReturn("visa");
        VisaSubscriptions subs = new VisaSubscriptions(mapper, codec, mock(JiraClient.class), visas, analyzer, warmer,
            mock(PendingCommits.class));
        subs.arm(PR, "IGNITE-28867", "jira-pat", USER);

        subs.onRunFinished(PR);

        ArgumentCaptor<AnalysisResult> posted = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(visas, timeout(10_000)).compose(eq(PR), posted.capture(), any());
        assertThat(posted.getValue().blockers()).extracting(TestVerdict::suite)
            .as("Queries 5 failed after the verdict cached mid-run; the visa must carry it")
            .containsExactlyInAnyOrder(DPC1.id(), QUERIES5.id(), QUERIES6.id());
    }

    /** Waits until the thread parks: past the verdict lookup, the only wait left is on a compute. */
    private static void awaitWaiting(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (t.getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as(t.getName() + " never waits").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    /** Restores the user from a snapshot taken a day before the run, as a restart would. */
    private void enrolledLongBeforeTheRun(Path dir) throws Exception {
        standing.change(USER, TOK, null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) snap.get("enrollments").get(0)).put("enabledAt", (now - 86_400) * 1000);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }

    private List<String> rerunSuites() {
        ArgumentCaptor<String> suites = ArgumentCaptor.forClass(String.class);
        verify(tc, atLeastOnce()).triggerBuildReplacingQueued(eq(TOK), suites.capture(), eq(PR), anyBoolean(), anyString());

        return suites.getAllValues();
    }

    /**
     * A cached result for the chain whose blockers are those suites' failures. {@code liveBuildId} is
     * the chain still going when it was computed (0 for none); {@code finishedAt} is 0 when the
     * analysed chain itself had not finished yet.
     */
    private AnalysisResult verdict(long computedAtMs, long liveBuildId, long finishedAt, List<Suite> blockers,
        long watermark) {
        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", computedAtMs,
            blockers.stream().map(Suite::blocker).toList(), List.of(), List.of(), List.of(), List.of(), 140, 12, false,
            0, liveBuildId != 0, liveBuildId, now - 4 * 3600, now - 4 * 3600, finishedAt, watermark);
    }

    private static TcModel.Build build(long id, String buildTypeId, String state, String finishDate,
        String finishEstimate, TcModel.Triggered triggered) {
        return new TcModel.Build(id, null, state, "pull/13335/head", buildTypeId, null, null, null, finishDate, null,
            finishEstimate, null, triggered, null, null, null, null, null);
    }

    private static List<TcModel.TestOccurrence> statuses(String status, int n) {
        List<TcModel.TestOccurrence> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(new TcModel.TestOccurrence(null, null, status, null, null, null));

        return l;
    }

    /** One suite of the chain with the one test that failed in it. */
    private record Suite(String id, String name, long buildId, long testId, String test) {
        FailedTest failed() {
            return new FailedTest(testId, test, id, buildId, name, "o" + testId);
        }

        /** The test's run in the chain. */
        TcModel.TestOccurrence run(String status) {
            return rerun(buildId, status);
        }

        /** The test's run in a build of this suite. */
        TcModel.TestOccurrence rerun(long suiteBuildId, String status) {
            return new TcModel.TestOccurrence("o" + testId, test, status, null, new TcModel.BuildRef(suiteBuildId,
                "pull/13335/head", "finished", status, id, new TcModel.BuildType(id, name), null, null), null);
        }

        TestVerdict blocker() {
            return new TestVerdict(testId, test, id, buildId, name, "o" + testId, true, false,
                "not seen failing in 100 master run(s); failed the only run on this branch", "F", 1);
        }
    }
}
