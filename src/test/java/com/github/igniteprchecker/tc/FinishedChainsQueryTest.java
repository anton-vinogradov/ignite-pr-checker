package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The standing sweep looked each of the 50 listed PRs up every 10 minutes to learn, nearly always, that no RunAll of
 * it had finished. It now asks once which RunAll chains finished since the last sweep, across all branches, bounded
 * by start date as the branch watermark is: without the bound TeamCity reads the RunAll's whole history.
 */
class FinishedChainsQueryTest {
    /** 2026-10-07 12:00:00 UTC. */
    private static final long SINCE = 1_791_374_400L;

    private final List<String> locators = new CopyOnWriteArrayList<>();

    private final List<String> fields = new CopyOnWriteArrayList<>();

    private volatile String answer = "{\"build\":[{\"id\":9390500,\"branchName\":\"pull/13583/head\"},"
        + "{\"id\":9390499,\"branchName\":\"pull/13654/head\"},{\"id\":9390480,\"branchName\":\"master\"},"
        + "{\"id\":9390470,\"branchName\":\"pull/13583/head\"},{\"id\":9390460}]}";

    private volatile int status = 200;

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            locators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            byte[] body = (status == 200 ? answer : "Error has occurred during request processing, Status: BAD_REQUEST")
                .getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/plain");
            ex.sendResponseHeaders(status, body.length);
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
    void namesThePrsWhoseChainsFinishedSince() {
        assertThat(client().prsWithChainsFinishedAfter("t", SINCE)).contains(Set.of(13583, 13654));
        assertThat(locators).singleElement().asString().contains("buildType:(id:RunAll)", "branch:(default:any)",
            "state:finished", "canceled:any", "failedToStart:any",
            "startDate:(date:20261006T120000+0000,condition:after)",
            "finishDate:(date:20261007T120000+0000,condition:after)", "count:200");
        assertThat(fields).containsExactly("build(id,branchName)");
    }

    /** As many chains as asked for may not be all of them: then the sweep looks every PR up. */
    @Test
    void aListAsLongAsAskedForSaysNothing() {
        answer = IntStream.range(0, 200).mapToObj(i -> "{\"id\":" + (9390000 + i) + ",\"branchName\":\"pull/" + i
            + "/head\"}").collect(Collectors.joining(",", "{\"build\":[", "]}"));

        assertThat(client().prsWithChainsFinishedAfter("t", SINCE)).isEmpty();
    }

    /** ci2 has not been asked this form; if it refuses it, the sweep goes back to its lookups, and asks no more. */
    @Test
    void aRejectedQueryIsNotAskedAgain() {
        status = 400;
        TcClient client = client();

        assertThat(client.prsWithChainsFinishedAfter("t", SINCE)).isEmpty();
        assertThat(client.prsWithChainsFinishedAfter("t", SINCE)).isEmpty();
        assertThat(locators).hasSize(1);
    }

    private TcClient client() {
        return new TcClient(new TeamcityProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(new ObjectMapper()));
    }

    private static String param(String rawQuery, String name) {
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (kv.substring(0, eq).equals(name))
                return URLDecoder.decode(kv.substring(eq + 1), UTF_8);
        }

        return null;
    }
}
