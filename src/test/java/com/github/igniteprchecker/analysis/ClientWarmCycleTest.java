package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.ClientSpec;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.ThinClients;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The page of a thin client's PR opens on a verdict the warm cycle computed, as an Ignite PR's does: the cycle
 * warms the client's open PRs after Ignite's, on the same tokens and under the same limit of PRs per project.
 */
class ClientWarmCycleTest {
    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final ClientVerdicts clients = mock(ClientVerdicts.class);

    private final ThinClients registry = new ThinClients(new PythonClientProperties(null, null, null, null),
        new ThinClientProperties(null, null));

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final Warmer warmer = new Warmer(analyzer, github, new WarmProperties(true, 3, 10, 60), pool, pool, worker,
        clients, registry);

    @AfterEach
    void stop() {
        pool.shutdownNow();
        worker.shutdownNow();
    }

    /** Given five PRs per client, one cycle warms each client's first three under the shared pool. */
    @Test
    void theClientsOpenPrsAreWarmedInTheSameCycle() {
        when(github.openPrs()).thenReturn(List.of(pr(13636)));
        for (ClientSpec client : registry.all())
            when(clients.openPrs(client))
                .thenReturn(IntStream.rangeClosed(65, 69).mapToObj(ClientWarmCycleTest::pr).toList());
        when(clients.warm(anyString(), any(ClientSpec.class), anyInt())).thenReturn(true);

        warmer.offerToken("alice", "tok");

        await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);
        verify(analyzer).warm("tok", 13636);
        for (ClientSpec client : registry.all()) {
            verify(clients).warm("tok", client, 65);
            verify(clients).warm("tok", client, 67);
            verify(clients, never()).warm("tok", client, 68);
        }
        verify(analyzer, never()).warm("tok", 65);
        assertThat(warmer.lastWarmed()).isEqualTo(9);
        assertThat(warmer.cycleTotal()).isEqualTo(10);
    }

    /** Someone waits on the page of a client's PR for its verdict: the cycle leaves ci2 to that compute. */
    @Test
    void theCycleYieldsToAViewOfAClientsPr() {
        when(github.openPrs()).thenReturn(List.of(pr(13636)));
        when(clients.openPrs(registry.require("nodejs"))).thenReturn(List.of(pr(69)));
        when(clients.userWaiting()).thenReturn(true);

        warmer.offerToken("alice", "tok");

        await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);
        verify(analyzer, never()).warm(anyString(), anyInt());
        verify(clients, never()).warm(anyString(), any(ClientSpec.class), anyInt());
    }

    private static PrSummary pr(int number) {
        return new PrSummary(number, "IGNITE-29117 Fix", null, null, null, null);
    }
}
