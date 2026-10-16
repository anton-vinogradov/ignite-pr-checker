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
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The session cookie asked for ten years, but Chrome keeps a cookie at most 400 days from the response that set it,
 * and only a login or a pasted token set it again: a user of the checker was logged out without a word some 13
 * months after the last login. And a logout cleared only that browser's cookie: a copy of it, or the cookie in another
 * browser, went on working for good.
 */
class SessionLifetimeTest {
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, mock(StandingVisas.class));

    private final LoginController login = new LoginController(mock(TcClient.class), codec,
        new SessionProperties(false, "test-secret"), warmer, users, null,
        new AdminActions(new AdminProperties(List.of()), mapper), mock(StandingVisas.class));

    private final JiraClient jira = mock(JiraClient.class);

    private final JiraController tokens = new JiraController(jira, mock(TcClient.class), mock(BlockerAnalyzer.class),
        codec, mock(VisaService.class), mock(VisaSubscriptions.class), mock(StandingVisas.class),
        mock(PendingCommits.class), true);

    @Test
    void aCookieInUseIsIssuedAgainBeforeTheBrowserDropsIt() throws Exception {
        MockHttpServletResponse resp = guard(cookie("alice", System.currentTimeMillis() - 31 * DAY));

        assertThat(resp.getStatus()).isEqualTo(200);
        HttpCookie renewed = setCookie(resp);
        assertThat(renewed.getMaxAge()).isEqualTo(Duration.ofDays(400).toSeconds());
        assertThat(codec.decode(renewed.getValue())).hasValueSatisfying(s -> {
            assertThat(s.username()).isEqualTo("alice");
            assertThat(s.token()).isEqualTo("alice-tc");
            assertThat(s.issuedAt()).isGreaterThan(System.currentTimeMillis() - DAY);
        });
    }

    @Test
    void aCookieOfAnEarlierVersionIsReadAndIssuedAgain() throws Exception {
        String earlier = codec.encryptString("{\"username\":\"alice\",\"token\":\"alice-tc\",\"jiraToken\":\"jira-pat\"}");

        MockHttpServletResponse resp = guard(earlier);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(codec.decode(setCookie(resp).getValue())).hasValueSatisfying(s -> {
            assertThat(s.jiraToken()).isEqualTo("jira-pat");
            assertThat(s.issuedAt()).isPositive();
        });
    }

    @Test
    void aRecentCookieIsNotSetOnEveryRequest() throws Exception {
        long hourAgo = System.currentTimeMillis() - Duration.ofHours(1).toMillis();

        MockHttpServletResponse resp = guard(cookie("alice", hourAgo));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    /**
     * The texts say a login ends when left unused for 400 days. A cookie was renewed only past 30 days, so a login
     * last used on its 29th day ended 371 days after that use.
     */
    @Test
    void aLoginLastUsedOnItsSecondDayLasts400DaysFromThen() throws Exception {
        MockHttpServletResponse resp = guard(cookie("alice", System.currentTimeMillis() - 2 * DAY));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(setCookie(resp).getMaxAge()).isEqualTo(Duration.ofDays(400).toSeconds());
    }

    @Test
    void theLoginCookieAsksForWhatBrowsersKeep() {
        String cookie = codec.encode(new SessionCodec.Session("alice", "alice-tc", null));

        ResponseEntity<?> me = login.me(request(codec.encryptString(
            "{\"username\":\"alice\",\"token\":\"alice-tc\"}")));

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(HttpCookie.parse(me.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge())
            .isEqualTo(Duration.ofDays(400).toSeconds());
        assertThat(login.me(request(cookie)).getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void aLogoutEndsEverySessionOfTheUserIssuedBefore() throws Exception {
        long before = System.currentTimeMillis() - DAY;
        String here = cookie("alice", before);
        String copy = cookie("alice", before + 1000);
        String bobs = cookie("bob", before);

        ResponseEntity<?> out = login.logout(true, request(here));

        assertThat(HttpCookie.parse(out.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge()).isZero();
        assertThat(guard(copy).getStatus()).isEqualTo(401);
        assertThat(guard(copy).getContentAsString()).contains("\"sessionEnded\":true")
            .contains(AuthInterceptor.ENDED);
        assertThat(login.me(request(copy)).getStatusCode().value()).isEqualTo(401);
        assertThat(((Map<?, ?>)login.me(request(copy)).getBody()).get("sessionEnded")).isEqualTo(true);
        assertThat(guard(bobs).getStatus()).isEqualTo(200);
        assertThat(guard(cookie("alice", System.currentTimeMillis() + 1000)).getStatus())
            .as("a login after the logout").isEqualTo(200);
        assertThat(auth.signedIn(request(copy))).isEmpty();
    }

    @Test
    void aLogoutOutlivesARestart(@TempDir Path dir) throws Exception {
        String copy = cookie("alice", System.currentTimeMillis() - DAY);
        login.logout(true, request(copy));
        Path file = dir.resolve(users.fileName());
        users.saveTo(file);

        UserDirectory restarted = new UserDirectory(mapper);
        restarted.loadFrom(file);

        assertThat(restarted.ended(codec.decode(copy).orElseThrow())).isTrue();
        assertThat(users.durable()).as("written within a second, not every 5 minutes").isTrue();
    }

    /**
     * Someone with a copy of alice's cookie pasted a JIRA token while alice logged out: the guard had let the copy
     * through before the logout, and the cookie issued once JIRA answered was dated after it, so the logout did not
     * end it.
     */
    @Test
    void aCookieIssuedWithAJiraTokenWhileALogoutCameIsEndedByIt() throws Exception {
        String copy = cookie("alice", System.currentTimeMillis() - DAY);
        MockHttpServletRequest req = passed(copy);
        when(jira.myself("mallory-jira")).thenAnswer(inv -> answerAfterLogout(copy, "Mallory"));

        ResponseEntity<?> saved = tokens.saveToken(new JiraController.TokenRequest("mallory-jira"),
            (SessionCodec.Session)req.getAttribute(AuthInterceptor.SESSION_ATTR),
            (Long)req.getAttribute(AuthInterceptor.CHECKED_AT_ATTR));

        assertEnded(saved);
    }

    /**
     * A logout ended a copy of alice's cookie, yet the copy could still log out: each time alice logged in again, a
     * logout with the dead copy ended her new login.
     */
    @Test
    void aCopyALogoutEndedEndsNoLaterLogin() throws Exception {
        String copy = cookie("alice", System.currentTimeMillis() - DAY);
        login.logout(true, request(copy));
        Thread.sleep(5); // alice logs in again a moment later
        String later = codec.encode(new SessionCodec.Session("alice", "alice-tc", null));

        login.logout(true, request(copy));

        assertThat(guard(copy).getStatus()).isEqualTo(401);
        assertThat(guard(later).getStatus()).isEqualTo(200);
    }

    /** alice revoked the TeamCity token of a session a copy of her cookie carried; her login on a new token goes on. */
    @Test
    void aCopyOnARevokedTokenEndsNoLogin() throws Exception {
        when(warmer.tokenRevoked("alice-old-tc")).thenReturn(true);
        String live = cookie("alice", "alice-new-tc", System.currentTimeMillis() - DAY);

        login.logout(true, request(cookie("alice", "alice-old-tc", System.currentTimeMillis() - DAY)));

        assertThat(guard(live).getStatus()).isEqualTo(200);
    }

    /** TeamCity refused the token of this browser's session: the user's sessions on other tokens go on. */
    @Test
    void aLogoutOfThisBrowserOnlyEndsNothingElse() throws Exception {
        String other = cookie("alice", System.currentTimeMillis() - DAY);

        login.logout(false, request(cookie("alice", System.currentTimeMillis() - DAY)));

        assertThat(guard(other).getStatus()).isEqualTo(200);
    }

    /** users.json of the version before logouts were kept. */
    @Test
    void anEarlierUserListReads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        Files.writeString(file, "[{\"name\":\"alice\",\"firstSeen\":1760000000000,\"lastSeen\":1760400000000,"
            + "\"logins\":7}]");

        users.loadFrom(file);

        assertThat(users.list()).singleElement().satisfies(u -> {
            assertThat(u.logins()).isEqualTo(7);
            assertThat(u.sessionsEndedAt()).isZero();
        });
        assertThat(guard(cookie("alice", 0)).getStatus()).isEqualTo(200);
    }

    private String cookie(String user, long issuedAt) {
        return cookie(user, user + "-tc", issuedAt);
    }

    private String cookie(String user, String tcToken, long issuedAt) {
        return codec.encryptString("{\"username\":\"" + user + "\",\"token\":\"" + tcToken + "\",\"iat\":" + issuedAt
            + "}");
    }

    /** What GitHub or JIRA answers a moment after alice logged out with her cookie. */
    private Optional<String> answerAfterLogout(String cookie, String who) throws InterruptedException {
        login.logout(true, request(cookie));
        Thread.sleep(5);

        return Optional.of(who);
    }

    private void assertEnded(ResponseEntity<?> saved) throws Exception {
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        String issued = HttpCookie.parse(saved.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
        assertThat(guard(issued).getStatus()).isEqualTo(401);
        assertThat(login.me(request(issued)).getStatusCode().value()).isEqualTo(401);
    }

    /** The request the guard let through. */
    private MockHttpServletRequest passed(String cookie) throws Exception {
        MockHttpServletRequest req = request(cookie);
        assertThat(auth.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        return req;
    }

    private MockHttpServletResponse guard(String cookie) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        auth.preHandle(request(cookie), resp, new Object());

        return resp;
    }

    private static MockHttpServletRequest request(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));

        return req;
    }

    private static HttpCookie setCookie(MockHttpServletResponse resp) {
        List<String> set = resp.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(set).hasSize(1);

        return HttpCookie.parse(set.get(0)).get(0);
    }
}
