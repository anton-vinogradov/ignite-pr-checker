package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;

/**
 * Classifies each failed test of a PR chain as a blocker (broke by this PR) or noise, using the
 * test's base-branch history. Mirrors the original tcbot rule: a failure is a blocker only if the
 * test's base-branch fail rate is below the threshold and the test is not flaky, where "flaky"
 * means it flips pass/fail on builds that carried no code changes. Per-test history lookups run in
 * parallel and are cached (shared across PRs); the whole result is cached per build id.
 */
@Component
public class BlockerAnalyzer {
    private final TcClient tc;
    private final ChainCollector chains;
    private final AnalysisProperties cfg;
    private final ExecutorService executor;
    private final AnalysisCache cache;

    public BlockerAnalyzer(TcClient tc, ChainCollector chains, AnalysisProperties cfg,
        ExecutorService analysisExecutor, AnalysisCache cache) {
        this.tc = tc;
        this.chains = chains;
        this.cfg = cfg;
        this.executor = analysisExecutor;
        this.cache = cache;
    }

    /** @return the analysis, or empty if no RunAll build exists for the PR yet. */
    public Optional<AnalysisResult> analyze(String token, int prNumber) {
        Optional<Long> buildId = chains.findBuildId(token, prNumber);
        if (buildId.isEmpty())
            return Optional.empty();

        return Optional.of(cache.result(buildId.get(), () -> compute(token, prNumber, buildId.get())));
    }

    private AnalysisResult compute(String token, int prNumber, long buildId) {
        ChainCollector.Chain chain = chains.collectForBuild(token, buildId);

        List<Callable<TestVerdict>> tasks = chain.failedTests().stream()
            .<Callable<TestVerdict>>map(t -> () -> classify(token, t))
            .toList();

        List<TestVerdict> verdicts = Parallel.run(executor, tasks);
        List<TestVerdict> blockers = verdicts.stream().filter(TestVerdict::blocker).toList();
        List<TestVerdict> filtered = verdicts.stream().filter(v -> !v.blocker()).toList();

        return new AnalysisResult(prNumber, buildId, chain.branchName(), blockers, filtered);
    }

    private TestVerdict classify(String token, FailedTest t) {
        List<TcModel.TestOccurrence> history = cache.history(t.testId(), () -> tc.getBaseBranchHistory(token, t.testId()));

        int runs = history.size();
        if (runs == 0) {
            // No base-branch history to prove the failure is pre-existing -> treat as a blocker.
            return new TestVerdict(t.testId(), t.name(), t.suite(), true,
                "no base-branch history (can't prove pre-existing)", 0.0, 0, 0);
        }

        int fails = 0;
        int statusChanges = 0;
        String prevStatus = null;

        for (TcModel.TestOccurrence occ : history) {
            if ("FAILURE".equals(occ.status()))
                fails++;

            if (prevStatus != null && !prevStatus.equals(occ.status()) && noCodeChanges(occ))
                statusChanges++;

            prevStatus = occ.status();
        }

        double failRatePct = 100.0 * fails / runs;
        boolean flaky = statusChanges >= cfg.flakinessStatusChangeBorder();
        boolean lowFailRate = failRatePct < cfg.failRateBlockerThresholdPercents();

        if (lowFailRate && !flaky) {
            return new TestVerdict(t.testId(), t.name(), t.suite(), true,
                String.format(Locale.ROOT, "base fail-rate %.1f%% < %.1f%%, not flaky",
                    failRatePct, cfg.failRateBlockerThresholdPercents()),
                failRatePct, statusChanges, runs);
        }

        String reason = flaky
            ? String.format(Locale.ROOT, "flaky: %d status flip(s) without code changes", statusChanges)
            : String.format(Locale.ROOT, "pre-existing: base fail-rate %.1f%% >= %.1f%%",
                failRatePct, cfg.failRateBlockerThresholdPercents());

        return new TestVerdict(t.testId(), t.name(), t.suite(), false, reason, failRatePct, statusChanges, runs);
    }

    /** A base-branch run whose build carried no code changes (a flip here indicates flakiness). */
    private static boolean noCodeChanges(TcModel.TestOccurrence occ) {
        return occ.build() != null && occ.build().changes() != null && occ.build().changes().count() == 0;
    }
}
