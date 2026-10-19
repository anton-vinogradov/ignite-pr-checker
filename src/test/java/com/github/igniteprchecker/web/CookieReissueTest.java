package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * v1.36.0 and earlier kept the user's JIRA token in the session cookie for the visa buttons, and earlier versions the
 * GitHub token too. The checker takes neither from a cookie: such a cookie still logs its user in, and the cookie
 * issued with the very next answer goes without them.
 */
class CookieReissueTest {
    private static final String USER = "avinogradov";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, tc, standing, mock(MergedVerdicts.class),
        new UserDirectory(mapper));

    /** A cookie of v1.36.0, issued just now, with the JIRA token its owner pasted for Post now. */
    @Test
    void aCookieWithAJiraTokenLogsInAndIsIssuedAgainWithoutIt() throws Exception {
        MockHttpServletRequest req = request(cookie(", \"jiraToken\": \"jira-pat\""));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(guard().preHandle(req, resp, new Object())).isTrue();

        assertThat(req.getAttribute(AuthInterceptor.USER_ATTR)).isEqualTo(USER);
        JsonNode issued = payload(HttpCookie.parse(resp.getHeader(HttpHeaders.SET_COOKIE)).get(0).getValue());
        assertThat(issued.path("username").asText()).isEqualTo(USER);
        assertThat(issued.has("jiraToken")).isFalse();
    }

    /** What ⚙ does next: switches the visa on through the session guard, with no token in the request. */
    @Test
    void theJiraTokenOfACookieSwitchesNoVisaOn() throws Exception {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addMappedInterceptors(new String[] {"/api/auto-visa-all"}, guard()).build();

        MockHttpServletResponse res = mvc.perform(post("/api/auto-visa-all").param("visa", "true")
            .cookie(new Cookie(AuthInterceptor.COOKIE, cookie(", \"jiraToken\": \"jira-pat\"")))).andReturn()
            .getResponse();

        assertThat(res.getStatus()).isEqualTo(412);
        assertThat(res.getContentAsString()).contains("\"need\":\"jira\"");
        assertThat(standing.visaOn(USER)).isFalse();
    }

    @Test
    void theGithubTokenOfAnEarlierCookieSwitchesNoAutofixOn() throws Exception {
        when(github.writes()).thenReturn(true);
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), List.of("public_repo"))));
        standing.linkGhLogin(USER, "tc", GhUsers.of("anton-vinogradov"));

        ResponseEntity<?> res = switchOn(cookie(", \"ghToken\": \"gh-pat\""), null, true, true);

        assertThat(res.getStatusCode().value()).isEqualTo(412);
        assertThat(standing.styleFixOn(USER)).isFalse();
    }

    /** Logged in again with a fresh TeamCity token while the browser still held the session of the old one. */
    @Test
    void aLoginKeepsTheSessionOfTheSameUsersCookieOnlyAndNoJiraToken() throws Exception {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), users, logins, new LoginThrottle(),
            new AdminActions(new AdminProperties(null), mapper), standing);

        ResponseEntity<?> same = login.login(new LoginController.LoginRequest("fresh-tc"),
            request(cookie(", \"jiraToken\": \"jira-pat\"")));
        ResponseEntity<?> other = login.login(new LoginController.LoginRequest("fresh-tc"),
            carrying(new SessionCodec.Session("someone-else", "their-tc")));

        assertThat(session(same)).isEqualTo(new SessionCodec.Session(USER, "fresh-tc"));
        assertThat(payload(cookieOf(same)).has("jiraToken")).isFalse();
        assertThat(session(other)).isEqualTo(new SessionCodec.Session(USER, "fresh-tc"));
    }

    /** alice logged out in one browser. The other browser still held the cookie of the ended session. */
    @Test
    void aLoginAfterALogoutTakesNothingOfTheEndedCookie() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), users, logins, new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper),
            standing);
        MockHttpServletRequest other = carrying(new SessionCodec.Session(USER, "old-tc"));

        login.logout(carrying(new SessionCodec.Session(USER, "old-tc")));
        SessionCodec.Session after = session(login.login(new LoginController.LoginRequest("fresh-tc"), other));

        assertThat(after).isEqualTo(new SessionCodec.Session(USER, "fresh-tc"));
    }

    /** A change in ⚙ through the session guard, with no token in the request's body. */
    private ResponseEntity<?> switchOn(String cookie, Boolean visa, Boolean style, Boolean commands) throws Exception {
        MockHttpServletRequest req = request(cookie);
        assertThat(guard().preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        return controller.standingVisa(visa, null, null, style, commands, null,
            (String)req.getAttribute(AuthInterceptor.TOKEN_ATTR), (String)req.getAttribute(AuthInterceptor.USER_ATTR),
            null, null);
    }

    private AuthInterceptor guard() {
        return new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing);
    }

    /** A cookie of the current format, issued now, with {@code more} fields in it. */
    private String cookie(String more) {
        return codec.encryptString("{\"username\": \"" + USER + "\", \"token\": \"tc\"" + more + ", \"iat\": "
            + System.currentTimeMillis() + ", \"v\": 2}");
    }

    private static MockHttpServletRequest request(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auto-visa-all");
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));

        return req;
    }

    private MockHttpServletRequest carrying(SessionCodec.Session session) {
        return request(codec.encode(session));
    }

    private JsonNode payload(String cookie) throws Exception {
        return mapper.readTree(codec.decryptString(cookie).orElseThrow());
    }

    private static String cookieOf(ResponseEntity<?> res) {
        return HttpCookie.parse(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
    }

    /** The session the answer's cookie carries; when it was issued is left out. */
    private SessionCodec.Session session(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        SessionCodec.Session s = codec.decode(cookieOf(res)).orElseThrow();

        return new SessionCodec.Session(s.username(), s.token(), 0, s.carriesJira());
    }
}
