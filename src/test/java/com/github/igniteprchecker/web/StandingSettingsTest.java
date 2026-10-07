package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * A login issues a cookie without the JIRA and GitHub tokens, while the options on the server go on
 * running on the saved ones. In such a session the panel showed the options off, and a click on any
 * of them posted all four flags: the visa went off for want of a JIRA token in the cookie, and the
 * saved GitHub token was thrown away.
 */
class StandingSettingsTest {
    private static final String USER = "avinogradov";

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, mock(BlockerAnalyzer.class),
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(VisaService.class),
        mock(VisaSubscriptions.class), standing, github, mock(PendingCommits.class), false);

    @BeforeEach
    void enrolledEarlierWithEverything() {
        when(github.ghUser(anyString())).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself(anyString())).thenReturn(Optional.of(USER));
        assertThat(post(true, true, true, true, "jira-pat", "gh-pat").getStatusCode().value()).isEqualTo(200);
        clearInvocations(github, jira);
    }

    @Test
    void aClickChangesOnlyItsOwnSwitchAndKeepsTheSavedTokens() {
        ResponseEntity<?> res = post(null, false, null, null, null, null);

        assertThat(res.getBody()).isEqualTo(new StandingVisas.Settings(true, false, true, true, false,
            "anton-vinogradov", true, true, false, false, false));
    }

    @Test
    void anOptionSwitchedOnRunsOnTheSavedTokenWhenTheSessionHasNone() {
        post(null, null, null, false, null, null);

        ResponseEntity<?> res = post(null, null, null, true, null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.styleFixOn(USER)).isTrue();
        verify(github, never()).ghUser(anyString());
    }

    @Test
    void theSavedTokenGoesOnlyWithTheLastOptionThatNeedsIt() {
        post(null, null, false, null, null, null);

        assertThat(standing.settings(USER).ghStored()).as("checkstyle autofix still pushes with it").isTrue();

        post(null, null, null, false, null, null);

        assertThat(standing.settings(USER).ghStored()).isFalse();
        assertThat(standing.settings(USER).jiraStored()).isTrue();
    }

    @Test
    void aMissingTokenIsNamedAndNothingElseChanges() {
        post(false, null, null, null, null, null);

        ResponseEntity<?> res = post(true, null, null, null, null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(412);
        assertThat(((Map<?, ?>)res.getBody()).get("need")).isEqualTo("jira");
        assertThat(standing.settings(USER)).isEqualTo(new StandingVisas.Settings(false, true, true, true, false,
            "anton-vinogradov", false, true, false, false, false));
    }

    @Test
    void prCommandsTakeTheLoginTheGithubTokenProves() {
        ResponseEntity<?> res = post(null, null, null, null, null, null, true);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.commandsOn(USER)).isTrue();
    }

    @Test
    void prCommandsWithoutAnyLoginAskForOne() {
        ResponseEntity<?> res = controller.standingVisa(null, null, null, null, true, "tc-new", "newcomer", null,
            null);

        assertThat(res.getStatusCode().value()).isEqualTo(412);
        assertThat(((Map<?, ?>)res.getBody()).get("need")).isEqualTo("login");
        assertThat(standing.settings("newcomer").commands()).isFalse();
    }

    @Test
    void theStateReadShowsWhichTokensTheServerHolds() {
        StandingVisas.Settings st = controller.standingVisaStatus(USER);

        assertThat(st.jiraStored()).isTrue();
        assertThat(st.ghStored()).isTrue();
        assertThat(mapper.convertValue(st, Map.class)).containsKeys("visa", "rerun", "gh", "style", "commands", "login",
            "jiraStored", "ghStored", "ghTokenRejected", "jiraTokenRejected", "tcTokenRejected");
    }

    private ResponseEntity<?> post(Boolean visa, Boolean rerun, Boolean gh, Boolean style, String jiraToken,
        String ghToken) {
        return post(visa, rerun, gh, style, jiraToken, ghToken, null);
    }

    private ResponseEntity<?> post(Boolean visa, Boolean rerun, Boolean gh, Boolean style, String jiraToken,
        String ghToken, Boolean commands) {
        return controller.standingVisa(visa, rerun, gh, style, commands, "tc", USER, jiraToken, ghToken);
    }
}
