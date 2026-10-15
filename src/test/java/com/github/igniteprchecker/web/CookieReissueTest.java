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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Saving a JIRA token re-issued the session cookie without the GitHub token it carried: the next switch of a GitHub
 * option in ⚙ answered "this option needs your GitHub token" for a token pasted a minute before. The GitHub token no
 * longer rides in the cookie at all: a cookie of an earlier version loses it when it is issued again.
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
        mock(VisaService.class), mock(VisaSubscriptions.class), standing, mock(PendingCommits.class), false);

    @Test
    void savingAJiraTokenDropsTheGithubTokenOfAnEarlierCookie() throws Exception {
        when(github.writes()).thenReturn(true);
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), List.of("public_repo"))));
        String earlier = codec.encryptString("{\"username\":\"" + USER + "\",\"token\":\"tc\",\"ghToken\":\"gh-pat\","
            + "\"iat\":" + System.currentTimeMillis() + "}");
        SessionCodec.Session old = codec.decode(earlier).orElseThrow();

        ResponseEntity<?> saved = controller.saveToken(new JiraController.TokenRequest("jira-pat"), old,
            System.currentTimeMillis());

        assertThat(session(saved)).isEqualTo(new SessionCodec.Session(USER, "tc", "jira-pat"));
        assertThat(mapper.readTree(codec.decryptString(cookieOf(saved)).orElseThrow()).has("ghToken")).isFalse();
        assertThat(switchOn(earlier).getStatusCode().value()).as("the guard takes no GitHub token from a cookie")
            .isEqualTo(412);
        assertThat(standing.styleFixOn(USER)).isFalse();
    }

    /** Logged in again with a fresh TeamCity token while the browser still held the session of the old one. */
    @Test
    void aLoginKeepsThePatsOfTheSameUsersCookieOnly() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), mock(UserDirectory.class), new LoginThrottle(),
            new AdminActions(new AdminProperties(null), mapper), standing);

        SessionCodec.Session same = session(login.login(new LoginController.LoginRequest("fresh-tc"),
            carrying(new SessionCodec.Session(USER, "old-tc", "jira-pat"))));
        SessionCodec.Session other = session(login.login(new LoginController.LoginRequest("fresh-tc"),
            carrying(new SessionCodec.Session("someone-else", "their-tc", "their-jira"))));

        assertThat(same).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", "jira-pat"));
        assertThat(other).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", null));
    }

    /**
     * alice logged out in one browser. The other browser still held the cookie of the ended session, with her JIRA
     * token, and a login there must not take it into the new session.
     */
    @Test
    void aLoginAfterALogoutTakesNoPatsOfTheEndedCookie() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        UserDirectory users = new UserDirectory(mapper);
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), users, new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper),
            standing);
        MockHttpServletRequest other = carrying(new SessionCodec.Session(USER, "old-tc", "jira-pat"));

        login.logout(true, codec.encode(new SessionCodec.Session(USER, "old-tc", null)));
        SessionCodec.Session after = session(login.login(new LoginController.LoginRequest("fresh-tc"), other));

        assertThat(after).isEqualTo(new SessionCodec.Session(USER, "fresh-tc", null));
    }

    /**
     * What ⚙ does next: switches PR commands and the checkstyle autofix on through the session guard, with no token
     * in the request.
     */
    private ResponseEntity<?> switchOn(String cookie) throws Exception {
        standing.linkGhLogin(USER, "tc", GhUsers.of("anton-vinogradov"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auto-visa-all");
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));
        AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class), standing);
        assertThat(auth.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        return controller.standingVisa(null, null, null, true, null, null,
            (String)req.getAttribute(AuthInterceptor.TOKEN_ATTR), (String)req.getAttribute(AuthInterceptor.USER_ATTR),
            (String)req.getAttribute(AuthInterceptor.JIRA_ATTR), null);
    }

    private MockHttpServletRequest carrying(SessionCodec.Session session) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(session)));

        return req;
    }

    private static String cookieOf(ResponseEntity<?> res) {
        return HttpCookie.parse(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
    }

    /** The session the answer's cookie carries; when it was issued is left out. */
    private SessionCodec.Session session(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        SessionCodec.Session s = codec.decode(cookieOf(res)).orElseThrow();

        return new SessionCodec.Session(s.username(), s.token(), s.jiraToken());
    }
}
