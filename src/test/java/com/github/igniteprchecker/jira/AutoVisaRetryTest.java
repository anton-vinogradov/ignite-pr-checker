package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR 13654: the one-shot visa held back for a 502 got one more try, kept in memory only. A 502 on that
 * try, or a deploy before it, left the visa to the next finished RunAll, which may never come, and the
 * visa never reached IGNITE-28890. It is tried until it posts, for an hour, across restarts.
 */
class AutoVisaRetryTest {
    private static final String TOK = "tc";

    private static final int PR = 13654;

    private static final long RUN_ALL = 9391879L;

    private static final String ISSUE = "IGNITE-28890";

    private static final String USER = "avinogradov";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final long now = System.currentTimeMillis();

    @Test
    void aTryThatFailsAfterTheHoldIsFollowedByAnother() {
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete()))
            .thenThrow(new IllegalStateException("502 Bad Gateway"))
            .thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        VisaSubscriptions subs = subscriptions(analyzer, jira);
        subs.retryDelayMs = 100;
        subs.arm(PR, ISSUE, "jira-pat", USER);

        subs.onChainFinished(finished());

        verify(jira, timeout(5_000)).addComment("jira-pat", ISSUE, "verdict of " + RUN_ALL);
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void aCancelledSubscriptionIsNotTriedAgain() {
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete()));
        VisaSubscriptions subs = subscriptions(analyzer, mock(JiraClient.class));
        subs.retryDelayMs = 100;
        subs.arm(PR, ISSUE, "jira-pat", USER);
        subs.onChainFinished(finished());
        verify(analyzer, timeout(2_000)).forceRefresh(TOK, PR);

        subs.cancel(PR, USER);

        verify(analyzer, after(500).times(1)).forceRefresh(TOK, PR);
    }

    @Test
    void aTryDueAtARestartIsMadeAfterIt(@TempDir Path dir) throws Exception {
        BlockerAnalyzer before = analyzer();
        when(before.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete()));
        VisaSubscriptions running = subscriptions(before, mock(JiraClient.class));
        running.retryDelayMs = 500;
        running.arm(PR, ISSUE, "jira-pat", USER);
        running.onChainFinished(finished());
        Path file = dir.resolve("visa-subs.json");
        savedWithATryDue(running, file);
        running.cancel(PR, USER);

        BlockerAnalyzer after = analyzer();
        when(after.forceRefresh(TOK, PR)).thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        subscriptions(after, jira).loadFrom(file);

        verify(jira, timeout(5_000)).addComment("jira-pat", ISSUE, "verdict of " + RUN_ALL);
    }

    /**
     * visa-subs.json as the release on prod keeps it: armed, waiting for the chain to finish, and written back
     * as it was. Once the chain finishes, a held verdict gets its tries.
     */
    @Test
    void aSubscriptionSavedWithoutRetriesWaitsForItsChain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("visa-subs.json");
        Files.writeString(file, "[{\"pr\":" + PR + ",\"issue\":\"" + ISSUE + "\",\"token\":\""
            + codec.encryptString("jira-pat") + "\",\"username\":\"" + USER + "\",\"armedAt\":" + (now - 3_600_000)
            + "}]");
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete()))
            .thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        VisaSubscriptions subs = subscriptions(analyzer, jira);
        subs.retryDelayMs = 100;

        subs.loadFrom(file);

        assertThat(subs.armed(PR, USER).issue()).isEqualTo(ISSUE);
        verify(analyzer, after(500).never()).forceRefresh(anyString(), eq(PR));
        Path saved = dir.resolve("saved.json");
        subs.saveTo(saved);
        assertThat(mapper.readTree(saved.toFile())).isEqualTo(mapper.readTree(file.toFile()));

        subs.onChainFinished(finished());

        verify(jira, timeout(5_000)).addComment("jira-pat", ISSUE, "verdict of " + RUN_ALL);
    }

    /** After an hour of failed tries the visa waits for the next finished RunAll, still armed. */
    @Test
    void triesStopAfterAnHour(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("visa-subs.json");
        Files.writeString(file, "[{\"pr\":" + PR + ",\"issue\":\"" + ISSUE + "\",\"token\":\""
            + codec.encryptString("jira-pat") + "\",\"username\":\"" + USER + "\",\"armedAt\":" + (now - 7_200_000)
            + ",\"retryChain\":" + RUN_ALL + ",\"retryAt\":" + (now - 1_000) + ",\"retryingSince\":"
            + (now - 3_660_000) + "}]");
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.forceRefresh(TOK, PR)).thenThrow(new IllegalStateException("502 Bad Gateway"));
        VisaSubscriptions subs = subscriptions(analyzer, mock(JiraClient.class));
        subs.retryDelayMs = 100;

        subs.loadFrom(file);

        verify(analyzer, after(1_000).times(1)).forceRefresh(TOK, PR);
        assertThat(subs.armed(PR, USER).issue()).isEqualTo(ISSUE);
        subs.saveTo(file);
        assertThat(mapper.readTree(file.toFile()).get(0).has("retryAt")).isFalse();
    }

    /**
     * Subscriptions are per user now, and the tries serve each: the author and a reviewer armed on the ticket
     * get one visa, of the chain that finished, once its verdict is complete. A user who armed after the
     * chain finished asked for the next one, and the tries leave them armed.
     */
    @Test
    void theTriesPostTheChainsVerdictToThoseArmedWhenItFinished(@TempDir Path dir) throws Exception {
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.forceRefresh(TOK, PR)).thenReturn(Optional.of(incomplete()))
            .thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        VisaSubscriptions subs = subscriptions(analyzer, jira);
        subs.retryDelayMs = 300;
        subs.arm(PR, ISSUE, "author-pat", "author");
        subs.arm(PR, ISSUE, "reviewer-pat", "reviewer");
        subs.onChainFinished(finished());
        savedWithATryDue(subs, dir.resolve("visa-subs.json"));
        Thread.sleep(5);

        subs.arm(PR, "IGNITE-28900", "late-pat", "late");

        verify(jira, timeout(5_000)).addComment(anyString(), eq(ISSUE), eq("verdict of " + RUN_ALL));
        verify(jira, after(500).times(1)).addComment(anyString(), anyString(), anyString());
        assertThat(subs.armed(PR, "late").issue()).isEqualTo("IGNITE-28900");
        assertThat(subs.armed(PR, "late").others()).isEmpty();
    }

    /** Saves the subscriptions once the held visa has its next try set: what a deploy in between finds. */
    private void savedWithATryDue(VisaSubscriptions subs, Path file) throws Exception {
        for (long deadline = System.currentTimeMillis() + 3_000; System.currentTimeMillis() < deadline; ) {
            subs.saveTo(file);
            JsonNode retryAt = mapper.readTree(file.toFile()).get(0).get("retryAt");
            if (retryAt != null && retryAt.asLong() > 0)
                return;
            Thread.sleep(20);
        }
    }

    private static BlockerAnalyzer analyzer() {
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.stillRetrying(any())).thenCallRealMethod();

        return analyzer;
    }

    private VisaSubscriptions subscriptions(BlockerAnalyzer analyzer, JiraClient jira) {
        Warmer warmer = mock(Warmer.class);
        when(warmer.borrowToken()).thenReturn(TOK);
        VisaService visas = mock(VisaService.class);
        when(visas.compose(eq(PR), any(), any())).thenAnswer(inv -> "verdict of " + inv.getArgument(1,
            AnalysisResult.class).buildId());
        StandingVisas standing = mock(StandingVisas.class);
        when(standing.visaCover(any(), anyInt(), anyLong(), any())).thenReturn(StandingVisas.VisaCover.NONE);

        return new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer, mock(PendingCommits.class),
            mock(TcClient.class), standing);
    }

    private static RerunTracker.ChainFinished finished() {
        return new RerunTracker.ChainFinished(PR, RUN_ALL, false);
    }

    /** One test of Cache 2 left unchecked by a 502 a minute ago: held back. */
    private AnalysisResult incomplete() {
        TestVerdict unchecked = new TestVerdict(1L, "GridCacheTest.testPut", "IgniteTests24Java8_Cache2", 9391902L,
            "Cache 2", "o1", false, false, "could not verify (TeamCity error: 502 Bad Gateway)", "", 0);

        return new AnalysisResult(PR, RUN_ALL, "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60, List.of(), List.of(),
            List.of(unchecked), now - 60_000);
    }

    private AnalysisResult complete() {
        return new AnalysisResult(PR, RUN_ALL, "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60);
    }
}
