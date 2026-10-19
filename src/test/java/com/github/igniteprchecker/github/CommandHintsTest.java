package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * "/run all", "/run-all.", "`/run-all`" and "Please /run-all" were ignored without a word, "/run-all
 * --top" ran without top, and a refused /top or a failed /run-all got a bare 😕: the commander could
 * not tell a typo from a broken checker. The checker's account answers in a reply, whatever token the commander saved.
 */
class CommandHintsTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final String HINT = "💡 _Nothing was run: a command has to be the very first word of the"
        + " comment, as is — `/run-all`, `/run-all top` or `/top`. Post it that way in a new comment._";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        when(github.writes()).thenReturn(true);
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.linkGhLogin("newcomer", "tc-2", GhUsers.of("Newcomer"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build("queued"));
        when(github.appComment(anyInt(), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, PR_URL + "#issuecomment-77"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run all", "/run-all.", "`/run-all`", "Please /run-all", "/run_all", "can you /top?"})
    void aCommandTriedWithoutOpeningWithItIsExplained(String typed) {
        comment(1L, typed, "Author");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).appReact(PR, "author", 1L, "confused");
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), eq("author"), reply.capture());
        assertThat(reply.getValue()).isEqualTo("@Author " + HINT);
        assertThat(PrCommands.readsAsCommand(reply.getValue())).isFalse();
        assertThat(PrCommands.triesCommand(reply.getValue())).isFalse();
    }

    @Test
    void aStrangerGetsNoPublicAnswer() {
        comment(1L, "Please /run-all", "stranger");

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), anyString());
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    /** GitHub's "Quote reply" on a command comment opens with the quoted command. */
    @Test
    void aQuoteReplyOfACommandIsNotATry() {
        comment(1L, "> /run-all\n> \n> ---\n> 🚀 **RunAll queued** — build 9400000\n\nWhy did Cache 1 fail here?",
            "Author");

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), anyString());
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    /** "Please /run-all" written yesterday and edited now: nobody is trying a command any more. */
    @Test
    void anOldCommentEditedNowGetsNoHint() {
        comment(1L, "Please /run-all", "Author", Instant.now().minusSeconds(3 * 3600));

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), anyString());
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    /** A user who has the checker comment their verdicts, but no PR commands, uses no commands to hint at. */
    @Test
    void aUserWithoutPrCommandsGetsNoHint() {
        standing.linkGhLogin("reviewer", "tc-3", GhUsers.of("Reviewer"));
        standing.change("reviewer", "tc-3", null, null, new StandingVisas.OptionChange(false, false, true, false,
            false));

        comment(1L, "Please /run-all", "Reviewer");

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), anyString());
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    @Test
    void aCommandMentionedInALongerLineIsNotATry() {
        comment(1L, "I ran /run-all yesterday and Cache 1 failed", "Author");

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), anyString());
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    /** The commander edits the comment, so the next poll lists it again. */
    @Test
    void aHintIsGivenOnce() {
        comment(1L, "Please /run-all", "Author");
        comment(1L, "Please /run-all!", "Author");

        verify(github, times(1)).appComment(eq(PR), eq("author"), anyString());
    }

    @Test
    void aRestartDoesNotHintTheSameCommentAgain(@TempDir Path dir) throws Exception {
        comment(1L, "Please /run-all", "Author");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        commands = commands();
        commands.loadFrom(file);

        comment(1L, "Please /run-all!", "Author");

        verify(github, times(1)).appComment(eq(PR), eq("author"), anyString());
    }

    @Test
    void aCommentFixedAfterTheHintRuns() {
        comment(1L, "Please /run-all", "Author");

        comment(1L, "/run-all", "Author");

        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).appComment(eq(PR), eq("author"), startsWith("@Author 🚀 **RunAll queued**"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run-all top", "/run-all --top", "/runall please top"})
    void topIsReadFromAnyWord(String typed) {
        comment(1L, typed, "Author");

        verify(tc).triggerRunAll("tc", PR, true);
    }

    @Test
    void aTopWithNoRunOfTheirsSaysWhy() {
        comment(1L, "/top", "Author");

        verify(github).appReact(PR, "author", 1L, "confused");
        verify(github).appComment(PR, "author", "@Author ⬆️ _Nothing moved: `/top` moves the RunAll that your own"
            + " `/run-all` started on this PR, and none is in progress._");
    }

    @Test
    void aTopForARunningBuildSaysWhy() {
        comment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("running"));

        comment(2L, "/top", "Author");

        verify(github).appComment(PR, "author", "@Author ⬆️ _Nothing moved: build " + CHAIN + " is already running,"
            + " and `/top` only moves a build that still waits in the queue._");
        verify(tc, never()).moveToQueueTop(anyString(), anyLong());
    }

    @Test
    void aFailedRunAllSaysNothingWasQueuedAndWhy() {
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean()))
            .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        comment(1L, "/run-all", "Newcomer");

        verify(github).appComment(PR, "newcomer", "@Newcomer 🚀 _Nothing was queued: the request failed with HTTP"
            + " 502. Try again in a few minutes._");
    }

    /** RunAll was queued, only the story around it failed: "nothing was queued" would be a lie. */
    @Test
    void aRunAllQueuedBeforeAFailureIsNotCalledFailed() {
        when(github.appComment(anyInt(), anyString(), anyString())).thenThrow(new HttpServerErrorException(
            HttpStatus.BAD_GATEWAY));

        comment(1L, "/run-all", "Newcomer");

        verify(tc).triggerRunAll("tc-2", PR, false);
        verify(github, never()).appComment(anyInt(), anyString(), contains("Nothing was queued"));
        verify(github, never()).appReact(PR, "newcomer", 1L, "confused");
    }

    private void comment(long id, String body, String login) {
        comment(id, body, login, Instant.now());
    }

    private void comment(long id, String body, String login, Instant createdAt) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, createdAt.toString(), GhUsers.of(login))));
        commands.poll();
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }

    private static TcModel.Build build(String state) {
        return new TcModel.Build(CHAIN, null, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
