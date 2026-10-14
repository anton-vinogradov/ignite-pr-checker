package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * GITHUB_TOKEN was checked once, at startup: revoked or expired later, it failed every comment, edit and reaction of
 * the checker while the status page went on naming the account, and a refusal of the checker's own narration edit
 * dropped the GitHub token of the user whose run it told, with three of their options.
 */
class AppTokenRefusalTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final Metrics metrics = new Metrics(mapper);

    @Test
    void aRefusalLaterOnIsSeenAndAWorkingCallClearsIt() {
        GithubClient github = client();
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/" + PR))
            .andExpect(header("Authorization", "Bearer app-token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/" + PR))
            .andRespond(withSuccess("{\"title\":\"IGNITE-28867 Fix\",\"state\":\"open\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker-bot\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> github.pullState(PR)).isInstanceOf(HttpClientErrorException.Unauthorized.class);

        assertThat(github.appAccount().refused()).isTrue();

        github.pullState(PR);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(github.appAccount())
            .extracting(GithubClient.AppAccount::state, GithubClient.AppAccount::login,
                GithubClient.AppAccount::canPush).containsExactly("ok", "ignite-pr-checker-bot", false));
    }

    /** A user's own token refused says nothing of the app's, and its calls are counted apart. */
    @Test
    void aUsersRefusedTokenIsNotTheAppsAndIsCountedApart() {
        GithubClient github = client();
        server.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer user-pat"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(github.ghIdentity("user-pat")).isEmpty();

        assertThat(github.appAccount().state()).isEqualTo("unknown");
        assertThat(metrics.github().byCategory()).containsOnlyKeys("pat:user");
    }

    /**
     * The checker tells a /run-all in a comment of its own; the commander keeps a GitHub token for the checkstyle
     * autofix, and then GITHUB_TOKEN was revoked.
     */
    @Test
    void theAppsRefusedEditKeepsTheCommandersToken() {
        GithubClient github = AppWriting.github();
        TcClient tc = mock(TcClient.class);
        StandingVisas standing = new StandingVisas(mapper,
            new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
        PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
            mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
            new TeamcityProperties("https://ci2.example/"));
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of("Author"))));
        TcModel.Build queued = new TcModel.Build(CHAIN, null, "queued", "pull/" + PR + "/head", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(queued);
        when(tc.getBuildState("tc", CHAIN)).thenReturn(queued);
        when(tc.chainRemainingSeconds(eq("tc"), eq(CHAIN), any())).thenReturn(3600L);
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        commands.poll();
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(null, null, null, true, null));
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(github)
            .appEdit(anyInt(), any(), anyLong(), anyString());

        commands.updateEtas();

        verify(github).appEdit(anyInt(), any(), eq(77L), anyString());
        assertThat(standing.ghTokenRejected("author")).isFalse();
        assertThat(standing.styleFixOn("author")).isTrue();
    }

    private GithubClient client() {
        return new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper, metrics, http.build());
    }
}
