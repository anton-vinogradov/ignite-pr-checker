package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Replace in ⚙ sends the new token with the switch of the option that already runs on the stored one. When JIRA or
 * GitHub refused the new token, or could not check it, the server answered 200 and kept the stored token: the form
 * closed as if the token were replaced, and the token the user meant to retire, perhaps a leaked one, went on posting
 * visas and committing. Avinogradov's visa and suite fixes run on stored tokens.
 */
class ReplacedTokenTest {
    private static final String USER = "avinogradov";

    private static final String UNCHECKED_JIRA = "JIRA could not check the token right now — try again in a minute";

    private static final String UNCHECKED_GH = "GitHub could not check the token right now — try again in a minute";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, mock(TcClient.class), standing,
        mock(MergedVerdicts.class), new UserDirectory(mapper));

    @TempDir
    private Path dir;

    @BeforeEach
    void enroll() {
        when(github.writes()).thenReturn(true);
        when(jira.myself("old-jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.tokenOwner("old-gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        controller.standingVisa(true, null, null, null, null, null, "tc", USER, "old-jira-pat", null);
        controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "old-gh-pat");
    }

    @Test
    void aJiraTokenJiraRejectsReplacesNothing() throws IOException {
        when(jira.myself("new-jira-pat")).thenReturn(Optional.empty());

        assertAnswer(replaceJira(), 412, "JIRA rejected the token — paste a new one");
        assertThat(standing.visaOn(USER)).isTrue();
        assertThat(stored("jiraToken")).isEqualTo("old-jira-pat");
    }

    @Test
    void aJiraTokenJiraCannotCheckReplacesNothing() throws IOException {
        when(jira.myself("new-jira-pat")).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertAnswer(replaceJira(), 502, UNCHECKED_JIRA);
        assertThat(standing.visaOn(USER)).isTrue();
        assertThat(stored("jiraToken")).isEqualTo("old-jira-pat");
    }

    @Test
    void aJiraTokenJiraTakesReplacesTheStoredOne() throws IOException {
        when(jira.myself("new-jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));

        assertThat(replaceJira().getStatusCode().value()).isEqualTo(200);
        assertThat(stored("jiraToken")).isEqualTo("new-jira-pat");
    }

    /** A token that comes with the switch of another option is checked all the same, not dropped without a word. */
    @Test
    void aJiraTokenWithAnotherSwitchIsCheckedToo() throws IOException {
        when(jira.myself("new-jira-pat")).thenReturn(Optional.empty());

        assertAnswer(controller.standingVisa(null, true, null, null, null, null, "tc", USER, "new-jira-pat", null), 412,
            "JIRA rejected the token — paste a new one");
        assertThat(standing.settings(USER).rerun()).isFalse();
        assertThat(stored("jiraToken")).isEqualTo("old-jira-pat");
    }

    @Test
    void aGithubTokenGithubRejectsReplacesNothing() {
        when(github.tokenOwner("new-gh-pat")).thenReturn(Optional.empty());

        assertAnswer(replaceGh(), 412, "GitHub rejected the token — paste a new one");
        assertThat(standing.settings(USER).suites()).isTrue();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("old-gh-pat"));
    }

    @Test
    void aGithubTokenGithubCannotCheckReplacesNothing() {
        when(github.tokenOwner("new-gh-pat")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertAnswer(replaceGh(), 502, UNCHECKED_GH);
        assertThat(standing.settings(USER).suites()).isTrue();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("old-gh-pat"));
    }

    /** What Save in the JIRA group sends: the visa's switch with the new token. */
    private ResponseEntity<?> replaceJira() {
        return controller.standingVisa(true, null, null, null, null, null, "tc", USER, "new-jira-pat", null);
    }

    /** What Save in the GitHub group sends: the switch of the option that commits, with the new token. */
    private ResponseEntity<?> replaceGh() {
        return controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "new-gh-pat");
    }

    /** The token the server keeps for the user, from its snapshot. */
    private String stored(String field) throws IOException {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        for (JsonNode e : mapper.readTree(file.toFile()).get("enrollments")) {
            if (USER.equals(e.get("username").asText()))
                return codec.decryptString(e.get(field).asText()).orElseThrow();
        }

        throw new AssertionError(USER + " is not enrolled");
    }

    private static void assertAnswer(ResponseEntity<?> res, int status, String error) {
        assertThat(res.getStatusCode().value()).isEqualTo(status);
        assertThat(((Map<?, ?>)res.getBody()).get("error")).isEqualTo(error);
    }
}
