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
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The GitHub login for PR commands was typed in by hand and proved nothing: anyone could take a login nobody had linked
 * yet, and the owner's /run-all then went out under the claimant's TeamCity token, while a 409 told a newcomer whose
 * login was taken that it was in the checker. The login is now proven with a token pasted once: GitHub names the
 * account, the checker keeps its login and id, and the token goes nowhere.
 */
class GithubLoginTest {
    private static final String ONE_TIME = "ghp_noScopes0123456789abcdefghij0123456";

    private final GithubClient github = mock(GithubClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        mock(BlockerAnalyzer.class), new SessionCodec(new SessionProperties(false, "test-secret"), mapper),
        mock(VisaService.class),
        mock(VisaSubscriptions.class), standing, github, mock(PendingCommits.class), false);

    @BeforeEach
    void enrolled() {
        standing.change("nsamelchev", "tc-1", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        standing.change("avinogradov", "tc-2", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null));
    }

    @Test
    void aTokenProvesTheLoginAndSwitchesCommandsOn() {
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(GhUsers.of("NSAmelchev")));

        ResponseEntity<?> res = prove("nsamelchev", " " + ONE_TIME + " ");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isInstanceOfSatisfying(StandingVisas.Settings.class, st -> {
            assertThat(st.login()).isEqualTo("NSAmelchev");
            assertThat(st.commands()).as("proving the login is how PR commands are switched on").isTrue();
            assertThat(st.loginUnproven()).isFalse();
            assertThat(st.ghStored()).as("the token is not kept").isFalse();
        });
        assertThat(standing.actorByGhUser(GhUsers.of("NSAmelchev"))).hasValueSatisfying(a -> {
            assertThat(a.username()).isEqualTo("nsamelchev");
            assertThat(a.ghToken()).isNull();
        });
    }

    /** The token goes into no cookie and no file: the answer sets no cookie, and the snapshot does not hold it. */
    @Test
    void theTokenIsForgotten(@TempDir Path dir) throws Exception {
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(GhUsers.of("NSAmelchev")));

        ResponseEntity<?> res = prove("nsamelchev", ONE_TIME);
        standing.saveTo(dir.resolve("standing-visas.json"));

        assertThat(res.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(Files.readString(dir.resolve("standing-visas.json"))).contains("\"ghLogin\":\"NSAmelchev\"")
            .contains("\"ghId\":" + GhUsers.idOf("NSAmelchev")).doesNotContain(ONE_TIME);
    }

    /** Proving the login was how PR commands went on, and it answered 412 until some other option was on. */
    @Test
    void provingTheLoginIsAllPrCommandsNeed() {
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(GhUsers.of("Newcomer")));

        ResponseEntity<?> res = controller.saveGithubLogin(new JiraController.TokenRequest(ONE_TIME), "tc-3",
            "newcomer");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.settings("newcomer")).isEqualTo(new StandingVisas.Settings(false, false, false, false,
            true, "Newcomer", false, false, false, false, false));
        assertThat(standing.actorByGhUser(GhUsers.of("Newcomer")))
            .hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("tc-3"));
    }

    /** A login typed in where the token goes is no token: GitHub refuses it, and nothing is linked. */
    @Test
    void aTypedLoginIsNoProof() {
        when(github.ghIdentity(anyString())).thenReturn(Optional.empty());

        ResponseEntity<?> res = prove("nsamelchev", "NSAmelchev");

        assertThat(res.getStatusCode().value()).as("a 401 would end the session on the page").isEqualTo(422);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "GitHub rejected the token"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
    }

    @Test
    void githubBeingDownIsNotARefusal() {
        when(github.ghIdentity(ONE_TIME)).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(prove("nsamelchev", ONE_TIME).getStatusCode().value()).isEqualTo(502);
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
    }

    /**
     * nsamelchev proved anton-vinogradov's account by mistake, a token of it at hand: the owner's proof takes it over,
     * and the answer to the owner says nothing of who held it.
     */
    @Test
    void theOwnersProofTakesTheAccountOverAndNamesNobody() {
        when(github.ghIdentity("token-1")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        when(github.ghIdentity("token-2")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        prove("nsamelchev", "token-1");

        ResponseEntity<?> res = prove("avinogradov", "token-2");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(String.valueOf(res.getBody())).doesNotContain("nsamelchev");
        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.commandsOn("nsamelchev")).isFalse();
        assertThat(standing.rerunOn("nsamelchev")).as("the other switches stay").isTrue();
    }

    /** A GitHub token saved for the PR comment proves its account as well, and takes it over the same way. */
    @Test
    void aSavedGithubTokenProvesTheLoginToo() {
        when(github.ghIdentity("token-1")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        prove("nsamelchev", "token-1");

        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, true, null, null));

        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
        assertThat(standing.settings("avinogradov").loginUnproven()).isFalse();
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
    }

    /** The proof outlives the GitHub token it came with: once GitHub refused that token, a login was anyone's. */
    @Test
    void aLoginStaysProvenWhenItsTokenGoes() {
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, true, null, true));

        standing.dropGhToken("avinogradov");

        assertThat(standing.settings("avinogradov").loginUnproven()).isFalse();
        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
    }

    /**
     * Two people's GitHub tokens prove the same account: the one who saved theirs last holds the
     * login, and the poll's backfill must not hand it back to the other one as well.
     */
    @Test
    void theBackfillDoesNotGiveOutALoginSomeoneElseHolds() {
        when(github.ghIdentity("gh-pat-1")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        when(github.ghIdentity("gh-pat-2")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        standing.change("nsamelchev", "tc-1", null, "gh-pat-1",
            new StandingVisas.OptionChange(null, null, true, null, null));
        standing.change("avinogradov", "tc-2", null, "gh-pat-2",
            new StandingVisas.OptionChange(null, null, true, null, null));

        standing.ensureGhLogins();

        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("anton-vinogradov");
    }

    @Test
    void aTokenOfAnotherAccountCannotReplaceTheOneTheSavedTokenProves() {
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(GhUsers.of("NSAmelchev")));
        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, true, null, null));

        ResponseEntity<?> res = prove("avinogradov", ONE_TIME);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("anton-vinogradov");
    }

    /** The checker's own comments must never read as somebody's commands. */
    @Test
    void theAppAccountCannotBeLinked() {
        GithubClient.GhUser app = GhUsers.of("ignite-pr-checker");
        when(github.isAppAccount(app)).thenReturn(true);
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(app));
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(app));

        ResponseEntity<?> res = prove("nsamelchev", ONE_TIME);
        Optional<StandingVisas.Refusal> refused = standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, true, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(res.getBody())).contains("the checker's own GitHub account");
        assertThat(refused).hasValueSatisfying(r -> assertThat(r.need()).isEqualTo("github"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.ghLoginOf("avinogradov")).isNull();
    }

    /**
     * In the minutes after a restart GitHub has not yet said whose GITHUB_TOKEN is, and a token of that account proves
     * it like any other. Once GitHub says, its comments are nobody's commands all the same.
     */
    @Test
    void theAppAccountProvenBeforeItWasKnownCommandsNothing() {
        GithubClient.GhUser app = GhUsers.of("ignite-pr-checker");
        when(github.ghIdentity(ONE_TIME)).thenReturn(Optional.of(app));
        prove("nsamelchev", ONE_TIME);

        when(github.isAppAccount(app)).thenReturn(true);

        assertThat(standing.ghLoginOf("nsamelchev")).isEqualTo("ignite-pr-checker");
        assertThat(standing.actorByGhUser(app)).isEmpty();
    }

    private ResponseEntity<?> prove(String username, String token) {
        return controller.saveGithubLogin(new JiraController.TokenRequest(token), "tc", username);
    }
}
