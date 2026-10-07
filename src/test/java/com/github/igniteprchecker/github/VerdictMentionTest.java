package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Someone without a GitHub token of their own commands from the checker's comment, whose story then ends with an
 * edit: GitHub tells nobody about edits, so they learnt that the verdict was in only by looking.
 */
class VerdictMentionTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String MENTION = "@Newcomer the verdict of your `/run-all` is ready: RunAll [9400000]"
        + "(https://ci2.example/build/9400000) — [see the verdict](https://checker.example/?pr=13800).";

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

    @Test
    void aCommanderWithoutATokenIsMentionedOnceTheVerdictIsReady() {
        standing.linkGhLogin("newcomer", "tc", "Newcomer");
        when(github.addPrCommentAsAppWithId(eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Newcomer");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "FAILURE"));

        commands.updateEtas();
        commands.updateEtas();

        verify(github, times(1)).addPrCommentAsApp(PR, MENTION);
        assertThat(PrCommands.readsAsCommand(MENTION)).isFalse();
    }

    @Test
    void aCommanderWhoseOwnTokenTellsTheStoryGetsNoSecondComment() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true, true));
        command("Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "FAILURE"));

        commands.updateEtas();

        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    @Test
    void aCancelledRunIsNoVerdict() {
        standing.linkGhLogin("newcomer", "tc", "Newcomer");
        when(github.addPrCommentAsAppWithId(eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Newcomer");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "UNKNOWN"));

        commands.updateEtas();

        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    private void command(String login) {
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(build("queued", null));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            new GithubClient.GhUser(login))));
        commands.poll();
    }

    private static TcModel.Build build(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
