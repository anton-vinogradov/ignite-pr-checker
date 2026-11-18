package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.Jackson2Defaults;
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
 * tests of apache/ignite#13335 run on Windows, Linux and Clang), so the queries behind a verdict and
 * the master failures on the flaky board must name the suite the test failed in.
 */
class TcClientTest {
    private static final long RECONNECT = 5272433775095107011L;
    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

    private final List<String> locators = new CopyOnWriteArrayList<>();

    private final List<String> occurrenceFields = new CopyOnWriteArrayList<>();

    private final List<String> buildFields = new CopyOnWriteArrayList<>();

    private final List<String> buildLocators = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/app/rest/testOccurrences", ex -> {
            locators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            occurrenceFields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            byte[] body = "{\"count\":0,\"testOccurrence\":[]}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/app/rest/builds", ex -> {
            buildFields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            buildLocators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            byte[] body = "{\"id\":9384769}".getBytes(UTF_8);
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

    /** One answer about every other branch serves all PRs, so the PR under review is left out by the caller. */
    @Test
    void otherBranchRunsAreAskedForTheFailingSuiteOnEveryBranchButMaster() {
        client().otherBranchRuns("tok", RECONNECT, CLANG);

        assertThat(locators).singleElement().asString()
            .contains("test:(id:" + RECONNECT + ")", "branch:(default:false)", "buildType:(id:" + CLANG + ")",
                "muted:false", "count:200")
            .doesNotContain("pull/");
    }

    /**
     * Given a test in one suite, when its board history is fetched, then the one request names that suite, as many runs
     * as the analysis looks at, and each run's finish date and commit.
     */
    @Test
    void masterBoardHistoryNamesTheSuiteFinishDatesAndCommits() {
        client().masterTestHistory("tok", RECONNECT, CLANG);

        assertThat(locators).singleElement().asString()
            .contains("test:(id:" + RECONNECT + ")", "branch:(default:true)", "buildType:(id:" + CLANG + ")", "count:100");
        assertThat(occurrenceFields).singleElement().asString()
            .contains("currentlyMuted", "build(id,buildTypeId,state,status,finishDate,revisions(revision(version)))");
    }

    /**
     * PR 13583's Build failed, and its victims read like suites broken on their own: each suite of a chain is read
     * with the runs it needed and how they ended.
     */
    @Test
    void theSuitesOfAChainAreReadWithTheRunsTheyNeeded() {
        client().getBuildWithDeps("tok", 9384769L);

        assertThat(buildFields).singleElement().asString()
            .contains("snapshot-dependencies(build(id,buildTypeId,status,state,queuedDate,")
            .contains(",snapshot-dependencies(build(id,buildTypeId,status,buildType(name)))))");
    }

    /**
     * The master board learns which of master's RunAll chains are new from their ids alone, as many chains as the
     * analysis looks at runs, and reads the suite runs of a chain in one request: how each ended and when, how many of
     * its tests ran and failed, and the types of its problems.
     */
    @Test
    void masterChainsAreListedByIdAndReadWithWhatTellsAProblemRun() {
        client().masterChains("tok");
        client().chainSuiteRuns("tok", 9384769L);

        assertThat(buildLocators).first().asString()
            .isEqualTo("buildType:(id:RunAll),branch:(default:true),state:finished,canceled:false,count:100");
        assertThat(buildFields).containsExactly("build(id)", "snapshot-dependencies(build(id,buildTypeId,state,status,"
            + "finishDate,buildType(name),testOccurrences(count,failed),problemOccurrences(problemOccurrence(type))))");
    }

    private TcClient client() {
        String baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";

        return new TcClient(new TeamcityProperties(baseUrl),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null),
            new Metrics(Jackson2Defaults.mapper()));
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
