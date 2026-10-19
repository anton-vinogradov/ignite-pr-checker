package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.mockito.ArgumentCaptor;

/**
 * The /run-all comment promised "The verdict lands here", while the verdict came in a comment of its
 * own; it ended with "the verdict comment has the full story" and "Superseded by a newer /run-all"
 * with no link, though both comments were known. The story is the checker's own comment now, and it goes once the
 * verdict comment of its chain is in.
 */
class RunStoryLinksTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final long PREVIOUS = 9390000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

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
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author"); // told already: this test follows the run, not the line on a new link
        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(false, false, true, false, true));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            PR_URL + "#issuecomment-77"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build(CHAIN, "queued", null));
        when(github.commentUrl(anyInt(), anyLong()))
            .thenAnswer(inv -> PR_URL + "#issuecomment-" + inv.getArgument(1, Long.class));
    }

    @Test
    void theAckSaysWhereTheVerdictWillBe() {
        command(1L);

        ArgumentCaptor<String> ack = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), eq("author"), ack.capture());
        assertThat(ack.getValue()).contains("When the run finishes, a new comment brings you the verdict, and this"
            + " story goes.").doesNotContain("lands here");
    }

    /** The verdict comment of the chain mentions the commander: the story says nothing more, and goes. */
    @Test
    void theFinishedStoryGivesWayToTheVerdictCommentOfItsChain(@TempDir Path dir) throws Exception {
        command(1L);
        verdictPosted(555L, dir);
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build(CHAIN, "finished", "FAILURE"));

        commands.updateEtas();

        verify(github).appDelete(PR, "author", 77L);
        verify(github, times(1)).appComment(eq(PR), any(), anyString());
    }

    /**
     * The chain finished and its re-run wave is settling; the author's verdict comment still carries the
     * chain before, and its link would send the reader to that old verdict.
     */
    @Test
    void aVerdictCommentOfAnotherChainIsNotLinked(@TempDir Path dir) throws Exception {
        command(1L);
        settlingAfterAnOlderVerdict(555L, dir);
        when(tc.getBuildState("tc", CHAIN)).thenReturn(build(CHAIN, "finished", "FAILURE"));

        commands.updateEtas();

        ArgumentCaptor<String> story = ArgumentCaptor.forClass(String.class);
        verify(github).appEdit(eq(PR), any(), eq(77L), story.capture());
        assertThat(story.getValue()).contains("Auto re-run **#1** — 3 suites that failed mid-run — "
            + "[details](https://checker.example/?pr=" + PR + ")").doesNotContain("#issuecomment-555");
    }

    @Test
    void aSupersededStoryLinksTheCommandThatSupersededIt() {
        command(1L);
        when(tc.cancelOwnRunAllChains("tc", PR, "author")).thenReturn(1);
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build(CHAIN + 1, "queued", null));

        command(2L);

        verify(github).appEdit(eq(PR), any(), eq(77L), contains("🛑 _Superseded by [a newer /run-all](" + PR_URL
            + "#issuecomment-2)._"));
    }

    private void command(long id) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id,
            "/run-all", PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    /** The sweep posted the verdict of the chain from the checker's account and marked the run handled. */
    private void verdictPosted(long commentId, Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        enrollment.putObject("posted").put(String.valueOf(PR), CHAIN);
        enrollment.putObject("ghThreads").putObject(String.valueOf(PR)).put("buildId", CHAIN)
            .put("commentId", commentId).put("done", true).put("app", true);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }

    /** The verdict comment of the previous chain, and a wave of the commanded one still settling. */
    private void settlingAfterAnOlderVerdict(long commentId, Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        enrollment.putObject("posted").put(String.valueOf(PR), PREVIOUS);
        enrollment.putObject("ghThreads").putObject(String.valueOf(PR)).put("buildId", PREVIOUS)
            .put("commentId", commentId).put("done", true).put("app", true);
        snap.putObject("retries").putObject(String.valueOf(PR)).put("buildId", CHAIN).put("attempts", 1)
            .put("what", "3 suites that failed mid-run").putNull("note").putArray("history")
            .add("3 suites that failed mid-run");
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }

    private static TcModel.Build build(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
