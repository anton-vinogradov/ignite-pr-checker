package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

/**
 * ASF JIRA serves a dead token as an anonymous user (JRASERVER-78126), so a failed visa does not say whether the token
 * is dead, and the checker took a JIRA outage for a rejected token when one was saved. /myself answers an anonymous
 * caller 401: it is asked, and only its 401 rejects a token.
 */
class JiraTokenCheckTest {
    private HttpServer server;

    @AfterEach
    void stopJira() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void myselfRejectsOnlyOn401() throws IOException {
        JiraClient jira = jira(Map.of("good", 200, "dead", 401, "down", 503));

        assertThat(jira.myself("good")).contains("Anton Vinogradov");
        assertThat(jira.myself("dead")).isEmpty();
        assertThatThrownBy(() -> jira.myself("down")).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    }

    @Test
    void aTokenIsCalledRejectedOnlyWhenMyselfSays401() throws IOException {
        JiraClient jira = jira(Map.of("good", 200, "dead", 401, "down", 503));

        assertThat(jira.tokenRejected("dead")).isTrue();
        assertThat(jira.tokenRejected("good")).isFalse();
        assertThat(jira.tokenRejected("down")).isFalse();
    }

    /** JIRA answering /myself with what each Bearer token gets. */
    private JiraClient jira(Map<String, Integer> statusByToken) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jira/rest/api/2/myself", ex -> {
            String token = ex.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length());
            int status = statusByToken.getOrDefault(token, 401);
            byte[] body = (status == 200 ? "{\"name\":\"avinogradov\",\"displayName\":\"Anton Vinogradov\"}" : "{}")
                .getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        return new JiraClient("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
            + "/jira", Duration.ofSeconds(10), new Metrics(new ObjectMapper()));
    }
}
