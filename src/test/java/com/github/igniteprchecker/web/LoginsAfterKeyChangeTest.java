package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator set a new SESSION_SECRET: every cookie of the old key reads as no cookie, yet ⚙ went on listing the
 * logins made under it as live. alice logged in again in Chrome and saw three logins, and Log out told her she was
 * still logged in on 2 other browsers that would never work again. Each login now names the key of its cookie, and
 * the logins of another key are not loaded.
 */
class LoginsAfterKeyChangeTest {
    private static final String CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private static final String FIREFOX_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 "
        + "Firefox/131.0";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final TcClient tc = mock(TcClient.class);

    @Test
    void theLoginsOfTheOldKeyAreGoneAfterTheKeyChanged(@TempDir Path dir) throws Exception {
        when(tc.currentUsername("alice-tc")).thenReturn(Optional.of("alice"));
        Server before = new Server("old-secret", dir);
        String chrome = cookieOf(before.login.login(new LoginController.LoginRequest("alice-tc"),
            req(null, CHROME_MAC)));
        String firefox = cookieOf(before.login.login(new LoginController.LoginRequest("alice-tc"),
            req(null, FIREFOX_WIN)));
        before.save();

        Server after = new Server("new-secret", dir);

        assertThat(after.guard(chrome, CHROME_MAC)).isEqualTo(401);
        assertThat(after.guard(firefox, FIREFOX_WIN)).isEqualTo(401);
        String again = cookieOf(after.login.login(new LoginController.LoginRequest("alice-tc"),
            req(chrome, CHROME_MAC)));
        String here = after.codec.decode(again).orElseThrow().login();

        assertThat(after.login.logins("alice", here).logins()).singleElement()
            .satisfies(l -> assertThat(l.current()).isTrue());
        assertThat(after.login.logout(req(again, CHROME_MAC)).getBody()).isEmpty();
    }

    /**
     * The logins.json of v1.42.0 names no key. Its logins were made under the key the server starts with, as a change
     * of the key logs everyone out: they stay, and a later change of the key drops them too.
     */
    @Test
    void theLoginsOfAnEarlierVersionStayAndGoWithTheNextKey(@TempDir Path dir) throws Exception {
        long seen = System.currentTimeMillis() - 60_000;
        Files.writeString(dir.resolve("logins.json"), "[{\"id\":\"laptop-login\",\"user\":\"alice\","
            + "\"browser\":\"Chrome on macOS\",\"createdAt\":" + seen + ",\"lastSeen\":" + seen + "},"
            + "{\"id\":\"old-cookie-login\",\"user\":\"alice\",\"browser\":\"Firefox on Windows\",\"createdAt\":" + seen
            + ",\"lastSeen\":" + seen + ",\"earlierIssuedAt\":" + (seen - 3_600_000) + "}]", UTF_8);
        Server same = new Server("test-secret", dir);
        String laptop = same.codec.encode(new SessionCodec.Session("alice", "alice-tc").withLogin("laptop-login"));

        assertThat(same.logins.of("alice")).extracting(Logins.Login::id)
            .containsExactlyInAnyOrder("laptop-login", "old-cookie-login");
        assertThat(same.guard(laptop, CHROME_MAC)).isEqualTo(200);
        same.save();

        assertThat(new Server("new-secret", dir).logins.of("alice")).isEmpty();
    }

    /** The checker as it starts on the cache directory {@code dir} with the secret {@code secret}. */
    private final class Server {
        final SessionCodec codec;

        final UserDirectory users = new UserDirectory(mapper);

        final Logins logins;

        final LoginController login;

        final AuthInterceptor auth;

        final Path dir;

        Server(String secret, Path dir) throws IOException {
            SessionProperties props = new SessionProperties(false, secret);
            this.dir = dir;
            codec = new SessionCodec(props, mapper);
            users.loadFrom(dir.resolve("users.json"));
            logins = new Logins(mapper, users, codec);
            logins.loadFrom(dir.resolve("logins.json"));
            login = new LoginController(tc, codec, props, mock(Warmer.class), users, logins, new LoginThrottle(),
                new AdminActions(new AdminProperties(null), mapper), mock(StandingVisas.class));
            auth = new AuthInterceptor(codec, mock(Warmer.class), users, logins, mock(StandingVisas.class));
        }

        void save() throws IOException {
            users.saveTo(dir.resolve("users.json"));
            logins.saveTo(dir.resolve("logins.json"));
        }

        int guard(String cookie, String userAgent) throws Exception {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            auth.preHandle(req(cookie, userAgent), resp, new Object());

            return resp.getStatus();
        }
    }

    private static MockHttpServletRequest req(String cookie, String userAgent) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (cookie != null)
            r.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));
        r.addHeader(HttpHeaders.USER_AGENT, userAgent);

        return r;
    }

    private static String cookieOf(ResponseEntity<?> resp) {
        return HttpCookie.parse(resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
    }
}
