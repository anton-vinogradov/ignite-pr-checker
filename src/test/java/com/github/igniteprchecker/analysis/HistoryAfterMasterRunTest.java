package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The board read a test's master history once in 24 hours: a test that broke in the morning's master run was newly
 * broken on the board the next day, and a fixed one stayed there as long. A suite's new master run now has the
 * histories it may have changed read in the next cycles. Both timers run here by hand against a stand-in for TeamCity
 * that lists master's chains, gives a chain's suite runs and a test's master runs: {@code requests} are "chains",
 * "chain N" and "history N" for the history of test N. A history is written oldest run first, P passed, F failed.
 */
class HistoryAfterMasterRunTest {
    private static final String CACHE = "Cache_1";

    private static final String PDS = "PDS_Indexing";

    private static final String QUERIES = "Queries_1";

    /** Nine passes and a failure: a flaky test, newly broken with one more failure. */
    private static final String FAILED_ONCE = "PPPPPPPPPF";

    /** A flaky test whose latest run passed. */
    private static final String PASSES_NOW = "FPPPPPPPPP";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final Master ci = new Master();

    private final AnalysisCache cache =
        new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null, null), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final AnalysisProperties analysis = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final TcClient tc = new TcClient(new TeamcityProperties(ci.baseUrl()), analysis, new Metrics(mapper));

    private final List<TestVerdict> seen = new ArrayList<>();

    private FlakyStats stats = stats();

    private MasterSuites suites = suites(stats);

    @AfterEach
    void stopTeamCity() {
        ci.close();
    }

    /**
     * A run of a suite that failed some tests, or reported none, may have changed the history of any of its tests. A
     * run that passed changes the place of those alone whose history ended with a failure. The other histories wait
     * for their day.
     */
    @Test
    void aSuitesNewRunHasTheHistoriesItMayHaveChangedReadAnew() {
        test(1, CACHE, FAILED_ONCE);
        test(2, CACHE, PASSES_NOW);
        test(3, PDS, FAILED_ONCE + "F");
        test(4, PDS, PASSES_NOW);
        test(5, QUERIES, PASSES_NOW);
        ci.chain(101, ok(11, CACHE, 1), ok(12, PDS, 1), ok(13, QUERIES, 1));
        bothRead();

        assertThat(row(1).attention()).isNull();
        assertThat(row(3).attention()).isNotNull();
        ci.histories.put(1L, FAILED_ONCE + "F");
        ci.histories.put(3L, FAILED_ONCE + "FP");
        ci.chain(102, failed(21, CACHE, 2, 100, 1), ok(22, PDS, 2), failed(23, QUERIES, 2, 30, 0, "TC_EXECUTION_TIMEOUT"));
        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactlyInAnyOrder("chains", "chain 102", "history 1", "history 2", "history 3",
            "history 5");
        assertThat(row(1).history().status()).isEqualTo("PPPPPPPPPFF");
        assertThat(row(1).attention()).as("broke in the run just read").isNotNull();
        assertThat(row(3).history().status()).isEqualTo("PPPPPPPPPFFP");
        assertThat(row(3).attention()).as("passed in the run just read").isNull();
        ci.requests.clear();
        suites.scan();
        stats.harvest();

        assertThat(ci.requests).as("a history is read anew once").containsExactly("chains");
    }

    /**
     * Before the first chain is read nothing says what the suites did since the histories were read. So the newest
     * chain has the history of every test on the board whose suite ran in it read anew; the older chains read after
     * it ask for nothing.
     */
    @Test
    void theFirstChainReadAsksForEveryTestOfItsSuites() {
        test(1, CACHE, PASSES_NOW);
        test(2, PDS, PASSES_NOW);
        test(3, QUERIES, PASSES_NOW);
        ci.chain(101, failed(11, CACHE, 1, 100, 1), failed(12, PDS, 1, 100, 1));
        ci.chain(102, ok(21, CACHE, 2), ok(22, PDS, 2));
        stats.harvest();
        ci.requests.clear();

        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactlyInAnyOrder("chains", "chain 102", "chain 101", "history 1", "history 2");
    }

    /**
     * After the first start the older chains are read five a cycle. A failed run in one of them is not news: the
     * suite has run since.
     */
    @Test
    void anOlderChainReadLaterAsksForNothing() {
        test(1, CACHE, PASSES_NOW);
        ci.chain(101, failed(1, CACHE, 1, 100, 1));
        for (int i = 2; i <= 6; i++)
            ci.chain(100 + i, ok(i, CACHE, i));
        stats.harvest();
        suites.scan();
        stats.harvest();
        ci.requests.clear();

        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactly("chains", "chain 101");
        assertThat(suites.chainsRead()).isEqualTo(6);
    }

    /**
     * TeamCity reuses a suite's build in the next chain when nothing changed: that is no new run of the suite. A
     * suite with no test on the board has no history to read.
     */
    @Test
    void aReusedBuildAndASuiteOffTheBoardAskForNothing() {
        test(1, CACHE, PASSES_NOW);
        ci.chain(101, failed(11, CACHE, 1, 100, 1), ok(12, QUERIES, 1));
        bothRead();

        ci.chain(102, failed(11, CACHE, 1, 100, 1), failed(22, QUERIES, 2, 100, 3));
        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactly("chains", "chain 102");
    }

    /**
     * Two chains may finish within one cycle. A suite's latest run says where its tests stand now: here it passed, so
     * the test that was failing is read anew, and the one that passes waits for its day though the run before failed.
     */
    @Test
    void ofTwoNewChainsTheNewerRunOfASuiteIsTheOneThatCounts() {
        test(1, CACHE, FAILED_ONCE);
        test(2, CACHE, PASSES_NOW);
        ci.chain(101, ok(11, CACHE, 1));
        bothRead();

        ci.chain(102, failed(21, CACHE, 2, 100, 1));
        ci.chain(103, ok(31, CACHE, 3));
        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactly("chains", "chain 103", "chain 102", "history 1");
    }

    /**
     * A chain that started earlier may finish later, and is then read after the newer one. Its run of a suite is the
     * suite's latest when it finished last, as a test's history has it.
     */
    @Test
    void aRunThatFinishedLastCountsThoughItsChainIsOlder() {
        test(1, CACHE, PASSES_NOW);
        ci.chain(100, ok(1, CACHE, 1));
        ci.chain(102, ok(21, CACHE, 2));
        bothRead();

        ci.chain(101, failed(11, CACHE, 3, 100, 1));
        suites.scan();
        stats.harvest();

        assertThat(ci.requests).containsExactly("chains", "chain 101", "history 1");
    }

    /**
     * A test that came to the board after the chain was read has its history read as any new test has, once: that
     * reading has the run.
     */
    @Test
    void aTestThatCameAfterTheChainWasReadIsReadOnce() {
        test(1, CACHE, PASSES_NOW);
        ci.chain(101, ok(11, CACHE, 1));
        bothRead();

        ci.chain(102, failed(21, CACHE, 2, 100, 1));
        suites.scan();
        test(2, CACHE, FAILED_ONCE);
        stats.harvest();
        stats.harvest();

        assertThat(ci.requests).containsExactly("chains", "chain 102", "history 2", "history 1");
    }

    /**
     * A cycle reads ten histories. The tests never asked about a mute go first, then those a master run asked for,
     * then those read a day ago, which nothing asked for.
     */
    @Test
    void theHistoriesAskedForGoBeforeTheDayOldOnes(@TempDir Path dir) throws IOException {
        LongStream.rangeClosed(1, 11).forEach(id -> test(id, CACHE, PASSES_NOW));
        test(20, PDS, PASSES_NOW);
        stats.harvest();
        stats.harvest();
        dayOld(dir, 20);
        ci.chain(101, ok(11, CACHE, 1));
        suites.scan();
        test(30, QUERIES, PASSES_NOW);
        ci.requests.clear();

        stats.harvest();
        List<String> first = List.copyOf(ci.requests);
        ci.requests.clear();
        stats.harvest();
        List<String> second = List.copyOf(ci.requests);
        ci.requests.clear();
        stats.harvest();

        assertThat(first).hasSize(10).startsWith("history 30").doesNotContain("history 20");
        assertThat(second).hasSize(3).endsWith("history 20");
        assertThat(first.subList(1, 10)).doesNotContainAnyElementsOf(second);
        assertThat(ci.requests).isEmpty();
    }

    /**
     * What a master run asked for stands until the history is read: through a cycle with no token to borrow, and
     * through one in which TeamCity fails, which ends after three failed calls as before.
     */
    @Test
    void whatWasAskedForWaitsForACycleThatCanReadIt() {
        LongStream.rangeClosed(1, 4).forEach(id -> test(id, CACHE, PASSES_NOW));
        stats.harvest();
        ci.chain(101, ok(11, CACHE, 1));
        suites.scan();
        ci.requests.clear();

        when(warmer.borrowToken()).thenReturn(null);
        stats.harvest();

        assertThat(ci.requests).isEmpty();
        when(warmer.borrowToken()).thenReturn("token");
        ci.failing = true;
        stats.harvest();

        assertThat(ci.requests).hasSize(3);
        ci.failing = false;
        ci.requests.clear();
        stats.harvest();
        stats.harvest();

        assertThat(ci.requests).containsExactlyInAnyOrder("history 1", "history 2", "history 3", "history 4");
    }

    /** The chains read survive a restart, so what they asked for does too: no later cycle would ask again. */
    @Test
    void whatWasAskedForSurvivesARestart(@TempDir Path dir) throws IOException {
        test(1, CACHE, PASSES_NOW);
        stats.harvest();
        ci.chain(101, ok(11, CACHE, 1));
        suites.scan();
        Path file = dir.resolve("flaky.json");
        stats.saveTo(file);
        ci.requests.clear();

        stats = stats();
        stats.loadFrom(file);
        stats.harvest();
        stats.saveTo(file);

        assertThat(ci.requests).containsExactly("history 1");
        assertThat(mapper.readTree(file.toFile()).get(0).get("rereadAsked").asBoolean()).isFalse();
    }

    /** Both timers until neither has anything to read, and a log of requests that starts anew. */
    private void bothRead() {
        stats.harvest();
        suites.scan();
        stats.harvest();
        suites.scan();
        ci.requests.clear();
    }

    /**
     * A test with these master runs, in TeamCity and in the PR analyses, which saw it fail in a PR as it fails on
     * master.
     */
    private void test(long id, String suite, String oldestFirst) {
        ci.histories.put(id, oldestFirst);
        String newestFirst = new StringBuilder(oldestFirst).reverse().toString();
        cache.history(id, suite, () -> new RunHistory(newestFirst, "a".repeat(newestFirst.length()),
            List.of(RunEnv.UNKNOWN), List.of()));
        seen.add(new TestVerdict(id, "org.apache.ignite.Test" + id + ".test", suite, 8801, suite, "occ-" + id, false, false,
            "pre-existing", "F", 1));
        cache.putResult(8800, new AnalysisResult(13786, 8800, "pull/13786/head", System.currentTimeMillis(), List.of(),
            List.of(), List.copyOf(seen), List.of(), List.of(), 2, 0, false, 0, false, 0, 0, 0, 0, 0));
    }

    /** The board after a restart with a snapshot in which the history of {@code test} was read 25 hours ago. */
    private void dayOld(Path dir, long test) throws IOException {
        Path file = dir.resolve("flaky.json");
        stats.saveTo(file);
        JsonNode kept = mapper.readTree(file.toFile());
        for (JsonNode entry : kept) {
            if (entry.get("testId").asLong() == test)
                ((ObjectNode) entry).put("masterAnchorAt", System.currentTimeMillis() - Duration.ofHours(25).toMillis());
        }
        mapper.writeValue(file.toFile(), kept);
        stats = stats();
        stats.loadFrom(file);
        suites = suites(stats);
    }

    private FlakyStats stats() {
        when(warmer.borrowToken()).thenReturn("token");

        return new FlakyStats(cache, mapper, tc, warmer, mock(GithubClient.class));
    }

    private MasterSuites suites(FlakyStats board) {
        return new MasterSuites(cache, mapper, tc, warmer, mock(SuiteBaseline.class), board, analysis);
    }

    private FlakyStats.TopFlaky row(long test) {
        return stats.top(40).stream().filter(r -> r.testId() == test).findFirst().orElseThrow();
    }

    /** A suite run that passed its 100 tests, finished at hour {@code hour}. */
    private ObjectNode ok(long build, String suite, int hour) {
        return run(build, suite, "SUCCESS", hour, 100, 0);
    }

    private ObjectNode failed(long build, String suite, int hour, int tests, int failedTests, String... problems) {
        return run(build, suite, "FAILURE", hour, tests, failedTests, problems);
    }

    /** A finished suite run as TeamCity gives it among the runs of a chain. */
    private ObjectNode run(long build, String suite, String status, int hour, int tests, int failedTests,
        String... problems) {
        ObjectNode run = mapper.createObjectNode().put("id", build).put("buildTypeId", suite).put("state", "finished")
            .put("status", status).put("finishDate", date(hour));
        run.putObject("testOccurrences").put("count", tests).put("failed", failedTests);
        for (String problem : problems)
            run.withObject("problemOccurrences").withArray("problemOccurrence").addObject().put("type", problem);

        return run;
    }

    /** When a run finished, as TeamCity writes it: at this hour, counted from the start of 1 November. */
    private static String date(int hour) {
        return String.format("202611%02dT%02d0000+0000", 1 + hour / 24, hour % 24);
    }

    private final class Master implements AutoCloseable {
        private final Pattern test = Pattern.compile("test:\\(id:(\\d+)\\).*buildType:\\(id:([^)]+)\\)");

        private final Map<Long, List<ObjectNode>> chains = new TreeMap<>(Comparator.reverseOrder());

        /** Test id -> its master runs, oldest first. */
        private final Map<Long, String> histories = new ConcurrentHashMap<>();

        private final List<String> requests = new CopyOnWriteArrayList<>();

        /** Whether a request for a history gets a 500. */
        private volatile boolean failing;

        private final HttpServer server;

        Master() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/app/rest/builds", ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/builds")) {
                    requests.add("chains");
                    answer(ex, 200, Map.of("build", chains.keySet().stream().map(id -> Map.of("id", id)).toList()));
                }
                else {
                    long chain = Long.parseLong(path.substring(path.indexOf("id:") + 3));
                    requests.add("chain " + chain);
                    answer(ex, 200, Map.of("snapshot-dependencies", Map.of("build", chains.get(chain))));
                }
            });
            server.createContext("/app/rest/testOccurrences", ex -> {
                Matcher asked = test.matcher(parameter(ex.getRequestURI().getRawQuery(), "locator"));
                long id = asked.find() ? Long.parseLong(asked.group(1)) : 0;
                requests.add("history " + id);
                String runs = histories.get(id);
                List<ObjectNode> rows = new ArrayList<>();
                for (int i = 0; i < runs.length(); i++) {
                    String status = runs.charAt(i) == 'F' ? "FAILURE" : "SUCCESS";
                    ObjectNode row = mapper.createObjectNode().put("id", "build:(id:" + (1000 + i) + "),id:" + id)
                        .put("status", status).put("currentlyMuted", false);
                    row.putObject("build").put("id", 1000 + i).put("buildTypeId", asked.group(2)).put("state", "finished")
                        .put("status", status).put("finishDate", date(i));
                    rows.add(row);
                }
                answer(ex, failing ? 500 : 200, Map.of("testOccurrence", rows));
            });
            server.start();
        }

        private void answer(HttpExchange ex, int status, Object body) throws IOException {
            try {
                byte[] bytes = mapper.writeValueAsBytes(body);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            }
            finally {
                ex.close();
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        void chain(long id, ObjectNode... runs) {
            chains.put(id, List.of(runs));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String parameter(String raw, String name) {
        if (raw == null)
            return "";
        for (String field : raw.split("&")) {
            int equals = field.indexOf('=');
            if (equals >= 0 && field.substring(0, equals).equals(name))
                return URLDecoder.decode(field.substring(equals + 1), UTF_8);
        }

        return "";
    }
}
