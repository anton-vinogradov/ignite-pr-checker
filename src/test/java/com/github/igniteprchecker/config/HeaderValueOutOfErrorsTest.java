package com.github.igniteprchecker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A script logged in with a token that had a line break in it. The JDK's HTTP client refused the header and named it
 * in its error, "Illegal character(s) in message header value: Bearer <token>"; the login answered 500, and the error
 * went into the log with the token. The same came of a JIRA token. Both clients call a closed local port: the header
 * is refused before any connection.
 */
class HeaderValueOutOfErrorsTest {
    private static final String BROKEN = "FAKEpartOne0123456789\nFAKEpartTwo0123456789";

    private final Metrics metrics = new Metrics(new ObjectMapper());

    @Test
    void aRefusedHeaderComesBackWithoutItsValue() {
        TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:9/"),
            new AnalysisProperties(null, null, null, null, null, null, null), metrics);
        JiraClient jira = new JiraClient("http://127.0.0.1:9", Duration.ofSeconds(1), metrics);

        assertRefusedUnnamed(() -> tc.currentUsername(BROKEN), "TeamCity");
        assertRefusedUnnamed(() -> jira.myself(BROKEN), "JIRA");
    }

    private static void assertRefusedUnnamed(Executable call, String service) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));

        assertThat(e.getMessage()).isEqualTo(service + " was not called: a request header has a character HTTP does "
            + "not allow");
        assertThat(trace.toString()).doesNotContain("FAKEpart");
    }
}
