package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

/**
 * A token saved while GitHub or JIRA was down was answered "GitHub rejected the token": the user threw away a good
 * token and made another.
 */
class UncheckedTokenTest {
    private static final String USER = "avinogradov";

    private static final String UNCHECKED_GH = "GitHub could not check the token right now — try again in a minute";

    private static final String UNCHECKED_JIRA = "JIRA could not check the token right now — try again in a minute";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github, analyzer,
        jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, mock(TcClient.class), standing,
        mock(MergedVerdicts.class), new UserDirectory(mapper));

    @Test
    void aTokenGithubCouldNotCheckIsNotCalledRejected() {
        when(github.writes()).thenReturn(true);
        when(github.tokenOwner("gh-pat")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(github.tokenOwner("dead-pat")).thenReturn(Optional.empty());

        assertAnswer(controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "gh-pat"), 502,
            UNCHECKED_GH);
        assertAnswer(controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "dead-pat"), 412,
            "GitHub rejected the token — paste a new one");
        assertThat(standing.settings(USER).suites()).isFalse();
    }

    /** The stored token keeps serving the options while GitHub cannot check one the page brings. */
    @Test
    void aStoredTokenServesWhileGithubCannotCheckANewOne() {
        when(github.writes()).thenReturn(true);
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.linkGhLogin(USER, "tc", GhUsers.of("anton-vinogradov"));
        controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "gh-pat");
        when(github.tokenOwner("new-pat")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        ResponseEntity<?> res = controller.standingVisa(null, null, null, true, null, null, "tc", USER, null,
            "new-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.styleFixOn(USER)).isTrue();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("gh-pat"));
    }

    @Test
    void aTokenJiraCouldNotCheckIsNotCalledRejected() {
        when(jira.myself("jira-pat")).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertAnswer(controller.standingVisa(true, null, null, null, null, null, "tc", USER, "jira-pat", null), 502,
            UNCHECKED_JIRA);
        assertThat(standing.visaOn(USER)).isFalse();
    }

    private static void assertAnswer(ResponseEntity<?> res, int status, String error) {
        assertThat(res.getStatusCode().value()).isEqualTo(status);
        assertThat(((Map<?, ?>)res.getBody()).get("error")).isEqualTo(error);
    }
}
