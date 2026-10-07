package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * "/run all", "/run-all.", "`/run-all`" and "Please /run-all" were ignored without a word, "/run-all
 * --top" ran without top, and a refused /top or a failed /run-all got a bare 😕: the commander could
 * not tell a typo from a broken checker.
 */
class CommandHintsTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final String HINT = "💡 _Nothing was run: a command has to be the very first word of the"
        + " comment, as is — `/run-all`, `/run-all top` or `/top`. Post it that way in a new comment._";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, true, false, true));
        standing.linkGhLogin("newcomer", "tc-2", "Newcomer");
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build("queued"));
        when(github.addPrCommentAsApp(anyInt(), anyString())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run all", "/run-all.", "`/run-all`", "Please /run-all", "/run_all", "can you /top?"})
    void aCommandTriedWithoutOpeningWithItIsExplained(String typed) {
        comment(1L, typed, "Author");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).reactToComment("gh-pat", 1L, "confused");
        verify(github).updatePrComment("gh-pat", 1L, typed + "\n\n---\n" + HINT);
    }

    @Test
    void aParticipantWithoutAGithubTokenGetsTheHintInAReply() {
        comment(1L, "Please /run-all", "Newcomer");

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(github).addPrCommentAsApp(eq(PR), reply.capture());
        assertThat(reply.getValue()).isEqualTo("@Newcomer " + HINT);
        assertThat(PrCommands.readsAsCommand(reply.getValue())).isFalse();
        assertThat(PrCommands.triesCommand(reply.getValue())).isFalse();
    }

    @Test
    void aStrangerGetsNoPublicAnswer() {
        comment(1L, "Please /run-all", "stranger");

        verify(github, never()).reactToCommentAsApp(anyLong(), anyString());
        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    /** GitHub's "Quote reply" on a command comment opens with the quoted command. */
    @Test
    void aQuoteReplyOfACommandIsNotATry() {
        comment(1L, "> /run-all\n> \n> ---\n> 🚀 **RunAll queued** — build 9400000\n\nWhy did Cache 1 fail here?",
            "Author");

        verify(github, never()).reactToComment(anyString(), anyLong(), anyString());
        verify(github, never()).updatePrComment(anyString(), anyLong(), anyString());
        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    /** "Please /run-all" written yesterday and edited now: nobody is trying a command any more. */
    @Test
    void anOldCommentEditedNowGetsNoHint() {
        comment(1L, "Please /run-all", "Author", Instant.now().minusSeconds(3 * 3600));

        verify(github, never()).reactToComment(anyString(), anyLong(), anyString());
        verify(github, never()).updatePrComment(anyString(), anyLong(), anyString());
    }

    /** A user who has the checker comment their verdicts, but no PR commands, uses no commands to hint at. */
    @Test
    void aUserWithoutPrCommandsGetsNoHint() {
        when(github.ghUser("reviewer-pat")).thenReturn(Optional.of("Reviewer"));
        standing.change("reviewer", "tc-3", null, "reviewer-pat",
            new StandingVisas.OptionChange(false, false, true, false, false));

        comment(1L, "Please /run-all", "Reviewer");

        verify(github, never()).reactToComment(anyString(), anyLong(), anyString());
        verify(github, never()).reactToCommentAsApp(anyLong(), anyString());
        verify(github, never()).updatePrComment(anyString(), anyLong(), anyString());
        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    @Test
    void aCommandMentionedInALongerLineIsNotATry() {
        comment(1L, "I ran /run-all yesterday and Cache 1 failed", "Author");

        verify(github, never()).reactToComment(anyString(), anyLong(), anyString());
        verify(github, never()).updatePrComment(anyString(), anyLong(), anyString());
    }

    /** The hint edits the comment, so the next poll lists it again. */
    @Test
    void aHintIsGivenOnce() {
        comment(1L, "Please /run-all", "Author");
        comment(1L, "Please /run-all\n\n---\n" + HINT, "Author");

        verify(github, times(1)).updatePrComment(eq("gh-pat"), eq(1L), anyString());
    }

    @Test
    void aRestartDoesNotHintTheSameCommentAgain(@TempDir Path dir) throws Exception {
        comment(1L, "Please /run-all", "Author");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        commands = commands();
        commands.loadFrom(file);

        comment(1L, "Please /run-all\n\n---\n" + HINT, "Author");

        verify(github, times(1)).updatePrComment(eq("gh-pat"), eq(1L), anyString());
    }

    @Test
    void aCommentFixedAfterTheHintRunsWithoutIt() {
        comment(1L, "Please /run-all", "Author");

        comment(1L, "/run-all\n\n---\n" + HINT, "Author");

        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).updatePrComment(eq("gh-pat"), eq(1L), contains("/run-all\n\n---\n🚀 **RunAll queued**"));
    }

    /** A comment fixed in the browser comes back with CRLF line breaks, the hint's separator too. */
    @Test
    void aCommentFixedInTheBrowserDropsTheHint() {
        comment(1L, "Please /run-all", "Author");

        comment(1L, "/run-all\r\n\r\n---\r\n" + HINT, "Author");

        ArgumentCaptor<String> fixed = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).updatePrComment(eq("gh-pat"), eq(1L), fixed.capture());
        assertThat(fixed.getValue()).startsWith("/run-all\n\n---\n🚀 **RunAll queued**")
            .doesNotContain("Nothing was run");
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

        verify(github).reactToComment("gh-pat", 1L, "confused");
        verify(github).updatePrComment(eq("gh-pat"), eq(1L), contains("Nothing moved: `/top` moves the RunAll that"
            + " your own `/run-all` started on this PR, and none is in progress."));
    }

    @Test
    void aTopForARunningBuildSaysWhy() {
        comment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build("running"));

        comment(2L, "/top", "Author");

        verify(github).updatePrComment(eq("gh-pat"), eq(2L), contains("Nothing moved: build " + CHAIN
            + " is already running, and `/top` only moves a build that still waits in the queue."));
        verify(tc, never()).moveToQueueTop(anyString(), anyLong());
    }

    @Test
    void aFailedRunAllSaysNothingWasQueuedAndWhy() {
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean()))
            .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        comment(1L, "/run-all", "Newcomer");

        verify(github).addPrCommentAsApp(PR, "@Newcomer 🚀 _Nothing was queued: the request failed with HTTP 502."
            + " Try again in a few minutes._");
    }

    /** RunAll was queued, only the story around it failed: "nothing was queued" would be a lie. */
    @Test
    void aRunAllQueuedBeforeAFailureIsNotCalledFailed() {
        when(github.addPrCommentAsAppWithId(anyInt(), anyString())).thenThrow(new HttpServerErrorException(
            HttpStatus.BAD_GATEWAY));

        comment(1L, "/run-all", "Newcomer");

        verify(tc).triggerRunAll("tc-2", PR, false);
        verify(github, never()).addPrCommentAsApp(anyInt(), contains("Nothing was queued"));
        verify(github, never()).reactToCommentAsApp(1L, "confused");
    }

    private void comment(long id, String body, String login) {
        comment(id, body, login, Instant.now());
    }

    private void comment(long id, String body, String login, Instant createdAt) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, createdAt.toString(), new GithubClient.GhUser(login))));
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
