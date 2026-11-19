package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.LeaderboardController;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Master timelines come through the real TeamCity client and retain only dated evidence for a possible regression.
 * A history is written run by run, oldest first, a letter each: P passed, F failed; "P*88 F*12" repeats a part.
 */
class FlakyMasterHistoryTest {
    private static final long TEST = 42L;

    private static final int PR = 13786;

    private static final String SUITE = "IgniteTests24Java8_Cache";

    private static final String SIBLING = "IgniteTests24Java8_Queries";

    private static final String NAME = "org.apache.ignite.CacheTest.testReconnect";

    /** Five passes, then three failures: the shortest history of a newly broken test. */
    private static final String BROKE = "PPPPPFFF";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final MasterTeamCity ci = new MasterTeamCity();

    private final AnalysisCache cache =
        new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null, null), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final AnalysisProperties analysis = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final TcClient tc = new TcClient(new TeamcityProperties(ci.baseUrl()), analysis, new Metrics(mapper));

    @AfterEach
    void stopTeamCity() {
        ci.close();
    }

    /** Given shuffled build IDs in two suites, harvesting orders each timeline by finish date and keeps its own streak. */
    @Test
    void datesOrderTheObservedTransitionWithoutAnotherSuitesRuns() {
        observed(Map.of(SUITE, BROKE, SIBLING, "FPP"));
        ci.add(SUITE, "PPPP");
        ci.add(200, SUITE, "FAILURE", date(6), "c0ffee2");
        ci.add(300, SUITE, "SUCCESS", date(4), "c0ffee0");
        ci.add(100, SUITE, "FAILURE", date(5), "c0ffee1");
        ci.add(50, SUITE, "FAILURE", date(7), "c0ffee3");
        ci.add(400, SIBLING, "FAILURE", date(0), null);
        ci.add(500, SIBLING, "SUCCESS", date(1), null);
        ci.add(600, SIBLING, "SUCCESS", date(2), null);

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.TopFlaky row = row(stats, SUITE);

        assertThat(row.history().builds()).containsExactly(1000L, 1001L, 1002L, 1003L, 300L, 100L, 200L, 50L);
        assertThat(row.history().status()).isEqualTo(BROKE);
        assertThat(row.history().finished()).containsExactly(at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7));
        assertThat(row.attention()).isEqualTo(new FlakyStats.Attention(new FlakyStats.AttentionRun(300, at(4), "c0ffee0"),
            new FlakyStats.AttentionRun(100, at(5), "c0ffee1"), 3, 0, 5));
        assertThat(row.historyCheckedAt()).isPositive();
        assertThat(row(stats, SIBLING).history().builds()).containsExactly(400L, 500L, 600L);
        assertThat(row(stats, SIBLING).attention()).isNull();
        assertThat(ci.locators).hasSize(2)
            .anySatisfy(locator -> assertThat(locator).contains("buildType:(id:" + SUITE + ")"))
            .anySatisfy(locator -> assertThat(locator).contains("buildType:(id:" + SIBLING + ")"));
        assertThat(ci.locators).allSatisfy(locator -> assertThat(locator).contains("count:100"));
    }

    /** Given an older one-run cache, fresh observed passes and three failures still put the candidate on the board. */
    @Test
    void freshTransitionIsVisibleBeforeTheClassifierCacheRefreshes() {
        observed(Map.of(SUITE, "F"));
        ci.add(SUITE, BROKE);

        FlakyStats stats = stats();
        stats.harvest();

        FlakyStats.TopFlaky row = row(stats, SUITE);
        assertThat(row.masterFails()).isEqualTo(1);
        assertThat(row.masterRuns()).isEqualTo(1);
        assertThat(row.history().status()).isEqualTo(BROKE);
        assertThat(row.attention()).isNotNull();
        assertThat(row.attention().lastPass().buildId()).isEqualTo(1004);
        assertThat(row.attention().firstFailure().buildId()).isEqualTo(1005);
        assertThat(row.attention().failedRuns()).isEqualTo(3);
    }

    /**
     * Given two tests per group, a one-row limit leaves a flaky test and a broken test visible, and both candidates: a
     * cause is told by all its tests, how many broke together and each by name in its prompt. Cut like the other
     * groups, 40 of 88 newly broken tests made "39 tests" of a cause of 87.
     */
    @Test
    void eachOfTheThreeGroupsKeepsItsOwnDisplayLimit() {
        Map<String, String> histories = Map.of("CandidateA", BROKE, "CandidateB", BROKE,
            "FlakyA", "FP", "FlakyB", "FP", "BrokenA", "FFF", "BrokenB", "FFF");
        observed(histories);
        histories.forEach(ci::add);
        FlakyStats stats = stats();
        stats.harvest();

        List<FlakyStats.TopFlaky> rows = stats.top(1);
        assertThat(stats.groupSizes()).isEqualTo(new FlakyStats.GroupSizes(2, 2, 2));
        assertThat(rows).hasSize(4);
        assertThat(rows.subList(0, 2)).extracting(FlakyStats.TopFlaky::attention).doesNotContainNull();
        assertThat(rows.get(2).attention()).isNull();
        assertThat(rows.get(2).broken()).isFalse();
        assertThat(rows.get(3).attention()).isNull();
        assertThat(rows.get(3).broken()).isTrue();
    }

    /**
     * Any flaky test ends with a couple of failures after a pass now and then: under the rule "two failures after a
     * pass", 40 of the board's 91 tests were candidates. A test is newly broken when the failures at the end of its
     * history are too many in a row for how seldom it failed in the five or more runs before them.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
        "P*88 F*12, NEW, passed 88 runs and fails since",
        "P*97 F*2, NEW, two failures after 97 passes",
        "P*9 F*2, NEW, the shortest streak after the fewest passes that makes it",
        "P*8 F*2, FLAKY, two failures after eight passes may be chance",
        "PF*48 P F*3, FLAKY, fails every other run and the last three",
        "F*87 P F*12, BROKEN, failed as often before its one pass",
        "P*4 F*5, FLAKY, four runs before the streak say too little",
        "P*30 F*3 P, FLAKY, passed again",
        "P*30 F, FLAKY, one failure",
        "F*3, BROKEN, no pass at all"})
    void aTestIsNewlyBrokenWhenItsFailuresAreNoChance(String history, String group, String why) {
        String oldestFirst = runs(history);
        observed(Map.of(SUITE, oldestFirst));
        ci.add(SUITE, oldestFirst);

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.TopFlaky row = row(stats, SUITE);

        assertThat(row.history().status()).isEqualTo(oldestFirst);
        assertThat(row.attention() != null ? "NEW" : row.broken() ? "BROKEN" : "FLAKY").as(why).isEqualTo(group);
    }

    /**
     * What the PR analyses saw of master gets old: a test fixed on master fails in no PR that would read its runs
     * anew. Its history is read after the suite's next master run, so a test whose newest run there passed moves out
     * of the tests that always fail.
     */
    @Test
    void aTestWhoseNewestRunPassedDoesNotAlwaysFail() {
        observed(Map.of(SUITE, runs("P*5 F*12")));
        ci.add(SUITE, runs("P*5 F*12 P"));

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(stats.groupSizes()).isEqualTo(new FlakyStats.GroupSizes(0, 1, 0));
    }

    /** The row says what the rule looked at: the runs before the streak and how many of them failed. */
    @Test
    void aNewlyBrokenTestSaysHowOftenItFailedBefore() {
        observed(Map.of(SUITE, runs("P*40 F P*47 F*12")));
        ci.add(SUITE, runs("P*40 F P*47 F*12"));

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(row(stats, SUITE).attention()).isEqualTo(new FlakyStats.Attention(
            new FlakyStats.AttentionRun(1087, at(87), "c1087"), new FlakyStats.AttentionRun(1088, at(88), "c1088"),
            12, 1, 88));
    }

    /** The test that broke last is the first to look at; the other groups keep their order by fail rate. */
    @Test
    void theNewlyBrokenComeLatestBreakFirst() {
        Map<String, String> histories = Map.of("BrokeFirst", "PPPPPFFFF", "BrokeLast", "PPPPPPPFFF",
            "BrokeBetween", "PPPPPPFFF", "FlakyRarely", "FPPP", "FlakyOften", "FFPF");
        observed(histories);
        histories.forEach(ci::add);

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(stats.top(40)).extracting(FlakyStats.TopFlaky::suite)
            .containsExactly("BrokeLast", "BrokeBetween", "BrokeFirst", "FlakyOften", "FlakyRarely");
    }

    /** Given an absent or malformed date and non-results, harvesting preserves uncertainty and excludes unfinished runs. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "unreadable"})
    void unknownDatesCannotProveWhenFailuresStarted(String date) {
        observed(Map.of(SUITE, BROKE));
        ci.add(SUITE, "PPPPPFF");
        ci.add(200, SUITE, "FAILURE", date.equals("missing") ? null : "not-a-teamcity-date", null);
        ci.add(900, SUITE, "UNKNOWN", "finished", "SUCCESS", date(8), null);
        ci.add(901, SUITE, "FAILURE", "finished", "UNKNOWN", date(9), null);
        ci.add(902, SUITE, "FAILURE", "running", "FAILURE", date(10), null);

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.TopFlaky row = row(stats, SUITE);

        assertThat(row.history().builds())
            .containsExactly(200L, 1000L, 1001L, 1002L, 1003L, 1004L, 1005L, 1006L);
        assertThat(row.history().finished().get(0)).isNull();
        assertThat(row.attention()).isNull();
    }

    /** Given a dated pass and failure at the same instant, harvesting does not claim the failure began after that pass. */
    @Test
    void equalFinishTimesDoNotProveAnObservedTransition() {
        observed(Map.of(SUITE, BROKE));
        ci.add(SUITE, "PPPP");
        ci.add(100, SUITE, "SUCCESS", date(4), null);
        ci.add(200, SUITE, "FAILURE", date(4), null);
        ci.add(300, SUITE, "FAILURE", date(5), null);
        ci.add(400, SUITE, "FAILURE", date(6), null);

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(row(stats, SUITE).history().builds())
            .containsExactly(1000L, 1001L, 1002L, 1003L, 100L, 200L, 300L, 400L);
        assertThat(row(stats, SUITE).attention()).isNull();
    }

    /** Given a harvested timeline, saving and loading it retains its evidence without storing a second failure list. */
    @Test
    void aRestartRetainsTheDatedHistoryAndItsAttention(@TempDir Path dir) throws IOException {
        observed(Map.of(SUITE, BROKE));
        ci.add(SUITE, BROKE);
        FlakyStats before = stats();
        before.harvest();
        FlakyStats.TopFlaky original = row(before, SUITE);
        Path file = dir.resolve("flaky.json");
        before.saveTo(file);

        assertThat(mapper.readTree(file.toFile()).get(0).get("masterFailures").isNull()).isTrue();
        assertThat(mapper.readTree(file.toFile()).get(0).get("masterHistory")).hasSize(8);
        FlakyStats after = stats();
        after.loadFrom(file);

        assertThat(original.attention().firstFailure().commit()).isEqualTo("c1005");
        assertThat(original.history().whole()).isTrue();
        assertThat(row(after, SUITE)).isEqualTo(original);
        assertThat(ci.locators).hasSize(1);
    }

    /**
     * The release before this one kept a run without its commit and did not note whether the history was whole. Its
     * snapshot reads as it is: the break is on the board at once, its commits unknown until the next check.
     */
    @Test
    void aSnapshotWithoutCommitsStillLoads(@TempDir Path dir) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode entry = mapper.createObjectNode().put("testId", TEST).put("name", NAME).put("suite", SUITE)
            .put("suiteName", SUITE).put("suiteBuildId", 8801).put("occurrenceId", occurrence(8801))
            .put("masterFails", 3).put("masterRuns", 8).put("failStreak", 3).put("lastSeen", now)
            .put("masterAnchorAt", now).put("muted", false);
        entry.putObject("prSeen").put(String.valueOf(PR), now);
        for (int i = 0; i < BROKE.length(); i++)
            entry.withArray("masterHistory").addObject().put("buildId", 1000 + i).put("btId", SUITE)
                .put("occ", occurrence(1000 + i)).put("status", String.valueOf(BROKE.charAt(i))).put("finishedAt", at(i));
        Path file = dir.resolve("flaky.json");
        mapper.writeValue(file.toFile(), List.of(entry));

        FlakyStats stats = stats();
        stats.loadFrom(file);

        FlakyStats.TopFlaky row = row(stats, SUITE);
        assertThat(row.history()).isEqualTo(new FlakyStats.History(BROKE,
            List.of(1000L, 1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 1007L),
            List.of(at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7)),
            List.of(occurrence(1005), occurrence(1006), occurrence(1007)), false));
        assertThat(row.attention()).isEqualTo(new FlakyStats.Attention(new FlakyStats.AttentionRun(1004, at(4), null),
            new FlakyStats.AttentionRun(1005, at(5), null), 3, 0, 5));
        assertThat(ci.locators).as("the history is not asked for again").isEmpty();
    }

    /**
     * The page polls the board, and a run cost it 120 bytes: its suite, its occurrence and a name for each. A test's
     * runs come as one object: a letter a run, the builds and the finish times side by side, and the occurrences of
     * the failed runs alone, which open the test in a run. The commits come with the two runs of a break.
     */
    @Test
    void thePageGetsARunAsALetterABuildAndATime() throws IOException {
        observed(Map.of(SUITE, BROKE, SIBLING, "FPF"));
        ci.add(SUITE, BROKE);
        ci.add(SIBLING, "FPF");
        FlakyStats stats = stats();
        stats.harvest();

        JsonNode answer = mapper.valueToTree(new LeaderboardController(stats, suites(), analysis).topFlaky(40, false));

        assertThat(answer.get("window").asInt()).isEqualTo(100);
        assertThat(answer.get("attentionCount").asInt()).isEqualTo(1);
        assertThat(answer.get("flakyCount").asInt()).isEqualTo(1);
        JsonNode broke = answer.get("tests").get(0);
        assertThat(broke.propertyNames()).containsExactly("testId", "name", "suite", "suiteName", "occurrenceId",
            "masterFails", "masterRuns", "failStreak", "broken", "prCount", "prs", "history", "historyCheckedAt",
            "attention");
        assertThat(broke.get("history").toString()).isEqualTo("{\"status\":\"PPPPPFFF\","
            + "\"builds\":[1000,1001,1002,1003,1004,1005,1006,1007],\"finished\":[" + at(0) + "," + at(1) + ","
            + at(2) + "," + at(3) + "," + at(4) + "," + at(5) + "," + at(6) + "," + at(7) + "],\"occ\":[\""
            + occurrence(1005) + "\",\"" + occurrence(1006) + "\",\"" + occurrence(1007) + "\"],\"whole\":true}");
        assertThat(broke.get("attention").toString()).isEqualTo("{\"lastPass\":{\"buildId\":1004,\"finishedAt\":"
            + at(4) + ",\"commit\":\"c1004\"},\"firstFailure\":{\"buildId\":1005,\"finishedAt\":" + at(5)
            + ",\"commit\":\"c1005\"},\"failedRuns\":3,\"failsBefore\":0,\"runsBefore\":5}");
        assertThat(answer.get("tests").get(1).get("history").get("occ"))
            .extracting(JsonNode::asText).containsExactly(occurrence(1000), occurrence(1002));
        assertThat(answer.get("tests").get(1).get("attention").isNull()).isTrue();
    }

    /**
     * A test that never passed gets no strip of runs on the page, which links its latest failure alone: 100 occurrence
     * ids a row are left out. TeamCity gave as many runs as it was asked for, so older ones may exist.
     */
    @Test
    void aTestThatNeverPassedNamesItsLatestOccurrenceAlone() {
        observed(Map.of(SUITE, runs("F*100")));
        ci.add(SUITE, runs("F*100"));

        FlakyStats stats = stats();
        stats.harvest();
        FlakyStats.History history = row(stats, SUITE).history();

        assertThat(history.status()).isEqualTo(runs("F*100"));
        assertThat(history.builds()).hasSize(100);
        assertThat(history.occ()).containsExactly(occurrence(1099));
        assertThat(history.whole()).isFalse();
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

        assertThat(row(stats, SUITE).history().builds()).containsExactly(777L);
        assertThat(row(stats, SUITE).historyCheckedAt()).isZero();
        assertThat(row(stats, SUITE).attention()).isNull();
        observed(Map.of(SUITE, BROKE));
        ci.add(SUITE, BROKE);
        stats.harvest();

        assertThat(ci.locators).hasSize(1);
        assertThat(row(stats, SUITE).history().status()).isEqualTo(BROKE);
        assertThat(row(stats, SUITE).historyCheckedAt()).isPositive();
        assertThat(row(stats, SUITE).attention()).isNotNull();
    }

    /**
     * The commit that broke the tests of a cause came after the last pass of each of them, so it is among the commits
     * after the latest of those passes. The board asks GitHub for them once, however many cycles pass, and names them
     * with the cause.
     */
    @Test
    void aCauseIsAmongTheCommitsAfterTheLatestLastPassOfItsTests() {
        observed(Map.of(SUITE, BROKE, SIBLING, BROKE));
        broke(SUITE, 4, "early", 7, "head");
        broke(SIBLING, 5, "late", 6, "head");
        when(github.commitsBetween("late", "head", 20)).thenReturn(Optional.of(new GithubClient.Commits(21, List.of(
            new GithubClient.Commit("head", "IGNITE-2 Break it (#2)"),
            new GithubClient.Commit("between", "IGNITE-1 Tidy up (#1)")))));

        FlakyStats stats = stats();
        stats.harvest();
        stats.harvest();
        JsonNode answer = mapper.valueToTree(new LeaderboardController(stats, suites(), analysis).topFlaky(1, false));

        assertThat(answer.get("tests")).extracting(test -> test.get("suite").asText())
            .containsExactlyInAnyOrder(SUITE, SIBLING);
        assertThat(answer.get("causes").toString()).isEqualTo("[{\"commit\":\"head\",\"base\":\"late\",\"total\":21,"
            + "\"commits\":[{\"sha\":\"head\",\"title\":\"IGNITE-2 Break it (#2)\"},"
            + "{\"sha\":\"between\",\"title\":\"IGNITE-1 Tidy up (#1)\"}]}]");
        verify(github).commitsBetween("late", "head", 20);
        verifyNoMoreInteractions(github);
    }

    /** A reader who asks for a whole group gets the board whole, whatever the limit of a poll. */
    @Test
    void theWholeBoardIsNotCut() {
        observed(Map.of(SUITE, "FPF", SIBLING, "FPF"));
        ci.add(SUITE, "FPF");
        ci.add(SIBLING, "FPF");
        FlakyStats stats = stats();
        stats.harvest();
        LeaderboardController board = new LeaderboardController(stats, suites(), analysis);

        assertThat(mapper.valueToTree(board.topFlaky(1, false)).get("tests")).hasSize(1);
        assertThat(mapper.valueToTree(board.topFlaky(1, true)).get("tests")).hasSize(2);
    }

    /** After a restart every cause is to ask for: a cycle asks for three, and the next one for the rest. */
    @Test
    void aCycleAsksGitHubForThreeCauses() {
        List<String> suites = List.of("A", "B", "C", "D");
        observed(suites.stream().collect(Collectors.toMap(suite -> suite, suite -> BROKE)));
        suites.forEach(suite -> broke(suite, 4, "base" + suite, 5, "head" + suite));
        when(github.commitsBetween(anyString(), anyString(), anyInt()))
            .thenReturn(Optional.of(new GithubClient.Commits(1, List.of(new GithubClient.Commit("head", "IGNITE-1")))));

        FlakyStats stats = stats();
        stats.harvest();

        verify(github, times(3)).commitsBetween(anyString(), anyString(), anyInt());
        assertThat(stats.causes(stats.top(40))).filteredOn(cause -> cause.total() != null).hasSize(3);
        stats.harvest();

        verify(github, times(4)).commitsBetween(anyString(), anyString(), anyInt());
        assertThat(stats.causes(stats.top(40))).extracting(FlakyStats.Cause::total).containsExactly(1, 1, 1, 1);
    }

    /**
     * A call that failed is made again in the next cycle. Two failures end a cycle, and GitHub's limit ends it at
     * once: the board does not go on asking a GitHub that is down or wants it to wait. Until GitHub answers, a cause
     * names its two commits alone.
     */
    @Test
    void aCallThatFailedIsMadeAgainInALaterCycle() {
        List<String> suites = List.of("A", "B", "C");
        observed(suites.stream().collect(Collectors.toMap(suite -> suite, suite -> BROKE)));
        suites.forEach(suite -> broke(suite, 4, "base" + suite, 5, "head" + suite));
        FlakyStats stats = stats();

        doThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)).when(github)
            .commitsBetween(anyString(), anyString(), anyInt());
        stats.harvest();

        verify(github, times(2)).commitsBetween(anyString(), anyString(), anyInt());
        assertThat(mapper.valueToTree(stats.causes(stats.top(40))).toString()).contains(
            "{\"commit\":\"headA\",\"base\":\"baseA\"}", "{\"commit\":\"headB\",\"base\":\"baseB\"}",
            "{\"commit\":\"headC\",\"base\":\"baseC\"}");
        doThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).when(github)
            .commitsBetween(anyString(), anyString(), anyInt());
        stats.harvest();

        verify(github, times(3)).commitsBetween(anyString(), anyString(), anyInt());
        doReturn(Optional.of(new GithubClient.Commits(1, List.of(new GithubClient.Commit("head", "IGNITE-1")))))
            .when(github).commitsBetween(anyString(), anyString(), anyInt());
        stats.harvest();

        verify(github, times(6)).commitsBetween(anyString(), anyString(), anyInt());
        assertThat(stats.causes(stats.top(40))).extracting(FlakyStats.Cause::total).containsExactly(1, 1, 1);
    }

    /** A last pass and a first failure on one commit have no commit between them, and GitHub is not asked for any. */
    @Test
    void runsOnTheSameCommitHaveNoCommitsToAskFor() {
        observed(Map.of(SUITE, BROKE));
        broke(SUITE, 4, "same", 5, "same");

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(stats.causes(stats.top(40))).containsExactly(new FlakyStats.Cause("same", "same", 0, List.of()));
        verifyNoInteractions(github);
    }

    /** When TeamCity named no commit for either run, there is nothing to count the commits from or up to. */
    @ParameterizedTest
    @CsvSource({", head", "base, "})
    void aBreakWithoutACommitAtEitherEndHasNoCommits(String base, String head) {
        observed(Map.of(SUITE, BROKE));
        broke(SUITE, 4, base, 5, head);

        FlakyStats stats = stats();
        stats.harvest();

        assertThat(stats.top(40)).extracting(FlakyStats.TopFlaky::attention).doesNotContainNull();
        assertThat(stats.causes(stats.top(40))).isEmpty();
        verifyNoInteractions(github);
    }

    /**
     * The commits of a cause are kept while the cause is on the board, and no longer. The snapshot in which the test
     * passed again stands for the day after which the board reads the test's history anew.
     */
    @Test
    void theCommitsOfACauseThatLeftTheBoardAreNotKept(@TempDir Path dir) throws IOException {
        observed(Map.of(SUITE, BROKE));
        ci.add(SUITE, BROKE);
        when(github.commitsBetween("c1004", "c1005", 20))
            .thenReturn(Optional.of(new GithubClient.Commits(1, List.of(new GithubClient.Commit("c1005", "IGNITE-1")))));
        FlakyStats stats = stats();
        stats.harvest();
        Path broke = dir.resolve("broke.json");
        Path passed = dir.resolve("passed.json");
        stats.saveTo(broke);
        JsonNode snap = mapper.readTree(broke.toFile());
        ((ObjectNode) snap.get(0)).withArray("masterHistory").addObject().put("buildId", 1008).put("btId", SUITE)
            .put("occ", occurrence(1008)).put("status", "P").put("finishedAt", at(8)).put("commit", "c1008");
        mapper.writeValue(passed.toFile(), snap);

        stats.loadFrom(passed);
        stats.harvest();

        assertThat(stats.causes(stats.top(40))).isEmpty();
        stats.loadFrom(broke);
        stats.harvest();

        assertThat(stats.causes(stats.top(40))).extracting(FlakyStats.Cause::total).containsExactly(1);
        verify(github, times(2)).commitsBetween("c1004", "c1005", 20);
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
        return new FlakyStats(cache, mapper, tc, warmer, github);
    }

    /**
     * The suite's test passed five runs and fails since: its last pass, at hour {@code passed}, ran on {@code base},
     * and the first of its three failures, at hour {@code failed}, on {@code head}.
     */
    private void broke(String suite, int passed, String base, int failed, String head) {
        ci.add(suite, "PPPP");
        ci.add(2000, suite, "SUCCESS", date(passed), base);
        ci.add(2001, suite, "FAILURE", date(failed), head);
        ci.add(2002, suite, "FAILURE", date(failed + 1), null);
        ci.add(2003, suite, "FAILURE", date(failed + 2), null);
    }

    /** The board's suites, none of whose master runs was read. */
    private MasterSuites suites() {
        return new MasterSuites(cache, mapper, tc, warmer, mock(SuiteBaseline.class), stats(), analysis);
    }

    private static FlakyStats.TopFlaky row(FlakyStats stats, String suite) {
        return stats.top(40).stream().filter(r -> suite.equals(r.suite())).findFirst().orElseThrow();
    }

    private static String occurrence(long buildId) {
        return "build:(id:" + buildId + "),id:42";
    }

    /** "P*88 F*12" as 88 letters P and 12 letters F. */
    private static String runs(String parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts.split(" ")) {
            String[] repeated = part.split("\\*");
            out.append(repeated[0].repeat(repeated.length > 1 ? Integer.parseInt(repeated[1]) : 1));
        }

        return out.toString();
    }

    /** When run {@code i} of a history finished, as TeamCity writes it: an hour after the run before it. */
    private static String date(int i) {
        return String.format("202611%02dT%02d0000+0000", 1 + i / 24, i % 24);
    }

    /** The same in epoch seconds. */
    private static long at(int i) {
        return Instant.parse("2026-11-01T00:00:00Z").plusSeconds(3600L * i).getEpochSecond();
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

        /** The suite's next runs after those it has, an hour apart: builds 1000, 1001, …, on commits c1000, c1001, …. */
        void add(String suite, String oldestFirst) {
            int had = (int) rows.stream().filter(r -> suite.equals(r.get("build").get("buildTypeId").asText())).count();
            for (int i = 0; i < oldestFirst.length(); i++) {
                int run = had + i;
                add(1000 + run, suite, oldestFirst.charAt(i) == 'F' ? "FAILURE" : "SUCCESS", date(run), "c" + (1000 + run));
            }
        }

        void add(long buildId, String suite, String status, String date, String commit) {
            add(buildId, suite, status, "finished", status, date, commit);
        }

        void add(long buildId, String suite, String status, String state, String buildStatus, String date,
            String commit) {
            ObjectNode row = mapper.createObjectNode().put("id", occurrence(buildId)).put("status", status)
                .put("currentlyMuted", false);
            ObjectNode build = row.putObject("build").put("id", buildId).put("buildTypeId", suite)
                .put("state", state).put("status", buildStatus);
            if (date != null) build.put("finishDate", date);
            if (commit != null) build.putObject("revisions").putArray("revision").addObject().put("version", commit);
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
