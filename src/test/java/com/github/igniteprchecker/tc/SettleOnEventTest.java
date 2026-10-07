package com.github.igniteprchecker.tc;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * PR 13335: the tracker saw RunAll 9389046 finish within 20 seconds, but its wave of re-runs, and then its
 * verdict, went in only with the next sweep, run every 10 minutes: 13 of the 69 minutes to the verdict were spent
 * waiting for it. Wired here the way the application wires it, a finished chain or re-run settles the run at once.
 */
class SettleOnEventTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final long RERUN = 9389100L;

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final Warmer warmer = mock(Warmer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aFinishedChainGetsItsWaveAtOnce() {
        RerunTracker tracker = wired();
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        tracker.record(PR, chain("running", null));
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(chain("finished", "FAILURE"));

        tracker.refresh();

        verify(tc, timeout(5_000)).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(PR), anyBoolean(),
            anyString());
    }

    @Test
    void aFinishedReRunSettlesTheRunAtOnce() {
        RerunTracker tracker = wired();
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        tracker.record(PR, chain("running", null));
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(chain("finished", "FAILURE"));
        tracker.refresh();
        verify(tc, timeout(5_000)).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(PR), anyBoolean(),
            anyString());

        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
        when(tc.getBuildState("tc", RERUN)).thenReturn(rerun("finished"));
        tracker.refresh();

        verify(github, timeout(5_000)).addPrComment(eq("gh-pat"), eq(PR), contains("Settled after 1 auto re-run wave(s)"));
    }

    /** The application's beans for the tracker's events, with an enrolled user who has auto re-run on. */
    private RerunTracker wired() {
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(SessionCodec.class, () -> codec);
        context.registerBean(AnalysisProperties.class, () -> new AnalysisProperties(null, RUN_ALL, null, null, null,
            null, null));
        context.registerBean(TcClient.class, () -> tc);
        context.registerBean(Warmer.class, () -> warmer);
        context.registerBean(BlockerAnalyzer.class, () -> analyzer);
        context.registerBean(GithubClient.class, () -> github);
        context.registerBean(JiraClient.class, () -> mock(JiraClient.class));
        context.registerBean(VisaService.class,
            () -> new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"));
        context.registerBean(PendingCommits.class, () -> mock(PendingCommits.class));
        context.register(RerunTracker.class, StandingVisas.class, VisaSubscriptions.class);
        context.refresh();

        stubs();
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(rerun("queued"));
        context.getBean(StandingVisas.class).change(USER, "tc", null, "gh-pat",
            new StandingVisas.OptionChange(false, true, true, false, null));

        return context.getBean(RerunTracker.class);
    }

    private void stubs() {
        when(warmer.borrowToken()).thenReturn("tc");
        when(tc.queuedBuilds("tc")).thenReturn(List.of());
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE", "finished",
            "pull/13335/head", RUN_ALL, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
    }

    private static TcModel.Build chain(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13335/head", RUN_ALL, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    private static TcModel.Build rerun(String state) {
        return new TcModel.Build(RERUN, null, state, "pull/13335/head", "IgniteTests24Java8_Cache1", null, null, null,
            null, null, null, null, null, null, null, null, null, null);
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
