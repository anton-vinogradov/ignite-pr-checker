package com.github.igniteprchecker.health;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.web.Logins;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The operator set LOGGING_LEVEL_ROOT=DEBUG to look into a problem. At DEBUG Spring reads the parameters of each
 * request to log it, before any handler runs, so a scanner's broken %-escape in the query ended the request with an
 * ERROR from Tomcat. The status page turned red for 6 hours, and the journal of problems got the parameter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "persist.enabled=false",
    "warm.enabled=false",
    "automation.enabled=false",
    "update.enabled=false",
    "server.address=127.0.0.1",
    "session.secret=malformed-requests-at-debug",
    "github.api-url=http://127.0.0.1:9",
    "jira.base-url=http://127.0.0.1:9",
    "teamcity.base-url=http://127.0.0.1:9/",
    "logging.level.root=DEBUG",
})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class MalformedRequestsAtDebugTest {
    private static final String JIRA_PAT = "NzQ1MjFAKEjiraPAT0123456789abcdef";

    private static Level rootBefore;

    @LocalServerPort
    private int port;

    @Autowired
    private LogTracker logs;

    @Autowired
    private SessionCodec codec;

    @Autowired
    private Logins logins;

    @BeforeAll
    static void rememberTheLevel() {
        rootBefore = root().getLevel();
    }

    /** The other tests of the run go on at the level they had. */
    @AfterAll
    static void putTheLevelBack() {
        root().setLevel(rootBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/top-flaky?limit=5&x=%zz", "/api/me?x=%zz", "/index.html?x=%zz",
        "/api/nothing-here?x=%zz", "/api/github/callback?code=FAKEoauthCODE123&state=s%zz"})
    void aBrokenEscapeInTheQueryIsNoErrorOfTheService(String target) throws Exception {
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("GET " + target + " HTTP/1.1\r\n", "");

        assertThat(answer).startsWith("HTTP/1.1 400").contains("\"error\":\"bad parameters\"");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
        assertThat(logs.snapshot().warnings()).isEqualTo(before.warnings());
        assertThat(logs.snapshot().recent()).noneMatch(e -> e.message().contains("%zz"));
    }

    /**
     * Spring reads no form body to log the request, so a handler reads it, and the exception that quotes the token must
     * end there. Past the handlers Spring would log it at DEBUG.
     */
    @Test
    void aFormBodyWithABrokenEscapeLeavesItsTokenOutOfTheLog(CapturedOutput output) throws Exception {
        String login = logins.start("alice", "curl").id();
        String cookie = codec.encode(new SessionCodec.Session("alice", "alice-tc").withLogin(login));
        String body = "{\"jiraToken\":\"" + JIRA_PAT + "\",\"note\":\"100%\"}";
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("POST /api/auto-visa-all?visa=true HTTP/1.1\r\nCookie: IPRC_SESSION=" + cookie + "\r\n"
            + "Content-Type: application/x-www-form-urlencoded\r\n", body);

        assertThat(answer).startsWith("HTTP/1.1 400").contains("\"error\":\"bad parameters\"");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
        assertThat(output.getAll()).contains(" DEBUG ").doesNotContain("FAKEjiraPAT");
    }

    /** Sends the request line and headers as they are, with {@code body}; answers what came back. */
    private String send(String head, String body) throws IOException, InterruptedException {
        byte[] content = body.getBytes(UTF_8);
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write((head + "Host: 127.0.0.1\r\nConnection: close\r\nContent-Length: " + content.length + "\r\n\r\n")
                .getBytes(ISO_8859_1));
            out.write(content);
            out.flush();
            InputStream in = s.getInputStream();
            String answer = new String(in.readAllBytes(), UTF_8);
            // The log of a request may come a moment after its answer.
            Thread.sleep(200);

            return answer;
        }
    }

    private static Logger root() {
        return ((LoggerContext)LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
