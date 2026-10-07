package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * One 502 from ci2 used to make a failed test a blocker that the sweep posted in the visa and re-ran a
 * suite for at once. A verdict TeamCity errors left incomplete waits while it is retried: two sweep
 * passes, or a later try of the one-shot visa. Past that it goes out as it is, its unchecked tests
 * listed apart and not counted as blockers.
 */
class IncompleteVerdictHoldTest {
    private static final String USER = "avinogradov";

    private static final String TOK = "tc";

    private static final int PR = 13654;

    private static final long RUN_ALL = 9391879L;

    private static final String CACHE1 = "IgniteTests24Java8_Cache1";

    private static final String CACHE2 = "IgniteTests24Java8_Cache2";

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final long now = System.currentTimeMillis();

    IncompleteVerdictHoldTest() {
        when(analyzer.stillRetrying(any())).thenCallRealMethod();
    }

    @Test
    void theSweepHoldsAFreshlyIncompleteVerdict(@TempDir Path dir) throws Exception {
        StandingVisas standing = sweepFinds(incomplete(now - 60_000), dir);

        standing.sweep();

        verify(tc, never()).triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString());
        assertThat(standing.buildHandled(USER, PR, RUN_ALL)).as("the next sweep takes it up again").isFalse();
    }

    @Test
    void afterTheHoldTheSweepActsWithoutCountingUncheckedTests(@TempDir Path dir) throws Exception {
        StandingVisas standing = sweepFinds(incomplete(now - 25 * 60_000), dir);
        when(tc.triggerBuildReplacingQueued(eq(TOK), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenAnswer(inv -> new TcModel.Build(9392600L, null, "queued", null, inv.getArgument(1), null, null, null,
                null, null, null, null, null, null, null, null, null, null));
        when(tc.getBuildState(eq(TOK), anyLong())).thenReturn(new TcModel.Build(9392600L, null, "queued", null, null,
            null, null, null, null, null, TcDates.format(now / 1000 + 1800), null, null, null, null, null, null, null));

        standing.sweep();

        ArgumentCaptor<String> suites = ArgumentCaptor.forClass(String.class);
        verify(tc).triggerBuildReplacingQueued(eq(TOK), suites.capture(), eq(PR), anyBoolean(), anyString());
        assertThat(suites.getAllValues()).as("only the blocker's suite; the unchecked test's is not re-run for it")
            .containsExactly(CACHE1);
    }

    @Test
    void theOneShotVisaWaitsAndTriesAgain() {
        JiraClient jira = mock(JiraClient.class);
        Warmer warmer = mock(Warmer.class);
        when(warmer.borrowToken()).thenReturn(TOK);
        when(analyzer.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete(now - 60_000)));
        when(analyzer.analyzeForAction(TOK, PR)).thenReturn(Optional.of(complete()));
        VisaService visas = new VisaService(new TeamcityProperties("https://ci2/"), "https://checker");
        StandingVisas standing = mock(StandingVisas.class);
        when(standing.visaCover(any(), anyInt(), anyLong(), any())).thenReturn(StandingVisas.VisaCover.NONE);
        VisaSubscriptions subs = new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer,
            mock(PendingCommits.class), tc, standing);
        subs.retryDelayMs = 200;
        subs.arm(PR, "IGNITE-28890", "jira-pat", USER);

        subs.onChainFinished(new RerunTracker.ChainFinished(PR, RUN_ALL, false));

        verify(jira, after(100).never()).addComment(anyString(), anyString(), anyString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(jira, timeout(5_000)).addComment(eq("jira-pat"), eq("IGNITE-28890"), body.capture());
        assertThat(body.getValue()).doesNotContain("could not be checked");
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void theVisaListsUncheckedTestsApartAndNeverReadsGreen() {
        VisaService visas = new VisaService(new TeamcityProperties("https://ci2/"), "https://checker");
        AnalysisResult r = new AnalysisResult(PR, RUN_ALL, "pull/13654/head", now, List.of(), List.of(), List.of(),
            List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 0, 0, List.of(), List.of(),
            List.of(unchecked()), now - 25 * 60_000);

        String md = visas.composeMarkdown(PR, r, null);
        String wiki = visas.compose(PR, r, null);

        assertThat(md).doesNotContain("✅", "blocker(s) in")
            .contains("1 failed test(s) could not be checked", "- Cache 2 · `GridCacheTest.testPut` · [TC]");
        assertThat(wiki).doesNotContain("(/)", "blocker(s) in")
            .contains("1 failed test(s) could not be checked", "- Cache 2 · {{GridCacheTest.testPut}} · [TC|");
    }

    /** A user with auto re-run on, and the sweep finding their finished RunAll with this verdict. */
    private StandingVisas sweepFinds(AnalysisResult verdict, Path dir) throws Exception {
        StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, mock(JiraClient.class),
            mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
        standing.change(USER, TOK, null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) snap.get("enrollments").get(0)).put("enabledAt", now - 86_400_000);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);

        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28890 Fix it", null, null, null, null)));
        when(tc.findRunAllBuildForPr(TOK, PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE", "finished",
            "pull/13654/head", null, null, null, null, TcDates.format(now / 1000 - 3600), null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(TOK, PR)).thenReturn(Optional.of(verdict));

        return standing;
    }

    private AnalysisResult incomplete(long since) {
        TestVerdict blocker = new TestVerdict(2L, "GridCacheTest.testGet", CACHE1, 9391901L, "Cache 1", "o2", true,
            false, "not seen failing in 100 master run(s); failed the only run on this branch", "F", 1);

        return new AnalysisResult(PR, RUN_ALL, "pull/13654/head", now, List.of(blocker), List.of(), List.of(),
            List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60, List.of(),
            List.of(), List.of(unchecked()), since);
    }

    private AnalysisResult complete() {
        return new AnalysisResult(PR, RUN_ALL, "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60);
    }

    private static TestVerdict unchecked() {
        return new TestVerdict(1L, "GridCacheTest.testPut", CACHE2, 9391902L, "Cache 2", "o1", false, false,
            "could not verify (TeamCity error: 502 Bad Gateway)", "", 0);
    }
}
