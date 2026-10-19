package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/**
 * ⚙ says how long the saved JIRA token has left. JIRA lists the user's Personal Access Tokens, each with its expiry
 * and without its value; a token that never expires has no expiry in the list.
 */
class JiraTokensListTest {
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @AfterEach
    void stopJira() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void theUsersTokensComeWithTheirExpiry() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jira/rest/pat/latest/tokens", ex -> {
            asked.add(ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("[{\"id\":12,\"name\":\"prc\",\"createdAt\":\"2026-09-01T12:00:00.000+0000\","
                + "\"expiringAt\":\"2026-12-01T12:00:00.000+0000\"},{\"id\":7,\"name\":\"ide\","
                + "\"createdAt\":\"2025-01-01T09:00:00.000+0300\"}]").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        JiraClient jira = new JiraClient("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/jira", Duration.ofSeconds(5), new Metrics(new ObjectMapper()));

        List<JiraClient.Pat> tokens = jira.tokens("jira-pat");

        assertThat(asked).containsExactly("GET Bearer jira-pat");
        assertThat(tokens).extracting(JiraClient.Pat::expiringAt)
            .containsExactly("2026-12-01T12:00:00.000+0000", null);
    }

    /**
     * A JIRA that does not list tokens to a token answers so, and the JIRA card on the status page turned amber at
     * each opening of ⚙ while visas went out as usual. Only trouble with JIRA counts as a failed call.
     */
    @Test
    void aListJiraWithholdsIsNotAFailedCall() throws IOException {
        int[] status = {401};
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jira/rest/pat/latest/tokens", ex -> {
            ex.sendResponseHeaders(status[0], -1);
            ex.close();
        });
        server.start();
        Metrics metrics = new Metrics(new ObjectMapper());
        JiraClient jira = new JiraClient("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/jira", Duration.ofSeconds(5), metrics);

        for (int withheld : new int[] {401, 403, 404}) {
            status[0] = withheld;
            assertThatThrownBy(() -> jira.tokens("jira-pat")).isInstanceOf(RestClientResponseException.class);
        }
        status[0] = 503;
        assertThatThrownBy(() -> jira.tokens("jira-pat")).isInstanceOf(RestClientResponseException.class);

        assertThat(metrics.jira().lastHour()).extracting(Metrics.Stats::total, Metrics.Stats::fail)
            .containsExactly(4L, 1L);
    }

    @Test
    void jiraTimesAreReadWithTheirOffset() {
        Instant december = Instant.parse("2026-12-01T12:00:00Z");

        assertThat(JiraClient.instant("2026-12-01T12:00:00.000+0000")).isEqualTo(december);
        assertThat(JiraClient.instant("2026-12-01T15:00:00.000+0300")).isEqualTo(december);
        assertThat(JiraClient.instant("2026-12-01T12:00:00+00:00")).isEqualTo(december);
        assertThat(JiraClient.instant("2026-12-01T12:00:00Z")).isEqualTo(december);
        assertThatThrownBy(() -> JiraClient.instant("2026-12-01T12:00:00")).isInstanceOf(DateTimeParseException.class);
    }
}
