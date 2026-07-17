package com.github.igniteprchecker.analysis;

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
