package com.github.igniteprchecker.jira;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ChainCollector;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PR 13335: RunAll 9389046 finished while its author had the PR page open, which looked the PR's run up every
 * minute. The event settled it at once, but the analysis still had RunAll 9389000, the run before it, as the PR's
 * run for half a minute: the settle took that for a newer run having raced it and left the verdict to the sweep, up
 * to 10 minutes later.
 */
class FreshRunOnEventTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long BEFORE = 9389000L;

    private static final long CHAIN = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, 120, null);

    private final ChainCollector chains = spy(new ChainCollector(tc, mock(SuiteBaseline.class)));

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), new AnalysisCache(cfg, mapper),
        new RunDeltaStore(mapper));

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        long now = System.currentTimeMillis() / 1000;
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload", null, null, null,
            null)));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.branchFinishedAfter(anyString(), eq(PR), anyLong())).thenReturn(true);
        doReturn(chain(BEFORE, now - 86_400)).when(chains).collectForBuild(eq("tc"), eq(PR), eq(BEFORE), any());
        doReturn(chain(CHAIN, now - 5)).when(chains).collectForBuild(eq("tc"), eq(PR), eq(CHAIN), any());
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, true, false, null));
    }

    @Test
    void theVerdictComesAtOnceThoughThePageHadJustLookedTheRunUp() {
        when(tc.findRunAllBuildForAnalysis("tc", PR)).thenReturn(Optional.of(build(BEFORE)));
        analyzer.analyze("tc", PR);

        finish();

        verify(github, timeout(5_000)).addPrComment(eq("gh-pat"), eq(PR), anyString());
    }

    @Test
    void theVerdictComesAtOnceWhenNobodyLookedTheRunUp() {
        finish();

        verify(github, timeout(5_000)).addPrComment(eq("gh-pat"), eq(PR), anyString());
    }

    private void finish() {
        when(tc.findRunAllBuildForAnalysis("tc", PR)).thenReturn(Optional.of(build(CHAIN)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(build(CHAIN)));
        standing.onChainFinished(new RerunTracker.ChainFinished(PR, CHAIN, false));
    }

    private static ChainCollector.Chain chain(long id, long finishedAt) {
        return new ChainCollector.Chain(id, "pull/13335/head", List.of(), List.of(), List.of(), 150, 0, false, 0,
            false, 0, 0, 0, finishedAt);
    }

    private static TcModel.Build build(long id) {
        return new TcModel.Build(id, "SUCCESS", "finished", "pull/13335/head", "RunAll", null, null, null, null, null,
            null, null, new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null);
    }
}
