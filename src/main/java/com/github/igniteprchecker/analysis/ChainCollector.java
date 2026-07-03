package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Walks the RunAll chain build for a PR into its dependency suites and collects the failed tests
 * across all of them (deduplicated by test id).
 */
@Component
public class ChainCollector {
    private final TcClient tc;

    public ChainCollector(TcClient tc) {
        this.tc = tc;
    }

    public Optional<Chain> collect(String token, int prNumber) {
        Optional<TcModel.Build> found = tc.findRunAllBuildForPr(token, prNumber);
        if (found.isEmpty())
            return Optional.empty();

        TcModel.Build build = tc.getBuildWithDeps(token, found.get().id());

        List<FailedTest> failed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (TcModel.Build dep : depBuilds(build)) {
            if (!"FAILURE".equals(dep.status()))
                continue;

            for (TcModel.TestOccurrence occ : tc.getFailedTests(token, dep.id())) {
                if (occ.test() == null)
                    continue;

                if (seen.add(occ.test().id()))
                    failed.add(new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId()));
            }
        }

        return Optional.of(new Chain(build.id(), build.branchName(), failed));
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
