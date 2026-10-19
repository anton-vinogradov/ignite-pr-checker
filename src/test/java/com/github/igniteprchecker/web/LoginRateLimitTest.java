package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Each login was one whoami call to ci2 from the server's address, however many came in, and any error answer
 * (a WAF 403, a 503 during a TeamCity restart) was reported as "TeamCity rejected this token".
 */
class LoginRateLimitTest {
    /** The token TeamCity accepts whatever {@link #answer} says. */
    private static final String USER_TOKEN = "user-token";

    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

    private final AtomicInteger whoami = new AtomicInteger();

    private final AtomicLong now = new AtomicLong(1_000_000);

    private volatile int answer = 401;

    private HttpServer teamcity;

    private SessionCodec codec;

    private Logins logins;

    private LoginController login;

    @BeforeEach
    void start() throws IOException {
        teamcity = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        teamcity.createContext("/app/rest/users/current", ex -> {
            whoami.incrementAndGet();
            boolean user = ("Bearer " + USER_TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
            int status = user ? 200 : answer;
            byte[] body = (status == 200 ? "{\"username\":\"avinogradov\"}" : "{}").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        teamcity.start();

        ObjectMapper mapper = Jackson2Defaults.mapper();
        TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + teamcity.getAddress().getPort() + "/"),
            new AnalysisProperties(null, null, null, null, null, null, null), new Metrics(mapper));
        SessionProperties session = new SessionProperties(true, "test-secret");
        UserDirectory users = new UserDirectory(mapper);
        codec = new SessionCodec(session, mapper);
        logins = new Logins(mapper, users, now::get);

        login = new LoginController(tc, codec, session, mock(Warmer.class), users, logins, new LoginThrottle(now::get),
            new AdminActions(new AdminProperties(null), mapper), mock(StandingVisas.class));
    }

    @AfterEach
    void stop() {
        teamcity.stop(0);
    }

    private static MockHttpServletRequest from(String client) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", client);

        return req;
    }

    private int post(String token, String client) {
        return login.login(new LoginController.LoginRequest(token), from(client)).getStatusCode().value();
    }

    private static String error(ResponseEntity<?> res) {
        return String.valueOf(((Map<?, ?>)res.getBody()).get("error"));
    }

    /** A login at the form of a browser with this User-Agent, which sends {@code cookie} unless it is null. */
    private ResponseEntity<?> login(String token, String client, String userAgent, String cookie) {
        MockHttpServletRequest req = from(client);
        req.addHeader(HttpHeaders.USER_AGENT, userAgent);
        if (cookie != null)
            req.setCookies(new Cookie(SessionCookies.HOST_COOKIE, cookie));

        return login.login(new LoginController.LoginRequest(token), req);
    }

    private static String cookieOf(ResponseEntity<?> res) {
        return HttpCookie.parse(res.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).get(0).getValue();
    }

    private String loginOf(ResponseEntity<?> res) {
        return codec.decode(cookieOf(res)).orElseThrow().login();
    }

    @Test
    void aFloodFromOneAddressStopsReachingTeamCity() {
        for (int i = 0; i < 15; i++)
            post("guess-" + i, "203.0.113.7");

        assertThat(whoami).hasValue(3);
        assertThat(post("guess-15", "203.0.113.7")).isEqualTo(429);
        assertThat(post("guess-16", "198.51.100.1")).as("another address").isEqualTo(401);
    }

    /**
     * The wait read "Too many login attempts from your address", without the limit: a user who shares the address
     * with others could not tell why one attempt was too many.
     */
    @Test
    void theWaitSaysTheLimitOfTheAddressAndHowLong() {
        for (int i = 0; i < 3; i++)
            post("guess-" + i, "203.0.113.7");

        ResponseEntity<?> res = login.login(new LoginController.LoginRequest("guess-3"), from("203.0.113.7"));

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getHeaders().getFirst("Retry-After")).isIn("599", "600");
        assertThat(error(res)).isEqualTo("TeamCity rejected 3 tokens from your network address in the last 10"
            + " minutes. Try again in 10 minutes.");
    }

    /**
     * Every login TeamCity checked used an attempt of its address, the ones that worked too: four people behind one
     * office address logging in within 10 minutes left the fourth waiting with a good token.
     */
    @Test
    void loginsThatWorkCostTheirAddressNothing() {
        for (int i = 0; i < 5; i++)
            assertThat(post(USER_TOKEN, "203.0.113.7")).as("login " + i).isEqualTo(200);

        for (int i = 0; i < 3; i++)
            assertThat(post("guess-" + i, "203.0.113.7")).isEqualTo(401);
        assertThat(post("guess-3", "203.0.113.7")).isEqualTo(429);
    }

    /** A check TeamCity did not answer is no rejection: the address keeps the attempt for when TeamCity is back. */
    @Test
    void aCheckTeamCityDoesNotAnswerCostsNothing() {
        answer = 503;
        for (int i = 0; i < 4; i++)
            assertThat(post("guess-" + i, "203.0.113.7")).isEqualTo(502);

        answer = 401;
        for (int i = 4; i < 7; i++)
            assertThat(post("guess-" + i, "203.0.113.7")).isEqualTo(401);
    }

    @Test
    void theSharedWaitSaysItCountsEveryone() {
        for (int i = 0; i < 30; i++)
            assertThat(post("guess-" + i, "203.0.113." + i)).isEqualTo(401);

        ResponseEntity<?> res = login.login(new LoginController.LoginRequest("guess-30"), from("198.51.100.1"));

        assertThat(whoami).hasValue(30);
        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(error(res)).isEqualTo("The checker checks the tokens of at most 30 logins a minute with TeamCity, for"
            + " all users together. Logins in the last minute used all 30: try again in a minute.");
    }

    /**
     * A login the shared checks turned away still used one of its address's 3 attempts. 30 addresses that kept the
     * shared checks used up for 3 minutes locked out a user who tried again each minute, as told, for 7 minutes more.
     */
    @Test
    void aUserWhoWaitsOutTheSharedLimitAsToldLogsInWhenTheFloodStops() {
        for (int minute = 0; minute < 3; minute++) {
            for (int i = 0; i < 30; i++)
                assertThat(post("guess-" + minute + "-" + i, "203.0.113." + (minute * 10 + i / 3))).isEqualTo(401);

            assertThat(post(USER_TOKEN, "192.0.2.50")).isEqualTo(429);
            now.addAndGet(Duration.ofMinutes(1).toMillis());
        }

        assertThat(post(USER_TOKEN, "192.0.2.50")).isEqualTo(200);
        assertThat(whoami).hasValue(91);
    }

    @Test
    void aRejectedTokenIsCheckedOnce() {
        for (int i = 0; i < 3; i++)
            assertThat(post("dead-token", "203.0.113.7")).isEqualTo(401);

        assertThat(whoami).hasValue(1);
    }

    @Test
    void aTeamCityErrorIsNotTakenForARejection() {
        answer = 503;

        ResponseEntity<?> res = login.login(new LoginController.LoginRequest("good-token"), from("203.0.113.7"));

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat((Map<?, ?>)res.getBody()).extracting(b -> b.get("error")).asString().contains("HTTP 503");

        answer = 200;

        assertThat(post("good-token", "203.0.113.7")).isEqualTo(200);
        assertThat(whoami).hasValue(2);
    }

    /**
     * A login that works costs its address nothing, so the address does not bound the logins of a script that logs in
     * with curl on every run. The user's logins do: the script's 51st login ends its own first one, and the laptop's
     * login stays.
     */
    @Test
    void theLoginsThatWorkAreBoundedByTheUserNotByTheAddress() {
        String laptop = loginOf(login(USER_TOKEN, "192.0.2.50", FIREFOX, null));
        String firstRun = null;
        for (int run = 0; run < Logins.KEPT_PER_USER; run++) {
            now.addAndGet(Duration.ofSeconds(3).toMillis());
            ResponseEntity<?> res = login(USER_TOKEN, "203.0.113.7", "curl/8.4.0", null);
            assertThat(res.getStatusCode().value()).as("run " + run).isEqualTo(200);
            if (firstRun == null)
                firstRun = loginOf(res);
        }

        assertThat(logins.of("avinogradov")).hasSize(Logins.KEPT_PER_USER).extracting(Logins.Login::id)
            .contains(laptop).doesNotContain(firstRun);
    }

    /**
     * Tokens TeamCity rejects at the form of a logged-in browser, and the attempts past the address's limit, leave the
     * browser's login as it was. Only a token TeamCity accepts starts a new login, which ends the one it replaces.
     */
    @Test
    void aLoginThatFailsLeavesTheBrowsersLoginAsItWas() {
        ResponseEntity<?> first = login(USER_TOKEN, "203.0.113.7", FIREFOX, null);
        String cookie = cookieOf(first);
        String laptop = loginOf(first);

        for (int i = 0; i < 3; i++)
            assertThat(login("guess-" + i, "203.0.113.7", FIREFOX, cookie).getStatusCode().value()).as("guess " + i)
                .isEqualTo(401);
        assertThat(login(USER_TOKEN, "203.0.113.7", FIREFOX, cookie).getStatusCode().value()).isEqualTo(429);

        assertThat(logins.of("avinogradov")).extracting(Logins.Login::id).containsExactly(laptop);

        now.addAndGet(LoginThrottle.CLIENT_WINDOW.toMillis());
        ResponseEntity<?> again = login(USER_TOKEN, "203.0.113.7", FIREFOX, cookie);

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(logins.of("avinogradov")).extracting(Logins.Login::id).containsExactly(loginOf(again));
    }
}
