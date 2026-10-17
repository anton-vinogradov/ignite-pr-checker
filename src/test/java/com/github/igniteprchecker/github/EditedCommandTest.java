package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Anyone with write access to apache/ignite may edit another's comment. A committer who edited a fresh comment of
 * nsamelchev into "/run-all" queued RunAll under nsamelchev's TeamCity account: the poll reads edited comments, and the
 * checker looked only at when a comment was written.
 */
class EditedCommandTest {
    private static final int PR = 13335;

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

    private final Instant written = Instant.now().minusSeconds(5 * 60);

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aCommentEditedIntoRunAllDoesNotRunAndSaysWhy() {
        poll(comment(1L, "/run-all", Instant.now()));

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(tc, never()).cancelOwnRunAllChains(anyString(), anyInt(), anyString());
        verify(github).appReact(PR, "nsamelchev", 1L, "confused");
        verify(github).appComment(PR, "nsamelchev",
            "@NSAmelchev nothing was queued: the comment was edited. Post the command in a new comment.");
    }

    @Test
    void aCommentEditedIntoTopMovesNothing() {
        poll(comment(1L, "/top", Instant.now()));

        verify(tc, never()).moveToQueueTop(anyString(), anyLong());
        verify(github).appComment(PR, "nsamelchev",
            "@NSAmelchev nothing moved: the comment was edited. Post the command in a new comment.");
    }

    @Test
    void aCommandNobodyEditedRuns() {
        poll(comment(1L, "/run-all", written));

        verify(tc).triggerRunAll("tc", PR, false);
        verify(github, never()).appReact(PR, "nsamelchev", 1L, "confused");
    }

    /** An edited "Please /run-all" gets no hint: the hint would credit its author with someone else's words. */
    @Test
    void anEditedNearMissGetsNoHint() {
        poll(comment(1L, "Please /run-all", Instant.now()));

        verify(github, never()).appReact(anyInt(), anyString(), anyLong(), eq("confused"));
        verify(github, never()).appComment(anyInt(), anyString(), anyString());
    }

    @Test
    void theEditTimeIsReadFromGithub() throws Exception {
        GithubClient.IssueComment[] read = mapper.readValue("""
            [{"id": 1, "body": "/run-all", "html_url": "https://github.com/apache/ignite/pull/13335#issuecomment-1",
              "created_at": "2026-10-17T10:00:00Z", "updated_at": "2026-10-17T10:03:12Z",
              "user": {"login": "NSAmelchev", "id": 1}}]""", GithubClient.IssueComment[].class);

        assertThat(read[0].updatedAt()).isEqualTo("2026-10-17T10:03:12Z");
    }

    private void poll(GithubClient.IssueComment c) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(c));
        commands.poll();
    }

    private GithubClient.IssueComment comment(long id, String body, Instant updatedAt) {
        return new GithubClient.IssueComment(id, body,
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-" + id, written.toString(),
            GhUsers.of("NSAmelchev"), updatedAt.toString());
    }
}
