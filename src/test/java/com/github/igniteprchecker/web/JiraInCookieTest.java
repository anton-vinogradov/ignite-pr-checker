package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * alice pasted her JIRA token for Post now, v1.36.0 kept it in her login cookie, and her Visa my runs in JIRA was
 * off. The checker uses no JIRA token from a cookie, and the next cookie goes without it, so ⚙ is to tell her for a
 * while that visas need the option, until she switches it on.
 */
class JiraInCookieTest {
    private static final String USER = "alice";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final JiraClient jira = mock(JiraClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, mock(GithubClient.class),
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final LoginController login = new LoginController(tc, codec, props, mock(Warmer.class), users, logins,
        new LoginThrottle(), new AdminActions(new AdminProperties(List.of()), mapper), standing);

    private final JiraController options = new JiraController(jira, tc, standing, mock(MergedVerdicts.class), users);

    @Test
    void theTokenOfTheCookieIsToldOfAfterTheCookieLostIt() {
        ResponseEntity<?> first = login.me(request(cookie(true)));
        String issued = HttpCookie.parse(first.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();

        assertThat(me(first).jiraInCookie()).isTrue();
        assertThat(codec.decode(issued)).hasValueSatisfying(s -> assertThat(s.carriesJira()).isFalse());
        assertThat(me(login.me(request(issued))).jiraInCookie()).as("the cookie issued again has no token").isTrue();
    }

    @Test
    void theVisaSwitchedOnWithATokenEndsIt() {
        login.me(request(cookie(true)));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Alice"));

        options.standingVisa(true, null, null, null, null, null, "alice-tc", USER, "jira-pat", null);
        standing.change(USER, "alice-tc", null, null, new StandingVisas.OptionChange(false, null, null, null, null));

        assertThat(me(login.me(request(cookie(false)))).jiraInCookie()).isFalse();
    }

    /**
     * Her browser's cookie still held the JIRA token when she logged in again, as often as she liked from one address:
     * a login TeamCity takes costs the address none of its attempts. Each new cookie goes without the token, and ⚙ is
     * to tell her of it.
     */
    @Test
    void aLoginFromABrowserWithTheTokenIssuesACookieWithoutItAndCostsNoAttempt() {
        when(tc.currentUsername("alice-tc-new")).thenReturn(Optional.of(USER));

        for (int i = 0; i <= LoginThrottle.ATTEMPTS_PER_CLIENT; i++) {
            MockHttpServletRequest req = request(cookie(true));
            req.setRemoteAddr("203.0.113.7");
            ResponseEntity<?> res = login.login(new LoginController.LoginRequest("alice-tc-new"), req);
            String issued = HttpCookie.parse(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();

            assertThat(me(res).jiraInCookie()).as("login " + i).isTrue();
            assertThat(codec.decode(issued)).hasValueSatisfying(s -> {
                assertThat(s.token()).isEqualTo("alice-tc-new");
                assertThat(s.carriesJira()).isFalse();
            });
        }
    }

    @Test
    void aCookieWithoutATokenTellsNothing() throws Exception {
        assertThat(me(login.me(request(cookie(false)))).jiraInCookie()).isFalse();

        MockHttpServletRequest req = request(cookie(false));
        new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing).preHandle(req, new MockHttpServletResponse(),
            new Object());
        assertThat(users.jiraInCookie(USER)).isFalse();
    }

    /** The login guard marks her too, whichever request her browser sends first. */
    @Test
    void theGuardMarksTheUser() throws Exception {
        new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing).preHandle(request(cookie(true)),
            new MockHttpServletResponse(), new Object());

        assertThat(users.jiraInCookie(USER)).isTrue();
    }

    /** ⚙ stops telling of the mark a month after her cookie last had the token, a restart or not. */
    @Test
    void theMarkIsKeptForAMonth(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path file = dir.resolve("users.json");
        Files.writeString(file, "[{\"name\":\"alice\",\"firstSeen\":1,\"lastSeen\":2,\"logins\":3,\"sessionsEndedAt\":0,"
            + "\"jiraInCookieAt\":" + (now - Duration.ofDays(29).toMillis()) + "},{\"name\":\"bob\",\"firstSeen\":1,"
            + "\"lastSeen\":2,\"logins\":3,\"sessionsEndedAt\":0,\"jiraInCookieAt\":"
            + (now - Duration.ofDays(31).toMillis()) + "}]");

        UserDirectory restarted = new UserDirectory(mapper);
        restarted.loadFrom(file);

        assertThat(restarted.jiraInCookie("alice")).isTrue();
        assertThat(restarted.jiraInCookie("bob")).isFalse();
        assertThat(UserDirectory.JIRA_IN_COOKIE_TOLD_FOR).isEqualTo(Duration.ofDays(30));
    }

    /** The guard marked her, and the service restarted before her next visit to ⚙. */
    @Test
    void theMarkIsSavedWithTheUsers(@TempDir Path dir) throws Exception {
        new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing).preHandle(request(cookie(true)),
            new MockHttpServletResponse(), new Object());
        Path file = dir.resolve(users.fileName());
        users.saveTo(file);

        UserDirectory restarted = new UserDirectory(mapper);
        restarted.loadFrom(file);

        assertThat(restarted.jiraInCookie(USER)).isTrue();
    }

    private static LoginController.UserResponse me(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        return (LoginController.UserResponse)res.getBody();
    }

    /** A cookie of the current format, issued now. */
    private String cookie(boolean withJira) {
        return codec.encryptString("{\"username\":\"" + USER + "\",\"token\":\"alice-tc\""
            + (withJira ? ",\"jiraToken\":\"jira-pat\"" : "") + ",\"iat\":" + System.currentTimeMillis() + ",\"v\":2}");
    }

    private static MockHttpServletRequest request(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));

        return req;
    }
}
