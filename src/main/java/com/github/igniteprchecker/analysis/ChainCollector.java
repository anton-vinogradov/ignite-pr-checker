package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Walks the RunAll chain build for a PR into its dependency suites and collects the failed tests
 * across all of them (deduplicated by test id). The per-suite lookups run in parallel.
 */
@Component
public class ChainCollector {
    private final TcClient tc;
    private final ExecutorService executor;

    public ChainCollector(TcClient tc, @Qualifier("analysisExecutor") ExecutorService analysisExecutor) {
        this.tc = tc;
        this.executor = analysisExecutor;
    }

    /** The latest RunAll build id for a PR branch, if any. */
    public Optional<Long> findBuildId(String token, int prNumber) {
        return tc.findRunAllBuildForPr(token, prNumber).map(TcModel.Build::id);
    }

    /** Expands a RunAll build into its failed tests (across its FAILURE suites). */
    public Chain collectForBuild(String token, long buildId) {
        TcModel.Build build = tc.getBuildWithDeps(token, buildId);

        List<Callable<List<FailedTest>>> tasks = depBuilds(build).stream()
            .filter(dep -> "FAILURE".equals(dep.status()))
            .<Callable<List<FailedTest>>>map(dep -> () -> failedTestsOf(token, dep))
            .toList();

        List<FailedTest> failed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (List<FailedTest> perSuite : Parallel.run(executor, tasks)) {
            for (FailedTest ft : perSuite) {
                if (seen.add(ft.testId()))
                    failed.add(ft);
            }
        }

        return new Chain(build.id(), build.branchName(), failed);
    }

    private List<FailedTest> failedTestsOf(String token, TcModel.Build dep) {
        String suiteName = dep.buildType() != null && dep.buildType().name() != null
            ? dep.buildType().name()
            : dep.buildTypeId();

        return tc.getFailedTests(token, dep.id()).stream()
            .filter(occ -> occ.test() != null)
            .map(occ -> new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId(), dep.id(), suiteName))
            .toList();
    }

    private static List<TcModel.Build> depBuilds(TcModel.Build build) {
        TcModel.SnapshotDeps deps = build.snapshotDependencies();
        if (deps == null || deps.build() == null)
            return List.of();

        return deps.build();
    }

    public record Chain(long buildId, String branchName, List<FailedTest> failedTests) {
    }
}
