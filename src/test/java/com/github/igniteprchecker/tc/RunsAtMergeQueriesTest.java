package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * apache/ignite#13709 was merged at 10:12:39 UTC while its re-run of Control Utility 5 on the PR's head still went.
 * Completing its verdict takes, in one call, every build of the branch with when it was queued and finished, and
 * master's runs that finished before the merge. Telling a mixed run takes the commit of each run the verdict reads, in
 * the calls that read those runs anyway.
 */
class RunsAtMergeQueriesTest {
    /** 2026-10-20 10:12:39 UTC. */
    private static final long MERGED_AT = 1_792_491_159L;

    private final Map<String, String> asked = new ConcurrentHashMap<>();

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            String fields = param(ex.getRequestURI().getRawQuery(), "fields");
            String locator = param(ex.getRequestURI().getRawQuery(), "locator");
            asked.put(ex.getRequestURI().getPath() + " " + locator, fields);
            answer(ex, """
                {"build":[
                 {"id":9426316,"buildTypeId":"IgniteTests24Java8_ControlUtility5","state":"running",
                  "queuedDate":"20261020T100150+0000","buildType":{"name":"Control Utility 5"},
                  "triggered":{"type":"user"},"revisions":{"revision":[{"version":"9b02561"}]}},
                 {"id":9425992,"buildTypeId":"IgniteTests24Java8_RunAll","state":"finished","status":"FAILURE",
                  "queuedDate":"20261020T080000+0000","finishDate":"20261020T095000+0000",
                  "canceledInfo":{"text":"stopped"}}]}
                """);
        });
        server.createContext("/app/rest/testOccurrences", ex -> {
            asked.put("history", param(ex.getRequestURI().getRawQuery(), "fields"));
            answer(ex, """
                {"testOccurrence":[
                 {"status":"FAILURE","build":{"id":9427100,"finishDate":"20261020T120000+0000"}},
                 {"status":"SUCCESS","build":{"id":9420000,"finishDate":"20261019T230000+0000"}},
                 {"status":"SUCCESS","build":{"id":9410000}}]}
                """);
        });
        server.start();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void theBranchIsListedInEveryStateWithWhenEachBuildWasQueuedAndFinished() {
        List<TcModel.Build> builds = client().branchBuilds("tok", 13709);

        assertThat(asked.keySet()).singleElement().asString()
            .contains("branch:(name:pull/13709/head)", "defaultFilter:false", "count:1000");
        assertThat(asked.values()).singleElement().asString().contains("queuedDate", "finishDate", "triggered(type)",
            "canceledInfo(text)", "revisions(revision(version))", "testOccurrences(count)", "state", "status",
            "buildType(name)");
        assertThat(builds).extracting(TcModel.Build::id, TcModel.Build::state)
            .containsExactly(tuple(9426316L, "running"), tuple(9425992L, "finished"));
        assertThat(builds.get(0).triggered().type()).isEqualTo("user");
        assertThat(builds.get(1).canceledInfo()).isNotNull();
    }

    /** A master run that finished after the merge may hold the PR's own code; one with no finish date proves nothing. */
    @Test
    void masterRunsThatFinishedAfterTheMergeAreLeftOut() {
        List<TcModel.TestOccurrence> runs = client().getBaseBranchHistory("tok", 1001L,
            "IgniteTests24Java8_ControlUtility5", MERGED_AT);

        assertThat(asked.get("history")).contains("build(id,finishDate,");
        assertThat(runs).extracting(o -> o.build().id()).containsExactly(9420000L);
    }

    @Test
    void theRunsAVerdictReadsComeWithTheirCommits() {
        TcClient tc = client();
        tc.latestSuiteRun("tok", 13709, "IgniteTests24Java8_ControlUtility5");
        tc.finishedBuildsSince("tok", 13709, "20261020T080000+0000");
        tc.failedBuildsSince("tok", 13709, "20261020T080000+0000");
        tc.getBuildWithDeps("tok", 9425992L);

        assertThat(asked).hasSize(4);
        assertThat(asked.values()).allSatisfy(fields -> assertThat(fields).contains("revisions(revision(version))"));
        String chain = asked.entrySet().stream().filter(e -> e.getKey().contains("id:9425992")).findFirst()
            .orElseThrow().getValue();
        assertThat(chain.substring(chain.indexOf("snapshot-dependencies(build("))).as("each suite of the chain")
            .contains("revisions(revision(version))");
    }

    private TcClient client() {
        String baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";

        return new TcClient(new TeamcityProperties(baseUrl),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null),
            new Metrics(Jackson2Defaults.mapper()));
    }

    private static void answer(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
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
