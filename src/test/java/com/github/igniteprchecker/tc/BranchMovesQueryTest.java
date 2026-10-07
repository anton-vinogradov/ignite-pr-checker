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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one question a recompute asks before reusing a PR branch's runs: which suites finished a build on the
 * branch since it was last checked. Asked like the branch watermark is (by finish date, bounded by start date),
 * which ci2 answers; failed-to-start builds are asked for too, since a suite's newest run may be one.
 */
class BranchMovesQueryTest {
    private static final int PR = 13583;

    /** 2026-10-07 12:00:00 UTC. */
    private static final long SINCE = 1_791_374_400L;

    private final List<String> locators = new CopyOnWriteArrayList<>();

    private final List<String> fields = new CopyOnWriteArrayList<>();

    private volatile String answer = "{\"build\":[{\"id\":9390500,\"buildTypeId\":\"IgniteTests24Java8_Queries5\"},"
        + "{\"id\":9390499,\"buildTypeId\":\"IgniteTests24Java8_Queries5\"},"
        + "{\"id\":9390480,\"buildTypeId\":\"IgniteTests24Java8_Cache1\"}]}";

    private volatile boolean rejectsFailedToStart;

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            String locator = param(ex.getRequestURI().getRawQuery(), "locator");
            locators.add(locator);
            fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            boolean reject = rejectsFailedToStart && locator.contains("failedToStart");
            byte[] body = (reject ? "Error has occurred during request processing, Status: BAD_REQUEST" : answer)
                .getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", reject ? "text/plain" : "application/json");
            ex.sendResponseHeaders(reject ? 400 : 200, body.length);
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
    void namesTheSuitesThatFinishedABuildOnTheBranchSince() {
        Optional<Set<String>> moved = client().suitesFinishedAfter("t", PR, SINCE);

        assertThat(moved).contains(Set.of("IgniteTests24Java8_Queries5", "IgniteTests24Java8_Cache1"));
        assertThat(locators).singleElement().asString().contains(
            "branch:(name:pull/13583/head)", "state:finished", "canceled:any", "failedToStart:any",
            "startDate:(date:20261006T120000+0000,condition:after)",
            "finishDate:(date:20261007T120000+0000,condition:after)", "count:1000");
        assertThat(fields).containsExactly("build(id,buildTypeId)");
    }

    /** As many builds as asked for may not be all of them: then nothing kept about the branch is trusted. */
    @Test
    void aListAsLongAsAskedForSaysNothing() {
        answer = IntStream.range(0, 1000).mapToObj(i -> "{\"id\":" + (9390000 + i) + ",\"buildTypeId\":\"S" + i + "\"}")
            .collect(Collectors.joining(",", "{\"build\":[", "]}"));

        assertThat(client().suitesFinishedAfter("t", PR, SINCE)).isEmpty();
    }

    @Test
    void aRejectedFailedToStartIsLeftOutForGood() {
        rejectsFailedToStart = true;
        TcClient client = client();

        Optional<Set<String>> moved = client.suitesFinishedAfter("t", PR, SINCE);
        client.suitesFinishedAfter("t", PR, SINCE);

        assertThat(moved).contains(Set.of("IgniteTests24Java8_Queries5", "IgniteTests24Java8_Cache1"));
        assertThat(locators).hasSize(3);
        assertThat(locators.get(0)).contains("failedToStart:any");
        assertThat(locators.subList(1, 3)).allSatisfy(l -> assertThat(l)
            .doesNotContain("failedToStart").contains("finishDate:(date:20261007T120000+0000,condition:after)"));
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
