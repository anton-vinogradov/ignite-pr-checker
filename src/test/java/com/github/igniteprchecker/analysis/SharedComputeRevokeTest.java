package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.tc.TcClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The warmer found PR 42's build already being computed under another, revoked token and shared that compute,
 * TeamCity's 401 to the other token included. It took the 401 for its own and marked its working token revoked,
 * so every session carrying it was told "TeamCity rejected your token — log in again".
 */
class SharedComputeRevokeTest {
    private final ExecutorService refresh = Executors.newFixedThreadPool(4);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(mock(TcClient.class), chains, cfg,
        Executors.newFixedThreadPool(4), Executors.newFixedThreadPool(2), refresh,
        new AnalysisCache(cfg, new ObjectMapper()), new RunDeltaStore(new ObjectMapper()));

    @AfterEach
    void stop() {
        refresh.shutdownNow();
    }

    @Test
    void anotherTokens401DoesNotRevokeTheTokenThatSharedItsCompute() throws Exception {
        CountDownLatch revokedComputing = new CountDownLatch(1);
        CountDownLatch teamCityAnswers = new CountDownLatch(1);
        AtomicReference<Thread> warm = new AtomicReference<>();
        when(chains.findBuildId(eq("revoked"), eq(42))).thenReturn(Optional.of(999L));
        when(chains.findBuildId(eq("valid"), eq(42))).thenAnswer(inv -> {
            warm.set(Thread.currentThread());

            return Optional.of(999L);
        });
        when(chains.collectForBuild(eq("revoked"), eq(42), eq(999L), any())).thenAnswer(inv -> {
            revokedComputing.countDown();
            teamCityAnswers.await();
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                new byte[0], UTF_8);
        });

        GithubClient github = mock(GithubClient.class);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(42, "IGNITE-1 x", "u", null, null, null)));
        Warmer warmer = new Warmer(analyzer, github, new WarmProperties(true, 50, 10, 60), refresh);

        Thread other = new Thread(() -> {
            try {
                analyzer.refresh("revoked", 42);
            }
            catch (RuntimeException expected) {
                // TeamCity's 401 to this token
            }
        });
        other.start();
        revokedComputing.await();

        warmer.offerToken("valid");

        // Only the join on the shared compute parks the warm thread: everything before it is a mock or a map.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> warm.get() != null && warm.get().getState() == Thread.State.WAITING);
        teamCityAnswers.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> warmer.cyclesCompleted() >= 1);
        other.join();

        assertThat(warmer.lastFailed()).isEqualTo(1);
        assertThat(warmer.tokenRevoked("valid")).isFalse();
    }
}
