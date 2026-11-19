package com.github.igniteprchecker.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.analysis.model.Upstream;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.github.igniteprchecker.web.AnalyzeController;
import com.github.igniteprchecker.web.LeaderboardController;
import com.github.igniteprchecker.web.PrHeads;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A suite's master run that times out, crashes or fails to compile reports no tests: nothing of it reached the board
 * of tests failing on master, though a PR's run of the suite ends the same way and shows there as a broken suite. The
 * board keeps the master runs of every suite, read through the real TeamCity client from a stand-in for TeamCity that
 * lists master's RunAll chains and gives the suite runs of each.
 */
class MasterSuitesTest {
    private static final String CACHE = "Cache_1";

    private static final String PDS = "PDS_Indexing";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final MasterChains ci = new MasterChains();

    private final AnalysisCache cache =
        new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null, null), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    @AfterEach
    void stopTeamCity() {
        ci.close();
    }

    /**
     * TeamCity reuses a suite's build in the next chain when nothing changed: the build is one run of the suite,
     * whichever chains hold it. The runs stand by finish date, not by build id, and a suite with no problem run is not
     * a problem suite.
     */
    @Test
    void aSuitesHistoryIsItsDistinctBuildsByFinishDate() {
        ci.chain(101, ok(11, CACHE, 1), ok(12, PDS, 1));
        ci.chain(102, ok(11, CACHE, 1), failed(22, PDS, 5, 300, 2, "TC_EXECUTION_TIMEOUT"));
        ci.chain(103, ok(31, CACHE, 3), failed(22, PDS, 5, 300, 2, "TC_EXECUTION_TIMEOUT"));
        ci.chain(104, ok(31, CACHE, 3), ok(21, PDS, 4));

        MasterSuites suites = suites(100);
        suites.scan();

        assertThat(suites.problemSuites()).containsExactly(new MasterSuites.ProblemSuite(PDS, "PDS Indexing",
            new MasterSuites.Runs("PPT", List.of(12L, 21L, 22L), List.of(at(1), at(4), at(5))), 0, List.of()));
        assertThat(suites.chainsRead()).isEqualTo(4);
        assertThat(ci.requests).containsExactly("chains", "chain 104", "chain 103", "chain 102", "chain 101");
    }

    /** A cycle with no new chain costs TeamCity the list of chains alone, and a new chain is read once. */
    @Test
    void onlyANewChainIsRead() {
        ci.chain(101, failed(11, CACHE, 1, 0, 0, "TC_EXIT_CODE"));
        MasterSuites suites = suites(100);
        suites.scan();
        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 101", "chains");
        ci.chain(102, ok(21, CACHE, 2));
        suites.scan();
        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 101", "chains", "chains", "chain 102", "chains");
        assertThat(history(suites, CACHE)).isEqualTo(new MasterSuites.Runs("XP", List.of(11L, 21L), List.of(at(1), at(2))));
    }

    /**
     * After the first start every chain TeamCity lists is unread. A cycle reads the newest five, so the board shows
     * what fails now at once and the older runs within the hour, and no chain is read twice.
     */
    @Test
    void theFirstStartReadsFiveChainsACycleNewestFirst() {
        for (int i = 1; i <= 12; i++)
            ci.chain(100 + i, i == 3 ? failed(i, CACHE, i, 0, 0, "TC_EXIT_CODE") : ok(i, CACHE, i));
        MasterSuites suites = suites(100);

        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 112", "chain 111", "chain 110", "chain 109", "chain 108");
        assertThat(suites.chainsRead()).isEqualTo(5);
        assertThat(suites.problemSuites()).as("the problem run is in a chain not read yet").isEmpty();
        ci.requests.clear();
        suites.scan();
        suites.scan();
        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 107", "chain 106", "chain 105", "chain 104",
            "chain 103", "chains", "chain 102", "chain 101", "chains");
        assertThat(history(suites, CACHE).status()).isEqualTo("PPXPPPPPPPPP");
    }

    /**
     * A history holds as many runs as the analysis looks at, the newest: TeamCity is asked for that many chains, and
     * a run older than those of that many newer chains leaves the history.
     */
    @Test
    void aHistoryKeepsTheNewestRunsOfTheWindow() {
        for (int i = 1; i <= 4; i++)
            ci.chain(100 + i, failed(i, CACHE, i, 0, 0, "TC_EXIT_CODE"));
        MasterSuites suites = suites(3);
        suites.scan();

        assertThat(ci.locators).containsExactly("buildType:(id:RunAll),branch:(default:true),state:finished,"
            + "canceled:false,count:3");
        assertThat(history(suites, CACHE).builds()).containsExactly(2L, 3L, 4L);
        ci.chain(105, ok(5, CACHE, 5));
        ci.chain(106, ok(6, CACHE, 6));
        suites.scan();

        assertThat(history(suites, CACHE)).isEqualTo(new MasterSuites.Runs("XPP", List.of(4L, 5L, 6L),
            List.of(at(4), at(5), at(6))));
        assertThat(suites.chainsRead()).isEqualTo(3);
        ci.chain(107, ok(7, CACHE, 7));
        suites.scan();

        assertThat(suites.problemSuites()).as("its last problem run left the window").isEmpty();
    }

    /**
     * TeamCity down, or a token it refuses, must not be asked chain after chain: a cycle ends when the list of chains
     * fails, or two chains do. What failed is asked for again in the next cycle, and one chain that fails does not
     * hold the others back.
     */
    @Test
    void aChainThatFailedIsReadInALaterCycle() {
        for (int i = 1; i <= 4; i++)
            ci.chain(100 + i, failed(i, CACHE, i, 0, 0, "TC_EXIT_CODE"));
        MasterSuites suites = suites(100);

        ci.failing.add("chains");
        suites.scan();

        assertThat(ci.requests).containsExactly("chains");
        assertThat(suites.chainsRead()).isZero();
        ci.failing.clear();
        ci.failing.add("chain 104");
        ci.requests.clear();
        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 104", "chain 103", "chain 102", "chain 101");
        assertThat(history(suites, CACHE).builds()).containsExactly(1L, 2L, 3L);
        ci.failing.add("chain 105");
        ci.chain(105, ok(5, CACHE, 5));
        ci.chain(106, ok(6, CACHE, 6));
        ci.requests.clear();
        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 106", "chain 105", "chain 104");
        ci.failing.clear();
        suites.scan();

        assertThat(history(suites, CACHE)).isEqualTo(new MasterSuites.Runs("XXXXPP", List.of(1L, 2L, 3L, 4L, 5L, 6L),
            List.of(at(1), at(2), at(3), at(4), at(5), at(6))));
    }

    /** Without a pooled token nothing is asked: nobody is logged in, and the board keeps what it has. */
    @Test
    void withoutATokenTeamCityIsNotAsked() {
        ci.chain(101, failed(11, CACHE, 1, 0, 0, "TC_EXIT_CODE"));
        MasterSuites suites = suites(100);
        when(warmer.borrowToken()).thenReturn(null);

        suites.scan();

        assertThat(ci.requests).isEmpty();
        assertThat(suites.chainsRead()).isZero();
    }

    /**
     * A problem run on master is a broken suite in a PR: the same run, read as a PR's suite is, is broken exactly when
     * the board keeps it as a problem run, and its letter is the kind its problems name first. A run with failed tests
     * and no crash reported its tests; so did a suite that timed out after nearly all of master's 1200 tests, whose
     * failures stand in a PR too. A suite a failed Build kept from running is in a PR that Build's victim, and on
     * master no run of the suite; nor is a cancelled one.
     */
    @ParameterizedTest(name = "{5}")
    @CsvSource({
        "SUCCESS, 1200, 0, '', P, passed",
        "FAILURE, 1200, 2, '', P, failed tests and no problem",
        "FAILURE, 1200, 2, TC_EXIT_CODE, P, failed tests and an exit code",
        "FAILURE, 1200, 0, TC_EXIT_CODE, X, an exit code and no failed test",
        "FAILURE, 0, 0, TC_EXIT_CODE TC_COMPILATION_ERROR, C, did not compile",
        "FAILURE, 300, 3, TC_EXIT_CODE TC_EXECUTION_TIMEOUT, T, timed out a quarter through",
        "FAILURE, 1190, 3, TC_EXECUTION_TIMEOUT, P, timed out after nearly all its tests",
        "FAILURE, 1190, 0, TC_EXECUTION_TIMEOUT, T, timed out near the end with no failed test",
        "FAILURE, 88, 1, TC_OOME, O, out of memory",
        "FAILURE, 88, 1, TC_JVM_CRASH, O, JVM crash",
        "FAILURE, 1200, 0, '', U, failed and TeamCity named no problem",
        "FAILURE, 1200, 0, ARTIFACT_DEPENDENCY_ERROR, U, a problem of another kind",
        "FAILURE, 40, 0, SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE, D, ran past a failed dependency",
        "FAILURE, 0, 0, SNAPSHOT_DEPENDENCY_ERROR, -, kept from running by a failed dependency",
        "UNKNOWN, 0, 0, '', -, cancelled"})
    void aProblemRunIsWhatAPrCallsABrokenSuite(String status, int tests, int failedTests, String problems,
        String letter, String what) {
        when(baseline.counts(anyString())).thenReturn(Map.of(CACHE, 1200));
        ObjectNode run = run(11, CACHE, status, 1, tests, failedTests, problems.isEmpty() ? new String[0]
            : problems.split(" "));
        ci.chain(101, run, failed(12, PDS, 1, 0, 0, "TC_EXIT_CODE"));
        ci.chain(102, failed(21, CACHE, 2, 0, 0, "TC_EXIT_CODE"), ok(22, PDS, 2));
        TcClient tc = tc(100);

        MasterSuites suites = suites(100);
        suites.scan();
        TcModel.Build asInAPr = tc.chainSuiteRuns("token", 101).get(0);
        ChainCollector.SuiteRun pr = new ChainCollector(tc, baseline).collectForSuiteRun("token", asInAPr);

        assertThat(history(suites, CACHE).status()).as(what).isEqualTo(letter.equals("-") ? "X" : letter + "X");
        assertThat(pr.broken() != null && !pr.broken().keptFromRunning()).as("a broken suite in a PR")
            .isEqualTo(!"P-".contains(letter));
        if (letter.equals("-") && "FAILURE".equals(status))
            assertThat(pr.broken().keptFromRunning()).isTrue();
    }

    /**
     * A row counts the PRs of the last 14 days whose verdict has the suite among its broken suites. A suite a failed
     * Build kept from running in a PR is not broken its own way, a suite that crashed near the end with its results
     * standing is not broken, and a run that finished over 14 days ago no longer counts.
     */
    @Test
    void aSuiteCountsThePrsItWasABrokenSuiteIn(@TempDir Path dir) throws IOException {
        ci.chain(101, failed(11, CACHE, 1, 0, 0, "TC_EXIT_CODE"), failed(12, PDS, 1, 0, 0, "TC_EXIT_CODE"));
        long now = System.currentTimeMillis() / 1000;
        verdict(13583, now - 3600, List.of(broken(CACHE, 0, "execution timeout")), List.of());
        verdict(13655, now - Duration.ofDays(13).toSeconds(), List.of(broken(CACHE, 0, "non-zero exit code"),
            broken(PDS, 0, "failed dependency")), List.of());
        verdict(13660, now - Duration.ofDays(15).toSeconds(), List.of(broken(CACHE, 0, "execution timeout")), List.of());
        verdict(13700, now - 60, List.of(), List.of(broken(CACHE, 1190, "execution timeout")));

        MasterSuites suites = suites(100);
        suites.scan();

        assertThat(suites.problemSuites()).extracting(MasterSuites.ProblemSuite::suite,
            MasterSuites.ProblemSuite::prCount, MasterSuites.ProblemSuite::prs).containsExactly(
                tuple(CACHE, 2, List.of(13583, 13655)),
                tuple(PDS, 0, List.of()));

        Path file = dir.resolve("master-suites.json");
        suites.saveTo(file);
        ObjectNode kept = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) kept.get("prSeen").get(CACHE)).put("13583", System.currentTimeMillis() - Duration.ofDays(15).toMillis());
        mapper.writeValue(file.toFile(), kept);
        MasterSuites later = new MasterSuites(new AnalysisCache(new AnalysisProperties(null, null, null, null, 15, null,
            null), mapper), mapper, tc(100), warmer, baseline, flaky(100), analysis(100));
        later.loadFrom(file);

        assertThat(later.problemSuites().get(0).prs()).as("PR 13583 was seen 15 days ago").containsExactly(13655);
    }

    /**
     * The histories survive a restart in the shape the page gets them in, a letter a run, and the chains read are not
     * read again. A first start has no file, and says that no chain was read: the page then does not claim that every
     * suite reported its tests.
     */
    @Test
    void aRestartKeepsTheHistoriesAndTheChainsRead(@TempDir Path dir) throws IOException {
        ci.chain(101, ok(11, CACHE, 1), ok(12, PDS, 1));
        ci.chain(102, failed(21, CACHE, 2, 300, 3, "TC_EXECUTION_TIMEOUT"), ok(22, PDS, 2));
        verdict(13583, System.currentTimeMillis() / 1000 - 3600, List.of(broken(CACHE, 0, "execution timeout")),
            List.of());
        MasterSuites before = suites(100);
        before.scan();
        Path file = dir.resolve("master-suites.json");
        before.saveTo(file);

        JsonNode kept = mapper.readTree(file.toFile());
        assertThat(kept.get("chains").toString()).isEqualTo("[101,102]");
        assertThat(kept.get("suites").get(CACHE).toString()).isEqualTo("{\"name\":\"Cache 1\",\"runs\":{\"status\":\"PT\","
            + "\"builds\":[11,21],\"finished\":[" + at(1) + "," + at(2) + "]}}");
        assertThat(kept.get("suites").get(PDS).get("runs").get("status").asText()).isEqualTo("PP");
        MasterSuites fresh = suites(100);
        fresh.loadFrom(dir.resolve("absent.json"));

        assertThat(fresh.chainsRead()).isZero();
        assertThat(fresh.problemSuites()).isEmpty();
        MasterSuites after = suites(100);
        after.loadFrom(file);
        ci.requests.clear();
        after.scan();

        assertThat(after.problemSuites()).isEqualTo(before.problemSuites());
        assertThat(after.chainsRead()).isEqualTo(2);
        assertThat(ci.requests).containsExactly("chains");
    }

    /** A suite RunAll runs no more gets no new run: left on the board, its last problem run would stand for good. */
    @Test
    void aSuiteThatLeftRunAllLeavesTheBoard() {
        ci.chain(101, failed(11, CACHE, 1, 0, 0, "TC_EXIT_CODE"), failed(12, PDS, 1, 0, 0, "TC_EXIT_CODE"));
        MasterSuites suites = suites(100);
        suites.scan();

        assertThat(suites.problemSuites()).extracting(MasterSuites.ProblemSuite::suite).containsExactly(CACHE, PDS);
        ci.chain(102, ok(21, CACHE, 2));
        suites.scan();

        assertThat(suites.problemSuites()).extracting(MasterSuites.ProblemSuite::suite).containsExactly(CACHE);
    }

    /**
     * Given a suite absent from the newest chain, when startup backfills older chains, then the suite stays off the
     * board while a current suite keeps its older history even if its latest run was cancelled.
     */
    @ParameterizedTest
    @CsvSource({"SUCCESS, XP", "UNKNOWN, X"})
    void olderChainsCannotBringBackASuiteAbsentFromTheLatestChain(String currentStatus, String history) {
        ci.chain(101, failed(11, CACHE, 1, 0, 0, "TC_EXIT_CODE"), failed(12, PDS, 1, 0, 0, "TC_EXIT_CODE"));
        ci.chain(102, run(21, CACHE, currentStatus, 2, 100, 0));
        MasterSuites suites = suites(100);

        suites.scan();

        assertThat(ci.requests).containsExactly("chains", "chain 102", "chain 101");
        assertThat(suites.problemSuites()).extracting(MasterSuites.ProblemSuite::suite).containsExactly(CACHE);
        assertThat(history(suites, CACHE).status()).isEqualTo(history);
    }

    /**
     * A run TeamCity gave no finish date of is still a run, shown first as nothing says when it was. The name of a
     * suite is that of its newest run.
     */
    @Test
    void aRunWithoutAFinishDateStandsFirst() {
        ObjectNode undated = failed(13, CACHE, 1, 0, 0, "TC_EXIT_CODE");
        undated.remove("finishDate");
        ci.chain(101, ok(11, CACHE, 1).set("buildType", mapper.createObjectNode().put("name", "Cache (old name)")));
        ci.chain(102, ok(21, CACHE, 2));
        ci.chain(103, undated);

        MasterSuites suites = suites(100);
        suites.scan();

        assertThat(suites.problemSuites()).containsExactly(new MasterSuites.ProblemSuite(CACHE, "Cache 1",
            new MasterSuites.Runs("XPP", List.of(13L, 11L, 21L), Arrays.asList(null, at(1), at(2))), 0,
            List.of()));
    }

    /**
     * The page polls the board: a suite comes as one object with a letter a run, only the suites with a problem run
     * come, and the letters come with the words the PR page has for the kinds of problem.
     */
    @Test
    void thePageGetsTheProblemSuitesWithALetterARun() {
        ci.chain(101, ok(11, CACHE, 1), ok(12, PDS, 1));
        ci.chain(102, failed(21, CACHE, 2, 300, 3, "TC_EXECUTION_TIMEOUT"), ok(22, PDS, 2));
        verdict(13583, System.currentTimeMillis() / 1000 - 3600, List.of(broken(CACHE, 0, "execution timeout")),
            List.of());
        MasterSuites suites = suites(100);
        LeaderboardController board = new LeaderboardController(flaky(100), suites, analysis(100));

        JsonNode unread = mapper.valueToTree(board.topFlaky(40, false));
        suites.scan();
        JsonNode answer = mapper.valueToTree(board.topFlaky(40, false));

        assertThat(unread.get("suiteChains").asInt()).isZero();
        assertThat(unread.get("suites")).isEmpty();
        assertThat(answer.get("suiteChains").asInt()).isEqualTo(2);
        assertThat(answer.get("suites").toString()).isEqualTo("[{\"suite\":\"Cache_1\",\"suiteName\":\"Cache 1\","
            + "\"history\":{\"status\":\"PT\",\"builds\":[11,21],\"finished\":[" + at(1) + "," + at(2) + "]},"
            + "\"prCount\":1,\"prs\":[13583]}]");
        assertThat(answer.get("suiteKinds").toString()).isEqualTo("{\"C\":\"compilation error\","
            + "\"T\":\"execution timeout\",\"O\":\"JVM crash / out of memory\",\"X\":\"non-zero exit code\","
            + "\"D\":\"failed dependency\",\"U\":\"another problem\"}");
    }

    private MasterSuites suites(int depth) {
        when(warmer.borrowToken()).thenReturn("token");

        return new MasterSuites(cache, mapper, tc(depth), warmer, baseline, flaky(depth), analysis(depth));
    }

    /** A board of tests with no test on it. */
    private FlakyStats flaky(int depth) {
        return new FlakyStats(cache, mapper, tc(depth), warmer, mock(GithubClient.class));
    }

    private static AnalysisProperties analysis(int depth) {
        return new AnalysisProperties(null, "RunAll", depth, null, null, null, null);
    }

    private TcClient tc(int depth) {
        return new TcClient(new TeamcityProperties(ci.baseUrl()), analysis(depth), new Metrics(mapper));
    }

    private static MasterSuites.Runs history(MasterSuites suites, String suite) {
        return suites.problemSuites().stream().filter(s -> suite.equals(s.suite())).findFirst().orElseThrow().history();
    }

    /** The cached verdict of a PR whose run finished at {@code finishedAt}, epoch seconds, with these suites. */
    private void verdict(int pr, long finishedAt, List<BrokenSuite> broken, List<BrokenSuite> unstable) {
        cache.putResult(pr, new AnalysisResult(pr, pr, "pull/" + pr + "/head", System.currentTimeMillis(), List.of(),
            List.of(), List.of(), broken, List.of(), 2, 0, false, 0, false, 0, 0, 0, finishedAt, 0, unstable, List.of(),
            List.of(), 0));
    }

    private static BrokenSuite broken(String suite, int tests, String problem) {
        return new BrokenSuite(suite, 9000, suite.replace('_', ' '), List.of(problem), tests, 1200);
    }

    /** A suite run that passed its 100 tests, finished at hour {@code hour}. */
    /**
     * A PR's verdict says of a suite its run left broken how the suite does on master: the same when its newest master
     * run ended with a problem of that kind, fine when every master run read reported its tests. A suite that ends
     * another way on master is neither, and nothing is known before a chain is read.
     */
    @Test
    void aBrokenSuiteOfAPrIsToldHowItDoesOnMaster() {
        ci.chain(101, ok(11, CACHE, 1), ok(12, PDS, 1));
        ci.chain(102, ok(21, CACHE, 2), failed(22, PDS, 2, 300, 2, "TC_EXIT_CODE", "TC_EXECUTION_TIMEOUT"));
        MasterSuites suites = suites(100);
        List<String> timeout = List.of("TC_EXECUTION_TIMEOUT", "TC_EXIT_CODE");

        assertThat(suites.onMaster(PDS, timeout)).isEqualTo(MasterSuites.OnMaster.UNKNOWN);
        suites.scan();

        assertThat(suites.onMaster(PDS, timeout)).isEqualTo(MasterSuites.OnMaster.SAME);
        assertThat(suites.onMaster(PDS, List.of("TC_EXIT_CODE"))).isEqualTo(MasterSuites.OnMaster.UNKNOWN);
        assertThat(suites.onMaster(CACHE, timeout)).isEqualTo(MasterSuites.OnMaster.FINE);
        assertThat(suites.onMaster("Queries_1", timeout)).isEqualTo(MasterSuites.OnMaster.UNKNOWN);
    }

    /**
     * Given real master runs and a PR verdict, Analyze and Refresh mark only a result whose every gap is a matching
     * problem suite, as the PR comment does; no other missing evidence becomes a master failure.
     */
    @ParameterizedTest
    @ValueSource(strings = {"same", "no-master", "master-fine", "different-kind", "no-types", "artifacts",
        "partial", "unknown-kind", "upstream", "blocker", "watch", "interrupted", "shrunk", "unchecked", "running",
        "mixed", "no-broken"})
    void thePageMarksOnlyGapsExplainedByMaster(String scenario) {
        ci.chain(101, scenario.equals("master-fine") ? ok(11, CACHE, 1)
            : failed(11, CACHE, 1, 0, 0, scenario.equals("unknown-kind") ? "UNRECOGNIZED_MASTER" : "TC_EXECUTION_TIMEOUT"));
        MasterSuites masters = suites(100);
        if (!scenario.equals("no-master"))
            masters.scan();
        List<String> types = switch (scenario) {
            case "different-kind" -> List.of("TC_EXIT_CODE");
            case "no-types" -> List.of();
            case "unknown-kind" -> List.of("UNRECOGNIZED_PR");
            case "artifacts" -> List.of("TC_EXECUTION_TIMEOUT", "ARTIFACT_DEPENDENCY_ERROR");
            default -> List.of("TC_EXECUTION_TIMEOUT", "TC_EXIT_CODE");
        };
        BrokenSuite broken = new BrokenSuite(CACHE, 9000, "Cache 1", List.of("execution timeout"), 0, 100, types, null);
        verdict(13854, 1, List.of(broken), List.of());
        ObjectNode data = mapper.valueToTree(cache.peekResult(13854).orElseThrow());
        switch (scenario) {
            case "partial" -> data.withArray("brokenSuites").add(mapper.valueToTree(new BrokenSuite("Other", 9002,
                "Other", List.of("execution timeout"), 0, 100, types, null)));
            case "upstream" -> data.withArray("brokenSuites").add(mapper.valueToTree(new BrokenSuite("Victim", 9002,
                "Victim", List.of("failed dependency"), 0, 100, List.of(), new Upstream(CACHE, 9000, "Cache 1"))));
            case "blocker", "watch", "unchecked" -> {
                TestVerdict test = new TestVerdict(1L, "CacheTest.testPut", CACHE, 9000, "Cache 1", "occ-1",
                    scenario.equals("blocker"), scenario.equals("watch"), "failed", "FF", 2);
                data.withArray(scenario.equals("blocker") ? "blockers" : scenario.equals("watch") ? "watch"
                    : "unverified").add(mapper.valueToTree(test));
            }
            case "interrupted" -> data.put("interrupted", true).put("canceledSuites", 1);
            case "shrunk" -> data.withArray("shrunkSuites").add(mapper.valueToTree(
                new ShrunkSuite("Other", "Other", 9002, 1, 100, 99)));
            case "running" -> data.put("live", true).put("liveBuildId", 13854);
            case "mixed" -> {
                data.put("revision", "1".repeat(40));
                data.withArray("otherCodeRuns").add(mapper.valueToTree(
                    new RunRef(CACHE, "Cache 1", 9002, "2".repeat(40))));
            }
            case "no-broken" -> data.putArray("brokenSuites");
            default -> { }
        }
        AnalysisResult result = mapper.treeToValue(data, AnalysisResult.class);
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.analyze("token", 13854)).thenReturn(Optional.of(result));
        when(analyzer.forceRefresh("token", 13854)).thenReturn(Optional.of(result));
        AnalyzeController controller = new AnalyzeController(analyzer, mock(RunDeltaStore.class),
            mock(PendingCommits.class), mock(PrHeads.class), masters);

        for (var response : List.of(controller.analyze(13854, "token"), controller.refresh(13854, "token"))) {
            JsonNode served = mapper.valueToTree(response.getBody());
            assertThat(served.get("onlyMasterFailures").asBoolean()).isEqualTo(scenario.equals("same"));
            if (scenario.equals("same")) {
                assertThat(served.get("standing").asText()).isEqualTo("UNPROVEN");
                assertThat(served.get("caveats")).hasSize(1);
            }
        }
        if (scenario.equals("same")) {
            VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
                "https://checker.example", new GithubProperties(null, null, null), null, masters);
            assertThat(visas.composeMarkdown(13854, result)).contains("same as on master:")
                .contains("**Next:** nothing to do for this PR.");
        }
    }

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
        run.putObject("buildType").put("name", suite.replace('_', ' '));
        run.putObject("testOccurrences").put("count", tests).put("failed", failedTests);
        for (String problem : problems)
            run.withObject("problemOccurrences").withArray("problemOccurrence").addObject().put("type", problem);

        return run;
    }

    /** When a run finished, as TeamCity writes it: at this hour of 1 November. */
    private static String date(int hour) {
        return String.format("20261101T%02d0000+0000", hour);
    }

    /** The same in epoch seconds. */
    private static long at(int hour) {
        return Instant.parse("2026-11-01T00:00:00Z").plusSeconds(3600L * hour).getEpochSecond();
    }

    /**
     * Lists master's finished RunAll chains, newest first and as many as asked for, gives the suite runs of a chain,
     * and the failed tests of a suite run as a PR's analysis asks for them. {@code requests} are "chains" for a list
     * and "chain N" for a chain's runs; those in {@code failing} get a 500.
     */
    private final class MasterChains implements AutoCloseable {
        private final Pattern count = Pattern.compile("count:(\\d+)");

        private final Map<Long, List<ObjectNode>> chains = new TreeMap<>(Comparator.reverseOrder());

        private final List<String> requests = new CopyOnWriteArrayList<>();

        private final List<String> locators = new CopyOnWriteArrayList<>();

        private final Set<String> failing = new HashSet<>();

        private final HttpServer server;

        MasterChains() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/app/rest/builds", ex -> {
                try {
                    String path = ex.getRequestURI().getPath();
                    String locator = parameter(ex.getRequestURI().getRawQuery(), "locator");
                    Object body;
                    String request;
                    if (path.endsWith("/builds")) {
                        request = "chains";
                        locators.add(locator);
                        Matcher asked = count.matcher(locator);
                        body = Map.of("build", chains.keySet().stream().limit(asked.find() ? Long.parseLong(asked.group(1))
                            : 100).map(id -> Map.of("id", id)).toList());
                    }
                    else {
                        long chain = Long.parseLong(path.substring(path.indexOf("id:") + 3));
                        request = "chain " + chain;
                        body = Map.of("snapshot-dependencies", Map.of("build", chains.get(chain)));
                    }
                    requests.add(request);
                    byte[] bytes = mapper.writeValueAsBytes(body);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(failing.contains(request) ? 500 : 200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
                finally {
                    ex.close();
                }
            });
            server.createContext("/app/rest/testOccurrences", ex -> {
                try {
                    Matcher build = Pattern.compile("build:\\(id:(\\d+)\\)")
                        .matcher(parameter(ex.getRequestURI().getRawQuery(), "locator"));
                    long id = build.find() ? Long.parseLong(build.group(1)) : 0;
                    int failedTests = chains.values().stream().flatMap(List::stream).filter(r -> r.get("id").asLong() == id)
                        .mapToInt(r -> r.get("testOccurrences").get("failed").asInt()).findFirst().orElse(0);
                    List<Map<String, Object>> tests = IntStream.range(0, failedTests)
                        .<Map<String, Object>>mapToObj(i -> Map.of("id", "occ-" + i, "name", "Test" + i, "status", "FAILURE",
                            "test", Map.of("id", 500 + i))).toList();
                    byte[] bytes = mapper.writeValueAsBytes(Map.of("testOccurrence", tests));
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
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
