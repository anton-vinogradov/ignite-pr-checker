package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * alice was logged in on her laptop, her desktop and a colleague's computer. Log out on the colleague's computer
 * ended all three, and nothing showed her the logins, nor ended one of them alone. Each login is now a record the
 * cookie names: Log out ends this browser's, ⚙ lists them and ends the ones she picks, or every one but this browser.
 * The cookies issued before the upgrade name no login, and each gets one at its first request, so nobody is logged out
 * by the upgrade itself.
 */
class LoginListTest {
    private static final long DAY = Duration.ofDays(1).toMillis();

    private static final String CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    private final Logins logins = new Logins(mapper, users, now::get);

    private final TcClient tc = mock(TcClient.class);

    private final AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), users, logins,
        mock(StandingVisas.class));

    private final LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
        mock(Warmer.class), users, logins, new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper),
        mock(StandingVisas.class));

    /**
     * A cookie v1.36.0 issued an hour ago, too fresh to be renewed for its age, goes on in a login of its own, and a
     * copy of it is the same login.
     */
    @Test
    void aCookieOfTheVersionBeforeGetsALoginAtItsFirstRequest() throws Exception {
        long hourAgo = now.get() - Duration.ofHours(1).toMillis();
        String before = earlierCookie("alice", hourAgo);

        MockHttpServletResponse first = guard(before, CHROME_MAC);
        MockHttpServletResponse copy = guard(before, "curl/8.4.0");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(copy.getStatus()).isEqualTo(200);
        String issued = issuedCookie(first);
        assertThat(codec.decode(issued).orElseThrow().login()).isEqualTo(codec.decode(issuedCookie(copy))
            .orElseThrow().login());
        assertThat(guard(issued, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(logins.of("alice")).singleElement().satisfies(l -> {
            assertThat(l.browser()).isEqualTo("Chrome on macOS");
            assertThat(l.earlierIssuedAt()).isEqualTo(hourAgo);
        });
        String formLogin = loggedIn("alice");
        assertThat(login.logins("alice", loginOf(issued)).logins())
            .as("⚙ cannot say when a login of the version before began")
            .extracting(LoginController.LoginView::id, LoginController.LoginView::earlier)
            .containsExactlyInAnyOrder(tuple(loginOf(issued), true), tuple(loginOf(formLogin), false));
    }

    /**
     * alice's cookie of v1.36.0 got a login after the upgrade, and she logged out; someone kept a copy of that cookie.
     * 402 days on, the ended login is forgotten, and the copy came back in a new login. No browser keeps a cookie that
     * long, so only a copy can bring one.
     */
    @Test
    void aCopyOfAnEarlierCookieStaysOutOnceItsLoginIsForgotten() throws Exception {
        String before = earlierCookie("alice", now.get() - DAY);
        login.logout(request(issuedCookie(guard(before, CHROME_MAC)), CHROME_MAC));

        now.addAndGet(Duration.ofDays(402).toMillis());
        loggedIn("bob");

        assertThat(guard(before, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(logins.live(codec.decode(before).orElseThrow())).isFalse();
        assertThat(logins.of("alice")).isEmpty();
    }

    /** A cookie of the version before, issued 402 days ago and never seen since the upgrade: no browser has it. */
    @Test
    void anEarlierCookieOlderThanABrowserKeepsOneGetsNoLogin() throws Exception {
        String kept = earlierCookie("alice", now.get() - Duration.ofDays(402).toMillis());

        assertThat(guard(kept, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(logins.live(codec.decode(kept).orElseThrow())).isFalse();
        assertThat(logins.of("alice")).isEmpty();
    }

    /**
     * A cookie of a local run from before cookies said when they were issued has no age to tell a copy by. Once the
     * login made for it ends, its copies stay out for good.
     */
    @Test
    void aCopyOfAnUndatedCookieStaysOutForGood() throws Exception {
        String undated = codec.encryptString("{\"username\":\"alice\",\"token\":\"alice-tc\"}");
        login.logout(request(issuedCookie(guard(undated, CHROME_MAC)), CHROME_MAC));

        now.addAndGet(Duration.ofDays(402).toMillis());
        loggedIn("bob");

        assertThat(guard(undated, CHROME_MAC).getStatus()).isEqualTo(401);
    }

    /**
     * A tab opened before the upgrade sent no request since, and alice pressed Log out in it. The checker had no login
     * for that cookie yet and ended nothing, so a copy of the cookie went on in a login of its own.
     */
    @Test
    void aLogoutOnAnEarlierCookieEndsItsCopies() throws Exception {
        String before = earlierCookie("alice", now.get() - DAY);

        login.logout(request(before, CHROME_MAC));

        assertThat(guard(before, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(logins.of("alice")).isEmpty();
    }

    /** The same browser logs in at the form over a cookie of the version before: a copy of that cookie stays out. */
    @Test
    void aLoginOverAnEarlierCookieEndsItsCopies() throws Exception {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of("alice"));
        String before = earlierCookie("alice", now.get() - DAY);

        ResponseEntity<?> again = login.login(new LoginController.LoginRequest("fresh-tc"),
            request(before, CHROME_MAC));

        String fresh = HttpCookie.parse(again.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
        assertThat(guard(before, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(logins.of("alice")).singleElement().satisfies(l -> assertThat(l.id()).isEqualTo(loginOf(fresh)));
    }

    /** v1.36.0's Log out ended every cookie of alice's issued until then: such a cookie gets no login after it. */
    @Test
    void aCookieTheVersionBeforeLoggedOutGetsNoLogin() throws Exception {
        String ended = earlierCookie("alice", now.get() - DAY);
        users.endSessions("alice");
        String later = earlierCookie("alice", now.get() + 1000);

        assertThat(guard(ended, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(later, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(logins.of("alice")).hasSize(1);
    }

    /** The login made for a cookie of the version before ended: a copy of that cookie must not make it again. */
    @Test
    void aCopyOfAnEarlierCookieWhoseLoginEndedStaysOut(@TempDir Path dir) throws Exception {
        String before = earlierCookie("alice", now.get() - DAY);
        String issued = issuedCookie(guard(before, CHROME_MAC));
        String laptop = loggedIn("alice");

        login.endLogins(new LoginController.EndRequest(List.of(codec.decode(issued).orElseThrow().login())), "alice",
            loginOf(laptop));
        Path file = dir.resolve(logins.fileName());
        logins.saveTo(file);
        Logins restarted = new Logins(mapper, new UserDirectory(mapper), now::get);
        restarted.loadFrom(file);

        assertThat(guard(before, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(issued, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(restarted.live(codec.decode(before).orElseThrow())).isFalse();
        assertThat(restarted.of("alice")).singleElement().satisfies(l -> assertThat(l.id()).isEqualTo(loginOf(laptop)));
    }

    /**
     * ⚙ of the laptop lists alice's three logins, the laptop first, and ends the desktop alone. Bob's login, and the
     * laptop's own, are not hers to end there.
     */
    @Test
    void settingsListTheLoginsAndEndTheChosenOnly() throws Exception {
        now.addAndGet(-DAY);
        String desktop = loggedIn("alice");
        now.addAndGet(DAY / 2);
        String phone = loggedIn("alice");
        now.addAndGet(DAY / 2);
        String laptop = loggedIn("alice");
        String bobs = loggedIn("bob");

        LoginController.LoginList listed = login.logins("alice", loginOf(laptop));

        assertThat(listed.logins()).extracting(LoginController.LoginView::id)
            .containsExactly(loginOf(laptop), loginOf(phone), loginOf(desktop));
        assertThat(listed.logins()).extracting(LoginController.LoginView::current).containsExactly(true, false, false);

        LoginController.LoginList left = login.endLogins(new LoginController.EndRequest(
            List.of(loginOf(desktop), loginOf(bobs), loginOf(laptop))), "alice", loginOf(laptop));

        assertThat(left.ended()).isEqualTo(1);
        assertThat(left.logins()).extracting(LoginController.LoginView::id)
            .containsExactly(loginOf(laptop), loginOf(phone));
        assertThat(guard(desktop, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(desktop, CHROME_MAC).getContentAsString()).contains(AuthInterceptor.ENDED);
        assertThat(guard(phone, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(guard(laptop, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(guard(bobs, CHROME_MAC).getStatus()).isEqualTo(200);
    }

    /**
     * alice's laptop was last seen four minutes ago: its last-seen time moves once in five minutes. Her desktop logged
     * in a minute ago. ⚙ of the laptop still lists the laptop first.
     */
    @Test
    void thisBrowserIsListedFirstEvenWhenAnotherWasSeenLater() {
        String laptop = loggedIn("alice");
        now.addAndGet(Duration.ofMinutes(3).toMillis());
        String desktop = loggedIn("alice");
        now.addAndGet(Duration.ofMinutes(1).toMillis());

        assertThat(login.logins("alice", loginOf(laptop)).logins()).extracting(LoginController.LoginView::id)
            .containsExactly(loginOf(laptop), loginOf(desktop));
    }

    /**
     * A page sent an id of null: the answer was a 500, and the log took the client's mistake for a fault of the
     * service.
     */
    @Test
    void aNullIdAmongTheLoginsToEndIsLeftOut() {
        String desktop = loggedIn("alice");
        String laptop = loggedIn("alice");

        LoginController.LoginList left = login.endLogins(
            new LoginController.EndRequest(Arrays.asList(null, loginOf(desktop))), "alice", loginOf(laptop));

        assertThat(left.ended()).isEqualTo(1);
        assertThat(left.logins()).extracting(LoginController.LoginView::id).containsExactly(loginOf(laptop));
    }

    /**
     * logins.json was set aside as unreadable, so the server starts without alice's login. Her browser goes to the
     * login form, which said the login was ended from another browser: nobody ended it.
     */
    @Test
    void aLoginTheServerLostNamesNoCauseItCannotKnow() throws Exception {
        String lost = codec.encode(new SessionCodec.Session("alice", "alice-tc").withLogin("lost-id"));

        MockHttpServletResponse resp = guard(lost, CHROME_MAC);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"sessionEnded\":true").contains(AuthInterceptor.ENDED)
            .doesNotContain("another browser");
    }

    /**
     * Log out everywhere else ends every login of alice's but this browser's, and the cookies of the version before
     * that no request brought since the upgrade.
     */
    @Test
    void everywhereElseLeavesThisBrowserOnly() throws Exception {
        String unseen = earlierCookie("alice", now.get() - DAY);
        String desktop = loggedIn("alice");
        String laptop = loggedIn("alice");
        String bobs = loggedIn("bob");

        LoginController.LoginList left = login.endOtherLogins("alice", loginOf(laptop));

        assertThat(left.ended()).isEqualTo(1);
        assertThat(left.logins()).singleElement().satisfies(l -> assertThat(l.current()).isTrue());
        assertThat(guard(desktop, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(unseen, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(laptop, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(guard(bobs, CHROME_MAC).getStatus()).isEqualTo(200);
    }

    /** A request writes the file: the last-seen time moves once in five minutes, not on every request. */
    @Test
    void aLoginInUseIsSeenAtMostOnceInFiveMinutes() throws Exception {
        String laptop = loggedIn("alice");
        long started = now.get();

        now.addAndGet(Duration.ofMinutes(4).toMillis());
        guard(laptop, CHROME_MAC);
        long afterFour = logins.of("alice").get(0).lastSeen();
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        guard(laptop, CHROME_MAC);

        assertThat(afterFour).isEqualTo(started);
        assertThat(logins.of("alice").get(0).lastSeen()).isEqualTo(now.get());
    }

    /** A browser drops the cookie 400 days after the last answer that set it: its login is forgotten after that. */
    @Test
    void aLoginUnseenLongerThanTheBrowserKeepsItsCookieIsForgotten(@TempDir Path dir) throws Exception {
        loggedIn("alice");
        now.addAndGet(DAY);
        String kept = loggedIn("alice");
        Path file = dir.resolve(logins.fileName());
        logins.saveTo(file);

        now.addAndGet(Duration.ofDays(400).toMillis() + DAY / 2);
        Logins restarted = new Logins(mapper, new UserDirectory(mapper), now::get);
        restarted.loadFrom(file);

        assertThat(restarted.of("alice")).extracting(Logins.Login::id).containsExactly(loginOf(kept));
    }

    /**
     * A script of alice's logs in with curl every hour and keeps no cookie. Past 50 logins it ended her laptop's, the
     * login seen longest ago. A login past 50 ends the user's earliest seen login from the same kind of client first.
     */
    @Test
    void aScriptThatLogsInOnEveryRunEndsOnlyItsOwnLogins() throws Exception {
        String laptop = loggedIn("alice", "Firefox on Linux");
        now.addAndGet(Duration.ofHours(1).toMillis());
        String firstRun = loggedIn("alice", "curl");
        for (int i = 1; i < Logins.KEPT_PER_USER; i++) {
            now.addAndGet(Duration.ofHours(1).toMillis());
            loggedIn("alice", "curl");
        }

        assertThat(logins.of("alice")).hasSize(Logins.KEPT_PER_USER);
        assertThat(guard(laptop, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(guard(firstRun, CHROME_MAC).getStatus()).isEqualTo(401);
    }

    /**
     * Without a limit, a script that logs in on every run would grow the file for good. A login past 50 from a kind of
     * client no other login has ends the one seen longest ago.
     */
    @Test
    void theLoginBeyondFiftyEndsTheOneSeenLongestAgo() throws Exception {
        String oldest = loggedIn("alice", "browser 0");
        for (int i = 1; i <= Logins.KEPT_PER_USER; i++) {
            now.incrementAndGet();
            loggedIn("alice", "browser " + i);
        }

        assertThat(logins.of("alice")).hasSize(Logins.KEPT_PER_USER);
        assertThat(guard(oldest, CHROME_MAC).getStatus()).isEqualTo(401);
    }

    /**
     * alice logs in again in a browser that held her login: that login ends, so a copy of its cookie does not go on.
     * Bob's login in the same browser goes on, for him to end in ⚙.
     */
    @Test
    void aLoginEndsTheLoginOfTheSameUserItReplaces() throws Exception {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of("alice"));
        String old = loggedIn("alice");
        String bobs = loggedIn("bob");

        ResponseEntity<?> again = login.login(new LoginController.LoginRequest("fresh-tc"), request(old, CHROME_MAC));
        login.login(new LoginController.LoginRequest("fresh-tc"), request(bobs, CHROME_MAC));

        String fresh = HttpCookie.parse(again.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
        assertThat(guard(old, CHROME_MAC).getStatus()).isEqualTo(401);
        assertThat(guard(fresh, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(guard(bobs, CHROME_MAC).getStatus()).isEqualTo(200);
        assertThat(logins.of("alice")).hasSize(2).allSatisfy(l -> assertThat(l.browser()).isEqualTo("Chrome on macOS"));
    }

    /** The file of this release, as it reads back: a renamed field would log out the browser of every login. */
    @Test
    void theLoginsFileReadsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("logins.json");
        String saved = "[{\"id\":\"A1\",\"user\":\"alice\",\"browser\":\"Firefox on Linux\",\"createdAt\":"
            + (now.get() - DAY) + ",\"lastSeen\":" + now.get() + "},{\"id\":\"B2\",\"user\":\"alice\",\"browser\":"
            + "\"Safari on macOS\",\"createdAt\":" + now.get() + ",\"lastSeen\":" + now.get() + ",\"earlierIssuedAt\":"
            + (now.get() - 2 * DAY) + ",\"endedAt\":" + now.get() + "}]";
        Files.writeString(file, saved);

        logins.loadFrom(file);
        logins.saveTo(dir.resolve("again.json"));

        assertThat(logins.live(new SessionCodec.Session("alice", "tc").withLogin("A1"))).isTrue();
        assertThat(logins.live(new SessionCodec.Session("alice", "tc", now.get() - 2 * DAY))).isFalse();
        assertThat(mapper.readTree(dir.resolve("again.json").toFile())).isEqualTo(mapper.readTree(saved));
    }

    /**
     * A copy of the state older than 2 days loses the tokens of every file in it. logins.json holds none, so the copy
     * keeps every login as it was: a state restored from it ends none of them, and a login that had ended stays ended.
     */
    @Test
    void aCopyOfTheLoginsKeepsThemAllOnceItsTokensGo(@TempDir Path dir) throws Exception {
        String laptop = loggedIn("alice");
        String phone = loggedIn("alice", "Safari on iOS");
        String bobsBefore = earlierCookie("bob", now.get() - DAY);
        login.logout(request(issuedCookie(guard(bobsBefore, CHROME_MAC)), CHROME_MAC));
        Path copy = dir.resolve("logins.json.before-1.37.0-built-20261016-120000");
        logins.saveTo(copy);
        String saved = Files.readString(copy);

        assertThat(TokenScrub.removeFrom(copy, now.get())).isFalse();
        assertThat(copy).hasContent(saved);

        Logins restored = new Logins(mapper, users, now::get);
        restored.loadFrom(copy);

        assertThat(restored.live(codec.decode(laptop).orElseThrow())).isTrue();
        assertThat(restored.live(codec.decode(phone).orElseThrow())).isTrue();
        assertThat(restored.live(codec.decode(bobsBefore).orElseThrow())).isFalse();
    }

    /** The cookie of a new login of the user. */
    private String loggedIn(String user) {
        return loggedIn(user, "Firefox");
    }

    /** The cookie of a new login of the user, from a browser with this label. */
    private String loggedIn(String user, String browser) {
        return codec.encode(new SessionCodec.Session(user, user + "-tc").withLogin(logins.start(user, browser).id()));
    }

    /** A cookie of v1.36.0, which names no login. */
    private String earlierCookie(String user, long issuedAt) {
        return codec.encryptString("{\"username\":\"" + user + "\",\"token\":\"" + user + "-tc\",\"iat\":" + issuedAt
            + ",\"v\":2}");
    }

    private String loginOf(String cookie) {
        return codec.decode(cookie).orElseThrow().login();
    }

    private MockHttpServletResponse guard(String cookie, String userAgent) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        auth.preHandle(request(cookie, userAgent), resp, new Object());

        return resp;
    }

    private static MockHttpServletRequest request(String cookie, String userAgent) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, cookie));
        req.addHeader(HttpHeaders.USER_AGENT, userAgent);

        return req;
    }

    private static String issuedCookie(MockHttpServletResponse resp) {
        return HttpCookie.parse(resp.getHeader(HttpHeaders.SET_COOKIE)).get(0).getValue();
    }
}
