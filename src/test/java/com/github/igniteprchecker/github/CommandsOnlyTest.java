package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Someone who switched on PR commands alone gets no visa, re-run or verdict comment, so nothing will
 * ever mark their run settled: the story in their command comment must end when the chain finishes,
 * not wait for a verdict that never comes.
 */
class CommandsOnlyTest {
    private static final int PR = 13800;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("newcomer", "commands-tc", "Newcomer");
    }

    @Test
    void theRunStoryEndsWhenTheChainFinishes() {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", "2026-10-07T10:00:00Z",
            new GithubClient.GhUser("Newcomer"))));
        when(tc.triggerRunAll("commands-tc", PR, false)).thenReturn(build("queued", null));
        when(github.addPrCommentAsAppWithId(eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        commands.poll();
        when(tc.getBuildState("commands-tc", 9400000L)).thenReturn(build("finished", "SUCCESS"));

        commands.updateEtas();

        verify(github).updatePrCommentAsApp(eq(77L), contains("Run finished — the verdict: https://checker.example"));
    }

    private static TcModel.Build build(String state, String status) {
        return new TcModel.Build(9400000L, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
