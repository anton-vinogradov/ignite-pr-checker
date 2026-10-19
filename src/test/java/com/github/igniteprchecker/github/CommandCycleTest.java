package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * PR commands were tested only for how they parse a comment, while the loop around them, from the poll to TeamCity
 * and back to the comment, is what went wrong in production: here it runs on a fake GitHub and TeamCity, for the
 * accepted command, a stranger's, a commander with a GitHub token of their own, and /top.
 */
class CommandCycleTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

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
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author"); // told already: this test follows the run, not the line on a new link
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(chain("queued"));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            PR_URL + "#issuecomment-77"));
    }

    @Test
    void anAcceptedCommandQueuesRunAllUnderTheCommandersTokenAndSaysSo() {
        comment(1L, "/run-all top", "Author");

        InOrder order = inOrder(tc, tracker);
        order.verify(tc).cancelOwnRunAllChains("tc", PR, "author");
        order.verify(tc).triggerRunAll("tc", PR, true);
        order.verify(tracker).record(eq(PR), eq(chain("queued")));
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github).appComment(eq(PR), eq("author"), startsWith("@Author 🚀 **RunAll queued at the top of the"
            + " queue** — [build 9400000](https://ci2.example/build/9400000) · live progress & verdict:"
            + " [Ignite PR Checker](https://checker.example/?pr=13800)."));
    }

    @Test
    void aStrangersCommandQueuesNothing() {
        comment(1L, "/run-all", "Someone");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).appReact(PR, "@someone", 1L, "confused");
    }

    /**
     * A commander who saved a GitHub token for the checkstyle autofix was answered under it: their own reaction, the
     * story written into their comment. A fine-grained token for their fork gets 403 there, so the checker's account
     * answers everyone alike.
     */
    @Test
    void aCommanderWithASavedTokenIsAnsweredByTheCheckersAccount() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(null, null, null, true, null));

        comment(1L, "/run-all", "Author");

        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github).appComment(eq(PR), eq("author"), startsWith("@Author 🚀 **RunAll queued**"));
        verify(github, never()).appEdit(anyInt(), any(), eq(1L), anyString());
    }

    /** /top answered "moved" by editing the commander's own comment; the run's story says it now. */
    @Test
    void topMovesTheCommandersQueuedRun() {
        comment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain("queued"));

        comment(2L, "/top", "Author");

        verify(tc).moveToQueueTop("tc", CHAIN);
        verify(github).appReact(PR, "author", 2L, "rocket");
        verify(github).appEdit(eq(PR), eq("author"), eq(77L), contains("\n⬆️ **Build 9400000 moved to the top of the"
            + " queue.**"));
        verify(github, times(1)).appComment(eq(PR), any(), anyString());
    }

    private void comment(long id, String body, String login) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of(login))));
        commands.poll();
    }

    private static TcModel.Build chain(String state) {
        return new TcModel.Build(CHAIN, null, state, "pull/13800/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/9400000", null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
