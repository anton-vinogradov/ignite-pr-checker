package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
 * edit: GitHub tells nobody about edits, so they learnt that the verdict was in only by looking. Now every commander
 * is mentioned in a new comment once the verdict is ready, and the story goes: the run leaves one comment of the
 * checker in the PR. The autofix's note, which tells the commander what was pushed to their branch, stays.
 */
class VerdictMentionTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String MENTION = "@Newcomer the verdict of your `/run-all` is ready: RunAll [9400000]"
        + "(https://ci2.example/build/9400000) — [see the verdict](https://checker.example/?pr=13800).";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        styleFix, mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));

    @Test
    void aCommanderIsMentionedOnceTheVerdictIsReadyAndTheStoryGoes() {
        standing.linkGhLogin("newcomer", "tc", GhUsers.of("Newcomer"));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Newcomer");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "FAILURE"));

        commands.updateEtas();
        commands.updateEtas();

        verify(github, times(1)).appComment(PR, "newcomer", MENTION);
        verify(github).appDelete(PR, "newcomer", 77L);
        assertThat(PrCommands.readsAsCommand(MENTION)).isFalse();
    }

    /** GitHub asked the checker's account to wait: the mention goes at a later look, once, and the story after it. */
    @Test
    void aMentionGithubMadeWaitGoesLater() {
        standing.linkGhLogin("newcomer", "tc", GhUsers.of("Newcomer"));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Newcomer");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "FAILURE"));
        when(github.appComment(PR, "newcomer", MENTION)).thenThrow(new GithubClient.WriteDeferred("wait a minute"))
            .thenReturn(new GithubClient.PostedComment(78L, "https://github.com/apache/ignite/pull/13800#c78"));

        commands.updateEtas();

        verify(github, never()).appDelete(anyInt(), any(), anyLong());

        commands.updateEtas();
        commands.updateEtas();

        verify(github, times(2)).appComment(PR, "newcomer", MENTION);
        verify(github, times(1)).appDelete(PR, "newcomer", 77L);
    }

    @Test
    void theStoryOfARunTheAutofixPushedForKeepsTheNote() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(null, null, null, true, null));
        when(styleFix.fixForCommand(eq(PR), any(), eq("Author"))).thenReturn("🎨 _Checkstyle autofix: pushed"
            + " `abc1234` to your branch `x` — `git pull` before your next push._");
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "FAILURE"));

        commands.updateEtas();

        verify(github).appComment(eq(PR), eq("author"), startsWith("@Author the verdict of your `/run-all` is ready"));
        verify(github, never()).appDelete(anyInt(), any(), anyLong());
        verify(github).appEdit(PR, "author", 77L, "@Author 🎨 _Checkstyle autofix: pushed `abc1234` to your branch"
            + " `x` — `git pull` before your next push._\n\n🏁 _RunAll [9400000](https://ci2.example/build/9400000)"
            + " finished — [see the verdict](https://checker.example/?pr=13800)._");
    }

    @Test
    void aCancelledRunIsNoVerdict() {
        standing.linkGhLogin("newcomer", "tc", GhUsers.of("Newcomer"));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        command("Newcomer");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("finished", "UNKNOWN"));

        commands.updateEtas();

        verify(github, times(1)).appComment(anyInt(), any(), anyString());
        verify(github).appEdit(eq(PR), eq("newcomer"), eq(77L), endsWith("\n🛑 _Run cancelled._"));
        verify(github, never()).appDelete(anyInt(), any(), anyLong());
    }

    private void command(String login) {
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(build("queued", null));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of(login))));
        commands.poll();
    }

    private static TcModel.Build build(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
