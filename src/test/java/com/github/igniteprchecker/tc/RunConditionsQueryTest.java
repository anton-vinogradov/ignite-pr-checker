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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JDK and the test scale factor of each run come inline with the test occurrences, as ci2 answered
 * for {@code testRecoveryClusterSnapshotJvmHalted} in Snapshots on 2026-10-06. Naming both properties takes a
 * pattern ci2 has not been asked yet; naming the JDK alone is known to work. If ci2 rejects the pattern,
 * the checker must keep working with the JDK alone instead of failing every history request.
 */
class RunConditionsQueryTest {
    private static final long TEST = 8882040372364155986L;

    private static final String SNAPSHOTS = "IgniteTests24Java8_Snapshots";

    /** Two of the master runs ci2 returned, oldest first to show the client orders them itself. */
    private static final String HISTORY = "{\"testOccurrence\":["
        + "{\"status\":\"FAILURE\",\"build\":{\"id\":9389909,\"resultingProperties\":{\"count\":1,\"property\":"
        + "[{\"name\":\"env.JAVA_HOME\",\"value\":\"/opt/java/jdk-open-21\"}]}}},"
        + "{\"status\":\"SUCCESS\",\"build\":{\"id\":9390120,\"resultingProperties\":{\"count\":1,\"property\":"
        + "[{\"name\":\"env.JAVA_HOME\",\"value\":\"/opt/java/jdk-open-17\"}]}}}]}";

    private final List<String> fields = new CopyOnWriteArrayList<>();

    private volatile boolean rejectsPattern;

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/testOccurrences", ex -> {
            String asked = param(ex.getRequestURI().getRawQuery(), "fields");
            fields.add(asked);
            boolean reject = rejectsPattern && asked.contains("matchType:matches");
            byte[] body = (reject ? "Error has occurred during request processing, Status: BAD_REQUEST" : HISTORY)
                .getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", reject ? "text/plain" : "application/json");
            ex.sendResponseHeaders(reject ? 400 : 200, body.length);
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
    void masterHistoryComesNewestFirstWithTheJdkAndScaleFactorOfEachRun() {
        List<TcModel.TestOccurrence> history = client().getBaseBranchHistory("tok", TEST, SNAPSHOTS);

        assertThat(fields).singleElement().asString()
            .contains("resultingProperties($locator(name:(value:(env.JAVA_HOME|TEST_SCALE_FACTOR),matchType:matches))");
        assertThat(history).extracting(o -> o.build().id()).containsExactly(9390120L, 9389909L);
        assertThat(history.get(0).build().resultingProperties().property())
            .containsExactly(new TcModel.Property(TcModel.JAVA_HOME, "/opt/java/jdk-open-17"));
    }

    @Test
    void branchRunsComeWithTheirRunConditions() {
        client().prBranchRuns("tok", 13654, TEST, SNAPSHOTS);

        assertThat(fields).singleElement().asString().contains("revisions(revision(version)),resultingProperties(");
    }

    @Test
    void aRejectedPatternFallsBackToTheJdkAloneForGood() {
        rejectsPattern = true;
        TcClient client = client();

        List<TcModel.TestOccurrence> history = client.getBaseBranchHistory("tok", TEST, SNAPSHOTS);
        client.getBaseBranchHistory("tok", TEST, SNAPSHOTS);

        assertThat(history).hasSize(2);
        assertThat(fields).hasSize(3);
        assertThat(fields.get(0)).contains("matchType:matches");
        assertThat(fields.subList(1, 3)).allSatisfy(f ->
            assertThat(f).contains("resultingProperties($locator(name:env.JAVA_HOME),property(name,value))"));
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
