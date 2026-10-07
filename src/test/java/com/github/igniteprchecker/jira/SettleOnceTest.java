package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
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
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A finished chain now settles its run at once, and the sweep keeps going through the same runs: RunAll 9389046
 * finished while the sweep was deciding on it. The two take turns, so its wave of re-runs goes in once.
 */
class SettleOnceTest {
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

    @Test
    void theSweepAndAFinishedChainQueueItsWaveOnce() throws Exception {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE", "finished",
            "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        AtomicInteger queued = new AtomicInteger();
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenAnswer(inv -> {
                queued.incrementAndGet();

                return new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
            });
        when(tracker.hasActive(PR)).thenAnswer(inv -> queued.get() > 0);
        CountDownLatch analysing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(analyzer.analyzeForAction("tc", PR)).thenAnswer(inv -> {
            analysing.countDown();
            release.await(10, TimeUnit.SECONDS);

            return Optional.of(new AnalysisResult(PR, CHAIN, "pull/13335/head", System.currentTimeMillis(),
                List.of(new TestVerdict(9389011L, "Cache1Test.test", "Cache1", 9389011L, "Cache 1", "o1", true, false,
                    "not seen failing in 100 master run(s)", "F", 1)), List.of(), List.of(), List.of(), List.of(), 140,
                1, false, 0, false, 0, 0, 0, 1, 0));
        });

        Thread sweep = new Thread(standing::sweep, "sweep");
        sweep.start();
        assertThat(analysing.await(10, TimeUnit.SECONDS)).as("the sweep is deciding on the run").isTrue();
        standing.onChainFinished(new RerunTracker.ChainFinished(PR, CHAIN, false));
        Thread.sleep(300);
        release.countDown();
        sweep.join(10_000);
        verify(tracker, timeout(5_000).times(2)).hasActive(PR);

        verify(tc, times(1)).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(PR), anyBoolean(), anyString());
    }
}
