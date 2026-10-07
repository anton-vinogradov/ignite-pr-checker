package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The remaining-time line of a /run-all comment was rewritten every minute ("~3h 59m remaining …
 * (updates every minute)"): up to a hundred edits over a 4-hour RunAll, and the author's own edits
 * lost among them in the comment's history.
 */
class NarrationEditsTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final long NARRATION = 77L;

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
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            new GithubClient.GhUser("Newcomer"))));
        when(tc.triggerRunAll("commands-tc", PR, false)).thenReturn(build("queued", null));
        when(github.addPrCommentAsAppWithId(eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(NARRATION, "https://github.com/apache/ignite/pull/13800#c77"));
        when(github.updatePrCommentAsApp(eq(NARRATION), anyString())).thenReturn(true);
        commands.poll();
    }

    @Test
    void aFewMinutesOfDriftInTheEstimateEditNothing() {
        when(tc.getBuildState("commands-tc", CHAIN)).thenReturn(build("running", null));
        when(tc.chainRemainingSeconds(eq("commands-tc"), eq(CHAIN), any())).thenReturn(4 * 3600L, 4 * 3600L - 60,
            4 * 3600L - 240, 4 * 3600L + 180, 4 * 3600L - 420);

        for (int minute = 0; minute < 5; minute++)
            commands.updateEtas();

        verify(github, times(1)).updatePrCommentAsApp(eq(NARRATION), anyString());
    }

    @Test
    void aNewStageOrAnEstimateMovedByTenMinutesIsEdited() {
        when(tc.getBuildState("commands-tc", CHAIN)).thenReturn(build("queued", null), build("running", null),
            build("running", null));
        when(tc.chainRemainingSeconds(eq("commands-tc"), eq(CHAIN), any())).thenReturn(4 * 3600L, 4 * 3600L - 60,
            4 * 3600L + 11 * 60);

        commands.updateEtas();
        commands.updateEtas();
        commands.updateEtas();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(github, times(3)).updatePrCommentAsApp(eq(NARRATION), bodies.capture());
        assertThat(bodies.getAllValues().get(0)).contains("Queued — expected to finish **≈ ").contains("/top");
        assertThat(bodies.getAllValues().get(1)).contains("Running — expected to finish **≈ ").doesNotContain("/top");
    }

    /** GitHub answered 502 to the edit that moved the comment from queued to running. */
    @Test
    void anEditThatFailedIsMadeAgainNextMinute() {
        when(tc.getBuildState("commands-tc", CHAIN)).thenReturn(build("queued", null), build("running", null));
        when(tc.chainRemainingSeconds(eq("commands-tc"), eq(CHAIN), any())).thenReturn(4 * 3600L);
        when(github.updatePrCommentAsApp(eq(NARRATION), anyString())).thenReturn(true)
            .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)).thenReturn(true);

        for (int minute = 0; minute < 5; minute++)
            commands.updateEtas();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(github, times(3)).updatePrCommentAsApp(eq(NARRATION), bodies.capture());
        assertThat(bodies.getValue()).contains("Running — expected to finish **≈ ");
    }

    @Test
    void theLineShowsTheFinishTimeOnly() {
        when(tc.getBuildState("commands-tc", CHAIN)).thenReturn(build("running", null));
        when(tc.chainRemainingSeconds(eq("commands-tc"), eq(CHAIN), any())).thenReturn(4 * 3600L);

        commands.updateEtas();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).updatePrCommentAsApp(eq(NARRATION), body.capture());
        assertThat(body.getValue()).doesNotContain("remaining").doesNotContain("every minute");
        assertThat(PrCommands.readsAsCommand(body.getValue())).isFalse();
    }

    private static TcModel.Build build(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
