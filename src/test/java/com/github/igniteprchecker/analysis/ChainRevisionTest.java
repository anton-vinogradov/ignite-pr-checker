package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A clean verdict is clean for the PR only while its chain ran the PR's head: 7 of 13 green ticks on prod were
 * for runs with commits pushed since. The revision comes with the chain, in the call that reads it anyway.
 */
class ChainRevisionTest {
    private static final long CHAIN = 9392000L;

    private final List<String> fields = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @AfterEach
    void stopTeamCity() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void theChainSaysWhichRevisionItRan() throws IOException {
        serve("""
            {"id":9392000,"status":"SUCCESS","state":"finished","branchName":"pull/13636/head",
             "queuedDate":"20261001T080000+0000","buildType":{"id":"IgniteTests24Java8_RunAll","name":"Run All"},
             "revisions":{"revision":[{"version":"5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4"}]},
             "snapshot-dependencies":{"count":1,"build":[
              {"id":9392001,"buildTypeId":"IgniteTests24Java8_Basic1","status":"SUCCESS","state":"finished",
               "queuedDate":"20261001T080001+0000","buildType":{"name":"Basic 1"},"testOccurrences":{"count":512}}]}}
            """);

        ChainCollector.Chain chain = collector().collectForBuild("t", 13636, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(fields).singleElement().asString().contains("revisions(revision(version))");
        assertThat(chain.revision()).isEqualTo("5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4");
    }

    @Test
    void aChainWithoutRevisionsHasNone() throws IOException {
        serve("""
            {"id":9392000,"status":"SUCCESS","state":"finished","branchName":"pull/13636/head",
             "snapshot-dependencies":{"count":0,"build":[]}}
            """);

        assertThat(collector().collectForBuild("t", 13636, CHAIN, Executors.newSingleThreadExecutor()).revision())
            .isNull();
    }

    private ChainCollector collector() {
        SuiteBaseline baseline = mock(SuiteBaseline.class);
        when(baseline.counts(anyString())).thenReturn(Map.of());
        TcClient tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(new ObjectMapper()));

        return new ChainCollector(tc, baseline);
    }

    private void serve(String chain) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            boolean asked = ex.getRequestURI().getPath().endsWith("/id:" + CHAIN);
            if (asked)
                fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            byte[] body = (asked ? chain : "{\"build\":[]}").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
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
