package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The warmer keeps the 50 newest PRs analysed on the tokens users lend it, and every background job borrows from its
 * pool, yet every test replaced it with a mock. Here its cycle runs: a token TeamCity refuses leaves the pool and the
 * cycle goes on with the others, ci2's firewall is not taken for a refusal, and a user waiting for an analysis goes
 * first.
 */
class WarmerCycleTest {
    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private final Warmer warmer = new Warmer(analyzer, github, new WarmProperties(true, 6, 10, 60), pool);

    private final List<PrSummary> prs = IntStream.rangeClosed(1, 6)
        .mapToObj(i -> new PrSummary(13800 + i, "IGNITE-2880" + i + " Fix", null, null, null, null)).toList();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void aTokenTeamCityRefusesLeavesThePoolAndTheOthersWarmTheRest() throws Exception {
        CountDownLatch bothLent = new CountDownLatch(1);
        when(github.openPrs()).thenAnswer(inv -> {
            bothLent.await();
            return prs;
        });
        when(analyzer.warm(eq("dead"), anyInt())).thenThrow(refused(401));
        when(analyzer.warm(eq("live"), anyInt())).thenReturn(true);

        warmer.offerToken("dead");
        warmer.offerToken("live");
        bothLent.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);

        assertThat(warmer.lastWarmed()).isEqualTo(5);
        assertThat(warmer.lastFailed()).isEqualTo(1);
        assertThat(warmer.pooledTokens()).isEqualTo(1);
        assertThat(warmer.tokenRevoked("dead")).isTrue();
        assertThat(warmer.borrowToken()).isEqualTo("live");
    }

    /** ci2's firewall answers 403 to requests it dislikes whatever the token: no reason to refuse the user's session. */
    @Test
    void aForbiddenAnswerDropsTheTokenForAWhileButDoesNotRevokeIt() {
        when(github.openPrs()).thenReturn(prs);
        when(analyzer.warm(eq("tc"), anyInt())).thenThrow(refused(403));

        warmer.offerToken("tc");
        await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(warmer.tokenRevoked("tc")).isFalse();
    }

    @Test
    void aUserWaitingForAnAnalysisGoesFirst() {
        when(github.openPrs()).thenReturn(prs);
        when(analyzer.userWaiting()).thenReturn(true);

        warmer.offerToken("tc");
        await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);

        verify(analyzer, never()).warm(anyString(), anyInt());
        assertThat(warmer.lastWarmed()).isZero();
        assertThat(warmer.lastFailed()).isZero();
    }

    private static HttpClientErrorException refused(int status) {
        return HttpClientErrorException.create(HttpStatusCode.valueOf(status), "refused", HttpHeaders.EMPTY,
            new byte[0], StandardCharsets.UTF_8);
    }
}
