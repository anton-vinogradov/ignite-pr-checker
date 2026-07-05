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
    /** How long a PR's latest-build lookup stays valid — short, since it only avoids re-querying the same
     * RunAll on rapid repeat views; a brand-new run is still picked up within this window. */
    private static final long BUILD_ID_TTL_MS = 30_000;

    private final TcClient tc;

    /** Caches the PR -> latest-RunAll-build-id lookup so a warm {@code /api/analyze} (and each warmer/refresh
     * check) doesn't pay a TeamCity round-trip that almost always returns the same build. */
    private final TtlCache<Integer, Long> buildIds = new TtlCache<>(BUILD_ID_TTL_MS);

    public ChainCollector(TcClient tc) {
        this.tc = tc;
    }

    /** The latest RunAll build id for a PR branch, if any (cached briefly). */
    public Optional<Long> findBuildId(String token, int prNumber) {
        Optional<Long> cached = buildIds.peek(prNumber);

        return cached.isPresent() ? cached : lookupBuildId(token, prNumber);
    }

    /** Like {@link #findBuildId} but bypasses the cache — for the manual refresh, which must see a just-finished run. */
    public Optional<Long> findBuildIdFresh(String token, int prNumber) {
        return lookupBuildId(token, prNumber);
    }

    private Optional<Long> lookupBuildId(String token, int prNumber) {
        Optional<Long> found = tc.findRunAllBuildForPr(token, prNumber).map(TcModel.Build::id);
        found.ifPresent(id -> buildIds.put(prNumber, id));

        return found;
    }

    /**
     * Expands a RunAll build into its failed tests (across its FAILURE suites). The {@code pool} is the
     * caller's fan-out pool: foreground analyses pass the analysis pool, background ones (warmer/refresh)
     * pass their own so they don't compete with user-facing requests.
     */
    public Chain collectForBuild(String token, long buildId, ExecutorService pool) {
        TcModel.Build build = tc.getBuildWithDeps(token, buildId);

        List<Callable<List<FailedTest>>> tasks = depBuilds(build).stream()
            .filter(dep -> "FAILURE".equals(dep.status()))
            .<Callable<List<FailedTest>>>map(dep -> () -> failedTestsOf(token, dep))
            .toList();

        List<FailedTest> failed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (List<FailedTest> perSuite : Parallel.run(pool, tasks)) {
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
            .map(occ -> new FailedTest(occ.test().id(), occ.name(), dep.buildTypeId(), dep.id(), suiteName, occ.id()))
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
