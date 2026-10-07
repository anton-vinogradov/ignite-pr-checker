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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fix-master queue as the audit found it: 12 of its 40 rows failed 100 of 100 master runs, the top eight
 * tests muted on TeamCity since 2017–2018 (testJobIdCollision, IGNITE-4706) or failing by design
 * (CacheNearReaderUpdateTest with fail("IGNITE-627")), every row asked the AI to "stabilise a flaky test", and
 * "252 open PRs" counted every PR a test had hit since it first showed up, 40 of them open.
 * Muted tests leave the queue, a test failing each of its latest master runs is broken rather than flaky and
 * ranks after the flaky ones, and a PR counts for 14 days after the test last failed there.
 */
class FlakyQueueTest {
    private static final String CACHE = "IgniteTests24Java8_Cache";

    private static final long JOB_ID_COLLISION = 101L;

    private static final long REBALANCE = 102L;

    private static final long NEAR_READER = 103L;

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
    void aTestMutedOnTeamCityLeavesTheQueue() {
        masterHistory(JOB_ID_COLLISION, "F".repeat(100));
        masterHistory(REBALANCE, "PPFPPPPPPF");
        ci2.muted(JOB_ID_COLLISION);
        filtered(13655, JOB_ID_COLLISION, REBALANCE);

        FlakyStats flaky = flaky();
        flaky.harvest();

        assertThat(flaky.top(40)).extracting(FlakyStats.TopFlaky::testId).containsExactly(REBALANCE);
        assertThat(flaky.mutedCount()).isEqualTo(1);
        assertThat(flaky.trackedCount()).isEqualTo(2);
    }

    @Test
    void aTestFailingItsLatestMasterRunsIsBrokenAndRanksAfterTheFlakyOnes() {
        masterHistory(NEAR_READER, "F".repeat(100));
        masterHistory(JOB_ID_COLLISION, "F".repeat(12) + "P".repeat(88));
        masterHistory(REBALANCE, "FFFPPPPPPPPPPPPPPFPP");
        filtered(13655, NEAR_READER, JOB_ID_COLLISION, REBALANCE);

        FlakyStats flaky = flaky();
        flaky.harvest();

        assertThat(flaky.top(40))
            .extracting(FlakyStats.TopFlaky::testId, FlakyStats.TopFlaky::broken, FlakyStats.TopFlaky::failStreak)
            .containsExactly(tuple(REBALANCE, false, 3), tuple(NEAR_READER, true, 100),
                tuple(JOB_ID_COLLISION, true, 12));
    }

    @Test
    void aPrCountsForFourteenDaysAfterTheTestFailedThere(@TempDir Path dir) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode entry = entry(REBALANCE, now);
        entry.putArray("prs").add(13655).add(12584);
        entry.putObject("prSeen").put("13655", now - Duration.ofDays(1).toMillis())
            .put("12584", now - Duration.ofDays(20).toMillis());
        entry.put("muted", false);
        Path file = dir.resolve("flaky.json");
        mapper.writeValue(file.toFile(), List.of(entry));

        FlakyStats flaky = flaky();
        flaky.loadFrom(file);

        assertThat(flaky.top(40)).singleElement()
            .extracting(FlakyStats.TopFlaky::prCount, FlakyStats.TopFlaky::prs)
            .containsExactly(1, List.of(13655));
    }

    /**
     * A snapshot of v1.22.1 lists every PR a test ever hit, undated, and says nothing about mutes. Its PRs are not
     * counted, and each of its tests is asked about a mute on the first harvest, however recently it was anchored.
     */
    @Test
    void aSnapshotOfTheOlderReleaseIsCleanedOnStart(@TempDir Path dir) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode muted = entry(JOB_ID_COLLISION, now);
        IntStream.rangeClosed(1, 252).forEach(muted.putArray("prs")::add);
        ObjectNode flakyOne = entry(REBALANCE, now);
        flakyOne.putArray("prs").add(13655);
        Path file = dir.resolve("flaky.json");
        mapper.writeValue(file.toFile(), List.of(muted, flakyOne));
        ci2.muted(JOB_ID_COLLISION);

        FlakyStats flaky = flaky();
        flaky.loadFrom(file);

        assertThat(flaky.top(40))
            .extracting(FlakyStats.TopFlaky::testId, FlakyStats.TopFlaky::prCount, FlakyStats.TopFlaky::broken)
            .containsExactly(tuple(REBALANCE, 0, false), tuple(JOB_ID_COLLISION, 0, true));

        flaky.harvest();

        assertThat(flaky.top(40)).extracting(FlakyStats.TopFlaky::testId).containsExactly(REBALANCE);
    }

    @Test
    void whatTheQueueKeepsSurvivesARestart(@TempDir Path dir) throws IOException {
        masterHistory(JOB_ID_COLLISION, "F".repeat(100));
        masterHistory(REBALANCE, "F".repeat(12) + "P".repeat(88));
        ci2.muted(JOB_ID_COLLISION);
        filtered(13655, JOB_ID_COLLISION, REBALANCE);
        FlakyStats before = flaky();
        before.harvest();
        Path file = dir.resolve("flaky.json");
        before.saveTo(file);

        FlakyStats after = flaky();
        after.loadFrom(file);

        assertThat(after.top(40))
            .extracting(FlakyStats.TopFlaky::testId, FlakyStats.TopFlaky::failStreak, FlakyStats.TopFlaky::prs)
            .containsExactly(tuple(REBALANCE, 12, List.of(13655)));
        assertThat(after.mutedCount()).isEqualTo(1);
    }

    private FlakyStats flaky() {
        when(warmer.borrowToken()).thenReturn("tok");

        return new FlakyStats(cache, mapper, tc, warmer);
    }

    /** {@code runs}: 'F' or 'P' per master run, newest first. */
    private void masterHistory(long testId, String runs) {
        cache.history(testId, CACHE, () -> new RunHistory(runs, "a".repeat(runs.length()), List.of(RunEnv.UNKNOWN),
            List.of()));
    }

    /** An analysis of PR {@code pr} that let off each of {@code tests} as failing on master. */
    private void filtered(int pr, long... tests) {
        List<TestVerdict> verdicts = Arrays.stream(tests)
            .mapToObj(t -> new TestVerdict(t, "org.apache.ignite.Test" + t + ".test", CACHE, 9388970L, "Cache",
                "build:(id:9388970),id:" + t, false, false, "pre-existing", "F", 1))
            .toList();

        cache.putResult(9389046L, new AnalysisResult(pr, 9389046L, "pull/" + pr + "/head", System.currentTimeMillis(),
            List.of(), List.of(), verdicts, List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0, 0));
    }

    /** A row as a snapshot of v1.22.1 kept it, anchored a minute ago to one master failure of its suite. */
    private ObjectNode entry(long testId, long now) {
        ObjectNode entry = mapper.createObjectNode()
            .put("testId", testId).put("name", "org.apache.ignite.Test" + testId + ".test").put("suite", CACHE)
            .put("suiteName", "Cache").put("suiteBuildId", 9388970L).put("occurrenceId", "build:(id:9388970),id:1")
            .put("branchRuns", "FFF").put("masterFails", testId == REBALANCE ? 3 : 100).put("masterRuns", 100)
            .put("lastSeen", now).put("masterAnchorAt", now - 60_000);
        entry.set("masterFailures", mapper.valueToTree(List.of(Map.of("buildId", 9380300L, "btId", CACHE,
            "occ", "build:(id:9380300),id:1"))));

        return entry;
    }

    /** Master runs of the tests in {@link #CACHE}: one failure each, and whether TeamCity has the test muted now. */
    private static final class FakeTeamCity implements AutoCloseable {
        private static final Pattern TEST = Pattern.compile("test:\\(id:(\\d+)\\)");

        private final Set<Long> muted = ConcurrentHashMap.newKeySet();

        private final HttpServer server;

        FakeTeamCity() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/app/rest/testOccurrences", ex -> {
                String query = ex.getRequestURI().getRawQuery();
                Matcher test = TEST.matcher(param(query, "locator"));
                long id = test.find() ? Long.parseLong(test.group(1)) : 0;
                // Like TeamCity, it answers only the fields asked for.
                String mute = param(query, "fields").contains("currentlyMuted")
                    ? "\"currentlyMuted\":" + muted.contains(id) + "," : "";
                byte[] body = ("{\"testOccurrence\":[{\"id\":\"build:(id:9380300),id:1\",\"status\":\"FAILURE\","
                    + mute + "\"build\":{\"id\":9380300,\"buildTypeId\":\"" + CACHE + "\"}}]}").getBytes(UTF_8);
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

        void muted(long testId) {
            muted.add(testId);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static String param(String rawQuery, String name) {
            for (String kv : rawQuery.split("&")) {
                if (kv.startsWith(name + "="))
                    return URLDecoder.decode(kv.substring(name.length() + 1), UTF_8);
            }

            return "";
        }
    }
}
