package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Any failed edit of a /run-all comment was told to its author as "GitHub rejected your personal access token": a
 * 502 from GitHub moved the story to the checker's account with that note, and so did switching off the option that
 * keeps the token. Only GitHub refusing the token says so now.
 */
class PatNoteTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String REJECTED = "GitHub rejected your personal access token";

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
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, true, false, true));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            new GithubClient.GhUser("Author"))));
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(chain("queued"));
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain("queued"));
        when(tc.chainRemainingSeconds(eq("tc"), eq(CHAIN), any())).thenReturn(3600L);
        when(github.addPrCommentAsAppWithId(eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
    }

    @Test
    void anEditGithubFailedIsNotBlamedOnTheToken() {
        doThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)).doNothing()
            .when(github).updatePrComment(eq("gh-pat"), eq(1L), anyString());

        commands.poll();
        commands.updateEtas();

        verify(github, never()).addPrCommentAsAppWithId(anyInt(), anyString());
        ArgumentCaptor<String> story = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).updatePrComment(eq("gh-pat"), eq(1L), story.capture());
        assertThat(story.getValue()).contains("🚀 **RunAll queued**", "⏱ _Queued");
    }

    @Test
    void aTokenGithubRefusesIsNamed() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .when(github).updatePrComment(eq("gh-pat"), eq(1L), anyString());

        commands.poll();

        verify(github).addPrCommentAsAppWithId(eq(PR), contains(REJECTED));
    }

    /** The author switched "Comment my runs' verdicts" off mid-run, and the token went with it. */
    @Test
    void aTokenTheAuthorSwitchedOffIsNotCalledRejected() {
        commands.poll();

        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, null, false, null, null));
        commands.updateEtas();

        verify(github, never()).addPrCommentAsAppWithId(anyInt(), contains(REJECTED));
        verify(github).addPrCommentAsAppWithId(eq(PR),
            contains("The checker no longer holds your GitHub token, so this is narrated from its own account."));
        verify(github, never()).updatePrComment(anyString(), anyLong(), contains("Queued — expected"));
    }

    private static TcModel.Build chain(String state) {
        return new TcModel.Build(CHAIN, null, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
