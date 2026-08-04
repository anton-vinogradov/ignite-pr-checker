package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A suite that ran far fewer tests than master must be surfaced; a suite matching master (or a
 * touch above it) must not. Mirrors the real numbers seen on ci2: the Thin Client suite that ran
 * 57 of its 439 tests, versus the C++ suites TeamCity's own stale metric false-alarms on (595
 * tests against master's 594).
 */
class SuiteShrinkTest {
    private static final String TOK = "t";

    @Test
    void flagsOnlyRealDrops() {
        TcClient tc = mock(TcClient.class);
        SuiteBaseline baseline = mock(SuiteBaseline.class);

        when(baseline.counts(anyString())).thenReturn(Map.of(
            "ThinClientJava", 439,   // the shrunk one
            "CppLinux", 594,         // TC's stale metric cries about this one; master says it's fine
            "Tiny", 10,              // below the min baseline: percentages here are noise
            "Cache1", 500));         // unchanged

        when(tc.getBuildWithDeps(TOK, 999L)).thenReturn(chain(
            dep(1, "ThinClientJava", "Thin Client: Java", "SUCCESS", 57),
            dep(2, "CppLinux", "Platform C++ CMake (Linux)", "SUCCESS", 595),
            dep(3, "Tiny", "Tiny suite", "SUCCESS", 4),
            dep(4, "Cache1", "Cache 1", "SUCCESS", 500)));
        when(tc.recentChains(TOK, 42, 3)).thenReturn(List.of());

        ChainCollector collector = new ChainCollector(tc, baseline);
        List<ShrunkSuite> shrunk = collector
            .collectForBuild(TOK, 42, 999L, Executors.newSingleThreadExecutor())
            .shrunkSuites();

        assertEquals(1, shrunk.size(), "only the real drop is flagged, got: " + shrunk);
        ShrunkSuite s = shrunk.get(0);
        assertEquals("Thin Client: Java", s.suiteName());
        assertEquals(57, s.tests());
        assertEquals(439, s.baseline());
        assertEquals(87, s.dropPct());
        assertTrue(shrunk.stream().noneMatch(x -> x.suite().equals("CppLinux")),
            "a suite level with master (595 vs 594) is not a drop");
    }

    /**
     * The real shape of ci2 build 9253651 (Cache (Failover) 5 on PR 13434): a hung suite that
     * TeamCity killed on the execution timeout after 33 of master's 67 tests. TeamCity's own status
     * line leads with "33 is 51% less than 67", which reads as "tests disappeared" and buries the
     * timeout — the checker must report the cause, with the shortfall as its evidence.
     */
    @Test
    void aTimedOutSuiteIsReportedAsBrokenNotAsShrunk() {
        TcClient tc = mock(TcClient.class);
        SuiteBaseline baseline = mock(SuiteBaseline.class);

        when(baseline.counts(anyString())).thenReturn(Map.of("CacheFailover5", 67));
        TcModel.Build hung = timedOut(9253651, "CacheFailover5", "Cache (Failover) 5", 33);
        when(tc.getBuildWithDeps(TOK, 999L)).thenReturn(chain(hung));
        when(tc.recentChains(TOK, 42, 3)).thenReturn(List.of());

        ChainCollector.Chain chain = new ChainCollector(tc, baseline)
            .collectForBuild(TOK, 42, 999L, Executors.newSingleThreadExecutor());

        assertTrue(chain.shrunkSuites().isEmpty(), "the timeout owns the missing tests, got: " + chain.shrunkSuites());
        assertEquals(1, chain.brokenSuites().size());
        BrokenSuite broken = chain.brokenSuites().get(0);
        assertEquals(List.of("execution timeout"), broken.problems());
        assertEquals(33, broken.tests(), "the shortfall rides along as evidence");
        assertEquals(67, broken.baseline());
    }

    @Test
    void aRunBackToFullStrengthIsNoLongerShrunk() {
        assertTrue(ChainCollector.isFullRun(63, 67), "63 of 67 is within the threshold");
        assertTrue(!ChainCollector.isFullRun(33, 67), "33 of 67 is still half the suite");
    }

    private static TcModel.Build timedOut(long id, String buildTypeId, String name, int tests) {
        TcModel.Build b = dep(id, buildTypeId, name, "FAILURE", tests);

        return new TcModel.Build(b.id(), b.status(), b.state(), b.branchName(), b.buildTypeId(), null, null, null,
            null, null, null, b.buildType(), null, null, null,
            new TcModel.ProblemOccurrences(List.of(
                new TcModel.ProblemOccurrence("TC_EXECUTION_TIMEOUT", "Execution timeout"))),
            null, b.testOccurrences());
    }

    private static TcModel.Build chain(TcModel.Build... deps) {
        return new TcModel.Build(999, "FAILURE", "finished", "pull/42/head", "RunAll", null, null, null, null, null,
            null, null, null, new TcModel.SnapshotDeps(deps.length, List.of(deps)), null, null, null, null);
    }

    private static TcModel.Build dep(long id, String buildTypeId, String name, String status, int tests) {
        return new TcModel.Build(id, status, "finished", "pull/42/head", buildTypeId, null, null, null, null, null,
            null, new TcModel.BuildType(buildTypeId, name), null, null, null, null, null,
            new TcModel.TestOccurrences(tests, List.of()));
    }
}
