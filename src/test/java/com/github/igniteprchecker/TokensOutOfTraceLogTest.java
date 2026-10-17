package com.github.igniteprchecker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.igniteprchecker.session.SessionCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With LOGGING_LEVEL_ROOT=DEBUG in the env file, every call to TeamCity and JIRA wrote its request headers to the log,
 * "Authorization: Bearer <token>" among them, and Tomcat wrote each request as it came in, with the token of a login
 * and the session cookie. Both the JDK's HttpURLConnection, the client of those calls, and Tomcat log through
 * java.util.logging, which Spring Boot keeps at the levels of the service's log. Runs the service at TRACE, with
 * TeamCity a local stub that refuses every token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "persist.enabled=false",
    "warm.enabled=false",
    "automation.enabled=false",
    "update.enabled=false",
    "server.address=127.0.0.1",
    "session.secret=trace-test",
    "github.api-url=http://127.0.0.1:9",
    "jira.base-url=http://127.0.0.1:9",
    "logging.level.root=TRACE",
})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class TokensOutOfTraceLogTest {
    private static final String TOKEN = "eyJ0eXAiOiAiVENWMiJ9.FAKEtcBODY0123456789.FAKEtcSIGN0123456789";

    private static final List<String> SEEN_BY_TEAMCITY = new CopyOnWriteArrayList<>();

    private static HttpServer teamcity;

    private static Level rootBefore;

    @LocalServerPort
    private int port;

    @Autowired
    private SessionCodec codec;

    @BeforeAll
    static void rememberTheLevel() {
        rootBefore = root().getLevel();
    }

    @DynamicPropertySource
    static void teamcity(DynamicPropertyRegistry registry) throws IOException {
        teamcity = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        teamcity.createContext("/", ex -> {
            SEEN_BY_TEAMCITY.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        teamcity.start();
        registry.add("teamcity.base-url", () -> "http://127.0.0.1:" + teamcity.getAddress().getPort() + "/");
    }

    /** The other tests of the run go on at the level they had. */
    @AfterAll
    static void putTheLevelBack() {
        teamcity.stop(0);
        root().setLevel(rootBefore);
    }

    /** The login goes through the JDK's HTTP client, which writes nothing to the log of the service. */
    @Test
    void aLoginLeavesNoTokenInTheLog(CapturedOutput output) throws Exception {
        HttpRequest login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + TOKEN + "\"}")).build();

        HttpResponse<String> res = HttpClient.newHttpClient().send(login, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(SEEN_BY_TEAMCITY).contains("Bearer " + TOKEN);
        assertThat(output.getAll()).contains(" TRACE ").doesNotContain("FAKEtcBODY", "FAKEtcSIGN");
    }

    /** The session cookie lets anyone who has it act as its user, like the token inside it. */
    @Test
    void aSignedInRequestLeavesNoCookieInTheLog(CapturedOutput output) throws Exception {
        String cookie = codec.encode(new SessionCodec.Session("alice", TOKEN, null));
        HttpRequest me = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me"))
            .header("Cookie", "IPRC_SESSION=" + cookie).GET().build();

        HttpResponse<String> res = HttpClient.newHttpClient().send(me, HttpResponse.BodyHandlers.ofString());

        assertThat(res.body()).contains("alice");
        assertThat(output.getAll()).contains(" TRACE ").doesNotContain(cookie.substring(0, 24), "FAKEtcBODY");
    }

    private static Logger root() {
        return ((LoggerContext)LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
