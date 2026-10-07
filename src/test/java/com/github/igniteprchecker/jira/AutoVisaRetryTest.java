package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.session.SessionCodec;
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

    private static final String ISSUE = "IGNITE-28890";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final long now = System.currentTimeMillis();

    @Test
    void aTryThatFailsAfterTheHoldIsFollowedByAnother() {
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.analyzeForAction(TOK, PR)).thenReturn(Optional.of(incomplete()))
            .thenThrow(new IllegalStateException("502 Bad Gateway"))
            .thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        VisaSubscriptions subs = subscriptions(analyzer, jira);
        subs.retryDelayMs = 100;
        subs.arm(PR, ISSUE, "jira-pat", "avinogradov");

        subs.onRunFinished(PR);

        verify(jira, timeout(5_000)).addComment(eq("jira-pat"), eq(ISSUE), anyString());
        assertThat(subs.armedCount()).isZero();
    }

    @Test
    void aCancelledSubscriptionIsNotTriedAgain() {
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.analyzeForAction(TOK, PR)).thenReturn(Optional.of(incomplete()));
        VisaSubscriptions subs = subscriptions(analyzer, mock(JiraClient.class));
        subs.retryDelayMs = 100;
        subs.arm(PR, ISSUE, "jira-pat", "avinogradov");
        subs.onRunFinished(PR);
        verify(analyzer, timeout(2_000)).analyzeForAction(TOK, PR);

        subs.cancel(PR);

        verify(analyzer, after(500).times(1)).analyzeForAction(TOK, PR);
    }

    @Test
    void aTryDueAtARestartIsMadeAfterIt(@TempDir Path dir) throws Exception {
        BlockerAnalyzer before = analyzer();
        when(before.analyzeForAction(TOK, PR)).thenReturn(Optional.of(incomplete()));
        VisaSubscriptions running = subscriptions(before, mock(JiraClient.class));
        running.retryDelayMs = 500;
        running.arm(PR, ISSUE, "jira-pat", "avinogradov");
        running.onRunFinished(PR);
        Path file = dir.resolve("visa-subs.json");
        savedWithATryDue(running, file);
        running.cancel(PR);

        BlockerAnalyzer after = analyzer();
        when(after.analyzeForAction(TOK, PR)).thenReturn(Optional.of(complete()));
        JiraClient jira = mock(JiraClient.class);
        subscriptions(after, jira).loadFrom(file);

        verify(jira, timeout(5_000)).addComment(eq("jira-pat"), eq(ISSUE), anyString());
    }

    /** visa-subs.json as the version before kept it: armed, waiting for the chain to finish. */
    @Test
    void aSubscriptionSavedWithoutRetriesWaitsForItsChain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("visa-subs.json");
        Files.writeString(file, "[{\"pr\":" + PR + ",\"issue\":\"" + ISSUE + "\",\"token\":\""
            + codec.encryptString("jira-pat") + "\",\"username\":\"avinogradov\",\"armedAt\":" + (now - 3_600_000) + "}]");
        BlockerAnalyzer analyzer = analyzer();
        VisaSubscriptions subs = subscriptions(analyzer, mock(JiraClient.class));

        subs.loadFrom(file);

        assertThat(subs.armedIssue(PR)).contains(ISSUE);
        verify(analyzer, after(500).never()).analyzeForAction(anyString(), eq(PR));
    }

    /** After an hour of failed tries the visa waits for the next finished RunAll, still armed. */
    @Test
    void triesStopAfterAnHour(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("visa-subs.json");
        Files.writeString(file, "[{\"pr\":" + PR + ",\"issue\":\"" + ISSUE + "\",\"token\":\""
            + codec.encryptString("jira-pat") + "\",\"username\":\"avinogradov\",\"armedAt\":" + (now - 7_200_000)
            + ",\"retryAt\":" + (now - 1_000) + ",\"retryingSince\":" + (now - 3_660_000) + "}]");
        BlockerAnalyzer analyzer = analyzer();
        when(analyzer.analyzeForAction(TOK, PR)).thenThrow(new IllegalStateException("502 Bad Gateway"));
        VisaSubscriptions subs = subscriptions(analyzer, mock(JiraClient.class));
        subs.retryDelayMs = 100;

        subs.loadFrom(file);

        verify(analyzer, after(1_000).times(1)).analyzeForAction(TOK, PR);
        assertThat(subs.armedIssue(PR)).contains(ISSUE);
        subs.saveTo(file);
        assertThat(mapper.readTree(file.toFile()).get(0).get("retryAt").asLong()).isZero();
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

        return new VisaSubscriptions(mapper, codec, jira, new VisaService(new TeamcityProperties("https://ci2/"),
            "https://checker"), analyzer, warmer, mock(PendingCommits.class));
    }

    /** One test of Cache 2 left unchecked by a 502 a minute ago: held back. */
    private AnalysisResult incomplete() {
        TestVerdict unchecked = new TestVerdict(1L, "GridCacheTest.testPut", "IgniteTests24Java8_Cache2", 9391902L,
            "Cache 2", "o1", false, false, "could not verify (TeamCity error: 502 Bad Gateway)", "", 0);

        return new AnalysisResult(PR, 9391879L, "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60, List.of(), List.of(),
            List.of(unchecked), now - 60_000);
    }

    private AnalysisResult complete() {
        return new AnalysisResult(PR, 9391879L, "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, now / 1000 - 3600, now / 1000 - 60);
    }
}
