package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
 * The GitHub login for PR commands was stored as typed and matched with case: GitHub reports the
 * author of a comment as "NSAmelchev", so a login linked as "nsamelchev" never matched, while the
 * panel answered "PR commands are live" to anything typed, typos included. A login typed in by hand
 * could also sit on someone else's account, and the owner then got 409 and their /run-all went out
 * under the claimant's TeamCity token.
 */
class GithubLoginTest {
    private final GithubClient github = mock(GithubClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(BlockerAnalyzer.class),
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(VisaService.class),
        mock(VisaSubscriptions.class), standing, github, mock(PendingCommits.class), false);

    @BeforeEach
    void enrolled() {
        standing.change("nsamelchev", "tc-1", null, null, new StandingVisas.OptionChange(null, true, null, null));
        standing.change("avinogradov", "tc-2", null, null, new StandingVisas.OptionChange(null, true, null, null));
        when(github.canonicalLogin("nsamelchev")).thenReturn(Optional.of("NSAmelchev"));
        when(github.canonicalLogin("anton-vinogradov")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.canonicalLogin("nsamelchevv")).thenReturn(Optional.empty());
    }

    @Test
    void aLoginIsStoredTheWayGithubSpellsItAndFoundByTheCommandsAuthor() {
        ResponseEntity<?> res = link("nsamelchev", " @nsamelchev ");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(Map.of("login", "NSAmelchev"));
        assertThat(standing.actorByGhLogin("NSAmelchev")).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("nsamelchev"));
    }

    @Test
    void aTypoIsRefusedAndNothingIsLinked() {
        ResponseEntity<?> res = link("nsamelchev", "nsamelchevv");

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
    }

    /** Linked before logins were checked: stored as typed, in another case than GitHub's. */
    @Test
    void aLoginLinkedInAnotherCaseStillMatchesAndIsPutRight() {
        standing.setGhLogin("nsamelchev", "nsamelchev");

        assertThat(standing.actorByGhLogin("NSAmelchev")).isPresent();
        assertThat(standing.ghLoginOf("nsamelchev")).isEqualTo("NSAmelchev");
    }

    @Test
    void aLoginProvenByATokenTakesOverAClaimTypedInByHand() {
        link("nsamelchev", "anton-vinogradov");
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));

        standing.change("avinogradov", "tc-2", null, "gh-pat", new StandingVisas.OptionChange(null, null, true, null));

        assertThat(standing.actorByGhLogin("anton-vinogradov")).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(link("nsamelchev", "anton-vinogradov").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void aTypedLoginCannotReplaceTheOneATokenProves() {
        when(github.ghUser(anyString())).thenReturn(Optional.of("anton-vinogradov"));
        standing.change("avinogradov", "tc-2", null, "gh-pat", new StandingVisas.OptionChange(null, null, true, null));

        ResponseEntity<?> res = link("avinogradov", "nsamelchev");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("anton-vinogradov");
    }

    private ResponseEntity<?> link(String username, String typed) {
        return controller.saveGithubLogin(new JiraController.TokenRequest(typed), username);
    }
}
