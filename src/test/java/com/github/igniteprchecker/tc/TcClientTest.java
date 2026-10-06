package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The test-occurrence locators sent to TeamCity. One test id runs in several suites of a chain (the C++
 * tests of apache/ignite#13335 run on Windows, Linux and Clang), so the queries behind a verdict must
 * name the suite the test failed in.
 */
class TcClientTest {
    private static final long RECONNECT = 5272433775095107011L;
    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

    private final List<String> locators = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/testOccurrences", ex -> {
            locators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            byte[] body = "{\"count\":0,\"testOccurrence\":[]}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void branchRunsAreAskedForTheFailingSuiteOnly() {
        client().prBranchRuns("tok", 13335, RECONNECT, CLANG);

        assertThat(locators).singleElement().asString()
            .contains("test:(id:" + RECONNECT + ")", "branch:(name:pull/13335/head)", "buildType:(id:" + CLANG + ")");
    }

    @Test
    void masterHistoryIsAskedForTheFailingSuiteOnly() {
        client().getBaseBranchHistory("tok", RECONNECT, CLANG);

        assertThat(locators).singleElement().asString()
            .contains("test:(id:" + RECONNECT + ")", "branch:(default:true)", "buildType:(id:" + CLANG + ")");
    }

    private TcClient client() {
        String baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";

        return new TcClient(new TeamcityProperties(baseUrl),
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
