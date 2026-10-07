package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * PR 13583 kept 489 tests on watch, and every recompute asked ci2 for the branch runs of each of them and
 * for the failed tests of every failed suite of a chain that had finished long before: an hour of one or two
 * users cost about 7,500 calls. A recompute now fetches only what can have changed since the last one: the
 * runs of the suites that finished a build on the branch in between.
 */
class IncrementalRecomputeTest {
    private static final String TOK = "t";

    private static final int PR = 13583;

    private static final long CHAIN = 9390000L;

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private static final String CACHE1 = "IgniteTests24Java8_Cache1";

    private static final String QUERIES5 = "IgniteTests24Java8_Queries5";

    private static final String SNAPSHOTS = "IgniteTests24Java8_Snapshots";

    private static final long CACHE1_RUN = 9390011L;

    private static final long QUERIES5_RUN = 9390012L;

    private static final long SNAPSHOTS_RUN = 9390013L;

    private static final long QUERIES5_RERUN = 9390500L;

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final ObjectMapper mapper = new ObjectMapper();

    private final AnalysisCache cache = new AnalysisCache(cfg, mapper);

    private final BlockerAnalyzer analyzer = analyzer(cache);

    IncrementalRecomputeTest() {
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(tc.findRunAllBuildForAnalysis(TOK, PR)).thenReturn(Optional.of(chain("finished", List.of())));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain("finished", List.of(
            suite(CACHE1_RUN, CACHE1, "finished"), suite(QUERIES5_RUN, QUERIES5, "finished"),
            suite(SNAPSHOTS_RUN, SNAPSHOTS, "finished"))));
        failing(CACHE1_RUN, CACHE1, 101L, 102L);
        failing(QUERIES5_RUN, QUERIES5, 201L);
        when(tc.getFailedTests(TOK, SNAPSHOTS_RUN)).thenReturn(List.of());
        when(tc.getBaseBranchHistory(eq(TOK), anyLong(), anyString())).thenReturn(cleanMaster());
        when(tc.latestSuiteRun(TOK, PR, SNAPSHOTS))
            .thenReturn(Optional.of(suite(SNAPSHOTS_RUN, SNAPSHOTS, "finished")));
    }

    /** A finished build does not change: its suites and failed tests are read once, whatever recomputes it. */
    @Test
    void aFinishedChainsSuitesAndFailedTestsAreReadOnce() {
        ChainCollector collector = new ChainCollector(tc, baseline);

        collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
        ChainCollector.Chain again = collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        assertThat(again.failedTests()).extracting(FailedTest::testId).containsExactlyInAnyOrder(101L, 102L, 201L);
        verify(tc, times(1)).getBuildWithDeps(TOK, CHAIN);
        verify(tc, times(1)).getFailedTests(TOK, CACHE1_RUN);
        verify(tc, times(1)).getFailedTests(TOK, QUERIES5_RUN);
    }

    /** A chain still running, and a suite of it still running, have more to say on every read. */
    @Test
    void aRunningChainAndItsRunningSuitesAreReadEachTime() {
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain("running", List.of(
            suite(CACHE1_RUN, CACHE1, "running"), suite(QUERIES5_RUN, QUERIES5, "finished"))));
        ChainCollector collector = new ChainCollector(tc, baseline);

        collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
        collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        verify(tc, times(2)).getBuildWithDeps(TOK, CHAIN);
        verify(tc, times(2)).getFailedTests(TOK, CACHE1_RUN);
        verify(tc, times(1)).getFailedTests(TOK, QUERIES5_RUN);
    }

    /** A chain someone cancelled has finished, but a suite of it that kept running has not. */
    @Test
    void aFinishedChainWithASuiteStillRunningIsReadAgain() {
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain("finished", List.of(
            suite(CACHE1_RUN, CACHE1, "running"), suite(QUERIES5_RUN, QUERIES5, "finished"))));
        ChainCollector collector = new ChainCollector(tc, baseline);

        collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());
        collector.collectForBuild(TOK, PR, CHAIN, Executors.newSingleThreadExecutor());

        verify(tc, times(2)).getBuildWithDeps(TOK, CHAIN);
    }

    /**
     * The checker re-ran Queries 5 and the re-run passed the test: only that suite's runs are fetched again,
     * and the verdict follows them. Cache 1 finished nothing since, so its runs are still TeamCity's answer.
     */
    @Test
    void aRecomputeFetchesTheRunsOfOnlyTheSuitesThatFinishedABuildSince() {
        AnalysisResult first = analyzer.forceRefresh(TOK, PR).orElseThrow();
        assertThat(first.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(101L, 102L, 201L);
        verify(tc, never()).suitesFinishedAfter(anyString(), anyInt(), anyLong());

        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of(QUERIES5, RUN_ALL)));
        when(tc.prBranchRuns(TOK, PR, 201L, QUERIES5)).thenReturn(List.of(
            run(QUERIES5_RUN, QUERIES5, "FAILURE"), run(QUERIES5_RERUN, QUERIES5, "SUCCESS")));

        AnalysisResult second = analyzer.forceRefresh(TOK, PR).orElseThrow();

        assertThat(second.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(second.filtered()).extracting(TestVerdict::testId, TestVerdict::reason)
            .containsExactly(tuple(201L, "not failing in the last finished run (passed on re-run)"));
        verify(tc).suitesFinishedAfter(TOK, PR, first.branchWatermarkAt());
        verify(tc, times(1)).prBranchRuns(TOK, PR, 101L, CACHE1);
        verify(tc, times(1)).prBranchRuns(TOK, PR, 102L, CACHE1);
        verify(tc, times(2)).prBranchRuns(TOK, PR, 201L, QUERIES5);
    }

    /** What a recompute reused is checked again by the next one, from where that one checked. */
    @Test
    void whatWasReusedIsReusedAgainUntilItsSuiteFinishesABuild() {
        AnalysisResult first = analyzer.forceRefresh(TOK, PR).orElseThrow();
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));
        AnalysisResult second = analyzer.forceRefresh(TOK, PR).orElseThrow();
        analyzer.forceRefresh(TOK, PR);

        verify(tc, times(1)).prBranchRuns(TOK, PR, 101L, CACHE1);
        verify(tc, times(1)).latestSuiteRun(TOK, PR, SNAPSHOTS);
        ArgumentCaptor<Long> since = ArgumentCaptor.forClass(Long.class);
        verify(tc, times(2)).suitesFinishedAfter(eq(TOK), eq(PR), since.capture());
        assertThat(since.getAllValues()).containsExactly(first.branchWatermarkAt(), second.branchWatermarkAt());

        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of(SNAPSHOTS)));
        analyzer.forceRefresh(TOK, PR);

        verify(tc, times(2)).latestSuiteRun(TOK, PR, SNAPSHOTS);
        verify(tc, times(1)).prBranchRuns(TOK, PR, 101L, CACHE1);
    }

    /** A broken suite whose re-run passed with all its tests is healed by that run, kept or not. */
    @Test
    void aBrokenSuiteIsHealedByARerunThatFinishedSince() {
        assertThat(analyzer.forceRefresh(TOK, PR).orElseThrow().brokenSuites()).hasSize(1);

        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of(SNAPSHOTS)));
        when(tc.latestSuiteRun(TOK, PR, SNAPSHOTS)).thenReturn(Optional.of(new TcModel.Build(9390600L, "SUCCESS",
            "finished", null, SNAPSHOTS, null, null, null, null, null, null, null, null, null, null, null, null,
            new TcModel.TestOccurrences(300, List.of()))));

        assertThat(analyzer.forceRefresh(TOK, PR).orElseThrow().brokenSuites()).isEmpty();
    }

    /** Without TeamCity's word on what finished since, nothing kept about the branch is trusted. */
    @Test
    void whenTeamCityCannotSayWhatFinishedTheBranchIsReadAgain() {
        analyzer.forceRefresh(TOK, PR);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong()))
            .thenThrow(new IllegalStateException("502 Bad Gateway"));

        AnalysisResult second = analyzer.forceRefresh(TOK, PR).orElseThrow();
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.empty());
        analyzer.forceRefresh(TOK, PR);

        assertThat(second.incompleteSince()).as("nothing was left unchecked").isZero();
        verify(tc, times(3)).prBranchRuns(TOK, PR, 101L, CACHE1);
        verify(tc, times(3)).latestSuiteRun(TOK, PR, SNAPSHOTS);
        verify(tc, times(1)).getFailedTests(TOK, CACHE1_RUN);
    }

    /** A deploy (and every rules bump with it) used to cost a cold recompute of every PR. */
    @Test
    void whatTeamCitySaidOutlivesARestart(@TempDir Path dir) throws Exception {
        AnalysisResult first = analyzer.forceRefresh(TOK, PR).orElseThrow();
        Path file = dir.resolve("analysis.json");
        cache.saveTo(file);

        AnalysisCache restarted = new AnalysisCache(cfg, mapper);
        restarted.loadFrom(file);
        clearInvocations(tc);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));

        AnalysisResult again = analyzer(restarted).forceRefresh(TOK, PR).orElseThrow();

        assertThat(again.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(101L, 102L, 201L);
        assertThat(again.brokenSuites()).hasSize(1);
        verify(tc).suitesFinishedAfter(TOK, PR, first.branchWatermarkAt());
        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());
        verify(tc, never()).getFailedTests(anyString(), anyLong());
        verify(tc, never()).prBranchRuns(anyString(), anyInt(), anyLong(), anyString());
        verify(tc, never()).latestSuiteRun(anyString(), anyInt(), anyString());
    }

    /**
     * Four rules bumps in one day each made every PR cold. A snapshot written under older rules loses its
     * verdicts, not what TeamCity said.
     */
    @Test
    void aRulesBumpKeepsWhatTeamCitySaid(@TempDir Path dir) throws Exception {
        analyzer.forceRefresh(TOK, PR);
        Path file = dir.resolve("analysis.json");
        cache.saveTo(file);
        ObjectNode root = (ObjectNode)mapper.readTree(file.toFile());
        root.put("rules", TestVerdict.RULES - 1);
        mapper.writeValue(file.toFile(), root);

        AnalysisCache restarted = new AnalysisCache(cfg, mapper);
        restarted.loadFrom(file);
        clearInvocations(tc);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));

        assertThat(restarted.peekResult(CHAIN)).as("the verdict of the older rules").isEmpty();
        analyzer(restarted).forceRefresh(TOK, PR);

        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());
        verify(tc, never()).getFailedTests(anyString(), anyLong());
        verify(tc, never()).prBranchRuns(anyString(), anyInt(), anyLong(), anyString());
        verify(tc, never()).latestSuiteRun(anyString(), anyInt(), anyString());
    }

    /** "Flush caches" is how an operator has the next analysis read everything from TeamCity again. */
    @Test
    void flushCachesDropsWhatTeamCitySaid() {
        analyzer.forceRefresh(TOK, PR);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));

        cache.clear();
        analyzer.forceRefresh(TOK, PR);

        verify(tc, times(2)).getBuildWithDeps(TOK, CHAIN);
        verify(tc, times(2)).getFailedTests(TOK, CACHE1_RUN);
        verify(tc, times(2)).prBranchRuns(TOK, PR, 101L, CACHE1);
        verify(tc, times(2)).latestSuiteRun(TOK, PR, SNAPSHOTS);
        verify(tc, never()).suitesFinishedAfter(anyString(), anyInt(), anyLong());
    }

    /** The application's own mapper, with the modules Spring adds, reads back what it wrote. */
    @Test
    void theApplicationsMapperReadsTheFactsBack(@TempDir Path dir) throws Exception {
        ObjectMapper app = Jackson2ObjectMapperBuilder.json().build();
        AnalysisCache before = new AnalysisCache(cfg, app);
        analyzer(before).forceRefresh(TOK, PR);
        Path file = dir.resolve("analysis.json");
        before.saveTo(file);

        AnalysisCache after = new AnalysisCache(cfg, app);
        after.loadFrom(file);
        clearInvocations(tc);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));
        analyzer(after).forceRefresh(TOK, PR);

        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());
        verify(tc, never()).getFailedTests(anyString(), anyLong());
        verify(tc, never()).prBranchRuns(anyString(), anyInt(), anyLong(), anyString());
        verify(tc, never()).latestSuiteRun(anyString(), anyInt(), anyString());
    }

    /**
     * A test muted after its build drops out of the build's failed tests; nothing is kept for more than three
     * hours, so a test muted since stops counting within that time. Older than that, the branch is not asked
     * what finished since either: everything is fetched again anyway.
     */
    @Test
    void afterThreeHoursEverythingIsFetchedAgain(@TempDir Path dir) throws Exception {
        analyzer.forceRefresh(TOK, PR);
        Path file = dir.resolve("analysis.json");
        cache.saveTo(file);
        age(file, BuildFacts.TTL_MS);

        AnalysisCache restarted = new AnalysisCache(cfg, mapper);
        restarted.loadFrom(file);
        clearInvocations(tc);
        failing(CACHE1_RUN, CACHE1, 102L);

        AnalysisResult again = analyzer(restarted).forceRefresh(TOK, PR).orElseThrow();

        assertThat(again.blockers()).extracting(TestVerdict::testId).containsExactlyInAnyOrder(102L, 201L);
        verify(tc).getFailedTests(TOK, CACHE1_RUN);
        verify(tc).prBranchRuns(TOK, PR, 102L, CACHE1);
        verify(tc, never()).suitesFinishedAfter(anyString(), anyInt(), anyLong());
    }

    /** analysis.json as the release before this one wrote it: no facts, results and history load as ever. */
    @Test
    void aSnapshotWithoutFactsStillLoads(@TempDir Path dir) throws Exception {
        AnalysisResult first = analyzer.forceRefresh(TOK, PR).orElseThrow();
        Path file = dir.resolve("analysis.json");
        cache.saveTo(file);
        ObjectNode root = (ObjectNode)mapper.readTree(file.toFile());
        root.remove("facts");
        mapper.writeValue(file.toFile(), root);

        AnalysisCache restarted = new AnalysisCache(cfg, mapper);
        restarted.loadFrom(file);

        assertThat(restarted.peekResult(CHAIN)).contains(first);
        assertThat(restarted.historyCount()).isEqualTo(3);
    }

    /**
     * A PR as large as 13583 on ci2: 150 suites, 20 of them failed 25 tests each, and two broke. After one
     * suite was re-run, a recompute reads that suite and what tells it apart, not the whole PR again: 29 calls
     * where it made 528.
     */
    @Test
    void aRecomputeOfALargePrAfterOneRerunReadsOnlyThatSuite() {
        List<TcModel.Build> suites = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String id = "IgniteTests24Java8_Suite" + i;
            long run = 9391000L + i;
            suites.add(i < 22 ? suite(run, id, "finished") : passed(run, id));
            if (i < 20)
                failing(run, id, LongStream.rangeClosed(i * 100L, i * 100L + 24).toArray());
            else if (i < 22) {
                when(tc.getFailedTests(TOK, run)).thenReturn(List.of());
                when(tc.latestSuiteRun(TOK, PR, id)).thenReturn(Optional.of(suite(run, id, "finished")));
            }
        }
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chain("finished", suites));

        AnalysisResult first = analyzer.forceRefresh(TOK, PR).orElseThrow();
        assertThat(first.blockers()).hasSize(500);
        assertThat(first.brokenSuites()).hasSize(2);
        clearInvocations(tc);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong()))
            .thenReturn(Optional.of(Set.of("IgniteTests24Java8_Suite0")));

        analyzer.forceRefresh(TOK, PR);

        assertThat(mockingDetails(tc).getInvocations()).as("calls to TeamCity for the recompute").hasSize(29);
        verify(tc, times(25)).prBranchRuns(eq(TOK), eq(PR), anyLong(), eq("IgniteTests24Java8_Suite0"));

        clearInvocations(tc);
        when(tc.suitesFinishedAfter(eq(TOK), eq(PR), anyLong())).thenReturn(Optional.of(Set.of()));
        analyzer.forceRefresh(TOK, PR);

        assertThat(mockingDetails(tc).getInvocations()).as("when nothing finished: the chain, newer chains, re-runs "
            + "and what finished").hasSize(4);
    }

    /** Moves every expiry of the kept facts, and the moments the branch was checked, back by {@code ms}. */
    private void age(Path file, long ms) throws Exception {
        JsonNode root = mapper.readTree(file.toFile());
        ObjectNode facts = (ObjectNode)root.get("facts");
        for (String kept : List.of("failedTests", "chains", "testRuns", "latestRuns")) {
            for (JsonNode e : facts.get(kept))
                ((ObjectNode)e).put("expiresAt", e.get("expiresAt").asLong() - ms);
        }
        ObjectNode checked = (ObjectNode)facts.get("checkedAt");
        checked.fieldNames().forEachRemaining(pr -> checked.put(pr, checked.get(pr).asLong() - ms / 1000));
        mapper.writeValue(file.toFile(), root);
    }

    private BlockerAnalyzer analyzer(AnalysisCache withCache) {
        return new BlockerAnalyzer(tc, new ChainCollector(tc, baseline, withCache), cfg,
            Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newSingleThreadExecutor(),
            withCache, new RunDeltaStore(mapper));
    }

    private void failing(long run, String suite, long... testIds) {
        List<TcModel.TestOccurrence> failed = new ArrayList<>();
        for (long id : testIds) {
            failed.add(new TcModel.TestOccurrence("id:" + id + ",build:(id:" + run + ")", "Test" + id, "FAILURE",
                new TcModel.TestRef(id), null, null));
            when(tc.prBranchRuns(TOK, PR, id, suite)).thenReturn(List.of(run(run, suite, "FAILURE")));
        }
        when(tc.getFailedTests(TOK, run)).thenReturn(failed);
    }

    private static TcModel.TestOccurrence run(long build, String suite, String status) {
        return new TcModel.TestOccurrence(null, null, status, null,
            new TcModel.BuildRef(build, null, "finished", status, suite, null, null, null), null);
    }

    private static List<TcModel.TestOccurrence> cleanMaster() {
        return new ArrayList<>(Collections.nCopies(20, new TcModel.TestOccurrence(null, null, "SUCCESS", null, null,
            null)));
    }

    private static TcModel.Build chain(String state, List<TcModel.Build> deps) {
        return new TcModel.Build(CHAIN, "FAILURE", state, "pull/" + PR + "/head", RUN_ALL, null, null, null, null,
            null, null, new TcModel.BuildType(RUN_ALL, "Run All"), null, new TcModel.SnapshotDeps(deps.size(), deps),
            null, null, null, null);
    }

    private static TcModel.Build suite(long id, String buildTypeId, String state) {
        return new TcModel.Build(id, "FAILURE", state, "pull/" + PR + "/head", buildTypeId, null, null, null, null,
            null, null, new TcModel.BuildType(buildTypeId, buildTypeId), null, null, null,
            new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence("TC_EXIT_CODE", null))), null,
            new TcModel.TestOccurrences(300, List.of()));
    }

    private static TcModel.Build passed(long id, String buildTypeId) {
        return new TcModel.Build(id, "SUCCESS", "finished", "pull/" + PR + "/head", buildTypeId, null, null, null,
            null, null, null, new TcModel.BuildType(buildTypeId, buildTypeId), null, null, null, null, null,
            new TcModel.TestOccurrences(300, List.of()));
    }
}
