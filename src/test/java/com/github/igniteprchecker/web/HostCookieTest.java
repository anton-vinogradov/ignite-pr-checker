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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The checker also answers at v888764.hosted-by-vdsina.com, and to a browser every other host at hosted-by-vdsina.com
 * is the same site. A page of such a host could set its own IPRC_SESSION for the whole domain: the visitor then worked
 * in its owner's session, and a GitHub token they pasted in ⚙ went into that session's options. Over HTTPS the cookie
 * is now __Host-IPRC_SESSION, which a browser keeps only as this host set it. A cookie an earlier version issued under
 * the old name is read once and issued under the new one, so nobody logs in again.
 */
class HostCookieTest {
    private static final String USER = "alice";

    private static final String TOKEN = "alice-tc";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties https = new SessionProperties(true, "test-secret");

    private final SessionCodec codec = new SessionCodec(https, mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final TcClient tc = mock(TcClient.class);

    private final AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), users,
        mock(StandingVisas.class), true);

    private final LoginController login = login(https);

    @Test
    void aLoginOverHttpsSetsACookieOnlyThisHostCanSet() {
        when(tc.currentUsername(TOKEN)).thenReturn(Optional.of(USER));

        List<String> set = login.login(new LoginController.LoginRequest(TOKEN), new MockHttpServletRequest())
            .getHeaders().get(HttpHeaders.SET_COOKIE);

        assertThat(set).singleElement().satisfies(c -> assertThat(c).startsWith("__Host-IPRC_SESSION=")
            .contains("; Path=/", "; Secure", "; HttpOnly").doesNotContain("Domain"));
        String overHttp = login(new SessionProperties(false, "test-secret"))
            .login(new LoginController.LoginRequest(TOKEN), new MockHttpServletRequest())
            .getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(overHttp).as("a browser refuses a __Host- cookie without Secure").startsWith("IPRC_SESSION=")
            .doesNotContain("Secure");
    }

    @Test
    void theCookieOfAnEarlierVersionIsReadOnceAndIssuedUnderTheNewName() throws Exception {
        String earlier = earlierCookie();

        MockHttpServletResponse guarded = new MockHttpServletResponse();
        assertThat(auth.preHandle(browser(AuthInterceptor.COOKIE, earlier), guarded, new Object())).isTrue();
        ResponseEntity<?> me = login.me(browser(AuthInterceptor.COOKIE, earlier));

        for (List<String> set : List.of(guarded.getHeaders(HttpHeaders.SET_COOKIE),
            me.getHeaders().get(HttpHeaders.SET_COOKIE))) {
            assertThat(set).hasSize(2);
            HttpCookie renamed = HttpCookie.parse(set.get(0)).get(0);
            assertThat(renamed.getName()).isEqualTo(SessionCookies.HOST_COOKIE);
            assertThat(codec.decode(renamed.getValue())).hasValueSatisfying(s -> {
                assertThat(s.username()).isEqualTo(USER);
                assertThat(s.token()).isEqualTo(TOKEN);
            });
            assertThat(set.get(1)).startsWith("IPRC_SESSION=;").contains("Max-Age=0");

            MockHttpServletResponse next = new MockHttpServletResponse();
            assertThat(auth.preHandle(browser(SessionCookies.HOST_COOKIE, renamed.getValue()), next, new Object()))
                .isTrue();
            assertThat(next.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        }
    }

    /** A cookie of this version set under the old name came from a page that planted it there. */
    @Test
    void aNewCookieUnderTheOldNameIsRefused() throws Exception {
        String planted = codec.encode(new SessionCodec.Session("mallory", "mallory-tc", null));

        MockHttpServletResponse guarded = new MockHttpServletResponse();
        assertThat(auth.preHandle(browser(AuthInterceptor.COOKIE, planted), guarded, new Object())).isFalse();
        assertThat(guarded.getStatus()).isEqualTo(401);
        assertThat(auth.signedIn(browser(AuthInterceptor.COOKIE, planted))).isEmpty();
        assertThat(login.me(browser(AuthInterceptor.COOKIE, planted)).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aLogoutRemovesTheCookieUnderBothNames() throws Exception {
        MockHttpServletRequest both = new MockHttpServletRequest();
        both.setCookies(new Cookie(SessionCookies.HOST_COOKIE, codec.encode(new SessionCodec.Session(USER, TOKEN,
            null))), new Cookie(AuthInterceptor.COOKIE, earlierCookie()));

        List<String> set = login.logout(true, both).getHeaders().get(HttpHeaders.SET_COOKIE);

        assertThat(set).hasSize(2);
        assertThat(set.get(0)).startsWith("__Host-IPRC_SESSION=;").contains("Max-Age=0");
        assertThat(set.get(1)).startsWith("IPRC_SESSION=;").contains("Max-Age=0");
    }

    /** The cookie as a version before the __Host- name issued it. */
    private String earlierCookie() {
        return codec.encryptString("{\"username\":\"" + USER + "\",\"token\":\"" + TOKEN + "\",\"iat\":"
            + System.currentTimeMillis() + "}");
    }

    private LoginController login(SessionProperties props) {
        return new LoginController(tc, codec, props, mock(Warmer.class), users, new LoginThrottle(),
            new AdminActions(new AdminProperties(null), mapper), mock(StandingVisas.class));
    }

    private static MockHttpServletRequest browser(String name, String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(name, cookie));

        return req;
    }
}
