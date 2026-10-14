package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * A token saved while GitHub or JIRA was down was answered "GitHub rejected the token": the user threw away a good
 * token and made another. A visa posted with a token JIRA no longer takes was answered "JIRA rejected the comment",
 * with nothing asking for a new token.
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

    private final JiraController controller = new JiraController(jira, mock(TcClient.class), analyzer, codec,
        mock(VisaService.class), mock(VisaSubscriptions.class), standing, github, mock(PendingCommits.class), false);

    private final SessionCodec.Session session = new SessionCodec.Session(USER, "tc", null, null);

    @Test
    void aTokenGithubCouldNotCheckIsNotCalledRejected() {
        when(github.ghUser("gh-pat")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(github.ghUser("dead-pat")).thenReturn(Optional.empty());

        assertAnswer(controller.saveGithubToken(new JiraController.TokenRequest("gh-pat"), session), 502, UNCHECKED_GH);
        assertAnswer(controller.saveGithubToken(new JiraController.TokenRequest("dead-pat"), session), 401,
            "GitHub rejected the token");
        assertAnswer(controller.standingVisa(null, null, true, null, null, null, "tc", USER, null, "gh-pat"), 502,
            UNCHECKED_GH);
        assertThat(standing.ghOn(USER)).isFalse();
    }

    /** The stored token keeps serving the options while GitHub cannot check one the session brings. */
    @Test
    void aStoredTokenServesWhileGithubCannotCheckANewOne() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        controller.standingVisa(null, null, true, null, null, null, "tc", USER, null, "gh-pat");
        when(github.ghUser("new-pat")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        ResponseEntity<?> res = controller.standingVisa(null, null, null, true, null, null, "tc", USER, null,
            "new-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.styleFixOn(USER)).isTrue();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("gh-pat"));
    }

    @Test
    void aTokenJiraCouldNotCheckIsNotCalledRejected() {
        when(jira.myself("jira-pat")).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertAnswer(controller.saveToken(new JiraController.TokenRequest("jira-pat"), session), 502, UNCHECKED_JIRA);
        assertAnswer(controller.standingVisa(true, null, null, null, null, null, "tc", USER, "jira-pat", null), 502,
            UNCHECKED_JIRA);
        assertAnswer(controller.armAutoVisa(13654, "IGNITE-28890", USER, "jira-pat"), 502, UNCHECKED_JIRA);
    }

    @Test
    void aVisaJiraRefusesAsksForANewTokenWhenTheTokenIsDead() {
        when(analyzer.analyze("tc", 13654)).thenReturn(Optional.of(new AnalysisResult(13654, 9391879L,
            "pull/13654/head", 0, List.of(), List.of(), List.of(), List.of(), List.of(), 140, 0, false, 0, false, 0, 0,
            0, 0, 0)));
        when(jira.addComment(anyString(), eq("IGNITE-28890"), any()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(jira.tokenRejected("dead-pat")).thenReturn(true);

        assertAnswer(controller.visa(13654, "IGNITE-28890", "tc", "dead-pat"), 412,
            "JIRA rejected your token — paste a new one");
        assertThat(controller.visa(13654, "IGNITE-28890", "tc", "jira-pat").getStatusCode().value()).isEqualTo(502);
    }

    private static void assertAnswer(ResponseEntity<?> res, int status, String error) {
        assertThat(res.getStatusCode().value()).isEqualTo(status);
        assertThat(((Map<?, ?>)res.getBody()).get("error")).isEqualTo(error);
    }
}
