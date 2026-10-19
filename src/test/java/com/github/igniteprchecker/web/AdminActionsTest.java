package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.update.UpdateService;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Restart (a System.exit), Update, Flush (re-warming 50 PRs on other users' tokens, thousands of TeamCity calls)
 * and the user list were open to anyone who logged in while PRC_ADMINS was not set, and anyone may register on ci2;
 * a cooldown only made them wait 10 minutes between presses.
 */
class AdminActionsTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final AtomicLong now = new AtomicLong(1_760_000_000_000L);

    private final UpdateService update = mock(UpdateService.class);

    private final AnalysisCache cache = mock(AnalysisCache.class);

    private AdminActions admin(String... operators) {
        return new AdminActions(new AdminProperties(List.of(operators)), mapper, now::get);
    }

    private static int status(ResponseEntity<?> res) {
        return res.getStatusCode().value();
    }

    private static Object error(ResponseEntity<?> res) {
        return ((Map<?, ?>)res.getBody()).get("error");
    }

    @Test
    void namedOperatorsAloneMayActAndNeedNoCooldown() {
        AdminActions admin = admin("AVinogradov");
        UpdateController ctl = new UpdateController(update, admin);
        CacheController flush = new CacheController(cache, mock(Warmer.class), admin);

        assertThat(status(ctl.restart("stranger"))).isEqualTo(403);
        assertThat(status(flush.flush("stranger"))).isEqualTo(403);
        assertThat(error(ctl.restart("stranger"))).isEqualTo("Only the operator can restart the service.");
        verify(update, never()).restart();
        verify(cache, never()).clear();

        assertThat(status(ctl.restart("avinogradov"))).isEqualTo(200);
        assertThat(status(ctl.restart("avinogradov"))).isEqualTo(200);
        verify(update, times(2)).restart();
    }

    @Test
    void namedOperatorsAloneSeeTheUsers() {
        LoginController login = new LoginController(null, null, null, null, new UserDirectory(mapper), logins(), null,
            admin("avinogradov"), mock(StandingVisas.class));

        assertThat(status(login.users("stranger"))).isEqualTo(403);
        assertThat(status(login.users("avinogradov"))).isEqualTo(200);
    }

    /**
     * The Users tab is the operators': who uses the service, and when. The logins in ⚙ are each user's own, an
     * operator's too: the user list names no browser, and nobody lists or ends another user's logins.
     */
    @Test
    void theUsersAreTheOperatorsAndTheLoginsEachUsersOwn() throws Exception {
        UserDirectory users = new UserDirectory(mapper);
        Logins logins = new Logins(mapper, users);
        LoginController login = new LoginController(null, null, null, mock(Warmer.class), users, logins, null,
            admin("avinogradov"), mock(StandingVisas.class));
        users.touchLogin("alice");
        String alices = logins.start("alice", "Chrome on macOS").id();
        String operators = logins.start("avinogradov", "Firefox on Linux").id();

        assertThat(status(login.users("alice"))).isEqualTo(403);
        assertThat(mapper.writeValueAsString(login.users("avinogradov").getBody())).contains("\"alice\"")
            .doesNotContain("Chrome on macOS", alices);
        assertThat(login.logins("alice", alices).logins()).extracting(LoginController.LoginView::id)
            .containsExactly(alices);
        assertThat(login.logins("avinogradov", operators).logins()).extracting(LoginController.LoginView::id)
            .containsExactly(operators);
        assertThat(login.endLogins(new LoginController.EndRequest(List.of(alices)), "avinogradov", operators).ended())
            .isZero();
        assertThat(logins.of("alice")).extracting(Logins.Login::id).containsExactly(alices);
    }

    /** The page shows Restart, Update and Flush only to whom /api/me or the login answer calls an admin. */
    @Test
    void onlyNamedOperatorsAreToldTheyAreAdmins() {
        SessionProperties props = new SessionProperties(true, "test-secret");
        SessionCodec codec = new SessionCodec(props, mapper);
        TcClient tc = mock(TcClient.class);
        when(tc.currentUsername("stranger-token")).thenReturn(Optional.of("stranger"));
        when(tc.currentUsername("operator-token")).thenReturn(Optional.of("AVinogradov"));
        LoginController login = new LoginController(tc, codec, props, mock(Warmer.class), new UserDirectory(mapper),
            logins(), new LoginThrottle(), admin("avinogradov"), mock(StandingVisas.class));

        assertThat(toldAdmin(login.login(token("stranger-token"), new MockHttpServletRequest()))).isFalse();
        assertThat(toldAdmin(login.login(token("operator-token"), new MockHttpServletRequest()))).isTrue();
        assertThat(toldAdmin(login.me(overHttps(codec.encode("stranger", "stranger-token"))))).isFalse();
        assertThat(toldAdmin(login.me(overHttps(codec.encode("AVinogradov", "operator-token"))))).isTrue();
    }

    @Test
    void withoutOperatorsNobodyMayActOrSeeTheUsers() throws Exception {
        AdminActions admin = admin();
        UpdateController ctl = new UpdateController(update, admin);
        CacheController flush = new CacheController(cache, mock(Warmer.class), admin);
        LoginController users = new LoginController(null, null, null, null, new UserDirectory(mapper), logins(), null,
            admin, mock(StandingVisas.class));

        assertThat(status(ctl.restart("alice"))).isEqualTo(403);
        assertThat(status(ctl.update("alice"))).isEqualTo(403);
        assertThat(status(flush.flush("alice"))).isEqualTo(403);
        assertThat(status(users.users("alice"))).isEqualTo(403);
        verify(update, never()).restart();
        verify(update, never()).performUpdate();
        verify(cache, never()).clear();
        assertThat(admin.lastUses()).isEmpty();
    }

    @Test
    void withoutOperatorsNobodyIsToldTheyAreAnAdmin() {
        SessionProperties props = new SessionProperties(true, "test-secret");
        SessionCodec codec = new SessionCodec(props, mapper);
        TcClient tc = mock(TcClient.class);
        when(tc.currentUsername("alice-token")).thenReturn(Optional.of("alice"));
        LoginController login = new LoginController(tc, codec, props, mock(Warmer.class), new UserDirectory(mapper),
            logins(), new LoginThrottle(), admin(), mock(StandingVisas.class));

        assertThat(toldAdmin(login.login(token("alice-token"), new MockHttpServletRequest()))).isFalse();
        assertThat(toldAdmin(login.me(overHttps(codec.encode("alice", "alice-token"))))).isFalse();
    }

    private Logins logins() {
        return new Logins(mapper, new UserDirectory(mapper));
    }

    /** A request from a browser that keeps the session cookie a checker behind HTTPS sets. */
    private static MockHttpServletRequest overHttps(String cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(SessionCookies.HOST_COOKIE, cookie));

        return req;
    }

    private static LoginController.LoginRequest token(String token) {
        return new LoginController.LoginRequest(token);
    }

    private static boolean toldAdmin(ResponseEntity<?> res) {
        return ((LoginController.UserResponse)res.getBody()).admin();
    }

    @Test
    void aFailedUpdateIsNotNamedAsTheLast() throws Exception {
        AdminActions admin = admin("alice");
        UpdateController ctl = new UpdateController(update, admin);
        doThrow(new IllegalStateException("no update available")).when(update).performUpdate();

        assertThat(status(ctl.update("alice"))).isEqualTo(400);
        assertThat(admin.lastUses()).isEmpty();
    }

    @Test
    void aFailedUpdateKeepsTheLastOneThatHappened() throws Exception {
        AdminActions admin = admin("carol", "bob");
        UpdateController ctl = new UpdateController(update, admin);
        long carolsAt = now.get();

        assertThat(status(ctl.update("carol"))).isEqualTo(200);

        now.addAndGet(Duration.ofMinutes(11).toMillis());
        doThrow(new IllegalStateException("no update available")).when(update).performUpdate();

        assertThat(status(ctl.update("bob"))).isEqualTo(400);
        assertThat(admin.lastUses().get("update")).isEqualTo(new AdminActions.Use("carol", carolsAt));
    }

    /** A restart exits the JVM: its record has to come back from disk, or the status page could not name who did it. */
    @Test
    void theLastUsesSurviveTheRestartTheyCause(@TempDir Path dir) throws IOException {
        AdminActions before = admin("alice");
        new UpdateController(update, before).restart("alice");
        Path file = dir.resolve(before.fileName());
        before.saveTo(file);

        AdminActions after = admin("alice");
        after.loadFrom(file);

        assertThat(after.lastUses()).containsOnlyKeys("restart");
        assertThat(after.lastUses().get("restart")).isEqualTo(new AdminActions.Use("alice", now.get()));
    }

    @Test
    void readsTheFileFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("admin-actions.json");
        Files.writeString(file, "{\"restart\":{\"by\":\"alice\",\"at\":1760000000000},"
            + "\"flush\":{\"by\":\"bob\",\"at\":1759999000000},\"unknown\":{\"by\":\"x\",\"at\":1}}");

        AdminActions admin = admin();
        admin.loadFrom(file);

        assertThat(admin.lastUses()).containsOnlyKeys("restart", "flush");
        assertThat(admin.lastUses().get("restart")).isEqualTo(new AdminActions.Use("alice", 1_760_000_000_000L));
        assertThat(admin.lastUses().get("flush")).isEqualTo(new AdminActions.Use("bob", 1_759_999_000_000L));
    }
}
