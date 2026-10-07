package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Each login was one whoami call to ci2 from the server's address, however many came in, and any error answer
 * (a WAF 403, a 503 during a TeamCity restart) was reported as "TeamCity rejected this token".
 */
class LoginRateLimitTest {
    private final AtomicInteger whoami = new AtomicInteger();

    private volatile int answer = 401;

    private HttpServer teamcity;

    private LoginController login;

    @BeforeEach
    void start() throws IOException {
        teamcity = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        teamcity.createContext("/app/rest/users/current", ex -> {
            whoami.incrementAndGet();
            byte[] body = (answer == 200 ? "{\"username\":\"avinogradov\"}" : "{}").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(answer, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        teamcity.start();

        ObjectMapper mapper = new ObjectMapper();
        TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + teamcity.getAddress().getPort() + "/"),
            new AnalysisProperties(null, null, null, null, null, null, null), new Metrics(mapper));
        SessionProperties session = new SessionProperties(true, "test-secret");

        login = new LoginController(tc, new SessionCodec(session, mapper), session, mock(Warmer.class),
            new UserDirectory(mapper), new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper),
            mock(StandingVisas.class));
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

    @Test
    void aFloodFromOneAddressStopsReachingTeamCity() {
        for (int i = 0; i < 15; i++)
            post("guess-" + i, "203.0.113.7");

        assertThat(whoami).hasValue(10);
        assertThat(post("guess-15", "203.0.113.7")).isEqualTo(429);
        assertThat(post("guess-16", "198.51.100.1")).as("another address").isEqualTo(401);
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
}
