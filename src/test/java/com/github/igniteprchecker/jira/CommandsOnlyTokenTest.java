package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Standing options lend the user's TeamCity token to background work: warming, the sweep's build
 * lookups. Someone who switched on PR commands alone asked for their own commands and nothing else.
 */
class CommandsOnlyTokenTest {
    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final Warmer warmer = mock(Warmer.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), warmer, mock(PendingCommits.class));

    @Test
    void aCommandsOnlyTokenStaysOutOfBackgroundWork() {
        standing.linkGhLogin("newcomer", "commands-tc", "Newcomer");
        standing.change("avinogradov", "rerun-tc", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13800, "IGNITE-29000 Something", null, null, null,
            null)));

        standing.sweep();

        verify(warmer, atLeastOnce()).offerToken("rerun-tc");
        verify(warmer, never()).offerToken("commands-tc");
        verify(tc, never()).findRunAllBuildForPr(eq("commands-tc"), anyInt());
        verify(tc, never()).runningRunAllChains("commands-tc");
    }

    /** Their run, found with someone else's token: nothing will post or re-run it, so nothing computes it. */
    @Test
    void theSweepComputesNoVerdictForARunOfCommandsOnly() {
        standing.linkGhLogin("newcomer", "commands-tc", "Newcomer");
        standing.change("avinogradov", "rerun-tc", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13800, "IGNITE-29000 Something", null, null, null,
            null)));
        when(tc.findRunAllBuildForPr("rerun-tc", 13800)).thenReturn(Optional.of(new TcModel.Build(9400000L,
            "FAILURE", "finished", "pull/13800/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User("newcomer")), null, null, null, null, null)));

        standing.sweep();

        verify(analyzer, never()).analyzeForAction(eq("commands-tc"), anyInt());
        assertThat(standing.buildHandled("newcomer", 13800, 9400000L)).isTrue();
    }
}
