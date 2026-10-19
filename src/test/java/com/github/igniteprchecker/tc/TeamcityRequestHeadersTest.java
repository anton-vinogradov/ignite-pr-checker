package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ci2 firewall answers a GET that carries "Content-Length: 0" with 403 "Access Blocked". The JDK's HttpClient
 * sends that header on every GET, so a TeamCity client built on it could read nothing from ci2. Checks what TeamCity
 * gets, on a local server that records it: a GET carries no body headers, and a call with a body sends its JSON in
 * chunks, as the calls to ci2 have gone so far.
 */
class TeamcityRequestHeadersTest {
    private static final String TOKEN = "FAKEtcTOKEN0123456789";

    /** Each request as the server got it: the method, then the headers by lower-case name. */
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private TcClient tc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> seen = new TreeMap<>();
            seen.put(":method", ex.getRequestMethod());
            ex.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), String.join(",", values)));
            seen.put(":body", new String(ex.getRequestBody().readAllBytes(), UTF_8));
            requests.add(seen);
            byte[] body = "{\"username\":\"alice\",\"id\":5}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, null, null, null, null, null, null), new Metrics(null));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void aGetCarriesNoBodyHeaders() {
        assertThat(tc.currentUsername(TOKEN)).contains("alice");

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).containsEntry(":method", "GET").containsEntry("authorization", "Bearer " + TOKEN)
                .containsEntry("accept", "application/json")
                .containsEntry("user-agent", "Java/" + System.getProperty("java.version"))
                .doesNotContainKeys("content-length", "transfer-encoding", "content-type");
            assertThat(r.keySet()).containsExactlyInAnyOrder(":method", ":body", "accept", "authorization",
                "connection", "host", "user-agent");
        });
    }

    @Test
    void aCallWithABodySendsItsJsonInChunks() {
        tc.cancelBuild(TOKEN, new TcModel.Build(5, null, "queued", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null));

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).containsEntry(":method", "POST").containsEntry("content-type", "application/json")
                .containsEntry("transfer-encoding", "chunked").doesNotContainKey("content-length");
            assertThat(r.keySet()).containsExactlyInAnyOrder(":method", ":body", "accept", "authorization",
                "connection", "content-type", "host", "transfer-encoding", "user-agent");
            assertThat(r.get(":body")).contains("\"comment\":\"Cancelled by Ignite PR Checker\"",
                "\"readdIntoQueue\":false");
        });
    }
}
