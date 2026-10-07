package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * The one-shot auto visa posted whatever verdict the PR had when any chain finished: a cancelled
 * chain sent out the verdict of the clean chain before it, and so did a build lookup cached from
 * before the finish. Subscriptions were kept per PR: a reviewer saw the author's "Armed — click to
 * cancel" and cancelled it with one click, arming again swapped in the reviewer's token, and with the
 * standing auto-visa on the ticket got a second visa.
 */
class OneShotVisaTest {
    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final long PREVIOUS = 9381000L;

    private static final String ISSUE = "IGNITE-28867";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final JiraClient jira = mock(JiraClient.class);

    private final VisaService visas = mock(VisaService.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final Warmer warmer = mock(Warmer.class);

    private final TcClient tc = mock(TcClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private VisaSubscriptions subs = subscriptions();

    @BeforeEach
    void setUp() {
        when(warmer.borrowToken()).thenReturn("tc");
        when(visas.compose(eq(PR), any(), any(), any())).thenAnswer(inv -> "verdict of " + inv.getArgument(1,
            AnalysisResult.class).buildId());
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(result(CHAIN, true)));
        when(standing.visaCover(any(), anyInt(), anyLong(), any())).thenReturn(StandingVisas.VisaCover.NONE);
    }

    @Test
    void aCancelledChainLeavesTheSubscriptionArmed() {
        subs.arm(PR, ISSUE, "author-pat", "author");

        subs.onChainFinished(new RerunTracker.ChainFinished(PR, CHAIN, true));

        verify(analyzer, after(300).never()).forceRefresh(anyString(), anyInt());
        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armed(PR, "author").issue()).isEqualTo(ISSUE);
    }

    @Test
    void theVerdictOfTheFinishedChainIsPosted() {
        subs.arm(PR, ISSUE, "author-pat", "author");

        subs.onChainFinished(new RerunTracker.ChainFinished(PR, CHAIN, false));

        verify(jira, after(300).times(1)).addComment("author-pat", ISSUE, "verdict of " + CHAIN);
        assertThat(subs.armed(PR, "author").issue()).isNull();
    }

    /** The PR's latest build is looked up again; a lookup that still names the chain before is not posted. */
    @Test
    void theVerdictOfAnotherChainIsNotPosted() {
        subs.arm(PR, ISSUE, "author-pat", "author");
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(result(PREVIOUS, true)));

        subs.settle(PR, CHAIN, System.currentTimeMillis());

        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armed(PR, "author").issue()).isEqualTo(ISSUE);
    }

    @Test
    void aVerdictReadBeforeTheChainFinishedIsComputedAgain() {
        subs.arm(PR, ISSUE, "author-pat", "author");
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(result(CHAIN, false)));
        AnalysisResult after = result(CHAIN, true);
        when(analyzer.analyzeAfterNow("tc", PR)).thenReturn(Optional.of(after));

        subs.settle(PR, CHAIN, System.currentTimeMillis());

        ArgumentCaptor<AnalysisResult> posted = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(visas).compose(eq(PR), posted.capture(), any(), any());
        assertThat(posted.getValue()).isSameAs(after);
    }

    @Test
    void aReviewerCancelsOnlyTheirOwn() {
        subs.arm(PR, ISSUE, "author-pat", "author");

        subs.cancel(PR, "reviewer");

        assertThat(subs.armed(PR, "author").issue()).isEqualTo(ISSUE);
        assertThat(subs.armed(PR, "reviewer").issue()).isNull();
        assertThat(subs.armed(PR, "reviewer").others()).containsExactly("author");
    }

    /** Both armed on the same ticket: one visa, from whoever armed first, with their own token. */
    @Test
    void aReviewerArmingTooDoesNotReplaceTheAuthorNorDoubleTheVisa() {
        subs.arm(PR, ISSUE, "author-pat", "author");
        subs.arm(PR, ISSUE, "reviewer-pat", "reviewer");

        subs.settle(PR, CHAIN, System.currentTimeMillis());

        verify(jira, times(1)).addComment(anyString(), anyString(), anyString());
        verify(jira).addComment("author-pat", ISSUE, "verdict of " + CHAIN);
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void aRunWhoseStandingVisaIsStillToComeWaitsForIt() {
        subs.arm(PR, ISSUE, "reviewer-pat", "reviewer");
        when(tc.buildTriggeredBy("tc", CHAIN)).thenReturn(Optional.of("author"));
        when(standing.visaCover("author", PR, CHAIN, ISSUE)).thenReturn(StandingVisas.VisaCover.PENDING);

        subs.settle(PR, CHAIN, System.currentTimeMillis());

        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        verify(analyzer, never()).forceRefresh(anyString(), anyInt());
        assertThat(subs.armed(PR, "reviewer").issue()).isEqualTo(ISSUE);
    }

    @Test
    void aRunWhoseStandingVisaIsInGetsNoSecondVisa() {
        subs.arm(PR, ISSUE, "reviewer-pat", "reviewer");
        when(tc.buildTriggeredBy("tc", CHAIN)).thenReturn(Optional.of("author"));
        when(standing.visaCover("author", PR, CHAIN, ISSUE)).thenReturn(StandingVisas.VisaCover.POSTED);

        subs.settle(PR, CHAIN, System.currentTimeMillis());

        verify(jira, never()).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armedCount()).isZero();
    }

    /** visa-subs.json of v1.20.11: one subscription per PR, each with its owner. */
    @Test
    void theSnapshotOfTheCurrentReleaseReadsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("visa-subs.json");
        Map<String, Object> old = Map.of("pr", PR, "issue", ISSUE, "token", codec.encryptString("author-pat"),
            "username", "author", "armedAt", 1_759_830_000_000L);
        Files.writeString(file, mapper.writeValueAsString(List.of(old)));

        subs = subscriptions();
        subs.loadFrom(file);
        Path saved = dir.resolve("saved.json");
        subs.saveTo(saved);

        assertThat(subs.armed(PR, "author").issue()).isEqualTo(ISSUE);
        assertThat(mapper.readTree(saved.toFile())).isEqualTo(mapper.readTree(file.toFile()));
        subs.settle(PR, CHAIN, System.currentTimeMillis());
        verify(jira).addComment("author-pat", ISSUE, "verdict of " + CHAIN);
    }

    private VisaSubscriptions subscriptions() {
        return new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer, mock(PendingCommits.class), tc,
            standing);
    }

    private static AnalysisResult result(long buildId, boolean finished) {
        long now = System.currentTimeMillis() / 1000;

        return new AnalysisResult(PR, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(), List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, now - 4 * 3600, now - 4 * 3600,
            finished ? now - 60 : 0, now - 60);
    }
}
