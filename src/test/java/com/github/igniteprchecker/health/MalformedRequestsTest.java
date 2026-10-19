package com.github.igniteprchecker.health;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.web.Logins;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * A scanner's request turned the status page red for 6 hours: on Tomcat 11 a broken %-escape in the query or the form
 * body ends the request with an ERROR and a stack trace, which counted as an error of the service. A form body of a
 * DELETE on any path did the same, and a path with "../" turned it amber for an hour. The ERROR quoted the parameter,
 * so a JIRA token in a form body got into the log and the operators' journal. Tomcat parses the requests as on the
 * server, which MockMvc does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "persist.enabled=false",
    "warm.enabled=false",
    "automation.enabled=false",
    "update.enabled=false",
    "server.address=127.0.0.1",
    "session.secret=malformed-requests",
    "github.api-url=http://127.0.0.1:9",
    "jira.base-url=http://127.0.0.1:9",
    "teamcity.base-url=http://127.0.0.1:9/",
})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class MalformedRequestsTest {
    private static final String JIRA_PAT = "NzQ1MjFAKEjiraPAT0123456789abcdef";

    @LocalServerPort
    private int port;

    @Autowired
    private LogTracker logs;

    @Autowired
    private SessionCodec codec;

    @Autowired
    private Logins logins;

    @Test
    void aBrokenEscapeInTheQueryIsTheCallersMistake(CapturedOutput output) throws Exception {
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("GET /api/top-flaky?limit=5&x=%zz HTTP/1.1\r\n", "");

        assertThat(answer).startsWith("HTTP/1.1 400").contains("\"error\":\"bad parameters\"");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
        assertThat(logs.snapshot().warnings()).isEqualTo(before.warnings());
        assertThat(output.getAll()).contains("Unreadable parameters of GET /api/top-flaky");
    }

    @Test
    void bytesThatAreNotUtf8InTheQueryAreTheCallersMistake() throws Exception {
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("GET /api/github/callback?code=%C0%AF&state=s HTTP/1.1\r\n", "");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
    }

    /** curl -d sends a form type unless told otherwise; the body with a JIRA token has a "%" Tomcat cannot decode. */
    @Test
    void aFormBodyWithABrokenEscapeLeavesItsTokenOutOfTheLog(CapturedOutput output) throws Exception {
        String login = logins.start("alice", "curl").id();
        String cookie = codec.encode(new SessionCodec.Session("alice", "alice-tc").withLogin(login));
        String body = "{\"jiraToken\":\"" + JIRA_PAT + "\",\"note\":\"100%\"}";
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("POST /api/auto-visa-all?visa=true HTTP/1.1\r\nCookie: IPRC_SESSION=" + cookie + "\r\n"
            + "Content-Type: application/x-www-form-urlencoded\r\n", body);

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
        assertThat(output.getAll()).doesNotContain("FAKEjiraPAT");
        assertThat(logs.snapshot().recent()).noneMatch(e -> e.message().contains("FAKEjiraPAT"));
    }

    /** The service has no PUT, PATCH or DELETE, yet a form body sent with one was parsed before the request was refused. */
    @Test
    void aFormBodyOfADeleteIsTheCallersMistake() throws Exception {
        LogTracker.Snapshot before = logs.snapshot();

        String answer = send("DELETE /api/logout HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n",
            "a=%zz");

        assertThat(answer).startsWith("HTTP/1.1 405");
        assertThat(logs.snapshot().errors()).isEqualTo(before.errors());
        assertThat(logs.snapshot().clientMistakes()).isEqualTo(before.clientMistakes() + 1);
    }

    @Test
    void aPathOutOfTheStaticFilesIsTheCallersMistake(CapturedOutput output) throws Exception {
        LogTracker.Snapshot before = logs.snapshot();

        send("GET /static/../application.yml HTTP/1.1\r\n", "");
        send("GET /api/x/../analyze HTTP/1.1\r\n", "");

        assertThat(output.getAll()).contains("Path contains \"../\"");
        assertThat(logs.snapshot().warnings()).isEqualTo(before.warnings());
        assertThat(logs.snapshot().clientMistakes()).isEqualTo(before.clientMistakes() + 2);
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
}
