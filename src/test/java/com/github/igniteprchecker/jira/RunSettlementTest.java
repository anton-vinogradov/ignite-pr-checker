package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A finished run is final only after a saved decision, or a policy that rules out future auto re-runs. */
class RunSettlementTest {
    private static final String USER = "alice";

    private static final int PR = 13786;

    private static final long BUILD = 9502804;

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "receipt-test"), mapper);

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final PendingCommits pending = mock(PendingCommits.class);

    /** Holds the queued settle still, so a page can observe the decision before the worker has run it. */
    private final ExecutorService settler = mock(ExecutorService.class);

    private final StandingVisas standing = fresh();

    /** Enrolls the starter and supplies external TeamCity/GitHub responses; settlement and snapshots are real. */
    @BeforeEach
    void enroll() {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29117 Change", null, null, null, null)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(BUILD, "FAILURE", "finished",
            "pull/13786/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(BUILD + 1, null, "queued", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
    }

    /** Given an eligible finished run with no tracker event, viewing it waits for the first positive decision. */
    @Test
    void anEligibleRunIsNotFinalBeforeItsFirstSettle() {
        StandingVisas.Phase before = standing.phase(PR, BUILD,
            () -> new StandingVisas.Run(USER, false, System.currentTimeMillis()));

        assertThat(before.phase()).isEqualTo("settling");
        assertThat(before.completedWaves()).isNull();
        verify(settler).execute(any(Runnable.class));

        standing.sweep();

        assertThat(standing.phase(PR, BUILD).phase()).isEqualTo("final");
        assertThat(standing.phase(PR, BUILD).completedWaves()).isZero();
        assertThat(standing.phase(PR, BUILD).reason()).isEqualTo("not-needed");
    }

    /** Given a queued wave followed by a clean verdict, cleanup and restart retain the completed wave. */
    @Test
    void aCompletedWaveSurvivesCleanupAndRestart(@TempDir Path dir) throws Exception {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        standing.sweep();
        assertThat(standing.phase(PR, BUILD).wave()).isEqualTo(1);
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
        standing.sweep();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);

        assertThat(mapper.readTree(file.toFile()).get("waves")).isEmpty();
        assertThat(mapper.readTree(file.toFile()).get("enrollments").get(0).get("posted").get("" + PR).asLong())
            .isEqualTo(BUILD);
        StandingVisas restarted = fresh();
        restarted.loadFrom(file);
        StandingVisas.Phase finalResult = restarted.phase(PR, BUILD, () -> {
            throw new AssertionError("the saved result needs no TeamCity lookup");
        });

        assertThat(finalResult.phase()).isEqualTo("final");
        assertThat(finalResult.completedWaves()).isEqualTo(1);
        assertThat(restarted.buildHandled(USER, PR, BUILD)).isTrue();
    }

    /** Given a legacy handled build without history, loading it keeps queue deduplication but never invents zero. */
    @Test
    void aLegacyHandledBuildHasUnknownWaveHistory(@TempDir Path dir) throws Exception {
        standing.sweep();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).remove("handledResults");
        mapper.writeValue(file.toFile(), snap);
        StandingVisas restarted = fresh();
        restarted.loadFrom(file);

        StandingVisas.Phase result = restarted.phase(PR, BUILD);

        assertThat(result.phase()).isEqualTo("final");
        assertThat(result.completedWaves()).isNull();
        assertThat(result.reason()).isEqualTo("legacy");
        assertThat(restarted.buildHandled(USER, PR, BUILD)).isTrue();
    }

    /** Given two client PRs with the same number, each receipt keeps its own build and re-run aliases after restart. */
    @Test
    void clientReceiptsKeepRepositoryIdentityAndAliases(@TempDir Path dir) throws Exception {
        String python = "apache/ignite-python-thin-client";
        String php = "apache/ignite-php-thin-client";
        standing.clientHandled(USER, python, 42, 9002, 9004, 1, null);
        standing.clientHandled(USER, php, 42, 9003, 0, 0, "not-needed");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = fresh();
        restarted.loadFrom(file);

        assertThat(restarted.completedPhase(python, 42, 9002).orElseThrow().completedWaves()).isEqualTo(1);
        assertThat(restarted.completedPhase(python, 42, 9004).orElseThrow().completedWaves()).isEqualTo(1);
        assertThat(restarted.completedPhase(php, 42, 9003).orElseThrow().completedWaves()).isZero();
        assertThat(restarted.completedPhase(php, 42, 9002)).isEmpty();
        assertThat(restarted.completedPhase(python, 43, 9002)).isEmpty();
        assertThat(restarted.completedPhase(null, 42, 9002)).isEmpty();
    }

    /** Given a newer client result, a delayed completion of its older run cannot replace that result. */
    @Test
    void aLateClientCompletionKeepsTheNewerResult() {
        String python = "apache/ignite-python-thin-client";
        standing.clientHandled(USER, python, 42, 9002, 9004, 1, null);

        standing.clientHandled(USER, python, 42, 9001, 0, 0, "not-needed");

        assertThat(standing.completedPhase(python, 42, 9002).orElseThrow().completedWaves()).isEqualTo(1);
        assertThat(standing.completedPhase(python, 42, 9001)).isEmpty();
    }

    /** Given options now off, the policy rules out future re-runs without claiming none happened before. */
    @Test
    void aDisabledEnrollmentDoesNotTurnUnknownHistoryIntoZero() {
        standing.disable(USER);

        StandingVisas.Phase result = standing.phase(PR, BUILD,
            () -> new StandingVisas.Run(USER, false, System.currentTimeMillis()));

        assertThat(result.phase()).isEqualTo("final");
        assertThat(result.reason()).isEqualTo("off");
        assertThat(result.completedWaves()).isNull();
    }

    /** Given an opted-in starter whose token was rejected, their eligible run still awaits a decision. */
    @Test
    void aPausedTokenDoesNotMakeAnEligibleRunFinal() {
        standing.markTcRejected(USER);

        StandingVisas.Phase result = standing.phase(PR, BUILD,
            () -> new StandingVisas.Run(USER, false, System.currentTimeMillis()));

        assertThat(result.phase()).isEqualTo("settling");
        assertThat(result.completedWaves()).isNull();
    }

    /** Given automation disabled for this instance, reading an eligible run does not schedule background work. */
    @Test
    void aPageReadHonorsTheInstancesAutomationSwitch() {
        ReflectionTestUtils.setField(standing, "automation", false);

        StandingVisas.Phase result = standing.phase(PR, BUILD,
            () -> new StandingVisas.Run(USER, false, System.currentTimeMillis()));

        assertThat(result.phase()).isEqualTo("final");
        assertThat(result.reason()).isEqualTo("off");
        assertThat(result.completedWaves()).isNull();
        verify(settler, never()).execute(any(Runnable.class));
    }

    /** Given only verdict comments enabled, settlement handles the run without inventing a re-run history. */
    @Test
    void aCommentOnlyRunCanBeSettledWithoutAWaveCount() {
        when(github.writes()).thenReturn(true);
        assertThat(standing.change(USER, "tc", null, null,
            new StandingVisas.OptionChange(false, false, true, false, null))).isEmpty();

        standing.sweep();

        assertThat(standing.buildHandled(USER, PR, BUILD)).isTrue();
        assertThat(standing.phase(PR, BUILD).completedWaves()).isNull();
        assertThat(standing.phase(PR, BUILD).reason()).isEqualTo("off");
    }

    /** Given 31 failing suites, the delivery callback sees a skipped decision rather than an unqueued second wave. */
    @Test
    void aSystemicSkipDoesNotInventAnActiveWaveWhileDeliveryRuns() {
        List<TestVerdict> failures = new ArrayList<>();
        for (int i = 0; i < 31; i++)
            failures.add(new TestVerdict(100 + i, "CacheTest.test", "Suite" + i, 100 + i, "Suite " + i,
                "occ-" + i, true, false, "not seen failing on master", "F", 1));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(failures.toArray(TestVerdict[]::new))));
        AtomicReference<StandingVisas.Phase> duringDelivery = new AtomicReference<>();
        when(pending.since("tc", PR, BUILD)).thenAnswer(inv -> {
            duringDelivery.set(standing.phase(PR, BUILD));
            return null;
        });

        standing.sweep();

        assertThat(duringDelivery.get()).isNotNull();
        assertThat(duringDelivery.get().phase()).isEqualTo("settling");
        assertThat(duringDelivery.get().wave()).isZero();
        assertThat(duringDelivery.get().reason()).isEqualTo("too-many-suites");
        assertThat(duringDelivery.get().rerunBuildIds()).isEmpty();
        assertThat(standing.phase(PR, BUILD).completedWaves()).isZero();
        assertThat(standing.phase(PR, BUILD).reason()).isEqualTo("too-many-suites");
        verify(tc, never()).triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString());
    }

    /** Given absent starter metadata or a failed lookup, viewing the run never fabricates a final result. */
    @Test
    void unknownTeamCityMetadataDoesNotProveAResult() {
        assertThat(standing.phase(PR, BUILD, () -> StandingVisas.Run.UNKNOWN).phase()).isEqualTo("unknown");
        assertThatThrownBy(() -> standing.phase(PR, BUILD, () -> {
            throw new IllegalStateException("TeamCity unavailable");
        })).isInstanceOf(IllegalStateException.class);
    }

    /** Creates the state owner with the same encryption key and a held worker, as a restarted instance would. */
    private StandingVisas fresh() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, mock(JiraClient.class),
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"), tracker,
            mock(Warmer.class), pending, mock(ExecutorService.class), settler,
            new SettingsJournal(mapper));
    }

    private static TestVerdict blocker() {
        return new TestVerdict(100, "CacheTest.test", "Cache", 100, "Cache", "occ-1", true, false,
            "not seen failing on master", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, BUILD, "pull/13786/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 1, 0);
    }
}
