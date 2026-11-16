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
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Master timelines come through the real TeamCity client and retain only dated evidence for a possible regression. */
class FlakyMasterHistoryTest {
    private static final long TEST = 42L;

    private static final int PR = 13786;

    private static final String SUITE = "IgniteTests24Java8_Cache";

    private static final String SIBLING = "IgniteTests24Java8_Queries";

    private static final String NAME = "org.apache.ignite.CacheTest.testReconnect";

    private static final List<String> DATES = List.of("20261115T000100+0000", "20261115T000200+0000",
        "20261115T000300+0000", "20261115T000400+0000", "20261115T000500+0000", "20261115T000600+0000");

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final MasterTeamCity ci = new MasterTeamCity();

    private final AnalysisCache cache =
        new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null, null), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final TcClient tc = new TcClient(new TeamcityProperties(ci.baseUrl()),
        new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(mapper));

    @AfterEach
    void stopTeamCity() {
        ci.close();
    }

    /** Given shuffled build IDs in two suites, harvesting orders each timeline by finish date and keeps its own streak. */
    @Test
    void datesOrderTheObservedTransitionWithoutAnotherSuitesRuns() {
        observed(Map.of(SUITE, "PFF", SIBLING, "FPP"));
        ci.add(200, SUITE, "FAILURE", DATES.get(2));
        ci.add(300, SUITE, "SUCCESS", DATES.get(0));
        ci.add(100, SUITE, "FAILURE", DATES.get(1));
        ci.add(400, SIBLING, "FAILURE", DATES.get(0));
        ci.add(500, SIBLING, "SUCCESS", DATES.get(1));
        ci.add(600, SIBLING, "SUCCESS", DATES.get(2));

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.TopFlaky row = row(stats, SUITE);
        long lastPassAt = Instant.parse("2026-11-15T00:01:00Z").getEpochSecond();
        long firstFailureAt = Instant.parse("2026-11-15T00:02:00Z").getEpochSecond();
        long latestFailureAt = Instant.parse("2026-11-15T00:03:00Z").getEpochSecond();

        assertThat(row.masterHistory()).extracting(FlakyStats.MasterRun::buildId).containsExactly(300L, 100L, 200L);
        assertThat(row.masterHistory()).extracting(FlakyStats.MasterRun::status).containsExactly("P", "F", "F");
        assertThat(row.masterHistory()).extracting(FlakyStats.MasterRun::finishedAt)
            .containsExactly(lastPassAt, firstFailureAt, latestFailureAt);
        assertThat(row.attention()).isEqualTo(new FlakyStats.Attention(
            new FlakyStats.MasterRun(300, SUITE, occurrence(300), "P", lastPassAt),
            new FlakyStats.MasterRun(100, SUITE, occurrence(100), "F", firstFailureAt), 2));
        assertThat(row.historyCheckedAt()).isPositive();
        assertThat(row.masterFailures()).extracting(FlakyStats.MasterRef::buildId).containsExactly(200L, 100L);
        assertThat(row(stats, SIBLING).masterHistory()).extracting(FlakyStats.MasterRun::buildId)
            .containsExactly(400L, 500L, 600L);
        assertThat(row(stats, SIBLING).attention()).isNull();
        assertThat(ci.locators).hasSize(2)
            .anySatisfy(locator -> assertThat(locator).contains("buildType:(id:" + SUITE + ")"))
            .anySatisfy(locator -> assertThat(locator).contains("buildType:(id:" + SIBLING + ")"));
        assertThat(ci.locators).allSatisfy(locator -> assertThat(locator).contains("count:50"));
    }

    /** Given an older one-run cache, a fresh observed pass and two failures still put the candidate on the board. */
    @Test
    void freshTransitionIsVisibleBeforeTheClassifierCacheRefreshes() {
        observed(Map.of(SUITE, "F"));
        ci.add(100, SUITE, "SUCCESS", DATES.get(0));
        ci.add(200, SUITE, "FAILURE", DATES.get(1));
        ci.add(300, SUITE, "FAILURE", DATES.get(2));

        FlakyStats stats = stats();
        stats.harvest();

        FlakyStats.TopFlaky row = row(stats, SUITE);
        assertThat(row.masterFails()).isEqualTo(1);
        assertThat(row.masterRuns()).isEqualTo(1);
        assertThat(row.masterHistory()).extracting(FlakyStats.MasterRun::status).containsExactly("P", "F", "F");
        assertThat(row.attention()).isNotNull();
        assertThat(row.attention().lastPass().buildId()).isEqualTo(100);
        assertThat(row.attention().firstFailure().buildId()).isEqualTo(200);
        assertThat(row.attention().failedRuns()).isEqualTo(2);
    }

    /** Given two tests per group, a one-row limit leaves a candidate, a flaky test and a broken test visible. */
    @Test
    void eachOfTheThreeGroupsKeepsItsOwnDisplayLimit() {
        Map<String, String> histories = Map.of("CandidateA", "PFF", "CandidateB", "PFF",
            "FlakyA", "FP", "FlakyB", "FP", "BrokenA", "FFF", "BrokenB", "FFF");
        observed(histories);
        histories.forEach((suite, history) -> {
            for (int i = 0; i < history.length(); i++)
                ci.add(100 + i, suite, history.charAt(i) == 'F' ? "FAILURE" : "SUCCESS", DATES.get(i));
        });
        FlakyStats stats = stats();
        stats.harvest();

        List<FlakyStats.TopFlaky> rows = stats.top(1);
        assertThat(stats.groupSizes()).isEqualTo(new FlakyStats.GroupSizes(2, 2, 2));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).attention()).isNotNull();
        assertThat(rows.get(1).attention()).isNull();
        assertThat(rows.get(1).broken()).isFalse();
        assertThat(rows.get(2).attention()).isNull();
        assertThat(rows.get(2).broken()).isTrue();
    }

    /** Given recovery, one failure, or only failures, harvesting never invents the pass before a current failing streak. */
    @ParameterizedTest
    @ValueSource(strings = {"PFFP", "PF", "FFF"})
    void onlyAnObservedPassBeforeRepeatedCurrentFailuresGetsAttention(String oldestFirst) {
        observed(Map.of(SUITE, oldestFirst));
        for (int i = 0; i < oldestFirst.length(); i++)
            ci.add(100 + i, SUITE, oldestFirst.charAt(i) == 'F' ? "FAILURE" : "SUCCESS", DATES.get(i));

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(row(stats, SUITE).masterHistory()).hasSize(oldestFirst.length());
        assertThat(row(stats, SUITE).attention()).isNull();
    }

    /** Given an absent or malformed date and non-results, harvesting preserves uncertainty and excludes unfinished runs. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "unreadable"})
    void unknownDatesCannotProveWhenFailuresStarted(String date) {
        observed(Map.of(SUITE, "PFF"));
        ci.add(300, SUITE, "SUCCESS", DATES.get(0));
        ci.add(100, SUITE, "FAILURE", DATES.get(1));
        ci.add(200, SUITE, "FAILURE", date.equals("missing") ? null : "not-a-teamcity-date");
        ci.add(900, SUITE, "UNKNOWN", "finished", "SUCCESS", DATES.get(3));
        ci.add(901, SUITE, "FAILURE", "finished", "UNKNOWN", DATES.get(4));
        ci.add(902, SUITE, "FAILURE", "running", "FAILURE", DATES.get(5));

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.TopFlaky row = row(stats, SUITE);

        assertThat(row.masterHistory()).extracting(FlakyStats.MasterRun::buildId).containsExactly(200L, 300L, 100L);
        assertThat(row.masterHistory().get(0).finishedAt()).isNull();
        assertThat(row.attention()).isNull();
    }

    /** Given a dated pass and failure at the same instant, harvesting does not claim the failure began after that pass. */
    @Test
    void equalFinishTimesDoNotProveAnObservedTransition() {
        observed(Map.of(SUITE, "PFF"));
        ci.add(100, SUITE, "SUCCESS", DATES.get(0));
        ci.add(200, SUITE, "FAILURE", DATES.get(0));
        ci.add(300, SUITE, "FAILURE", DATES.get(1));

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(row(stats, SUITE).masterHistory()).extracting(FlakyStats.MasterRun::buildId)
            .containsExactly(100L, 200L, 300L);
        assertThat(row(stats, SUITE).attention()).isNull();
    }

    /** Given a harvested timeline, saving and loading it retains its evidence without storing a second failure list. */
    @Test
    void aRestartRetainsTheDatedHistoryAndItsAttention(@TempDir Path dir) throws IOException {
        observed(Map.of(SUITE, "PFF"));
        ci.add(300, SUITE, "SUCCESS", DATES.get(0));
        ci.add(100, SUITE, "FAILURE", DATES.get(1));
        ci.add(200, SUITE, "FAILURE", DATES.get(2));
        FlakyStats before = stats();
        before.harvest();
        FlakyStats.TopFlaky original = row(before, SUITE);
        Path file = dir.resolve("flaky.json");
        before.saveTo(file);

        assertThat(mapper.readTree(file.toFile()).get(0).get("masterFailures").isNull()).isTrue();
        assertThat(mapper.readTree(file.toFile()).get(0).get("masterHistory")).hasSize(3);
        FlakyStats after = stats();
        after.loadFrom(file);

        assertThat(row(after, SUITE)).isEqualTo(original);
        assertThat(ci.locators).hasSize(1);
    }

    /** Given recent legacy failure links, loading keeps those links but normal harvest backfills the missing timeline. */
    @Test
    void aLegacyAnchorIsBackfilledEvenWhenItsOldTimestampIsRecent(@TempDir Path dir) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode entry = mapper.createObjectNode().put("testId", TEST).put("name", NAME).put("suite", SUITE)
            .put("suiteName", SUITE).put("suiteBuildId", 8801).put("occurrenceId", occurrence(8801))
            .put("masterFails", 1).put("masterRuns", 3).put("lastSeen", now).put("masterAnchorAt", now)
            .put("muted", false);
        entry.putObject("prSeen").put(String.valueOf(PR), now);
        entry.set("masterFailures", mapper.valueToTree(List.of(new FlakyStats.MasterRef(777, SUITE, occurrence(777)),
            new FlakyStats.MasterRef(888, SIBLING, occurrence(888)))));
        Path file = dir.resolve("flaky.json");
        mapper.writeValue(file.toFile(), List.of(entry));
        FlakyStats stats = stats();
        stats.loadFrom(file);

        assertThat(row(stats, SUITE).masterFailures()).extracting(FlakyStats.MasterRef::buildId).containsExactly(777L);
        assertThat(row(stats, SUITE).historyCheckedAt()).isZero();
        assertThat(row(stats, SUITE).attention()).isNull();
        observed(Map.of(SUITE, "PFF"));
        ci.add(300, SUITE, "SUCCESS", DATES.get(0));
        ci.add(100, SUITE, "FAILURE", DATES.get(1));
        ci.add(200, SUITE, "FAILURE", DATES.get(2));
        stats.harvest();

        assertThat(ci.locators).hasSize(1);
        assertThat(row(stats, SUITE).masterHistory()).extracting(FlakyStats.MasterRun::buildId)
            .containsExactly(300L, 100L, 200L);
        assertThat(row(stats, SUITE).historyCheckedAt()).isPositive();
        assertThat(row(stats, SUITE).attention()).isNotNull();
    }

    /** Gives the harvester real cached PR analyses and master rates; timelines still come only from HTTP. */
    private void observed(Map<String, String> historiesOldestFirst) {
        List<TestVerdict> filtered = new ArrayList<>();
        historiesOldestFirst.forEach((suite, oldestFirst) -> {
            String newestFirst = new StringBuilder(oldestFirst).reverse().toString();
            cache.history(TEST, suite, () -> new RunHistory(newestFirst, "a".repeat(newestFirst.length()),
                List.of(RunEnv.UNKNOWN), List.of()));
            long build = SUITE.equals(suite) ? 8801 : 8802;
            filtered.add(new TestVerdict(TEST, NAME, suite, build, suite, occurrence(build), false, false,
                "pre-existing", "F", 1));
        });
        cache.putResult(8800, new AnalysisResult(PR, 8800, "pull/" + PR + "/head", System.currentTimeMillis(),
            List.of(), List.of(), filtered, List.of(), List.of(), 2, 0, false, 0, false, 0, 0, 0, 0, 0));
    }

    private FlakyStats stats() {
        when(warmer.borrowToken()).thenReturn("fake-teamcity-token");
        return new FlakyStats(cache, mapper, tc, warmer);
    }

    private static FlakyStats.TopFlaky row(FlakyStats stats, String suite) {
        return stats.top(40).stream().filter(r -> suite.equals(r.suite())).findFirst().orElseThrow();
    }

    private static String occurrence(long buildId) {
        return "build:(id:" + buildId + "),id:42";
    }

    /** Serves raw master occurrences and honors the requested suite, as TeamCity does for a shared test ID. */
    private final class MasterTeamCity implements AutoCloseable {
        private final Pattern buildType = Pattern.compile("buildType:\\(id:([^)]+)\\)");

        private final List<ObjectNode> rows = new CopyOnWriteArrayList<>();

        private final List<String> locators = new CopyOnWriteArrayList<>();

        private final HttpServer server;

        MasterTeamCity() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/app/rest/testOccurrences", ex -> {
                try {
                    String locator = parameter(ex.getRequestURI().getRawQuery(), "locator");
                    locators.add(locator);
                    Matcher match = buildType.matcher(locator);
                    String suite = match.find() ? match.group(1) : null;
                    List<ObjectNode> found = rows.stream()
                        .filter(r -> suite == null || suite.equals(r.get("build").get("buildTypeId").asText()))
                        .toList();
                    byte[] body = mapper.writeValueAsBytes(Map.of("testOccurrence", found));
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                }
                finally {
                    ex.close();
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        void add(long buildId, String suite, String status, String date) {
            add(buildId, suite, status, "finished", status, date);
        }

        void add(long buildId, String suite, String status, String state, String buildStatus, String date) {
            ObjectNode row = mapper.createObjectNode().put("id", occurrence(buildId)).put("status", status)
                .put("currentlyMuted", false);
            ObjectNode build = row.putObject("build").put("id", buildId).put("buildTypeId", suite)
                .put("state", state).put("status", buildStatus);
            if (date != null) build.put("finishDate", date);
            rows.add(row);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String parameter(String raw, String name) {
        if (raw == null) return "";
        for (String field : raw.split("&")) {
            int equals = field.indexOf('=');
            if (equals >= 0 && field.substring(0, equals).equals(name))
                return URLDecoder.decode(field.substring(equals + 1), UTF_8);
        }
        return "";
    }
}
