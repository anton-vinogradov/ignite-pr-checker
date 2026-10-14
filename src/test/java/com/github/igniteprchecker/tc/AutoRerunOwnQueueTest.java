package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auto re-run cancelled the identical re-runs waiting in the queue whoever had queued them, while the page's Rerun
 * cancels only the viewer's own: ci2 lets anyone cancel anyone's build, so alice's automation took bob's re-run off
 * the queue, top place and all. On PR 13575 alice's auto re-run of Cache comes while a re-run of Cache by each of them
 * waits in the queue, next to a Cache build of alice's running chain.
 */
class AutoRerunOwnQueueTest {
    private static final int PR = 13575;

    /** "METHOD /path" of every call TeamCity received, in order. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private volatile int whoamiStatus = 200;

    private volatile String queue = "{\"build\":[" + queued(204, "Cache", "user", "alice") + ","
        + queued(305, "Cache", "user", "bob") + "," + queued(206, "Cache", "snapshotDependency", null) + ","
        + queued(207, "Query", "user", "alice") + "]}";

    private HttpServer server;

    private TcClient tc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"), new AnalysisProperties(null, "RunAll", null, null, null, null, null),
            new Metrics(new ObjectMapper()));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void autoRerunCancelsOnlyTheTokenOwnersQueuedRerun() {
        tc.triggerBuildReplacingQueued("alice-tc", "Cache", PR, false, "Auto re-run #1");

        assertThat(writes()).containsExactly("POST /app/rest/buildQueue/id:204", "POST /app/rest/buildQueue");
    }

    @Test
    void nobodyIsAskedForWhenNoIdenticalRerunWaits() {
        queue = "{\"build\":[" + queued(207, "Query", "user", "alice") + "]}";

        tc.triggerBuildReplacingQueued("alice-tc", "Cache", PR, false, "Auto re-run #1");

        assertThat(calls).doesNotContain("GET /app/rest/users/current");
        assertThat(writes()).containsExactly("POST /app/rest/buildQueue");
    }

    @Test
    void aTokenTeamCityDoesNotNameCancelsNothing() {
        whoamiStatus = 403;

        tc.triggerBuildReplacingQueued("alice-tc", "Cache", PR, false, "Auto re-run #1");

        assertThat(writes()).containsExactly("POST /app/rest/buildQueue");
    }

    private List<String> writes() {
        return calls.stream().filter(c -> c.startsWith("POST ")).toList();
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        calls.add(ex.getRequestMethod() + " " + path);
        int code = 200;
        String body = "{}";

        if ("POST".equals(ex.getRequestMethod()) && path.equals("/app/rest/buildQueue"))
            body = "{\"id\":400,\"state\":\"queued\",\"buildTypeId\":\"Cache\"}";
        else if (path.equals("/app/rest/buildQueue"))
            body = queue;
        else if (path.equals("/app/rest/users/current")) {
            code = whoamiStatus;
            body = code == 200 ? "{\"username\":\"alice\"}" : "{}";
        }

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String queued(long id, String buildType, String trigger, String user) {
        return "{\"id\":" + id + ",\"state\":\"queued\",\"buildTypeId\":\"" + buildType
            + "\",\"branchName\":\"pull/" + PR + "/head\",\"triggered\":{\"type\":\"" + trigger + "\""
            + (user == null ? "" : ",\"user\":{\"username\":\"" + user + "\"}") + "}}";
    }
}
