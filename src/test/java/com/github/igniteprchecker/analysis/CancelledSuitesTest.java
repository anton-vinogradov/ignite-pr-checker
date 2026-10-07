package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * PR 13592: TeamCity itself cancelled four suites of the RunAll with "Build revision not found". The
 * page never said which, a re-run did not lift "suites never ran", and the auto re-run left them alone.
 * On PR 13583 the header said "147 suites ran" over "137 never ran", counting every suite queued.
 */
class CancelledSuitesTest {
    private static final String TOK = "t";

    private static final int PR = 13592;

    private static final long CHAIN = 9392000L;

    private static final String CHAIN_QUEUED = "20261001T080000+0000";

    private static final List<String> CANCELLED = List.of("IgniteTests24Java8_Cache1", "IgniteTests24Java8_Cache2",
        "IgniteTests24Java8_Queries1", "IgniteTests24Java8_Snapshots1");

    private final TcClient tc = mock(TcClient.class);

    /** The chain as ci2 sends it: the fields asked for, a cancellation without a user, and one with. */
    @Test
    void theChainTellsWhichSuitesWereCancelledAndByWhom() throws IOException {
        List<String> fields = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            boolean chain = ex.getRequestURI().getPath().endsWith("/id:" + CHAIN);
            if (chain)
                fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            byte[] body = (chain ? CHAIN_JSON : "{\"build\":[]}").getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            TcClient client = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/"), new AnalysisProperties(null, "RunAll", null, null, null, null,
                null), new Metrics(new ObjectMapper()));
            SuiteBaseline baseline = mock(SuiteBaseline.class);
            when(baseline.counts(anyString())).thenReturn(Map.of());

            ChainCollector.Chain chain = new ChainCollector(client, baseline)
                .collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

            assertThat(fields).singleElement().asString().contains("canceledInfo(text,user(username))");
            assertThat(chain.cancelledSuites())
                .extracting(CancelledSuite::suite, CancelledSuite::suiteName, CancelledSuite::reason,
                    CancelledSuite::cancelledBy)
                .containsExactly(
                    tuple("IgniteTests24Java8_Cache1", "Cache 1", "Build revision not found", null),
                    tuple("IgniteTests24Java8_Cache2", "Cache 2", "Stopped: wrong agent", "avinogradov"));
            assertThat(chain.canceledSuites()).isEqualTo(2);
            assertThat(chain.interrupted()).isTrue();
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void onlySuitesThatFinishedWithAResultRan() {
        List<TcModel.Build> deps = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            deps.add(dep(9393000L + i, "Suite" + i, i % 2 == 0 ? "SUCCESS" : "FAILURE", CHAIN_QUEUED, null));
        for (int i = 10; i < 147; i++)
            deps.add(dep(9393000L + i, "Suite" + i, "UNKNOWN", CHAIN_QUEUED, "avinogradov"));
        deps.add(dep(9380000L, "Reused", "SUCCESS", "20260930T080000+0000", null));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainBuild("UNKNOWN", deps));
        SuiteBaseline baseline = mock(SuiteBaseline.class);
        when(baseline.counts(anyString())).thenReturn(Map.of());

        ChainCollector.Chain chain = new ChainCollector(tc, baseline)
            .collectForBuild(TOK, 13583, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(chain.suitesRan()).isEqualTo(10);
        assertThat(chain.suitesReused()).isEqualTo(1);
        assertThat(chain.canceledSuites()).isEqualTo(137);
    }

    @Test
    void aRerunOfACancelledSuiteTakesItOffTheList() {
        when(tc.finishedBuildsSince(TOK, PR, TcDates.format(TcDates.epochSeconds(CHAIN_QUEUED)))).thenReturn(List.of(
            run(9392500L, CANCELLED.get(0), "SUCCESS"),
            run(9392501L, CANCELLED.get(1), "FAILURE"),
            run(9391990L, CANCELLED.get(2), "SUCCESS"))); // older than the chain's own cancelled run of it

        AnalysisResult r = analyze();

        assertThat(r.cancelledSuites()).extracting(CancelledSuite::suite)
            .containsExactly(CANCELLED.get(2), CANCELLED.get(3));
        assertThat(r.canceledSuites()).isEqualTo(2);
        assertThat(r.interrupted()).isTrue();
    }

    @Test
    void onceEveryCancelledSuiteRanTheRunCoversThePr() {
        List<TcModel.Build> reruns = new ArrayList<>();
        for (int i = 0; i < CANCELLED.size(); i++)
            reruns.add(run(9392500L + i, CANCELLED.get(i), "SUCCESS"));
        when(tc.finishedBuildsSince(eq(TOK), eq(PR), anyString())).thenReturn(reruns);

        AnalysisResult r = analyze();

        assertThat(r.cancelledSuites()).isEmpty();
        assertThat(r.interrupted()).isFalse();
        assertThat(Caveats.proven(r)).isTrue();
    }

    @Test
    void whenTeamCityCannotSayTheCancelledSuitesStay() {
        when(tc.finishedBuildsSince(eq(TOK), eq(PR), anyString())).thenThrow(new IllegalStateException("502"));

        assertThat(analyze().cancelledSuites()).hasSize(4);
    }

    @Test
    void nothingCancelledCostsNoRequest() {
        ChainCollector chains = mock(ChainCollector.class);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13592/head", List.of(), List.of(), List.of(), 150, 0, false, 0, false, 0, 0, 0, 0));

        analyzer(chains).analyze(TOK, PR);

        verify(tc, never()).finishedBuildsSince(anyString(), anyInt(), any());
    }

    private AnalysisResult analyze() {
        List<CancelledSuite> cancelled = new ArrayList<>();
        for (int i = 0; i < CANCELLED.size(); i++)
            cancelled.add(new CancelledSuite(CANCELLED.get(i), 9392010L + i, CANCELLED.get(i), "Build revision not found",
                null));
        ChainCollector chains = mock(ChainCollector.class);
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(new ChainCollector.Chain(CHAIN,
            "pull/13592/head", List.of(), List.of(), List.of(), 146, 0, true, 4, false, 0,
            TcDates.epochSeconds(CHAIN_QUEUED), 0, 0, List.of(), cancelled));

        return analyzer(chains).analyze(TOK, PR).orElseThrow();
    }

    private BlockerAnalyzer analyzer(ChainCollector chains) {
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();

        return new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2), Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor(), new AnalysisCache(cfg, mapper), new RunDeltaStore(mapper));
    }

    private static TcModel.Build chainBuild(String status, List<TcModel.Build> deps) {
        return new TcModel.Build(CHAIN, status, "finished", "pull/13583/head", "IgniteTests24Java8_RunAll", null,
            CHAIN_QUEUED, null, null, null, null, new TcModel.BuildType("IgniteTests24Java8_RunAll", "Run All"), null,
            new TcModel.SnapshotDeps(deps.size(), deps), null, null, null, null);
    }

    private static TcModel.Build dep(long id, String suite, String status, String queued, String cancelledBy) {
        return new TcModel.Build(id, status, "finished", "pull/13583/head", suite, null, queued, null, null, null, null,
            new TcModel.BuildType(suite, suite), null, null, null, null, null, null,
            "UNKNOWN".equals(status) ? new TcModel.CanceledInfo("Canceled", new TcModel.User(cancelledBy)) : null);
    }

    private static TcModel.Build run(long id, String suite, String status) {
        return new TcModel.Build(id, status, "finished", "pull/13592/head", suite, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private static String param(String rawQuery, String name) {
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (kv.substring(0, eq).equals(name))
                return URLDecoder.decode(kv.substring(eq + 1), UTF_8);
        }

        return null;
    }

    private static final String CHAIN_JSON = """
        {"id":9392000,"status":"FAILURE","state":"finished","branchName":"pull/13592/head",
         "queuedDate":"20261001T080000+0000","buildType":{"id":"IgniteTests24Java8_RunAll","name":"Run All"},
         "snapshot-dependencies":{"count":4,"build":[
          {"id":9392001,"buildTypeId":"IgniteTests24Java8_Basic1","status":"SUCCESS","state":"finished",
           "queuedDate":"20261001T080001+0000","buildType":{"name":"Basic 1"},"testOccurrences":{"count":512}},
          {"id":9392010,"buildTypeId":"IgniteTests24Java8_Cache1","status":"UNKNOWN","state":"finished",
           "queuedDate":"20261001T080001+0000","buildType":{"name":"Cache 1"},
           "canceledInfo":{"timestamp":"20261001T080210+0000","text":"Build revision not found"}},
          {"id":9392011,"buildTypeId":"IgniteTests24Java8_Cache2","status":"UNKNOWN","state":"finished",
           "queuedDate":"20261001T080001+0000","buildType":{"name":"Cache 2"},
           "canceledInfo":{"text":"Stopped: wrong agent","user":{"username":"avinogradov","id":7}}},
          {"id":9392012,"buildTypeId":"IgniteTests24Java8_Queries1","status":"SUCCESS","state":"finished",
           "queuedDate":"20261001T080001+0000","buildType":{"name":"Queries 1"},"testOccurrences":{"count":300}}
         ]}}
        """;
}
