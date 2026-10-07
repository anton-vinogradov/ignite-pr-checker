package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

/**
 * PR commands were tested only for how they parse a comment, while the loop around them, from the poll to TeamCity
 * and back to the comment, is what went wrong in production: here it runs on a fake GitHub and TeamCity, for the
 * accepted command, a stranger's, a refused GitHub token, and /top.
 */
class CommandCycleTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, tracker,
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, true, false, true));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(chain("queued"));
    }

    @Test
    void anAcceptedCommandQueuesRunAllUnderTheCommandersTokenAndSaysSo() {
        comment(1L, "/run-all top", "Author");

        InOrder order = inOrder(tc, tracker);
        order.verify(tc).cancelOwnRunAllChains("tc", PR, "author");
        order.verify(tc).triggerRunAll("tc", PR, true);
        order.verify(tracker).record(eq(PR), eq(chain("queued")));
        verify(github).reactToComment("gh-pat", 1L, "rocket");
        verify(github).updatePrComment(eq("gh-pat"), eq(1L), startsWith("/run-all top\n\n---\n🚀 **RunAll queued at the"
            + " top of the queue** — [build 9400000](https://ci2.example/build/9400000) · live progress & verdict:"
            + " [Ignite PR Checker](https://checker.example/?pr=13800)."));
    }

    @Test
    void aStrangersCommandQueuesNothing() {
        comment(1L, "/run-all", "Someone");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).reactToCommentAsApp(1L, "confused");
    }

    @Test
    void aTokenGithubRefusesHandsTheAckToTheAppAccount() {
        doThrow(HttpClientErrorException.create(HttpStatusCode.valueOf(401), "Bad credentials", HttpHeaders.EMPTY,
            new byte[0], StandardCharsets.UTF_8)).when(github).reactToComment("gh-pat", 1L, "rocket");

        comment(1L, "/run-all", "Author");

        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).reactToCommentAsApp(1L, "rocket");
        verify(github).addPrCommentAsAppWithId(eq(PR), startsWith("@Author 🚀 **RunAll queued**"));
        assertThat(standing.ghTokenRejected("author")).isTrue();
    }

    @Test
    void topMovesTheCommandersQueuedRun() {
        comment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain("queued"));

        comment(2L, "/top", "Author");

        verify(tc).moveToQueueTop("tc", CHAIN);
        verify(github).reactToComment("gh-pat", 2L, "rocket");
        verify(github).updatePrComment(eq("gh-pat"), eq(2L), contains("⬆️ **Build 9400000 moved to the top of the"
            + " queue.**"));
    }

    private void comment(long id, String body, String login) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), new GithubClient.GhUser(login))));
        commands.poll();
    }

    private static TcModel.Build chain(String state) {
        return new TcModel.Build(CHAIN, null, state, "pull/13800/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/9400000", null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
