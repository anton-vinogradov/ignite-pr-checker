package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
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
 * months after the last login. And a logout cleared only that browser's cookie: a copy of it, or the cookie in another
 * browser, went on working for good.
 */
class SessionLifetimeTest {
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(true, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), users,
        mock(StandingVisas.class));

    private final LoginController login = new LoginController(null, codec, new SessionProperties(true, "test-secret"),
        mock(Warmer.class), users, null, new AdminActions(new AdminProperties(List.of()), mapper), null);

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
        MockHttpServletResponse resp = guard(cookie("alice", System.currentTimeMillis() - DAY));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void theLoginCookieAsksForWhatBrowsersKeep() {
        String cookie = codec.encode(new SessionCodec.Session("alice", "alice-tc", null, null));

        ResponseEntity<?> me = login.me(codec.encryptString("{\"username\":\"alice\",\"token\":\"alice-tc\"}"));

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(HttpCookie.parse(me.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge())
            .isEqualTo(Duration.ofDays(400).toSeconds());
        assertThat(login.me(cookie).getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void aLogoutEndsEverySessionOfTheUserIssuedBefore() throws Exception {
        long before = System.currentTimeMillis() - DAY;
        String here = cookie("alice", before);
        String copy = cookie("alice", before + 1000);
        String bobs = cookie("bob", before);

        ResponseEntity<Void> out = login.logout(true, here);

        assertThat(HttpCookie.parse(out.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getMaxAge()).isZero();
        assertThat(guard(copy).getStatus()).isEqualTo(401);
        assertThat(guard(copy).getContentAsString()).contains("\"sessionEnded\":true")
            .contains(AuthInterceptor.ENDED);
        assertThat(login.me(copy).getStatusCode().value()).isEqualTo(401);
        assertThat(((Map<?, ?>)login.me(copy).getBody()).get("sessionEnded")).isEqualTo(true);
        assertThat(guard(bobs).getStatus()).isEqualTo(200);
        assertThat(guard(cookie("alice", System.currentTimeMillis() + 1000)).getStatus())
            .as("a login after the logout").isEqualTo(200);
        assertThat(auth.signedIn(request(copy))).isEmpty();
    }

    @Test
    void aLogoutOutlivesARestart(@TempDir Path dir) throws Exception {
        String copy = cookie("alice", System.currentTimeMillis() - DAY);
        login.logout(true, copy);
        Path file = dir.resolve(users.fileName());
        users.saveTo(file);

        UserDirectory restarted = new UserDirectory(mapper);
        restarted.loadFrom(file);

        assertThat(restarted.ended(codec.decode(copy).orElseThrow())).isTrue();
        assertThat(users.durable()).as("written within a second, not every 5 minutes").isTrue();
    }

    /** TeamCity refused the token of this browser's session: the user's sessions on other tokens go on. */
    @Test
    void aLogoutOfThisBrowserOnlyEndsNothingElse() throws Exception {
        String other = cookie("alice", System.currentTimeMillis() - DAY);

        login.logout(false, cookie("alice", System.currentTimeMillis() - DAY));

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
        return codec.encryptString("{\"username\":\"" + user + "\",\"token\":\"" + user + "-tc\",\"iat\":" + issuedAt
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
