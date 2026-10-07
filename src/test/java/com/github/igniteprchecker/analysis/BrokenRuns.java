package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.analysis.model.Upstream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Verdicts of the runs the audit found broken suites in. PR 13583: the Build failed with a non-zero exit code, eight
 * suites failed on it and 137 never ran. PR 13655: 60 suites could not fetch the artifacts of a Build that had passed,
 * TeamCity writing the run two ways, next to four suites broken their own ways.
 */
public final class BrokenRuns {
    public static final int PR_13583 = 13583;

    public static final int PR_13655 = 13655;

    public static final String BUILD = "IgniteTests24Java8_BuildApacheIgnite";

    private static final long BUILD_RUN = 9384622L;

    private static final String ARTIFACTS_NUMBERED =
        "Failed to resolve artifacts from <[Apache Ignite 2.x / Tests] / > Build, build #30372 [id 9391124]>";

    private static final String ARTIFACTS_BY_ID =
        "Failed to resolve artifacts from <[Apache Ignite 2.x / Tests] / > Build, build with id: 9391124>";

    private BrokenRuns() {
    }

    /** PR 13583's RunAll 9384769: the Build failed, {@code compileError} saying whether it failed to compile. */
    public static AnalysisResult buildFailed(boolean compileError) {
        Upstream build = new Upstream(BUILD, BUILD_RUN, "> Build");
        List<BrokenSuite> broken = new ArrayList<>();
        broken.add(new BrokenSuite(BUILD, BUILD_RUN, "> Build",
            List.of(compileError ? "compilation error" : "non-zero exit code"), 0, 0,
            List.of(compileError ? "TC_COMPILATION_ERROR" : "TC_EXIT_CODE"), null));
        for (String name : List.of("Platform .NET (Core Linux)", "Thin client: Node.js", "Thin client: PHP",
            "Thin client: Python", "Platform .NET (Windows)", "Platform .NET (Windows) 2", "Platform .NET (Windows) 3",
            "Platform C++ (Linux)")) {
            broken.add(new BrokenSuite(suiteId(name), 9384700L + broken.size(), name, List.of("failed dependency",
                "Failed to resolve artifacts from <[Apache Ignite 2.x / Tests] / > Build, build #30306 [id 9384622]>"),
                0, 100, List.of("SNAPSHOT_DEPENDENCY_ERROR", "ARTIFACT_DEPENDENCY_ERROR"), build));
        }
        List<CancelledSuite> neverRan = IntStream.rangeClosed(1, 137)
            .mapToObj(i -> new CancelledSuite("IgniteTests24Java8_Cache" + i, 9384800L + i, "Cache " + i,
                "Snapshot dependency failed", null, true, 300, build))
            .toList();

        return new AnalysisResult(PR_13583, 9384769L, "pull/13583/head", System.currentTimeMillis(), List.of(),
            List.of(), List.of(), broken, List.of(), 1, 0, true, neverRan.size(), false, 0, 0, 0, 0, 0, List.of(),
            neverRan, List.of(), 0);
    }

    /** PR 13583's verdict once a re-run of the Build passed: the Build left the broken suites, its victims did not. */
    public static AnalysisResult buildPassedOnRerun() {
        AnalysisResult r = buildFailed(false);

        return withBroken(r, r.brokenSuites().subList(1, r.brokenSuites().size()));
    }

    /** PR 13655's RunAll: 60 suites without the Build's artifacts, the Build itself having passed. */
    public static AnalysisResult artifactsGlitch() {
        List<BrokenSuite> broken = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            broken.add(new BrokenSuite("IgniteTests24Java8_Suite" + i, 9391160L + i, "Suite " + i,
                List.of(i <= 38 ? ARTIFACTS_NUMBERED : ARTIFACTS_BY_ID), 0, 100, List.of("ARTIFACT_DEPENDENCY_ERROR"),
                null));
        }
        broken.add(new BrokenSuite("IgniteTests24Java8_Pds5", 9391301L, "PDS 5", List.of("non-zero exit code"), 0, 80));
        broken.add(new BrokenSuite("IgniteTests24Java8_Pds6", 9391302L, "PDS 6", List.of("non-zero exit code"), 0, 80));
        broken.add(new BrokenSuite("IgniteTests24Java8_Cache5", 9391303L, "Cache 5", List.of("execution timeout"), 12,
            35));
        broken.add(new BrokenSuite("IgniteTests24Java8_Basic3", 9391304L, "Basic 3", List.of("Failed to create "
            + "temporary custom script file in directory '/opt/teamcity/temp/agentTmp': java.io.IOException: No space "
            + "left on device"), 0, 164));

        return new AnalysisResult(PR_13655, 9391120L, "pull/13655/head", System.currentTimeMillis(), List.of(),
            List.of(), List.of(), broken, List.of(), 147, 0, false, 0, false, 0, 0, 0, 0, 0);
    }

    /** The same verdict with other broken suites. */
    public static AnalysisResult withBroken(AnalysisResult r, List<BrokenSuite> broken) {
        return new AnalysisResult(r.prNumber(), r.buildId(), r.branchName(), r.computedAt(), r.blockers(), r.watch(),
            r.filtered(), broken, r.shrunkSuites(), r.suitesRan(), r.suitesReused(), r.interrupted(),
            r.canceledSuites(), r.live(), r.liveBuildId(), r.queuedAt(), r.startedAt(), r.finishedAt(),
            r.branchWatermarkAt(), r.unstableSuites(), r.cancelledSuites(), r.unverified(), r.incompleteSince());
    }

    private static String suiteId(String name) {
        return "IgniteTests24Java8_" + name.replaceAll("[^A-Za-z0-9]", "");
    }
}
