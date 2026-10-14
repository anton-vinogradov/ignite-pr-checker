package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
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
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Saving a JIRA token re-issued the session cookie without the GitHub token it carried: the next switch of a GitHub
 * option in ⚙ answered "this option needs your GitHub token" for a token pasted a minute before.
 */
class CookieReissueTest {
    private static final String USER = "avinogradov";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, tc, mock(BlockerAnalyzer.class), codec,
        mock(VisaService.class), mock(VisaSubscriptions.class), standing, github, mock(PendingCommits.class), false);

    @Test
    void savingAJiraTokenKeepsTheGithubToken() throws Exception {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));

        SessionCodec.Session after = session(controller.saveToken(new JiraController.TokenRequest("jira-pat"),
            new SessionCodec.Session(USER, "tc", null, "gh-pat"), System.currentTimeMillis()));

        assertThat(after).isEqualTo(new SessionCodec.Session(USER, "tc", "jira-pat", "gh-pat"));
        assertThat(switchCommentOn(after).getStatusCode().value()).isEqualTo(200);
        assertThat(standing.ghOn(USER)).isTrue();
    }

    @Test
    void savingAGithubTokenKeepsTheJiraToken() {
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));

        SessionCodec.Session after = session(controller.saveGithubToken(new JiraController.TokenRequest("gh-pat"),
            new SessionCodec.Session(USER, "tc", "jira-pat", null), System.currentTimeMillis()));

        assertThat(after).isEqualTo(new SessionCodec.Session(USER, "tc", "jira-pat", "gh-pat"));
    }

    /** Logged in again with a fresh TeamCity token while the browser still held the session of the old one. */
    @Test
    void aLoginKeepsThePatsOfTheSameUsersCookieOnly() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), mock(UserDirectory.class), new LoginThrottle(),
            new AdminActions(new AdminProperties(null), mapper), standing);

        SessionCodec.Session same = session(login.login(new LoginController.LoginRequest("fresh-tc"),
            carrying(new SessionCodec.Session(USER, "old-tc", "jira-pat", "gh-pat"))));
        SessionCodec.Session other = session(login.login(new LoginController.LoginRequest("fresh-tc"),
            carrying(new SessionCodec.Session("someone-else", "their-tc", "their-jira", "their-gh"))));

        assertThat(same).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", "jira-pat", "gh-pat"));
        assertThat(other).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", null, null));
    }

    /**
     * alice logged out in one browser. The other browser still held the cookie of the ended session, with her JIRA and
     * GitHub tokens, and a login there must not take them into the new session.
     */
    @Test
    void aLoginAfterALogoutTakesNoPatsOfTheEndedCookie() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        UserDirectory users = new UserDirectory(mapper);
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), users, new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper),
            standing);
        MockHttpServletRequest other = carrying(new SessionCodec.Session(USER, "old-tc", "jira-pat", "gh-pat"));

        login.logout(true, codec.encode(new SessionCodec.Session(USER, "old-tc", null, null)));
        SessionCodec.Session after = session(login.login(new LoginController.LoginRequest("fresh-tc"), other));

        assertThat(after).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", null, null));
    }

    /** What ⚙ does next: switches the PR comment on, through the session guard, with the tokens the cookie carries. */
    private ResponseEntity<?> switchCommentOn(SessionCodec.Session session) throws Exception {
        MockHttpServletRequest req = carrying(session);
        AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class), standing);
        assertThat(auth.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        return controller.standingVisa(null, null, true, null, null, null,
            (String)req.getAttribute(AuthInterceptor.TOKEN_ATTR), (String)req.getAttribute(AuthInterceptor.USER_ATTR),
            (String)req.getAttribute(AuthInterceptor.JIRA_ATTR), (String)req.getAttribute(AuthInterceptor.GH_ATTR));
    }

    private MockHttpServletRequest carrying(SessionCodec.Session session) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(session)));

        return req;
    }

    /** The session the answer's cookie carries; when it was issued is left out. */
    private SessionCodec.Session session(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        String setCookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        SessionCodec.Session s = codec.decode(HttpCookie.parse(setCookie).get(0).getValue()).orElseThrow();

        return new SessionCodec.Session(s.username(), s.token(), s.jiraToken(), s.ghToken());
    }
}
