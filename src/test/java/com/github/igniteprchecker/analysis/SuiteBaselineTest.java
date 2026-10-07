package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The page's "RunAll (~N suites)" took the size of the master suite baseline, which keeps the last known
 * test count of every suite any master chain ever had: a suite dropped from RunAll stayed in the count for
 * good, and a suite that runs no tests never got in. Master's RunAll had Cache, Query and Compute, then
 * Compute was dropped; the chain also builds Licenses, which runs no tests.
 */
class SuiteBaselineTest {
    private static final String TOKEN = "tok";

    private final ObjectMapper mapper = new ObjectMapper();

    /** How many times TeamCity was asked for the suites of master's latest chain. */
    private final AtomicInteger chainLooks = new AtomicInteger();

    private volatile List<String> masterChain = List.of("Cache", "Query");

    private HttpServer server;

    private TcClient tc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(mapper));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void suiteDroppedFromRunAllLeavesTheCount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("suite-baseline.json");
        Files.writeString(file, "{\"fetchedAt\":1,\"counts\":{\"Cache\":900,\"Query\":400,\"Compute\":300},"
            + "\"durations\":{\"Cache\":3600,\"Query\":1800,\"Compute\":1200},\"chainSuites\":3}");
        SuiteBaseline baseline = new SuiteBaseline(mapper, tc);
        baseline.loadFrom(file);

        assertThat(baseline.counts(TOKEN)).as("a suite's last known test count is kept").containsKey("Compute");
        assertThat(baseline.chainSuites()).isEqualTo(2);
    }

    @Test
    void suiteThatRunsNoTestsCounts() {
        masterChain = List.of("Cache", "Query", "Licenses");
        SuiteBaseline baseline = new SuiteBaseline(mapper, tc);

        assertThat(baseline.counts(TOKEN)).containsOnlyKeys("Cache", "Query");
        assertThat(baseline.chainSuites()).isEqualTo(3);
    }

    /** A snapshot written before the chain size was kept loads whole, and the next analysis fetches the size. */
    @Test
    void snapshotWithoutTheChainSizeLoadsAndIsRefreshedAtOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("suite-baseline.json");
        Files.writeString(file, "{\"fetchedAt\":" + System.currentTimeMillis() + ",\"counts\":{\"Cache\":900,"
            + "\"Query\":400,\"Compute\":300},\"durations\":{\"Cache\":3600,\"Query\":1800,\"Compute\":1200}}");
        SuiteBaseline baseline = new SuiteBaseline(mapper, tc);
        baseline.loadFrom(file);

        assertThat(baseline.chainSuites()).isZero();
        assertThat(baseline.counts(TOKEN)).containsEntry("Compute", 300).containsEntry("Cache", 1000);
        assertThat(baseline.durations(TOKEN)).containsEntry("Compute", 1200L);
        assertThat(baseline.chainSuites()).isEqualTo(2);
        assertThat(chainLooks).hasValue(1);
    }

    @Test
    void chainSizeSurvivesARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("suite-baseline.json");
        SuiteBaseline first = new SuiteBaseline(mapper, tc);
        first.counts(TOKEN);
        first.saveTo(file);

        SuiteBaseline restarted = new SuiteBaseline(mapper, tc);
        restarted.loadFrom(file);
        restarted.counts(TOKEN);

        assertThat(restarted.chainSuites()).isEqualTo(2);
        assertThat(chainLooks).as("the restored baseline is fresh").hasValue(1);
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body;

        if (path.equals("/app/rest/builds"))
            body = "{\"build\":[{\"id\":900}]}";
        else if (path.equals("/app/rest/builds/id:900")) {
            chainLooks.incrementAndGet();
            body = "{\"snapshot-dependencies\":{\"build\":["
                + masterChain.stream().map(SuiteBaselineTest::masterRun).collect(Collectors.joining(",")) + "]}}";
        }
        else
            body = "{}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** One suite of master's chain: an hour long, with 1000 tests unless it is Licenses, which runs none. */
    private static String masterRun(String suite) {
        return "{\"buildTypeId\":\"" + suite + "\",\"startDate\":\"20261007T100000+0000\","
            + "\"finishDate\":\"20261007T110000+0000\""
            + ("Licenses".equals(suite) ? "" : ",\"testOccurrences\":{\"count\":1000}") + "}";
    }
}
