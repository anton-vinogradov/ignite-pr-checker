package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ⚙ says how long the TeamCity token of the login has left. TeamCity lists the user's tokens under any of them, without
 * their values, but names their expiry only when the request asks for it: ci2 answered names only, and every token
 * read as one that never expires. A token that never expires has no expiry in the list.
 */
class OwnTokensQueryTest {
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/users/current/tokens", ex -> {
            asked.add(ex.getRequestURI() + " " + ex.getRequestHeaders().getFirst("Authorization"));
            boolean expiryAsked = String.valueOf(ex.getRequestURI().getRawQuery()).contains("expirationTime");
            byte[] body = (expiryAsked
                ? "{\"count\":2,\"token\":[{\"name\":\"prc\",\"expirationTime\":\"20261201T120000+0000\"},"
                    + "{\"name\":\"ide\"}]}"
                : "{\"count\":2,\"token\":[{\"name\":\"prc\"},{\"name\":\"ide\"}]}").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void theUsersTokensComeWithTheirExpiry() {
        String baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";
        TcClient tc = new TcClient(new TeamcityProperties(baseUrl),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null),
            new Metrics(Jackson2Defaults.mapper()));

        List<TcModel.Token> tokens = tc.ownTokens("tc-token");

        assertThat(asked).hasSize(1).allMatch(a -> a.endsWith(" Bearer tc-token"));
        assertThat(tokens).extracting(TcModel.Token::expirationTime).containsExactly("20261201T120000+0000", null);
        assertThat(TcDates.instant(tokens.get(0).expirationTime())).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));
    }
}
