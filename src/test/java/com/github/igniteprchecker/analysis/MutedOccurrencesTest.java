package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Muted test occurrences follow one rule in every suite: they are skipped. Replays ci2 chain 9389046
 * (apache/ignite#13335), where TeamCity reported "Tests failed: 15" plus 8 muted and the checker
 * counted 16: the muted {@code CacheConfigurationP2PTest} got in only because Cache 14 also failed
 * on another test, while the 7 muted failures of green suites were never looked at.
 *
 * <p>The checker talks to a stand-in for TeamCity that honours the bits of its REST semantics the rule
 * runs into, all checked against ci2 on this very chain: a {@code muted:false} locator drops muted
 * occurrences, a build's test summary carries its {@code muted} count only when asked for, and a red
 * suite carries the problem it went red for. Muted failures are never that problem: Cache 14 failed one
 * test and one muted, and its {@code TC_FAILED_TESTS} reads "1 failed test detected".
 */
class MutedOccurrencesTest {
    private static final String TOK = "tok";

    private static final int PR = 13335;

    private static final long P2P = -7245597345807342674L;

    private static final long READ_ONLY_DDL = -5056430872436270883L;

    private final FakeTeamCity ci2 = new FakeTeamCity();

    private final TcClient tc = new TcClient(new TeamcityProperties(ci2.baseUrl()),
        new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(new ObjectMapper()));

    @AfterEach
    void stopTeamCity() {
        ci2.close();
    }

    @Test
    void aMutedFailureIsNoCandidateInARedSuiteJustAsInAGreenOne() {
        ci2.chain(9389046,
            new Suite(9389005, "Cache14", "Cache 14", "FAILURE", 193,
                new Problem("TC_FAILED_TESTS", "1 failed test detected")),
            new Suite(9388938, "ContinuousQuery2", "Continuous Query 2", "SUCCESS", 144));
        ci2.occurrence(9389005, P2P, "CacheConfigurationP2PTest.testCacheConfigurationP2P", true);
        ci2.occurrence(9389005, READ_ONLY_DDL, "GridCacheSqlDdlClusterReadOnlyModeTest.testAlterTableAllowed", false);
        for (int i = 1; i <= 3; i++)
            ci2.occurrence(9388938, -i, "MutedInGreenSuite.test" + i, true);

        ChainCollector.Chain chain = collect(9389046);

        assertThat(chain.failedTests()).extracting(FailedTest::testId).containsExactly(READ_ONLY_DDL);
        assertThat(chain.brokenSuites()).isEmpty();
    }

    /**
     * A muted failure does not turn a suite red, so a red suite whose only failures are muted went red for
     * another reason, and TeamCity names it: here the run's exit code. With its muted failures skipped it
     * is a red suite without failed tests, and like any such suite it is reported as broken for that
     * reason. Dropping it instead would make it vanish from the verdict without a trace.
     */
    @Test
    void aRedSuiteWhoseOnlyFailuresAreMutedIsBrokenForTheReasonItWentRed() {
        Problem exitCode = new Problem("TC_EXIT_CODE", "Process exited with code 1");
        ci2.chain(999,
            new Suite(101, "OnlyMutedFailed", "Suite with only muted failures", "FAILURE", 58, exitCode),
            new Suite(102, "NoneFailed", "Suite without failed tests", "FAILURE", 58, exitCode));
        ci2.occurrence(101, -1, "MutedInRedSuite.test1", true);
        ci2.occurrence(101, -2, "MutedInRedSuite.test2", true);

        ChainCollector.Chain chain = collect(999);

        assertThat(chain.failedTests()).isEmpty();
        assertThat(chain.brokenSuites())
            .extracting(BrokenSuite::suiteBuildId, BrokenSuite::problems, BrokenSuite::tests)
            .containsExactlyInAnyOrder(
                tuple(101L, List.of("non-zero exit code"), 58),
                tuple(102L, List.of("non-zero exit code"), 58));
    }

    /**
     * A muted failure on the branch is no evidence either way. Suppose the test gets muted after the chain
     * and a re-run of Cache 14 fails it muted. Read as a failure it would extend the blocker streak and
     * anchor the verdict to an occurrence TeamCity itself ignores; read as a pass it would clear a real
     * failure. The test's passes stay in: TeamCity records them with {@code muted:false} even while the
     * test is muted.
     */
    @Test
    void aMutedBranchFailureIsLeftOutOfTheTestsBranchHistory() {
        ci2.occurrence(9389005, READ_ONLY_DDL, "GridCacheSqlDdlClusterReadOnlyModeTest.testAlterTableAllowed", false);
        ci2.occurrence(9390000, READ_ONLY_DDL, "GridCacheSqlDdlClusterReadOnlyModeTest.testAlterTableAllowed", true);

        assertThat(tc.prBranchRuns(TOK, PR, READ_ONLY_DDL, "Cache14")).extracting(o -> o.build().id()).containsExactly(9389005L);
    }

    private ChainCollector.Chain collect(long chainId) {
        return new ChainCollector(tc, mock(SuiteBaseline.class))
            .collectForBuild(TOK, PR, chainId, Executors.newSingleThreadExecutor());
    }

    /** A suite build of the chain, with its test count and the problems it went red for. */
    private record Suite(long id, String buildTypeId, String name, String status, int tests, Problem... problems) {
    }

    /** A build problem as TeamCity reports it. */
    private record Problem(String type, String details) {
    }

    /** One failed occurrence of a test in a suite build of the PR branch. */
    private record Occurrence(long buildId, long testId, String name, boolean muted) {
    }

    /** Just enough of ci2's REST API for the chain walk and the branch history of a test. */
    private static final class FakeTeamCity implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();

        private static final Pattern BUILD = Pattern.compile("(?:^|,)build:\\(id:(-?\\d+)\\)");

        private static final Pattern TEST = Pattern.compile("(?:^|,)test:\\(id:(-?\\d+)\\)");

        private static final Pattern NOT_MUTED = Pattern.compile("(?:^|,)muted:false(?:,|$)");

        private static final Pattern MUTED_COUNT = Pattern.compile("testOccurrences\\([^)]*\\bmuted\\b");

        private final HttpServer server;

        private final Map<Long, List<Suite>> chains = new HashMap<>();

        private final List<Occurrence> occurrences = new ArrayList<>();

        FakeTeamCity() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/app/rest/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/";
        }

        void chain(long id, Suite... suites) {
            chains.put(id, List.of(suites));
        }

        void occurrence(long buildId, long testId, String name, boolean muted) {
            occurrences.add(new Occurrence(buildId, testId, name, muted));
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange ex) throws IOException {
            Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            String path = ex.getRequestURI().getPath();

            Object body;
            if (path.startsWith("/app/rest/builds/id:"))
                body = chainBuild(Long.parseLong(path.substring("/app/rest/builds/id:".length())), query.get("fields"));
            else if (path.equals("/app/rest/builds"))
                body = Map.of("build", List.of()); // no chain newer than the one under test
            else if (path.equals("/app/rest/testOccurrences"))
                body = Map.of("testOccurrence", testOccurrences(query.get("locator")));
            else {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            byte[] json = JSON.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(json);
            }
        }

        private Map<String, Object> chainBuild(long id, String fields) {
            boolean withMuted = MUTED_COUNT.matcher(fields).find();
            List<Map<String, Object>> deps = new ArrayList<>();
            for (Suite s : chains.getOrDefault(id, List.of())) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("count", s.tests());
                if (withMuted)
                    summary.put("muted", occurrences.stream().filter(o -> o.buildId() == s.id() && o.muted()).count());
                List<Map<String, String>> problems = Arrays.stream(s.problems())
                    .map(p -> Map.of("type", p.type(), "details", p.details()))
                    .toList();
                deps.add(Map.of("id", s.id(), "buildTypeId", s.buildTypeId(), "status", s.status(),
                    "state", "finished", "buildType", Map.of("name", s.name()), "testOccurrences", summary,
                    "problemOccurrences", Map.of("problemOccurrence", problems)));
            }

            return Map.of("id", id, "status", "FAILURE", "state", "finished", "branchName", "pull/" + PR + "/head",
                "snapshot-dependencies", Map.of("count", deps.size(), "build", deps));
        }

        private List<Map<String, Object>> testOccurrences(String locator) {
            Long build = longIn(BUILD, locator);
            Long test = longIn(TEST, locator);
            boolean skipMuted = NOT_MUTED.matcher(locator).find();

            List<Map<String, Object>> out = new ArrayList<>();
            for (Occurrence o : occurrences) {
                boolean other = (build != null && build != o.buildId()) || (test != null && test != o.testId());
                if (other || (skipMuted && o.muted()))
                    continue;
                out.add(Map.of("id", o.buildId() + ":" + o.testId(),
                    "name", o.name(), "status", "FAILURE", "muted", o.muted(), "test", Map.of("id", o.testId()),
                    "build", Map.of("id", o.buildId(), "state", "finished", "status", "FAILURE")));
            }

            return out;
        }

        private static Long longIn(Pattern p, String locator) {
            Matcher m = p.matcher(locator);

            return m.find() ? Long.parseLong(m.group(1)) : null;
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> params = new HashMap<>();
            for (String kv : raw == null ? new String[0] : raw.split("&")) {
                int eq = kv.indexOf('=');
                params.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }

            return params;
        }
    }
}
