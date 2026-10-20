package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.ServiceMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.github.igniteprchecker.web.AnalyzeController;
import com.github.igniteprchecker.web.PrHeads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * apache/ignite#13709 was merged at 10:12:39 UTC. Its RunAll 9425992 on c577b18 had blamed a test of Control Utility
 * 5, and the author's re-run of that suite on the PR's head 9b02561, build 9426316, ran from 10:02 to 10:28 and passed.
 * The verdict kept at the merge, computed at 10:11, kept showing the blocker for good, while the open PR's verdict
 * after 10:28 had none. Once such a run ends, the kept verdict is computed once more, of the runs queued before the
 * merge and of the runs of master and other PRs that finished before it, and it names the runs it adds.
 */
class RunsAtMergeTest {
    private static final String TOK = "tok";

    private static final int PR = 13709;

    private static final long CHAIN = 9425992L;

    private static final long CHAIN_CU5 = 9425950L;

    private static final long RERUN = 9426316L;

    private static final long RERUN_BUILD = 9426315L;

    private static final long TEST = 1001L;

    private static final String CU5 = "IgniteTests24Java8_ControlUtility5";

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private static final String RUN_ALL_COMMIT = "c577b18e2f0d4a6b8c1d3e5f7a9b0c2d4e6f8a1b";

    private static final String HEAD = "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a";

    /** 2026-10-20 10:12:39 UTC. */
    private static final long MERGED_AT = 1_792_491_159L;

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final AnalysisProperties cfg = new AnalysisProperties(null, RUN_ALL, null, null, null, null, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final SuiteBaseline baseline = mock(SuiteBaseline.class);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, new ChainCollector(tc, baseline), cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), cache,
        new RunDeltaStore(mapper));

    private long now = at("20261020T102000+0000") * 1000;

    @Test
    void aReRunGoingAtTheMergeCompletesTheVerdictOnceItEnds(@TempDir Path dir) throws Exception {
        MergedVerdicts merged = keptAtTheMerge(dir);
        RunsAtMerge runs = runsAtMerge(merged);
        assertThat(merged.runsDue()).containsExactly(PR);

        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("running", null), rerunsBuild(), runAll(), chainCu5()));
        runs.sweep();

        assertThat(merged.verdict(PR).orElseThrow().blockers()).as("while the re-run goes").hasSize(1);
        assertThat(merged.runsDue()).containsExactly(PR);
        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());

        now = at("20261020T103000+0000") * 1000;
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("finished", "20261020T102800+0000"), rerunsBuild(),
            runAll(), chainCu5()));
        runs.sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.blockers()).isEmpty();
        assertThat(done.filtered()).extracting(TestVerdict::reason)
            .containsExactly("not failing in the last finished run (passed on re-run)");
        assertThat(done.runsAtMerge()).as("the re-run, not the build it needed")
            .containsExactly(new RunRef(CU5, "Control Utility 5", RERUN, HEAD));
        assertThat(done.otherCodeRuns()).as("a mixed run").containsExactly(new RunRef(CU5, "Control Utility 5", RERUN,
            HEAD));
        assertThat(done.mergedAt()).isEqualTo(MERGED_AT);
        assertThat(done.buildId()).isEqualTo(CHAIN);
        assertThat(merged.runsDue()).isEmpty();
        verify(tc).getBaseBranchHistory(TOK, TEST, CU5, MERGED_AT);
        verify(tc, never()).getBaseBranchHistory(TOK, TEST, CU5);

        MergedVerdicts restarted = new MergedVerdicts(github, cache, dir, mapper);
        restarted.load();
        assertThat(restarted.runsDue()).as("once only").isEmpty();
        assertThat(restarted.verdict(PR).orElseThrow().runsAtMerge()).hasSize(1);
        assertThat(ServiceMapper.get().readValue(dir.resolve(PR + ".json").toFile(), ReleaseBefore.class).verdict()
            .blockers()).as("the release before reads the file, the new fields left aside").isEmpty();
    }

    /** A RunAll someone queued after the merge, and its failure in Control Utility 5, are not the PR's merged code. */
    @Test
    void runsQueuedAfterTheMergeDoNotCount(@TempDir Path dir) throws Exception {
        MergedVerdicts merged = keptAtTheMerge(dir);
        TcModel.Build later = build(9427000L, RUN_ALL, "Run All", "finished", "FAILURE", "20261020T110000+0000",
            "20261020T130000+0000", "user", HEAD);
        TcModel.Build laterCu5 = build(9426990L, CU5, "Control Utility 5", "finished", "FAILURE",
            "20261020T110000+0000", "20261020T120000+0000", null, HEAD);
        when(tc.branchBuilds(TOK, PR)).thenReturn(List.of(later, laterCu5,
            rerun("finished", "20261020T102800+0000"), rerunsBuild(), runAll(), chainCu5()));
        when(tc.recentChains(TOK, PR, 3)).thenReturn(List.of(later));
        when(tc.failedBuildsSince(anyString(), anyInt(), anyString())).thenReturn(List.of(laterCu5));
        when(tc.prBranchRuns(TOK, PR, TEST, CU5)).thenReturn(List.of(occurrence(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT),
            occurrence(RERUN, "SUCCESS", HEAD), occurrence(9426990L, "FAILURE", HEAD)));
        now = at("20261020T140000+0000") * 1000;

        runsAtMerge(merged).sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.blockers()).isEmpty();
        assertThat(done.filtered()).extracting(TestVerdict::branchRuns).containsExactly("FP");
        assertThat(done.buildId()).isEqualTo(CHAIN);
        verify(tc, never()).getBuildWithDeps(TOK, 9427000L);
        verify(tc, never()).getFailedTests(TOK, 9426990L);
    }

    @Test
    void aMergeWithNoRunGoingKeepsTheVerdictAsItWas(@TempDir Path dir) throws Exception {
        MergedVerdicts merged = keptAtTheMerge(dir);
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(runAll(), chainCu5()));

        runsAtMerge(merged).sweep();

        assertThat(merged.verdict(PR).orElseThrow().blockers()).hasSize(1);
        assertThat(merged.verdict(PR).orElseThrow().runsAtMerge()).isEmpty();
        assertThat(merged.runsDue()).isEmpty();
        assertThat(Files.readString(dir.resolve(PR + ".json"))).contains("\"runsAtMergeDue\":false");
        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());
    }

    /** One TeamCity error must not keep a verdict that left the re-run's test unchecked. */
    @Test
    void aVerdictTeamCityErrorsLeftIncompleteIsTriedAgain(@TempDir Path dir) throws Exception {
        MergedVerdicts merged = keptAtTheMerge(dir);
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("finished", "20261020T102800+0000"), runAll(),
            chainCu5()));
        when(tc.prBranchRuns(TOK, PR, TEST, CU5)).thenThrow(new IllegalStateException("502 Bad Gateway"));
        now = at("20261020T103000+0000") * 1000;

        runsAtMerge(merged).sweep();

        assertThat(merged.verdict(PR).orElseThrow().blockers()).hasSize(1);
        assertThat(merged.runsDue()).containsExactly(PR);
    }

    /**
     * A verdict an earlier release kept has no word on the runs that were going at the merge: it is looked at once if
     * the PR was merged in the last week, while TeamCity still keeps its builds.
     */
    @Test
    void aVerdictKeptByAnEarlierReleaseIsLookedAtOnceIfMergedLately(@TempDir Path dir) throws Exception {
        long nowSec = System.currentTimeMillis() / 1000;
        writeAsTheReleaseBefore(dir, 13709, nowSec - 86_400);
        writeAsTheReleaseBefore(dir, 13500, nowSec - 30 * 86_400);

        MergedVerdicts merged = new MergedVerdicts(github, cache, dir, mapper);
        merged.load();

        assertThat(merged.runsDue()).containsExactly(13709);
        assertThat(merged.verdict(13500)).isPresent();
    }

    /**
     * The verdict kept at the merge was computed at 10:05. The author's re-run passed at 10:10, before the merge at
     * 10:12:39, and no compute read it before the PR left the open list. The look counts it as a run the kept verdict
     * did not read.
     */
    @Test
    void aReRunThatEndedAfterTheKeptVerdictButBeforeTheMergeCompletesIt(@TempDir Path dir) {
        MergedVerdicts merged = kept(dir, "20261020T100500+0000", "20261020T100400+0000", keptAtTheMergeBlocker());
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("finished", "20261020T101000+0000"), runAll(),
            chainCu5()));
        now = at("20261020T103000+0000") * 1000;

        runsAtMerge(merged).sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.blockers()).isEmpty();
        assertThat(done.runsAtMerge()).extracting(RunRef::buildId).containsExactly(RERUN);
    }

    /**
     * The re-run going at the merge failed the test again on the PR's head. By the look, three PRs had taken master
     * after the merge, and with it this PR's code, and each failed the test. Other PRs' runs count only when they
     * finished before the merge, as master's do, so the PR's blocker stays one.
     */
    @Test
    void otherPrsRunsAfterTheMergeDoNotTurnItsBlockerFlaky(@TempDir Path dir) {
        MergedVerdicts merged = keptAtTheMerge(dir);
        when(tc.prBranchRuns(TOK, PR, TEST, CU5)).thenReturn(List.of(occurrence(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT),
            occurrence(RERUN, "FAILURE", HEAD)));
        List<TcModel.TestOccurrence> others = new ArrayList<>();
        for (int pr = 13720; pr <= 13722; pr++) {
            others.add(onOtherPr(9427000L + pr * 10L, pr));
            others.add(onOtherPr(9427001L + pr * 10L, pr));
        }
        when(tc.otherBranchRuns(TOK, TEST, CU5)).thenReturn(others);
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(build(RERUN, CU5, "Control Utility 5", "finished", "FAILURE",
            "20261020T100150+0000", "20261020T102800+0000", "user", HEAD), runAll(), chainCu5()));
        now = at("20261020T103000+0000") * 1000;

        runsAtMerge(merged).sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.runsAtMerge()).as("computed once more").hasSize(1);
        assertThat(done.blockers()).extracting(TestVerdict::testId).containsExactly(TEST);
        verify(tc).otherBranchRuns(TOK, TEST, CU5, MERGED_AT);
        verify(tc, never()).otherBranchRuns(TOK, TEST, CU5);
    }

    /**
     * The open PR's verdict computed at 10:45, half an hour after the merge, was kept: it read a re-run queued at
     * 10:15, after the merge, which passed. Nothing was going at the merge, but the verdict is computed once more
     * without that run, as the merged code's verdict.
     */
    @Test
    void aRunQueuedAfterTheMergeDoesNotStayInAVerdictKeptAfterIt(@TempDir Path dir) {
        long afterMerge = 9426400L;
        MergedVerdicts merged = kept(dir, "20261020T104500+0000", "20261020T104400+0000", new TestVerdict(TEST,
            "GridCommandHandlerTest.testCacheIdle", CU5, CHAIN_CU5, "Control Utility 5",
            "build:(id:" + CHAIN_CU5 + "),id:1", false, false,
            "not failing in the last finished run (passed on re-run)", "FP", 0));
        when(tc.prBranchRuns(TOK, PR, TEST, CU5)).thenReturn(List.of(occurrence(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT),
            occurrence(afterMerge, "SUCCESS", HEAD)));
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(build(afterMerge, CU5, "Control Utility 5", "finished",
            "SUCCESS", "20261020T101500+0000", "20261020T104000+0000", "user", HEAD), runAll(), chainCu5()));
        now = at("20261020T110000+0000") * 1000;

        runsAtMerge(merged).sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.blockers()).as("the merged code's verdict").extracting(TestVerdict::testId)
            .containsExactly(TEST);
        assertThat(done.runsAtMerge()).isEmpty();
        assertThat(merged.runsDue()).isEmpty();
    }

    /**
     * Control Utility 5 timed out in the RunAll. A full run of it queued after the merge passed: it does not heal the
     * suite in the merged PR's verdict. The verdict is computed once more for a re-run of Cache 1 that was going at
     * the merge.
     */
    @Test
    void aRunQueuedAfterTheMergeDoesNotHealABrokenSuite(@TempDir Path dir) {
        MergedVerdicts merged = keptAtTheMerge(dir);
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainWithCu5("TC_EXECUTION_TIMEOUT", 10));
        when(tc.getFailedTests(TOK, CHAIN_CU5)).thenReturn(List.of());
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(
            build(9426990L, CU5, "Control Utility 5", "finished", "SUCCESS", "20261020T101500+0000",
                "20261020T104000+0000", "user", HEAD),
            build(9426300L, "IgniteTests24Java8_Cache1", "Cache 1", "finished", "SUCCESS", "20261020T100100+0000",
                "20261020T102800+0000", "user", HEAD),
            runAll(), chainCu5()));
        now = at("20261020T110000+0000") * 1000;

        runsAtMerge(merged).sweep();

        AnalysisResult done = merged.verdict(PR).orElseThrow();
        assertThat(done.runsAtMerge()).extracting(RunRef::buildId).containsExactly(9426300L);
        assertThat(done.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(CU5);
    }

    /** A re-run queued before the merge and cancelled after it has no result: the verdict stays as it was. */
    @Test
    void aRunCancelledAfterTheMergeDoesNotCount(@TempDir Path dir) {
        MergedVerdicts merged = keptAtTheMerge(dir);
        TcModel.Build cancelled = new TcModel.Build(RERUN, "UNKNOWN", "finished", "pull/13709/head", CU5, null,
            "20261020T100150+0000", null, "20261020T102000+0000", null, null,
            new TcModel.BuildType(CU5, "Control Utility 5"), new TcModel.Triggered("user", null), null, null, null,
            revisions(HEAD), new TcModel.TestOccurrences(0, List.of()), new TcModel.CanceledInfo("stopped", null));
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(cancelled, runAll(), chainCu5()));
        now = at("20261020T103000+0000") * 1000;

        runsAtMerge(merged).sweep();

        assertThat(merged.verdict(PR).orElseThrow().blockers()).hasSize(1);
        assertThat(merged.runsDue()).isEmpty();
        verify(tc, never()).getBuildWithDeps(anyString(), anyLong());
    }

    /**
     * TeamCity fails every look at #13600, whose number is lower than #13709's. The next sweep looks at #13709 first,
     * and #13600 is settled as it is once the week of looks after its merge has passed.
     */
    @Test
    void aPrTeamCityAlwaysFailsDoesNotHoldUpTheOthers(@TempDir Path dir) throws Exception {
        long realNow = System.currentTimeMillis() / 1000;
        writeAsTheReleaseBefore(dir, 13600, realNow - 3 * 86_400);
        MergedVerdicts merged = keptAtTheMerge(dir);
        assertThat(merged.runsDue()).containsExactlyInAnyOrder(13600, PR);
        when(tc.branchBuilds(TOK, 13600)).thenThrow(new IllegalStateException("404 Not Found"));
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("finished", "20261020T102800+0000"), rerunsBuild(),
            runAll(), chainCu5()));
        now = at("20261020T103000+0000") * 1000;
        RunsAtMerge runs = runsAtMerge(merged);

        runs.sweep();
        runs.sweep();

        assertThat(merged.verdict(PR).orElseThrow().blockers()).as("#13709's re-run passed").isEmpty();
        assertThat(merged.runsDue()).containsExactly(13600);

        now = (realNow + 8 * 86_400) * 1000;
        runs.sweep();

        assertThat(merged.runsDue()).isEmpty();
        assertThat(merged.verdict(13600)).isPresent();
    }

    /** While the look is due, the page is told that the merged PR's verdict may still change; after it, not. */
    @Test
    void thePageIsToldWhileTheVerdictMayStillChange(@TempDir Path dir) {
        MergedVerdicts merged = keptAtTheMerge(dir);
        AnalyzeController page = new AnalyzeController(new BlockerAnalyzer(tc, new ChainCollector(tc, baseline), cfg,
            Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
            Executors.newFixedThreadPool(2), cache, new RunDeltaStore(mapper), merged), mock(RunDeltaStore.class),
            mock(PendingCommits.class), mock(PrHeads.class));
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("running", null), rerunsBuild(), runAll(), chainCu5()));
        RunsAtMerge runs = runsAtMerge(merged);

        runs.sweep();
        JsonNode going = mapper.valueToTree(page.analyze(PR, TOK).getBody());

        now = at("20261020T103000+0000") * 1000;
        when(tc.branchBuilds(TOK, PR)).thenReturn(branch(rerun("finished", "20261020T102800+0000"), rerunsBuild(),
            runAll(), chainCu5()));
        runs.sweep();
        JsonNode done = mapper.valueToTree(page.analyze(PR, TOK).getBody());

        assertThat(going.get("runsAtMergeDue").asBoolean()).isTrue();
        assertThat(going.get("blockers")).hasSize(1);
        assertThat(done.get("runsAtMergeDue").asBoolean()).isFalse();
        assertThat(done.get("blockers")).isEmpty();
    }

    /** The open PR's verdict computed at 10:11, a minute before the merge, kept once the PR left the list. */
    private MergedVerdicts keptAtTheMerge(Path dir) {
        return kept(dir, "20261020T101100+0000", "20261020T101000+0000", keptAtTheMergeBlocker());
    }

    /** The test of Control Utility 5 the RunAll failed, a blocker in the verdict kept at the merge. */
    private static TestVerdict keptAtTheMergeBlocker() {
        return new TestVerdict(TEST, "GridCommandHandlerTest.testCacheIdle", CU5, CHAIN_CU5, "Control Utility 5",
            "build:(id:" + CHAIN_CU5 + "),id:1", true, false,
            "not seen failing in 50 master runs; failed the only run on this branch", "F", 1);
    }

    /**
     * The open PR's verdict of the RunAll, computed at {@code computedAt} with the branch read up to {@code watermark},
     * with {@code test} as a blocker or a filtered test, kept once the PR left the list.
     */
    private MergedVerdicts kept(Path dir, String computedAt, String watermark, TestVerdict test) {
        when(baseline.counts(anyString())).thenReturn(Map.of());
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13800, "IGNITE-1 x", "u", null, null, null)));
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(true, true, MERGED_AT, "f00dcafe", HEAD,
            "IGNITE-29000 Fix the idle check"));
        when(tc.getBuildWithDeps(TOK, CHAIN)).thenReturn(chainWithCu5(null, 200));
        when(tc.getFailedTests(TOK, CHAIN_CU5)).thenReturn(List.of(new TcModel.TestOccurrence(
            "build:(id:" + CHAIN_CU5 + "),id:1", "GridCommandHandlerTest.testCacheIdle", "FAILURE",
            new TcModel.TestRef(TEST), null, null)));
        when(tc.getBaseBranchHistory(TOK, TEST, CU5, MERGED_AT)).thenReturn(passes(50));
        when(tc.prBranchRuns(TOK, PR, TEST, CU5)).thenReturn(List.of(occurrence(CHAIN_CU5, "FAILURE", RUN_ALL_COMMIT),
            occurrence(RERUN, "SUCCESS", HEAD)));

        cache.putResult(CHAIN, new AnalysisResult(PR, CHAIN, "pull/13709/head", at(computedAt) * 1000,
            test.blocker() ? List.of(test) : List.of(), List.of(), test.blocker() ? List.of() : List.of(test),
            List.of(), List.of(), 148, 1, false, 0, false, 0, at("20261020T080000+0000"), at("20261020T080100+0000"),
            at("20261020T095000+0000"), at(watermark), List.of(), List.of(), List.of(), 0, RUN_ALL_COMMIT, 0));

        MergedVerdicts merged = new MergedVerdicts(github, cache, dir, mapper);
        merged.load();
        merged.sweep();

        return merged;
    }

    /** RunAll 9425992 with its Control Utility 5, which ran {@code tests} tests and hit {@code problem}, if any. */
    private static TcModel.Build chainWithCu5(String problem, int tests) {
        return new TcModel.Build(CHAIN, "FAILURE", "finished", "pull/13709/head", RUN_ALL, null,
            "20261020T080000+0000", "20261020T080100+0000", "20261020T095000+0000", null, null,
            new TcModel.BuildType(RUN_ALL, "Run All"), null, new TcModel.SnapshotDeps(1, List.of(
                new TcModel.Build(CHAIN_CU5, "FAILURE", "finished", "pull/13709/head", CU5, null,
                    "20261020T080000+0000", null, null, null, null, new TcModel.BuildType(CU5, "Control Utility 5"),
                    null, null, null, problem == null ? null
                        : new TcModel.ProblemOccurrences(List.of(new TcModel.ProblemOccurrence(problem, null))),
                    revisions(RUN_ALL_COMMIT), new TcModel.TestOccurrences(tests, List.of())))),
            null, null, revisions(RUN_ALL_COMMIT), null);
    }

    private RunsAtMerge runsAtMerge(MergedVerdicts merged) {
        return new RunsAtMerge(merged, analyzer, tc, cfg, () -> TOK, () -> now);
    }

    /** The branch's builds as ci2 lists them, newest first. */
    private static List<TcModel.Build> branch(TcModel.Build... builds) {
        return List.of(builds);
    }

    /** The author's re-run of Control Utility 5 on the PR's head, queued ten minutes before the merge. */
    private static TcModel.Build rerun(String state, String finished) {
        return build(RERUN, CU5, "Control Utility 5", state, finished == null ? null : "SUCCESS",
            "20261020T100150+0000", finished, "user", HEAD);
    }

    /** The build step the re-run needed, queued with it as its dependency. */
    private static TcModel.Build rerunsBuild() {
        return build(RERUN_BUILD, "IgniteTests24Java8_BuildApacheIgnite", "Build Apache Ignite", "finished", "SUCCESS",
            "20261020T100150+0000", "20261020T102000+0000", null, HEAD);
    }

    private static TcModel.Build runAll() {
        return build(CHAIN, RUN_ALL, "Run All", "finished", "FAILURE", "20261020T080000+0000", "20261020T095000+0000",
            "user", RUN_ALL_COMMIT);
    }

    private static TcModel.Build chainCu5() {
        return build(CHAIN_CU5, CU5, "Control Utility 5", "finished", "FAILURE", "20261020T080000+0000",
            "20261020T092000+0000", null, RUN_ALL_COMMIT);
    }

    private static TcModel.Build build(long id, String suite, String name, String state, String status, String queued,
        String finished, String triggered, String commit) {
        return new TcModel.Build(id, status, state, "pull/13709/head", suite, null, queued, null, finished, null, null,
            new TcModel.BuildType(suite, name), triggered == null ? null : new TcModel.Triggered(triggered, null), null,
            null, null, revisions(commit), new TcModel.TestOccurrences(200, List.of()));
    }

    private static TcModel.TestOccurrence occurrence(long buildId, String status, String commit) {
        return new TcModel.TestOccurrence("build:(id:" + buildId + "),id:1", null, status, null,
            new TcModel.BuildRef(buildId, null, "finished", status, CU5,
                new TcModel.BuildType(CU5, "Control Utility 5"), revisions(commit), null), null);
    }

    /** A failure of the test in Control Utility 5 on another PR's branch. */
    private static TcModel.TestOccurrence onOtherPr(long buildId, int pr) {
        return new TcModel.TestOccurrence(null, null, "FAILURE", null, new TcModel.BuildRef(buildId,
            "pull/" + pr + "/head", "finished", "FAILURE", CU5, null, null, null), null);
    }

    private static TcModel.Revisions revisions(String commit) {
        return new TcModel.Revisions(List.of(new TcModel.Revision(commit)));
    }

    private static List<TcModel.TestOccurrence> passes(int n) {
        List<TcModel.TestOccurrence> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null));

        return out;
    }

    private static long at(String tcDate) {
        return TcDates.epochSeconds(tcDate);
    }

    /** A merged PR's file as the release before this look wrote it: no runsAtMergeDue, no fields of runs. */
    private void writeAsTheReleaseBefore(Path dir, int pr, long mergedAt) throws Exception {
        AnalysisResult verdict = new AnalysisResult(pr, CHAIN, "pull/" + pr + "/head", mergedAt * 1000, List.of(),
            List.of(), List.of(), List.of(), List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(),
            List.of(), 0, RUN_ALL_COMMIT, mergedAt);
        ObjectNode file = (ObjectNode)mapper.valueToTree(new MergedVerdicts.Merged(pr, "IGNITE-1 x", mergedAt, "f00dcafe", HEAD,
            mergedAt * 1000, TestVerdict.RULES - 1, verdict, null));
        file.remove("runsAtMergeDue");
        ((ObjectNode)file.get("verdict")).remove(List.of("otherCodeRuns", "runsAtMerge"));
        Files.createDirectories(dir);
        mapper.writeValue(dir.resolve(pr + ".json").toFile(), file);
    }

    /** What the release before reads of a merged PR's file. */
    record ReleaseBefore(int pr, long mergedAt, int rules, VerdictBefore verdict) {
    }

    record VerdictBefore(int prNumber, long buildId, List<TestVerdict> blockers, List<TestVerdict> filtered,
        long mergedAt) {
    }
}
