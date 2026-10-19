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
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * After Log out the user's TeamCity token stayed in the pool the background reads borrow from, and went on reading
 * ci2 for everyone for up to an hour. A token the user's standing options lend the pool stays: the options run on it,
 * and the logout says so. The same goes for the logins the user ends in ⚙ from another browser, whose tokens the
 * server never sees again.
 */
class LogoutTokenPoolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), github,
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), warmer,
        mock(PendingCommits.class));

    private final UserDirectory users = new UserDirectory(mapper);

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    private final Logins logins = new Logins(mapper, users, now::get);

    private final TcClient tc = mock(TcClient.class);

    private final LoginController login = new LoginController(tc, codec, props, warmer, users, logins,
        new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper), standing);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, logins, standing);

    @Test
    void theTokenOfAUserWithoutOptionsLeavesThePool() throws Exception {
        MockHttpServletRequest browser = visit("alice", "alice-tc");

        ResponseEntity<Map<String, Object>> out = login.logout(browser);

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(warmer.tokenRevoked("alice-tc")).isFalse();
        assertThat(out.getBody()).isEmpty();
    }

    @Test
    void aTokenTheOptionsLendStaysAndTheLogoutSaysSo() throws Exception {
        standing.change("bob", "bob-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        MockHttpServletRequest browser = visit("bob", "bob-tc");

        ResponseEntity<Map<String, Object>> out = login.logout(browser);

        assertThat(warmer.borrowToken()).isEqualTo("bob-tc");
        assertThat(out.getBody()).containsEntry("note",
            LoginController.LOGGED_OUT_HERE + " " + LoginController.OPTIONS_GO_ON);
    }

    /** PR commands alone lend the token to nothing: it leaves the pool, and the commands go on with it. */
    @Test
    void theTokenOfPrCommandsAloneLeavesThePool() throws Exception {
        when(github.writes()).thenReturn(true);
        standing.linkGhLogin("carol", "carol-tc", new GithubClient.GhUser("carol-gh", 4242));
        MockHttpServletRequest browser = visit("carol", "carol-tc");

        ResponseEntity<Map<String, Object>> out = login.logout(browser);

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(out.getBody()).containsEntry("note",
            LoginController.LOGGED_OUT_HERE + " " + LoginController.OPTIONS_GO_ON);
    }

    /** Log out ended every browser of alice at once; it ends this one, and the answer says the others go on. */
    @Test
    void aLogoutSaysTheOtherLoginsGoOn() throws Exception {
        MockHttpServletRequest laptop = visit("alice", "alice-laptop-tc");
        visit("alice", "alice-desktop-tc");
        visit("alice", "alice-phone-tc");

        ResponseEntity<Map<String, Object>> out = login.logout(laptop);

        assertThat(out.getBody()).containsEntry("note",
            "You are logged out of this browser. You are still logged in on 2 other browsers.");
        assertThat(warmer.pooledTokens()).isEqualTo(2);
    }

    /** alice logged out of her laptop while her desktop stayed logged in: the note names one other browser. */
    @Test
    void aLogoutNamesTheOneOtherBrowser() throws Exception {
        MockHttpServletRequest laptop = visit("alice", "alice-laptop-tc");
        visit("alice", "alice-desktop-tc");

        ResponseEntity<Map<String, Object>> out = login.logout(laptop);

        assertThat(out.getBody()).containsEntry("note",
            "You are logged out of this browser. You are still logged in on 1 other browser.");
    }

    /**
     * alice ends the login of her desktop, and then every other login, from her laptop. The tokens of those logins
     * leave the pool; the one her options lend stays, and so do the laptop's and bob's.
     */
    @Test
    void theTokensOfTheLoginsEndedInSettingsLeaveThePool() throws Exception {
        standing.change("alice", "alice-options-tc", null, null,
            new StandingVisas.OptionChange(false, true, false, false, null));
        MockHttpServletRequest desktop = visit("alice", "alice-desktop-tc");
        visit("alice", "alice-options-tc");
        visit("alice", "alice-tablet-tc");
        visit("bob", "bob-tc");
        MockHttpServletRequest laptop = visit("alice", "alice-laptop-tc");
        String here = (String)laptop.getAttribute(AuthInterceptor.LOGIN_ATTR);

        login.endLogins(new LoginController.EndRequest(List.of(
            (String)desktop.getAttribute(AuthInterceptor.LOGIN_ATTR))), "alice", here);

        assertThat(warmer.pooledTokens()).isEqualTo(4);

        LoginController.LoginList left = login.endOtherLogins("alice", here);

        assertThat(left.ended()).isEqualTo(2);
        assertThat(left.logins()).singleElement().satisfies(l -> assertThat(l.current()).isTrue());
        assertThat(List.of(warmer.borrowToken(), warmer.borrowToken(), warmer.borrowToken()))
            .containsExactlyInAnyOrder("alice-options-tc", "alice-laptop-tc", "bob-tc");
    }

    /** alice logs in at the form and logs out before the page sends a request: her token leaves the pool too. */
    @Test
    void aLoginAndALogoutAtOnceLeaveNoTokenInThePool() {
        when(tc.currentUsername("alice-tc")).thenReturn(Optional.of("alice"));
        ResponseEntity<?> in = login.login(new LoginController.LoginRequest("alice-tc"), new MockHttpServletRequest());
        assertThat(warmer.pooledTokens()).isEqualTo(1);
        MockHttpServletRequest browser = new MockHttpServletRequest();
        browser.setCookies(new Cookie(AuthInterceptor.COOKIE,
            HttpCookie.parse(in.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue()));

        login.logout(browser);

        assertThat(warmer.pooledTokens()).isZero();
    }

    /**
     * alice's 51st login ends her login seen longest ago, which offered a token of its own. That token leaves the pool,
     * as it would if she ended that login in ⚙.
     */
    @Test
    void theTokenOfALoginPastTheLimitLeavesThePool() throws Exception {
        visit("alice", "alice-old-tc");
        for (int i = 0; i < Logins.KEPT_PER_USER; i++) {
            now.addAndGet(1000);
            visit("alice", "alice-tc");
        }

        assertThat(warmer.pooledTokens()).isEqualTo(1);
        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");
    }

    /**
     * The options in ⚙ are the account's, not a browser's: neither Log out nor ending the other logins switches one
     * off or unlinks the GitHub account, and a login in another browser shows the same.
     */
    @Test
    void noLogoutChangesTheOptions() throws Exception {
        when(github.writes()).thenReturn(true);
        standing.linkGhLogin("alice", "alice-tc", new GithubClient.GhUser("alice-gh", 4242));
        standing.change("alice", "alice-tc", null, null, new StandingVisas.OptionChange(false, true, true, null, true));
        StandingVisas.Settings before = standing.settings("alice");
        MockHttpServletRequest laptop = visit("alice", "alice-tc");
        visit("alice", "alice-tc");
        MockHttpServletRequest phone = visit("alice", "alice-tc");

        login.endOtherLogins("alice", (String)laptop.getAttribute(AuthInterceptor.LOGIN_ATTR));
        login.logout(laptop);
        login.logout(phone);

        assertThat(standing.settings("alice")).isEqualTo(before);
        assertThat(before.rerun() && before.gh() && before.commands()).isTrue();
        assertThat(before.login()).isEqualTo("alice-gh");
    }

    /**
     * The state was restored from a backup older than 2 days, which keeps no tokens: alice's re-run option is on and
     * waits for a TeamCity token. Her phone opens the page and logs out: that gives the option no token, the note says
     * nothing of options running on a stored token, and the option stays on. A login she ended in ⚙ gives it none
     * either. The next request of her laptop, whose login goes on, does, and then her logout leaves that token to the
     * option.
     */
    @Test
    void optionsRestoredWithoutATokenTakeOneOnlyFromALoginThatGoesOn(@TempDir Path dir) throws Exception {
        StandingVisas before = new StandingVisas(mapper, codec, mock(TcClient.class), github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
        before.change("alice", "alice-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        Path copy = dir.resolve("standing-visas.json.before-1.37.0-built-20261016-120000");
        before.saveTo(copy);
        TokenScrub.removeFrom(copy, now.get());
        standing.loadFrom(copy);
        String desktop = loggedIn("alice", "alice-desktop-tc");
        logins.end("alice", Set.of(codec.decode(desktop).orElseThrow().login()));
        String phone = loggedIn("alice", "alice-phone-tc");
        String laptop = loggedIn("alice", "alice-tc");

        assertThat(login.me(request(phone)).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> phoneOut = login.logout(request(phone));

        assertThat(phoneOut.getBody()).containsEntry("note",
            "You are logged out of this browser. You are still logged in on 1 other browser.");
        assertThat(warmer.pooledTokens()).isZero();
        assertThat(standing.rerunOn("alice")).isTrue();
        assertThat(auth.preHandle(request(desktop), new MockHttpServletResponse(), new Object())).isFalse();
        assertThat(standing.keepsTcToken("alice")).as("no token from a login that ended").isFalse();

        assertThat(auth.preHandle(request(laptop), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(standing.keepsTcToken("alice")).isTrue();
        ResponseEntity<Map<String, Object>> laptopOut = login.logout(request(laptop));

        assertThat(laptopOut.getBody()).containsEntry("note",
            LoginController.LOGGED_OUT_HERE + " " + LoginController.OPTIONS_GO_ON);
        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");
    }

    /** A request of a logged-in browser, which offers its token to the pool; its login is the request's attribute. */
    private MockHttpServletRequest visit(String user, String token) throws Exception {
        MockHttpServletRequest req = request(loggedIn(user, token));
        assertThat(auth.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        return req;
    }

    /** The cookie of a new login of the user on the token. */
    private String loggedIn(String user, String token) {
        String login = logins.start(user, "Firefox on Linux").id();

        return codec.encode(new SessionCodec.Session(user, token).withLogin(login));
    }

    private static MockHttpServletRequest request(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));

        return req;
    }
}
