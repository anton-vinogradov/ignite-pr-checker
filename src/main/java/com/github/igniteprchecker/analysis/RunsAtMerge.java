package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Completes a merged PR's kept verdict with the runs it did not read. On #13709 Control Utility 5 re-ran on the PR's
 * head from 10:02 to 10:28 UTC, the PR was merged at 10:12, and the verdict kept at the merge showed the blocker that
 * re-run cleared.
 *
 * <p>A run counts when it was queued on the PR's branch before the merge and ended after the branch was read for the
 * kept verdict: it was still going at the merge, or no compute read it before the PR left the open list. Once every
 * such run has ended, the verdict is computed once more: of the runs queued before the merge, against master's and
 * other PRs' runs that finished before it. The runs are named with it. Runs queued after the merge never count: a
 * verdict kept after the merge that may have read one is computed once more without it. Any other verdict stays as it
 * was. Each look costs one TeamCity call per PR, under a token the warmer lends; runs still going after
 * {@link #WAIT_MS} are not waited for.
 */
@Component
public class RunsAtMerge {
    private static final Logger log = LoggerFactory.getLogger(RunsAtMerge.class);

    /** How long after a merge the runs that were going at it are waited for: a run lasts a day at most. */
    static final long WAIT_MS = 2 * 86_400_000L;

    /** The most merged PRs one sweep looks at. */
    private static final int PRS_PER_SWEEP = 10;

    private final MergedVerdicts merged;

    private final BlockerAnalyzer analyzer;

    private final TcClient tc;

    private final String runAll;

    private final Supplier<String> tokens;

    /** Epoch ms now. */
    private final LongSupplier clock;

    /** When the last look at each PR failed, epoch ms, while it keeps failing. */
    private final Map<Integer, Long> failedAt = new ConcurrentHashMap<>();

    @Autowired
    public RunsAtMerge(MergedVerdicts merged, BlockerAnalyzer analyzer, TcClient tc, AnalysisProperties cfg,
        Warmer warmer) {
        this(merged, analyzer, tc, cfg, warmer::borrowToken, System::currentTimeMillis);
    }

    RunsAtMerge(MergedVerdicts merged, BlockerAnalyzer analyzer, TcClient tc, AnalysisProperties cfg,
        Supplier<String> tokens, LongSupplier clock) {
        this.merged = merged;
        this.analyzer = analyzer;
        this.tc = tc;
        this.runAll = cfg.runAllBuildType();
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Looks at the merged PRs whose kept verdict is due for it, the lowest numbers first, and those whose last look
     * failed after the rest: a PR TeamCity always fails holds up no other.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
    void sweep() {
        String token = tokens.get();
        if (token == null)
            return;

        List<Integer> due = merged.runsDue().stream()
            .sorted(Comparator.comparingLong((Integer pr) -> failedAt.getOrDefault(pr, 0L)).thenComparing(pr -> pr))
            .limit(PRS_PER_SWEEP).toList();
        for (int pr : due) {
            try {
                look(token, pr);
                failedAt.remove(pr);
            }
            catch (RuntimeException e) {
                failed(pr, e);
                // The sweep runs on Spring's scheduler: a TeamCity that fails one PR is not asked about the rest.
                break;
            }
        }
    }

    /**
     * Puts the PR after the others in the next sweeps, and settles its verdict as it is once the PR was merged
     * {@link MergedVerdicts#LOOK_BACK_MS} ago. Warns of the first failure only: the next sweeps try again.
     */
    private void failed(int pr, RuntimeException e) {
        if (failedAt.put(pr, clock.getAsLong()) == null)
            log.warn("could not look at the runs queued before PR {} was merged; trying again: {}", pr, e.toString());
        else
            log.debug("could not look at the runs queued before PR {} was merged: {}", pr, e.toString());

        if (merged.merged(pr).filter(m -> clock.getAsLong() >= m.mergedAt() * 1000 + MergedVerdicts.LOOK_BACK_MS)
            .isPresent()) {
            merged.settled(pr);
            failedAt.remove(pr);
        }
    }

    /**
     * Completes the PR's kept verdict once the runs it did not read have ended, settles it when there were none, and
     * leaves it due while they go or TeamCity errors leave the new verdict incomplete.
     */
    void look(String token, int pr) {
        Optional<MergedVerdicts.Merged> kept = merged.merged(pr);
        if (kept.isEmpty())
            return;

        long mergedAt = kept.get().mergedAt();
        AnalysisResult atMerge = kept.get().verdict();
        List<TcModel.Build> builds = tc.branchBuilds(token, pr);
        Set<Long> afterMerge = builds.stream().filter(b -> TcDates.epochSeconds(b.queuedDate()) > mergedAt)
            .map(TcModel.Build::id).collect(Collectors.toSet());
        long readUntil = atMerge.branchWatermarkAt() > 0 ? atMerge.branchWatermarkAt() : mergedAt;
        List<TcModel.Build> unread = builds.stream()
            .filter(b -> !afterMerge.contains(b.id()) && b.canceledInfo() == null
                && (!finished(b) || TcDates.epochSeconds(b.finishDate()) > readUntil))
            .toList();
        List<TcModel.Build> ended = unread.stream().filter(RunsAtMerge::finished).toList();

        boolean waiting = clock.getAsLong() < mergedAt * 1000 + WAIT_MS;
        if (waiting && ended.size() < unread.size())
            return;
        if (ended.isEmpty() && !mayHaveReadARunAfterTheMerge(atMerge, builds, afterMerge)) {
            merged.settled(pr);

            return;
        }

        long chain = ended.stream().filter(b -> runAll.equals(b.buildTypeId())).mapToLong(TcModel.Build::id).max()
            .orElse(atMerge.buildId());
        AnalysisResult verdict = analyzer.verdictAtMerge(token, pr, chain, mergedAt, afterMerge, builds, named(ended));
        if (verdict.incompleteSince() == 0)
            merged.completed(pr, verdict);
        else if (clock.getAsLong() >= mergedAt * 1000 + MergedVerdicts.LOOK_BACK_MS)
            merged.settled(pr);
    }

    /**
     * Whether the kept verdict, computed after the merge, may have read a run queued after it: one that had ended by
     * the time the verdict was computed.
     */
    private static boolean mayHaveReadARunAfterTheMerge(AnalysisResult atMerge, List<TcModel.Build> builds,
        Set<Long> afterMerge) {
        long computedAt = atMerge.computedAt() / 1000;

        return builds.stream().anyMatch(b -> afterMerge.contains(b.id()) && finished(b) && b.canceledInfo() == null
            && TcDates.epochSeconds(b.finishDate()) <= computedAt);
    }

    /**
     * The runs to name, oldest first: those a person queued, a RunAll or a suite re-run on its own. The suites of a
     * RunAll go unnamed with it. When TeamCity says no person queued any of them, every run is named.
     */
    private static List<RunRef> named(List<TcModel.Build> ended) {
        List<TcModel.Build> queuedByPeople = ended.stream()
            .filter(b -> b.triggered() != null && "user".equals(b.triggered().type())).toList();

        return (queuedByPeople.isEmpty() ? ended : queuedByPeople).stream()
            .sorted(Comparator.comparingLong(TcModel.Build::id))
            .map(ChainCollector::runRef)
            .toList();
    }

    private static boolean finished(TcModel.Build b) {
        return "finished".equalsIgnoreCase(b.state());
    }
}
