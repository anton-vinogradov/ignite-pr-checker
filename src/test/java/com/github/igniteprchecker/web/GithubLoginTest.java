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
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The GitHub login for PR commands was typed in by hand and proved nothing: anyone could take a login nobody had linked
 * yet, and the owner's /run-all then went out under the claimant's TeamCity token, while a 409 told a newcomer whose
 * login was taken that it was in the checker. GitHub now names the account the user links, and the rules of who holds
 * it stay whatever names it: Link GitHub, or a GitHub token saved for commits.
 */
class GithubLoginTest {
    private final GithubClient github = AppWriting.github();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final GithubLinkController links = new GithubLinkController(github, standing,
        new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class),
            new Logins(mapper, mock(UserDirectory.class)), standing));

    @BeforeEach
    void enrolled() {
        when(github.linksAccounts()).thenReturn(true);
        when(github.authorizeUri(anyString())).thenAnswer(inv -> UriComponentsBuilder
            .fromUriString("https://github.com/login/oauth/authorize").queryParam("state", (String)inv.getArgument(0))
            .build().toUri());
        standing.change("nsamelchev", "tc-1", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        standing.change("avinogradov", "tc-2", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null));
    }

    /** Linking was how PR commands went on, and it answered 412 until some other option was on. */
    @Test
    void aLinkIsAllPrCommandsNeed() {
        assertThat(link("newcomer", "tc-3", GhUsers.of("Newcomer"))).isEqualTo("linked");

        assertThat(standing.settings("newcomer")).isEqualTo(new StandingVisas.Settings(false, false, false, false,
            true, "Newcomer", false, false, false, false, false, false, false, true, null, null, null, true, null));
        assertThat(standing.actorByGhUser(GhUsers.of("Newcomer")))
            .hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("tc-3"));
    }

    /**
     * nsamelchev linked anton-vinogradov's account by mistake, signed in to GitHub as him on a shared machine: the
     * owner's link takes it over, and the answer to the owner says nothing of who held it.
     */
    @Test
    void theOwnersLinkTakesTheAccountOverAndNamesNobody() {
        link("nsamelchev", "tc-1", GhUsers.of("anton-vinogradov"));

        String outcome = link("avinogradov", "tc-2", GhUsers.of("anton-vinogradov"));

        assertThat(outcome).isEqualTo("linked");
        assertThat(standing.settings("avinogradov").toString()).doesNotContain("nsamelchev");
        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.commandsOn("nsamelchev")).isFalse();
        assertThat(standing.rerunOn("nsamelchev")).as("the other switches stay").isTrue();
    }

    /** A GitHub token saved to add tests to suites proves its account as well, and takes it over the same way. */
    @Test
    void aSavedGithubTokenProvesTheLoginToo() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        link("nsamelchev", "tc-1", GhUsers.of("anton-vinogradov"));

        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, null, true));

        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).hasValueSatisfying(
            a -> assertThat(a.username()).isEqualTo("avinogradov"));
        assertThat(standing.settings("avinogradov").loginUnproven()).isFalse();
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
    }

    /** The proof outlives the GitHub token it came with: once GitHub refused that token, a login was anyone's. */
    @Test
    void aLoginStaysProvenWhenItsTokenGoes() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, true, true));

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
        when(github.tokenOwner("gh-pat-1")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        when(github.tokenOwner("gh-pat-2")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.change("nsamelchev", "tc-1", null, "gh-pat-1",
            new StandingVisas.OptionChange(null, null, null, null, null, true));
        standing.change("avinogradov", "tc-2", null, "gh-pat-2",
            new StandingVisas.OptionChange(null, null, null, null, null, true));

        standing.ensureGhLogins();

        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("anton-vinogradov");
    }

    @Test
    void anotherAccountCannotReplaceTheOneTheSavedTokenProves() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, null, true));

        assertThat(link("avinogradov", "tc-2", GhUsers.of("NSAmelchev"))).isEqualTo("token");
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("anton-vinogradov");
    }

    /** The checker's own comments must never read as somebody's commands. */
    @Test
    void theAppAccountCannotBeLinked() {
        GithubClient.GhUser app = GhUsers.of("ignite-pr-checker");
        when(github.isAppAccount(app)).thenReturn(true);
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(app, null)));

        String outcome = link("nsamelchev", "tc-1", app);
        Optional<StandingVisas.Refusal> refused = standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, null, true));

        assertThat(outcome).isEqualTo("app");
        assertThat(refused).hasValueSatisfying(r -> assertThat(r.need()).isEqualTo("github"));
        assertThat(standing.ghLoginOf("nsamelchev")).isNull();
        assertThat(standing.ghLoginOf("avinogradov")).isNull();
    }

    /**
     * In the minutes after a restart GitHub has not yet said whose GITHUB_TOKEN is, and a link of that account goes
     * through like any other. Once GitHub says, its comments are nobody's commands all the same.
     */
    @Test
    void theAppAccountLinkedBeforeItWasKnownCommandsNothing() {
        GithubClient.GhUser app = GhUsers.of("ignite-pr-checker");
        link("nsamelchev", "tc-1", app);

        when(github.isAppAccount(app)).thenReturn(true);

        assertThat(standing.ghLoginOf("nsamelchev")).isEqualTo("ignite-pr-checker");
        assertThat(standing.actorByGhUser(app)).isEmpty();
    }

    @Test
    void withoutAnOAuthAppThereIsNoLinkGithub() {
        when(github.linksAccounts()).thenReturn(false);

        assertThat(links.link(null, null, "tc-1", "nsamelchev").getStatusCode().value()).isEqualTo(409);
        assertThat(standing.settings("nsamelchev").githubLinkable()).isFalse();
    }

    @Test
    void withoutTheCheckersAccountNothingIsLinked() {
        when(github.writes()).thenReturn(false);

        ResponseEntity<?> res = links.link(null, null, "tc-1", "nsamelchev");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(res.getBody())).contains("no GitHub account to write with");
        assertThat(standing.settings("nsamelchev").githubLinkable()).isFalse();
    }

    /** Unlink forgets the account: PR commands go off, the other options stay. */
    @Test
    void unlinkForgetsTheAccountAndPrCommands() {
        link("nsamelchev", "tc-1", GhUsers.of("NSAmelchev"));

        StandingVisas.Settings st = links.unlink("nsamelchev");

        assertThat(st.login()).isNull();
        assertThat(st.commands()).isFalse();
        assertThat(st.rerun()).isTrue();
        assertThat(standing.actorByGhUser(GhUsers.of("NSAmelchev"))).isEmpty();
    }

    /**
     * The account was linked by the GitHub token saved for commits: unlinked, the token would link it again at the
     * next poll. It goes, and so do the options that commit with it.
     */
    @Test
    void unlinkDropsTheTokenThatWouldLinkTheAccountAgain() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.change("avinogradov", "tc-2", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, true, true));
        standing.change("avinogradov", "tc-2", null, null,
            new StandingVisas.OptionChange(null, null, null, true, null, null));

        StandingVisas.Settings st = links.unlink("avinogradov");
        standing.ensureGhLogins();

        assertThat(st.login()).isNull();
        assertThat(st.commands()).isFalse();
        assertThat(st.style()).isFalse();
        assertThat(st.suites()).isFalse();
        assertThat(st.ghStored()).isFalse();
        assertThat(st.ghTokenRejected()).as("nothing to replace").isFalse();
        assertThat(st.rerun()).isTrue();
        assertThat(standing.ghLoginOf("avinogradov")).isNull();
    }

    @Test
    void unlinkingTheLastOptionEndsTheEnrollment() {
        link("newcomer", "tc-3", GhUsers.of("Newcomer"));

        links.unlink("newcomer");

        assertThat(standing.enabled("newcomer")).isFalse();
    }

    /** The outcome the callback sends the browser back with, after a link GitHub confirmed as {@code account}. */
    private String link(String username, String tcToken, GithubClient.GhUser account) {
        URI authorize = URI.create(String.valueOf(((Map<?, ?>)links.link(null, null, tcToken, username).getBody())
            .get("url")));
        String state = UriComponentsBuilder.fromUri(authorize).build().getQueryParams().getFirst("state");
        String code = "code-of-" + username;
        when(github.oauthUser(code)).thenReturn(Optional.of(account));
        MockHttpServletRequest browser = new MockHttpServletRequest();
        browser.setCookies(new Cookie(AuthInterceptor.COOKIE,
            codec.encode(new SessionCodec.Session(username, tcToken))));

        String back = String.valueOf(links.callback(code, state, null, browser).getHeaders().getLocation());

        return back.substring(back.indexOf("github=") + "github=".length());
    }
}
