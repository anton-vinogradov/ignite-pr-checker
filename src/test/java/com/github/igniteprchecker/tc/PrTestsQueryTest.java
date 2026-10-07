package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the PR's new and changed test classes ran in its RunAll is one call: the RunAll is a composite build, whose
 * tests are its suites' tests, picked by name with a pattern of the class names, the way run conditions are already
 * asked for. If ci2 rejects the query, it is not asked again.
 */
class PrTestsQueryTest {
    private final List<String> locators = new CopyOnWriteArrayList<>();

    private final List<String> fields = new CopyOnWriteArrayList<>();

    private volatile boolean rejects;

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/testOccurrences", ex -> {
            locators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            byte[] body = (rejects ? "Error has occurred during request processing, Status: BAD_REQUEST" : """
                {"testOccurrence":[{"id":"build:(id:9391900),id:2000000001","status":"SUCCESS","duration":298000,
                  "name":"IgniteSpiDiscoverySelfTestSuite: org.apache.ignite.spi.discovery.tcp.TcpDiscoveryClientTopologyGapTest.testClientReconnect",
                  "test":{"id":"5272433775095107011"},
                  "build":{"id":9391900,"buildTypeId":"IgniteTests24Java8_Spi","buildType":{"name":"SPI"}}}]}
                """).getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", rejects ? "text/plain" : "application/json");
            ex.sendResponseHeaders(rejects ? 400 : 200, body.length);
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
    void theRunsOfTheClassesComeFromTheChainInOneCall() {
        Optional<List<TcModel.TestOccurrence>> runs = client().testRunsOfClasses("t", 9391879L,
            List.of("TcpDiscoveryClientTopologyGapTest", "GridCommandHandlerTest"));

        assertThat(locators).containsExactly("build:(id:9391879),name:(value:.*(TcpDiscoveryClientTopologyGapTest|"
            + "GridCommandHandlerTest).*,matchType:matches),count:2000");
        assertThat(fields).containsExactly(
            "testOccurrence(id,name,status,duration,test(id),build(id,buildTypeId,buildType(name)))");
        assertThat(runs.orElseThrow()).singleElement().satisfies(o -> {
            assertThat(o.duration()).isEqualTo(298_000L);
            assertThat(o.test().id()).isEqualTo(5272433775095107011L);
            assertThat(o.build().buildType().name()).isEqualTo("SPI");
        });
    }

    @Test
    void aRejectedQueryIsNotAskedAgain() {
        rejects = true;
        TcClient tc = client();

        assertThat(tc.testRunsOfClasses("t", 9391879L, List.of("FooTest"))).isEmpty();
        assertThat(tc.testRunsOfClasses("t", 9391880L, List.of("FooTest"))).isEmpty();
        assertThat(locators).hasSize(1);
    }

    /** Runs kept on disk (analysis.json) read and write as before when no duration was asked for. */
    @Test
    void aRunWithoutItsDurationIsKeptAsBefore() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TcModel.TestOccurrence run = new TcModel.TestOccurrence("o", "t", "FAILURE", null, null, null);

        assertThat(mapper.writeValueAsString(run)).doesNotContain("duration");
        assertThat(mapper.readValue("{\"id\":\"o\",\"name\":\"t\",\"status\":\"FAILURE\"}",
            TcModel.TestOccurrence.class)).isEqualTo(run);
    }

    @Test
    void noClassesNoCall() {
        assertThat(client().testRunsOfClasses("t", 9391879L, List.of())).contains(List.of());
        assertThat(locators).isEmpty();
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
