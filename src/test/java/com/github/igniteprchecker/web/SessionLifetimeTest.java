package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The session cookie asked for ten years, but Chrome keeps a cookie at most 400 days from the response that set it,
 * and only a login or a pasted token set it again: a user of the checker was logged out without a word some 13
 * months after the last login. And a logout cleared only that browser's cookie: a copy of it went on working for good.
 */
class SessionLifetimeTest {
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final Warmer warmer = mock(Warmer.class);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, logins, mock(StandingVisas.class));

    private final LoginController login = new LoginController(mock(TcClient.class), codec,
        new SessionProperties(false, "test-secret"), warmer, users, logins, null,
        new AdminActions(new AdminProperties(List.of()), mapper), mock(StandingVisas.class));

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
            assertThat(s.carriesJira()).isFalse();
            assertThat(s.issuedAt()).isPositive();
        });
    }

    @Test
    void aRecentCookieIsNotSetOnEveryRequest() throws Exception {
        long hourAgo = System.currentTimeMillis() - Duration.ofHours(1).toMillis();

        MockHttpServletResponse resp = guard(codec.encryptString("{\"username\":\"alice\",\"token\":\"alice-tc\","
            + "\"iat\":" + hourAgo + ",\"lid\":\"" + logins.start("alice", "Firefox").id() + "\"}"));

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
        String cookie = loggedIn("alice", "alice-tc");

        ResponseEntity<?> me = login.me(request(codec.encryptString(
            "{\"username\":\"alice\",\"token\":\"alice-tc\"}")));

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(HttpCookie.parse(me.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge())
            .isEqualTo(Duration.ofDays(400).toSeconds());
        assertThat(login.me(request(cookie)).getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    /**
     * Log out ended the user's logins in every browser at once. It ends this browser's login, and with it every copy
     * of its cookie; the user's other browsers go on.
     */
    @Test
    void aLogoutEndsThisBrowsersLoginAndItsCopiesOnly() throws Exception {
        String here = loggedIn("alice", "alice-tc");
        String other = loggedIn("alice", "alice-tc");
        String bobs = loggedIn("bob", "bob-tc");

        ResponseEntity<?> out = login.logout(request(here));

        assertThat(HttpCookie.parse(out.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge()).isZero();
        assertThat(guard(here).getStatus()).as("a copy of this browser's cookie").isEqualTo(401);
        assertThat(guard(here).getContentAsString()).contains("\"sessionEnded\":true").contains(AuthInterceptor.ENDED);
        assertThat(((Map<?, ?>)login.me(request(here)).getBody()).get("sessionEnded")).isEqualTo(true);
        assertThat(auth.signedIn(request(here))).isEmpty();
        assertThat(guard(other).getStatus()).isEqualTo(200);
        assertThat(guard(bobs).getStatus()).isEqualTo(200);
    }

    @Test
    void aLogoutOutlivesARestart(@TempDir Path dir) throws Exception {
        String here = loggedIn("alice", "alice-tc");
        String other = loggedIn("alice", "alice-tc");
        login.logout(request(here));
        Path file = dir.resolve(logins.fileName());
        logins.saveTo(file);

        Logins restarted = new Logins(mapper, new UserDirectory(mapper));
        restarted.loadFrom(file);

        assertThat(restarted.live(codec.decode(here).orElseThrow())).isFalse();
        assertThat(restarted.live(codec.decode(other).orElseThrow())).isTrue();
        assertThat(logins.durable()).as("written within a second, not every 5 minutes").isTrue();
    }

    /**
     * A logout ended a copy of alice's cookie, yet the copy could still log out: each time alice logged in again, a
     * logout with the dead copy ended her new login.
     */
    @Test
    void aCopyALogoutEndedEndsNoLaterLogin() throws Exception {
        String copy = loggedIn("alice", "alice-tc");
        login.logout(request(copy));
        String later = loggedIn("alice", "alice-tc");

        login.logout(request(copy));

        assertThat(guard(copy).getStatus()).isEqualTo(401);
        assertThat(guard(later).getStatus()).isEqualTo(200);
    }

    /** alice revoked the TeamCity token of a session a copy of her cookie carried; her login on a new token goes on. */
    @Test
    void aCopyOnARevokedTokenEndsNoOtherLogin() throws Exception {
        when(warmer.tokenRevoked("alice-old-tc")).thenReturn(true);
        String live = loggedIn("alice", "alice-new-tc");

        login.logout(request(loggedIn("alice", "alice-old-tc")));

        assertThat(guard(live).getStatus()).isEqualTo(200);
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

    /** The cookie of a new login of the user. */
    private String loggedIn(String user, String tcToken) {
        return codec.encode(new SessionCodec.Session(user, tcToken).withLogin(logins.start(user, "Firefox").id()));
    }

    private String cookie(String user, long issuedAt) {
        return cookie(user, user + "-tc", issuedAt);
    }

    private String cookie(String user, String tcToken, long issuedAt) {
        return codec.encryptString("{\"username\":\"" + user + "\",\"token\":\"" + tcToken + "\",\"iat\":" + issuedAt
            + "}");
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
