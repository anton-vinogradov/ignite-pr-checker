package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * TeamCity refused the stored token of a user whose /run-all was running. The story in the PR stopped at "Running —
 * expected to finish …" and never said why; past that time the author could only guess whether the run or the checker
 * had died.
 */
class StoryOnARefusedTokenTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final long NARRATION = 77L;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("newcomer", "commands-tc", GhUsers.of("Newcomer"));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of("Newcomer"))));
        when(tc.triggerRunAll("commands-tc", PR, false)).thenReturn(build("queued"));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(NARRATION, "https://github.com/apache/ignite/pull/13800#c77"));
        when(github.appEdit(anyInt(), any(), eq(NARRATION), anyString())).thenReturn(true);
        commands.poll();
    }

    @Test
    void theStorySaysOnceThatItWaitsForAFreshToken() {
        when(tc.getBuildState("commands-tc", CHAIN)).thenReturn(build("running"));
        when(tc.chainRemainingSeconds(eq("commands-tc"), eq(CHAIN), any())).thenReturn(4 * 3600L);
        commands.updateEtas();

        standing.markTcRejected("newcomer");
        commands.updateEtas();
        commands.updateEtas();

        standing.tcTokenAccepted("newcomer", "fresh-tc");
        when(tc.getBuildState("fresh-tc", CHAIN)).thenReturn(build("running"));
        when(tc.chainRemainingSeconds(eq("fresh-tc"), eq(CHAIN), any())).thenReturn(3 * 3600L);
        commands.updateEtas();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(github, times(3)).appEdit(anyInt(), any(), eq(NARRATION), bodies.capture());
        assertThat(bodies.getAllValues().get(1))
            .contains("TeamCity no longer accepts the token the checker stores for you")
            .contains("https://checker.example").doesNotContain("Running");
        assertThat(PrCommands.readsAsCommand(bodies.getAllValues().get(1))).isFalse();
        assertThat(bodies.getAllValues().get(2)).contains("Running — expected to finish **≈ ");
    }

    private static TcModel.Build build(String state) {
        return new TcModel.Build(CHAIN, null, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
