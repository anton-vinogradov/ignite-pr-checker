package com.github.igniteprchecker.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.github.igniteprchecker.LogCapture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * With LOGGING_LEVEL_ORG_APACHE_HC=TRACE in the env file, httpclient, the client of the GitHub calls, wrote each
 * request's headers and bytes to the log, "Authorization: Bearer ghp_…" included: the GitHub token of the checker's
 * account and those the users stored. Calls a local stub at TRACE.
 */
class GithubHeadersOutOfLogTest {
    private static final String GH = "ghp_FAKEfakeFAKEfake0123456789abcdefABCD";

    @Test
    void aGithubCallAtTraceLeavesNoTokenInTheLog() throws Exception {
        HttpServer github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        github.createContext("/", ex -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        github.start();
        RestClient client = RestClient.builder().requestFactory(OutboundHttp.withPatch(Duration.ofSeconds(5)))
            .baseUrl("http://127.0.0.1:" + github.getAddress().getPort()).build();

        try (LogCapture log = new LogCapture(Map.of("org.apache.hc", Level.TRACE))) {
            client.get().uri("/user").header("Authorization", "Bearer " + GH).retrieve().toBodilessEntity();

            assertThat(log.text()).contains("org.apache.hc.client5").doesNotContain(GH);
        }
        finally {
            github.stop(0);
        }
    }
}
