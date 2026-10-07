package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * PR 13335, RunAll 9389046: the author, whose standing auto-visa is on, started the chain, and a
 * reviewer armed the one-shot Auto visa on IGNITE-28867. When the chain finished, the one-shot visa
 * was dropped on the promise of the standing one, which then never came: the sweep goes through the
 * 50 most recently updated PRs only, and a JIRA token refused at posting switches the standing visa
 * off. The ticket got no visa at all.
 */
class StandingHandoverTest {
    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final VisaService visas = mock(VisaService.class);

    private final Warmer warmer = mock(Warmer.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, jira, visas,
        mock(RerunTracker.class), warmer, mock(PendingCommits.class));

    private VisaSubscriptions subs = subscriptions();

    @BeforeEach
    void setUp() {
        when(jira.myself("author-pat")).thenReturn(Optional.of("author"));
        standing.change("author", "author-tc", "author-pat", null,
            new StandingVisas.OptionChange(true, false, false, false, null));
        when(github.openPrs()).thenReturn(List.of(
            new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null, null, null, null)));
        when(warmer.borrowToken()).thenReturn("tc");
        when(tc.buildTriggeredBy("tc", CHAIN)).thenReturn(Optional.of("author"));
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User("author")), null, null, null, null, null)));
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(result()));
        when(analyzer.analyzeForAction("author-tc", PR)).thenReturn(Optional.of(result()));
        when(visas.compose(eq(PR), any(), any())).thenReturn("verdict of " + CHAIN);
        when(jira.addCommentWithId(anyString(), anyString(), anyString()))
            .thenReturn(new JiraClient.PostedComment("1001", "https://issues.example/" + ISSUE + "#1001"));

        subs.arm(PR, ISSUE, "reviewer-pat", "reviewer");
    }

    @Test
    void aPrTheSweepDoesNotListGetsTheOneShotVisa() {
        when(github.openPrs()).thenReturn(IntStream.range(13700, 13750)
            .mapToObj(n -> new PrSummary(n, "IGNITE-" + n + " Something else", null, null, null, null)).toList());

        subs.settle(PR, CHAIN);
        standing.sweep();

        verify(jira).addComment("reviewer-pat", ISSUE, "verdict of " + CHAIN);
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void theOneShotVisaWaitsForTheStandingOneAndEndsWhenItIsIn() {
        subs.settle(PR, CHAIN);

        assertThat(subs.armed(PR, "reviewer").issue()).as("waits for the standing visa").isEqualTo(ISSUE);

        standing.sweep();
        subs.settleLeftToStanding();

        verify(jira).addCommentWithId("author-pat", ISSUE, "verdict of " + CHAIN);
        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void aStandingVisaJiraRefusedLeavesTheVisaToTheOneShot() {
        when(jira.addCommentWithId(anyString(), anyString(), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        subs.settle(PR, CHAIN);

        standing.sweep();
        subs.settleLeftToStanding();

        verify(jira).addComment("reviewer-pat", ISSUE, "verdict of " + CHAIN);
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void aPausedStandingVisaLeavesTheVisaToTheOneShot() {
        standing.markTcRejected("author");

        subs.settle(PR, CHAIN);

        verify(jira).addComment("reviewer-pat", ISSUE, "verdict of " + CHAIN);
    }

    @Test
    void aRestartKeepsWaitingForTheStandingVisa(@TempDir Path dir) throws Exception {
        subs.settle(PR, CHAIN);
        Path file = dir.resolve("visa-subs.json");
        subs.saveTo(file);
        subs = subscriptions();
        subs.loadFrom(file);

        standing.sweep();
        subs.settleLeftToStanding();

        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armedCount()).isZero();
    }

    private VisaSubscriptions subscriptions() {
        return new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer, mock(PendingCommits.class), tc,
            standing);
    }

    private static AnalysisResult result() {
        long now = System.currentTimeMillis() / 1000;

        return new AnalysisResult(PR, CHAIN, "pull/13335/head", System.currentTimeMillis(), List.of(), List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, now - 4 * 3600, now - 4 * 3600, now - 60,
            now - 60);
    }
}
