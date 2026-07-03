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
import org.springframework.stereotype.Component;

/**
 * Walks the RunAll chain build for a PR into its dependency suites and collects the failed tests
 * across all of them (deduplicated by test id). The per-suite lookups run in parallel.
 */
@Component
public class ChainCollector {
    private final TcClient tc;
    private final ExecutorService executor;

    public ChainCollector(TcClient tc, ExecutorService analysisExecutor) {
        this.tc = tc;
        this.executor = analysisExecutor;
    }

    public Optional<Chain> collect(String token, int prNumber) {
        Optional<TcModel.Build> found = tc.findRunAllBuildForPr(token, prNumber);
        if (found.isEmpty())
            return Optional.empty();

        TcModel.Build build = tc.getBuildWithDeps(token, found.get().id());

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

        return Optional.of(new Chain(build.id(), build.branchName(), failed));
    }

    private List<FailedTest> failedTestsOf(String token, TcModel.Build dep) {
        return tc.getFailedTests(token, dep.id()).stream()
            .filter(occ -> occ.test() != null)
            .map(occ -> new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId()))
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
