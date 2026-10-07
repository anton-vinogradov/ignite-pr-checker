package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fix-master queue for a test that runs in several suites: the C++ thin-client tests of
 * apache/ignite#13335 run on Windows, Linux and Clang, and each platform has its own master fail rate
 * and its own failed master runs. A row of the queue is one test in one suite, and everything on it,
 * the fail rate and the master failures it links, is that suite's.
 *
 * <p>The master failures come from a stand-in for TeamCity that, like ci2, returns the test's runs in
 * every suite unless the locator names a build type.
 */
class FlakyStatsTest {
    private static final long RECONNECT = 5272433775095107011L;

    private static final String NAME = "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientReconnect";

    private static final String LINUX = "IgniteTests24Java8_PlatformCPPCMakeLinux";

    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

    private final ObjectMapper mapper = new ObjectMapper();

    private final FakeTeamCity ci2 = new FakeTeamCity();

    private final AnalysisCache cache =
        new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null, null), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final TcClient tc = new TcClient(new TeamcityProperties(ci2.baseUrl()),
        new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(new ObjectMapper()));

    @AfterEach
    void stopTeamCity() {
        ci2.close();
    }

    @Test
    void aTestFailingOnMasterInTwoSuitesGetsARowPerSuite() {
        masterHistory(CLANG, 33, 7);
        masterHistory(LINUX, 34, 2);
        filtered(13335, CLANG, LINUX);

        FlakyStats flaky = flaky();
        flaky.harvest();

        assertThat(flaky.top(40))
            .extracting(FlakyStats.TopFlaky::suite, FlakyStats.TopFlaky::masterFails, FlakyStats.TopFlaky::masterRuns)
            .containsExactly(tuple(CLANG, 7, 33), tuple(LINUX, 2, 34));
    }

    @Test
    void aRowLinksOnlyTheMasterFailuresOfItsOwnSuite() {
        masterHistory(LINUX, 34, 1);
        filtered(13335, LINUX);
        ci2.masterFailure(LINUX, 9380300L);
        ci2.masterFailure(CLANG, 9380400L);
        ci2.masterFailure(CLANG, 9380401L);

        FlakyStats flaky = flaky();
        flaky.harvest();

        assertThat(flaky.top(40)).singleElement()
            .extracting(FlakyStats.TopFlaky::masterFailures)
            .isEqualTo(List.of(ref(9380300L, LINUX)));
    }

    @Test
    void bothSuitesOfATestSurviveARestart(@TempDir Path dir) throws IOException {
        masterHistory(CLANG, 33, 7);
        masterHistory(LINUX, 34, 2);
        filtered(13335, CLANG, LINUX);
        FlakyStats before = flaky();
        before.harvest();
        Path file = dir.resolve("flaky.json");
        before.saveTo(file);

        FlakyStats after = flaky();
        after.loadFrom(file);

        assertThat(after.top(40)).extracting(FlakyStats.TopFlaky::suite).containsExactly(CLANG, LINUX);
    }

    /**
     * A snapshot written while the queue was kept per test id has one entry per test, with the suite it
     * was last seen in and master failures looked up in any suite. The entry is read as that suite's
     * row, without the other suites' failures, and is looked up again on the next harvest.
     */
    @Test
    void aSnapshotEntryKeptPerTestIdLosesTheOtherSuitesFailures(@TempDir Path dir) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode entry = mapper.createObjectNode()
            .put("testId", RECONNECT).put("name", NAME).put("suite", LINUX).put("suiteName", "Platform C++ (Linux)")
            .put("suiteBuildId", 9388970L).put("occurrenceId", "build:(id:9388970),id:2").put("branchRuns", "F")
            .put("masterFails", 1).put("masterRuns", 34).put("lastSeen", now).put("masterAnchorAt", now);
        entry.putArray("prs").add(13335);
        entry.set("masterFailures", mapper.valueToTree(List.of(ref(9380401L, CLANG), ref(9380300L, LINUX))));
        Path file = dir.resolve("flaky.json");
        mapper.writeValue(file.toFile(), List.of(entry));
        ci2.masterFailure(LINUX, 9380300L);
        ci2.masterFailure(LINUX, 9380302L);
        ci2.masterFailure(CLANG, 9380401L);

        FlakyStats flaky = flaky();
        flaky.loadFrom(file);

        assertThat(flaky.top(40)).singleElement()
            .extracting(FlakyStats.TopFlaky::suite, FlakyStats.TopFlaky::masterFails, FlakyStats.TopFlaky::masterRuns,
                t -> t.masterFailures().stream().map(FlakyStats.MasterRef::buildId).toList())
            .containsExactly(LINUX, 1, 34, List.of(9380300L));

        flaky.harvest();

        assertThat(flaky.top(40)).singleElement()
            .extracting(t -> t.masterFailures().stream().map(FlakyStats.MasterRef::buildId).toList())
            .isEqualTo(List.of(9380302L, 9380300L));
    }

    private FlakyStats flaky() {
        when(warmer.borrowToken()).thenReturn("tok");

        return new FlakyStats(cache, mapper, tc, warmer);
    }

    private void masterHistory(String suite, int runs, int fails) {
        cache.history(RECONNECT, suite, () -> new RunHistory("F".repeat(fails) + "P".repeat(runs - fails),
            "a".repeat(runs), List.of(RunEnv.UNKNOWN), List.of()));
    }

    /** An analysis of PR {@code pr} that let the test off in each of {@code suites} as failing on master. */
    private void filtered(int pr, String... suites) {
        List<TestVerdict> verdicts = Arrays.stream(suites)
            .map(s -> new TestVerdict(RECONNECT, NAME, s, 9388970L, s, "build:(id:9388970),id:2", false, false,
                "pre-existing", "F", 1))
            .toList();

        cache.putResult(9389046L, new AnalysisResult(pr, 9389046L, "pull/" + pr + "/head", System.currentTimeMillis(),
            List.of(), List.of(), verdicts, List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0, 0));
    }

    private static FlakyStats.MasterRef ref(long buildId, String suite) {
        return new FlakyStats.MasterRef(buildId, suite, FakeTeamCity.occurrenceId(buildId));
    }

    /** Master runs of {@link #RECONNECT} that failed, answered for any suite or for the one the locator names. */
    private static final class FakeTeamCity implements AutoCloseable {
        private static final Pattern BUILD_TYPE = Pattern.compile("buildType:\\(id:([^)]+)\\)");

        private final List<String[]> failures = new CopyOnWriteArrayList<>();

        private final HttpServer server;

        FakeTeamCity() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/app/rest/testOccurrences", ex -> {
                Matcher bt = BUILD_TYPE.matcher(param(ex.getRequestURI().getRawQuery(), "locator"));
                String suite = bt.find() ? bt.group(1) : null;
                String occurrences = failures.stream()
                    .filter(f -> suite == null || suite.equals(f[0]))
                    .map(f -> "{\"id\":\"" + occurrenceId(Long.parseLong(f[1])) + "\",\"status\":\"FAILURE\","
                        + "\"build\":{\"id\":" + f[1] + ",\"buildTypeId\":\"" + f[0] + "\"}}")
                    .collect(Collectors.joining(","));
                byte[] body = ("{\"testOccurrence\":[" + occurrences + "]}").getBytes(UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";
        }

        void masterFailure(String suite, long buildId) {
            failures.add(new String[] {suite, Long.toString(buildId)});
        }

        static String occurrenceId(long buildId) {
            return "build:(id:" + buildId + "),id:2";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static String param(String rawQuery, String name) {
            for (String kv : rawQuery.split("&")) {
                int eq = kv.indexOf('=');
                if (kv.substring(0, eq).equals(name))
                    return URLDecoder.decode(kv.substring(eq + 1), UTF_8);
            }

            return "";
        }
    }
}
