package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What /api/settling tells the PR page about the verdict on screen: a RunAll still going, auto re-runs settling the
 * run (and which wave, until when), the decision on them being made, or a final verdict.
 */
class SettlingPhaseTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        tracker, mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE", "finished",
            "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
    }

    @Test
    void aRunAllUnderWayIsRunning() {
        when(tracker.newestChainUnderWay(PR)).thenReturn(CHAIN + 500);

        assertThat(standing.phase(PR, CHAIN).phase()).isEqualTo("running");
    }

    @Test
    void aWaveGoingSaysWhichAndUntilWhen() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        standing.sweep();
        long eta = System.currentTimeMillis() / 1000 + 1500;
        when(tracker.active()).thenReturn(List.of(new RerunTracker.ActiveRerun(PR, "Cache1", "Cache 1", 9389100L,
            "running", "", 40, 1500L, null, null, 600L)));

        StandingVisas.Phase phase = standing.phase(PR, CHAIN);

        assertThat(phase.phase()).isEqualTo("settling");
        assertThat(phase.wave()).isEqualTo(1);
        assertThat(phase.of()).isEqualTo(2);
        assertThat(phase.what()).isEqualTo("1 blocker suite(s)");
        assertThat(phase.etaEpochSec()).isBetween(eta - 5, eta + 5);
    }

    @Test
    void theDecisionOnReRunsIsSettlingToo() throws Exception {
        CountDownLatch analysing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(analyzer.analyzeForAction("tc", PR)).thenAnswer(inv -> {
            analysing.countDown();
            release.await(10, TimeUnit.SECONDS);

            return Optional.of(verdict());
        });
        Thread sweep = new Thread(standing::sweep, "sweep");
        sweep.start();
        assertThat(analysing.await(10, TimeUnit.SECONDS)).isTrue();

        StandingVisas.Phase deciding = standing.phase(PR, CHAIN);
        release.countDown();
        sweep.join(10_000);

        assertThat(deciding.phase()).isEqualTo("settling");
        assertThat(deciding.wave()).isZero();
        assertThat(standing.phase(PR, CHAIN).phase()).isEqualTo("final");
    }

    /**
     * TeamCity failed part of the analysis, which is retried for up to 20 minutes before the run's waves are decided
     * on: the page called the verdict final all that time, and then showed the wave.
     */
    @Test
    void aRunWhoseDecisionWaitsForTeamCityIsNotFinal() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        when(analyzer.stillRetrying(any())).thenReturn(true);

        standing.sweep();

        StandingVisas.Phase waiting = standing.phase(PR, CHAIN);
        assertThat(waiting.phase()).isEqualTo("settling");
        assertThat(waiting.wave()).isZero();

        when(analyzer.stillRetrying(any())).thenReturn(false);
        standing.sweep();

        assertThat(standing.phase(PR, CHAIN).wave()).isEqualTo(1);
    }

    /** A newer RunAll the verdict saw going takes the run's place: nothing is decided on it any more. */
    @Test
    void aRunANewerOneHoldsBackIsNotDecidedOn() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13335/head", System.currentTimeMillis(), List.of(blocker()), List.of(), List.of(), List.of(),
            List.of(), 140, 1, false, 0, true, CHAIN + 500, 0, 0, 1, 0)));
        when(tc.getBuildState("tc", CHAIN + 500)).thenReturn(new TcModel.Build(CHAIN + 500, null, "running",
            "pull/13335/head", null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        standing.sweep();

        assertThat(standing.phase(PR, CHAIN).phase()).isEqualTo("final");
    }

    /**
     * v1.20.11 marked a run handled when its options went off, and left its wave behind: the page showed "Auto re-run
     * #1 in progress … the verdict below is interim" over the run's last verdict for good.
     */
    @Test
    void aWaveOfARunAlreadyHandledIsDropped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).putObject("posted").put(String.valueOf(PR), CHAIN);
        snap.remove("waves");
        snap.putObject("retries").putObject(String.valueOf(PR)).put("buildId", CHAIN).put("attempts", 1)
            .put("what", "2 blocker suite(s)").putNull("note").putArray("history").add("2 blocker suite(s)");
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);

        standing.sweep();

        assertThat(standing.phase(PR, CHAIN).phase()).isEqualTo("final");
        standing.saveTo(file);
        assertThat(mapper.readTree(file.toFile()).get("waves").size()).isZero();
    }

    private static TestVerdict blocker() {
        return new TestVerdict(9389011L, "Cache1Test.test", "Cache1", 9389011L, "Cache 1", "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, CHAIN, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
