package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * A poll of alice's page passed the login check at the moment she pressed Log out in the same browser. The logout took
 * her token out of the warming pool, then the poll put it back under the ended login, which no later logout names: the
 * token went on reading ci2 for everyone for up to an hour. The same could happen to the page as it opens and to a
 * login whose new login another browser ends in ⚙ at once.
 */
class LogoutRaceTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), mock(GithubClient.class),
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final UserDirectory users = new UserDirectory(mapper);

    /** What happens on another thread right after the login check of the next request, or the start of a login. */
    private final AtomicReference<Runnable> meanwhile = new AtomicReference<>();

    private final Logins logins = new Logins(mapper, users) {
        @Override
        public Optional<Login> enter(SessionCodec.Session session, String browser) {
            Optional<Login> login = super.enter(session, browser);
            runMeanwhile();

            return login;
        }

        @Override
        public synchronized Login start(String user, String browser) {
            Login login = super.start(user, browser);
            runMeanwhile();

            return login;
        }
    };

    private final TcClient tc = mock(TcClient.class);

    private final LoginController login = new LoginController(tc, codec, props, warmer, users, logins,
        new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper), mock(StandingVisas.class));

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, logins, mock(StandingVisas.class));

    @Test
    void aPollThatPassedJustBeforeLogOutLeavesNoTokenInThePool() throws Exception {
        String cookie = loggedIn();
        meanwhile.set(() -> login.logout(request(cookie)));

        assertThat(auth.preHandle(request(cookie), new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(logins.of("alice")).isEmpty();
        assertThat(warmer.pooledTokens()).isZero();
    }

    @Test
    void aPageThatOpensAsLogOutGoesLeavesNoTokenInThePool() {
        String cookie = loggedIn();
        meanwhile.set(() -> login.logout(request(cookie)));

        assertThat(login.me(request(cookie)).getStatusCode().value()).isEqualTo(200);

        assertThat(warmer.pooledTokens()).isZero();
    }

    /** Log out everywhere else in alice's laptop ends the login her desktop starts at that moment. */
    @Test
    void aLoginEndedAsItStartsLeavesNoTokenInThePool() {
        when(tc.currentUsername("alice-tc")).thenReturn(Optional.of("alice"));
        meanwhile.set(() -> logins.of("alice").forEach(l -> logins.end("alice", Set.of(l.id()))));

        assertThat(login.login(new LoginController.LoginRequest("alice-tc"), new MockHttpServletRequest())
            .getStatusCode().value()).isEqualTo(200);

        assertThat(logins.of("alice")).isEmpty();
        assertThat(warmer.pooledTokens()).isZero();
    }

    private void runMeanwhile() {
        Runnable r = meanwhile.getAndSet(null);
        if (r != null)
            r.run();
    }

    private String loggedIn() {
        return codec.encode(new SessionCodec.Session("alice", "alice-tc").withLogin(logins.start("alice", "Firefox")
            .id()));
    }

    private static MockHttpServletRequest request(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));

        return req;
    }
}
