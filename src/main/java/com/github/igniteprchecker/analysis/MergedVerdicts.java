package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.PersistProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.persist.Snapshots;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The verdicts of merged PRs, each kept as it stood when the PR was merged, one file per PR in {@code merged/} of
 * the persistence directory. TeamCity keeps about 15 days of builds, and a merged PR recomputed later is judged by
 * a master history that already holds its own failures, which then read as "pre-existing". Kept, its blockers, tests
 * to watch and filtered tests with their reasons and rates on master stay, to check later what the checker let
 * through. Once kept, a PR's verdict is served instead of being recomputed.
 *
 * <p>A PR is looked at when it leaves the open-PR list while the analysis cache holds a verdict for it: one GitHub
 * call says whether it was merged. Only a verdict computed before the merge, or within the hour after it, is kept.
 * A PR closed without a merge is left alone, and one still open past the list is asked again some hours later.
 * Without a usable directory the verdicts are kept in memory only.
 *
 * <p>A kept verdict changes once at most: when runs queued before the merge that it did not read end, {@link
 * RunsAtMerge} computes it again with their results. Until it has looked, the PR's verdict is due for that look.
 */
@Component
public class MergedVerdicts {
    private static final Logger log = LoggerFactory.getLogger(MergedVerdicts.class);

    /** The most PRs one sweep asks GitHub about. */
    private static final int ASKS_PER_SWEEP = 20;

    /**
     * How long after a merge a verdict may have been computed to be kept as the one at the merge. A verdict of a PR
     * merged long before, computed when someone opened it, already holds its own failures as master's.
     */
    private static final long KEPT_AFTER_MERGE_MS = 3_600_000L;

    /** How long a PR found open, though not in the list, is left before it is asked about again. */
    private static final long RECHECK_OPEN_MS = 6 * 3_600_000L;

    /**
     * How long after its merge a verdict kept by a release before {@link RunsAtMerge} is still looked at for runs it
     * did not read, once: TeamCity keeps about 15 days of builds.
     */
    static final long LOOK_BACK_MS = 7 * 86_400_000L;

    private static final Pattern FILE = Pattern.compile("(\\d+)\\.json");

    private final GithubClient github;

    private final AnalysisCache cache;

    private final ObjectMapper mapper;

    /** Where the verdicts are kept; null when they are kept in memory only. */
    private volatile Path dir;

    /** The PRs with a kept verdict. */
    private final Set<Integer> kept = ConcurrentHashMap.newKeySet();

    /** The verdicts kept in memory, while there is no directory to keep them in. */
    private final Map<Integer, Merged> inMemory = new ConcurrentHashMap<>();

    /** When each PR found not merged may be asked about again; never, for one closed without a merge. */
    private final Map<Integer, Long> notBefore = new ConcurrentHashMap<>();

    /** The PRs whose kept verdict is due for a look at the runs queued before the merge that it did not read. */
    private final Set<Integer> runsDue = ConcurrentHashMap.newKeySet();

    @Autowired
    public MergedVerdicts(GithubClient github, AnalysisCache cache, PersistProperties persist, ObjectMapper mapper) {
        this(github, cache, persist.enabled() ? Path.of(persist.dir()).resolve("merged") : null, mapper);
    }

    MergedVerdicts(GithubClient github, AnalysisCache cache, Path dir, ObjectMapper mapper) {
        this.github = github;
        this.cache = cache;
        this.dir = dir;
        this.mapper = mapper;
    }

    /** Keeps no verdicts: every PR is analysed live. */
    static MergedVerdicts none() {
        return new MergedVerdicts(null, null, (Path)null, null);
    }

    /**
     * Learns which PRs have a kept verdict, and which of them are due for a look at the runs that were going at the
     * merge; a directory that cannot be used leaves them in memory. Only the files written within {@link #LOOK_BACK_MS}
     * are read for that.
     */
    @PostConstruct
    void load() {
        if (dir == null)
            return;

        List<Path> recent = new ArrayList<>();
        long since = System.currentTimeMillis() - LOOK_BACK_MS;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Matcher m = FILE.matcher(f.getFileName().toString());
                    if (!m.matches())
                        continue;
                    kept.add(Integer.parseInt(m.group(1)));
                    if (modifiedSince(f, since))
                        recent.add(f);
                }
            }
        }
        catch (IOException | RuntimeException e) {
            log.warn("merged PRs' verdicts cannot be kept in {} ({}); keeping them in memory only", dir, e.toString());
            dir = null;

            return;
        }

        for (Path f : recent) {
            try {
                Merged m = mapper.readValue(f.toFile(), Merged.class);
                if (m.runsAtMergeDue() == null ? m.mergedAt() * 1000 >= since : m.runsAtMergeDue())
                    runsDue.add(m.pr());
            }
            catch (JacksonException e) {
                log.warn("could not read the verdict kept in {}: {}", f, e.toString());
            }
        }
    }

    private static boolean modifiedSince(Path f, long since) {
        try {
            return Files.getLastModifiedTime(f).toMillis() >= since;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** The verdict a merged PR had at its merge, if it was kept. */
    public Optional<AnalysisResult> verdict(int pr) {
        return merged(pr).map(Merged::verdict);
    }

    /** A merged PR as it stood at its merge, if its verdict was kept: its title, when it was merged and its head. */
    public Optional<GithubClient.PullState> atMerge(int pr) {
        return merged(pr).map(m -> new GithubClient.PullState(m.title(), false, true, m.mergedAt(), m.head()));
    }

    /** The merged PRs whose kept verdict is due for a look at the runs queued before the merge that it did not read. */
    public Set<Integer> runsDue() {
        return Set.copyOf(runsDue);
    }

    /** Whether the merged PR's kept verdict is due for that look: it may yet be computed once more. */
    public boolean runsDue(int pr) {
        return runsDue.contains(pr);
    }

    /**
     * Keeps {@code verdict}, computed with the results of the runs queued before the merge that the kept one did not
     * read, as the PR's verdict for good.
     */
    public void completed(int pr, AnalysisResult verdict) {
        merged(pr).ifPresent(m -> {
            write(new Merged(m.pr(), m.title(), m.mergedAt(), m.mergeCommit(), m.head(), m.keptAt(), TestVerdict.RULES,
                verdict, false));
            log.info("PR {}: completed its verdict at the merge with {} run(s) queued before it ({} blocker(s), "
                + "{} to watch, {} filtered)", pr, verdict.runsAtMerge().size(), verdict.blockers().size(),
                verdict.watch().size(), verdict.filtered().size());
        });
        runsDue.remove(pr);
    }

    /** No run queued before the merge adds to the PR's kept verdict: it stays as it is, for good. */
    public void settled(int pr) {
        merged(pr).ifPresent(m -> write(new Merged(m.pr(), m.title(), m.mergedAt(), m.mergeCommit(), m.head(),
            m.keptAt(), m.rules(), m.verdict(), false)));
        runsDue.remove(pr);
    }

    Optional<Merged> merged(int pr) {
        if (!kept.contains(pr))
            return Optional.empty();

        Merged inMem = inMemory.get(pr);
        if (inMem != null)
            return Optional.of(inMem);

        Path d = dir;
        if (d == null)
            return Optional.empty();

        try {
            return Optional.of(mapper.readValue(d.resolve(pr + ".json").toFile(), Merged.class));
        }
        catch (JacksonException e) {
            log.warn("could not read the verdict kept for merged PR {}: {}", pr, e.toString());

            return Optional.empty();
        }
    }

    /**
     * Keeps the verdicts of the PRs that left the open list merged. The open list is GitHub's 50 most recently
     * updated PRs, so a PR may leave it open: the call about it is what tells.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    void sweep() {
        if (github == null)
            return;

        List<PrSummary> open = github.openPrs();
        if (open.isEmpty())
            return; // GitHub could not be asked: nothing to compare with

        Set<Integer> listed = open.stream().map(PrSummary::number).collect(Collectors.toSet());
        notBefore.keySet().removeAll(listed); // listed again: reopened, and it may yet be merged

        long now = System.currentTimeMillis();
        int asked = 0;
        for (AnalysisResult r : latestPerPr()) {
            int pr = r.prNumber();
            if (listed.contains(pr) || kept.contains(pr) || now < notBefore.getOrDefault(pr, 0L))
                continue;
            if (asked++ >= ASKS_PER_SWEEP)
                break;

            GithubClient.PrOutcome outcome;
            try {
                outcome = github.prOutcome(pr);
            }
            catch (RuntimeException e) {
                // The sweep runs on Spring's scheduler: a GitHub that hangs must cost it one timeout, not twenty.
                log.debug("could not ask GitHub whether PR {} was merged: {}", pr, e.toString());
                break;
            }

            if (outcome.merged() && r.computedAt() <= outcome.mergedAt() * 1000 + KEPT_AFTER_MERGE_MS)
                keep(r, outcome);
            else
                notBefore.put(pr, outcome.closed() ? Long.MAX_VALUE : now + RECHECK_OPEN_MS);
        }
    }

    /**
     * The newest cached verdict of each PR, the one shown last, by PR number. The results carried over from before a
     * change of words count too: a PR merged shortly before that deploy has no other.
     */
    private List<AnalysisResult> latestPerPr() {
        Map<Integer, AnalysisResult> latest = new HashMap<>();
        for (AnalysisResult r : Stream.concat(cache.freshResults().stream(), cache.carriedOver().stream()).toList())
            latest.merge(r.prNumber(), r, (a, b) -> a.computedAt() >= b.computedAt() ? a : b);

        return latest.values().stream().sorted(Comparator.comparingInt(AnalysisResult::prNumber)).toList();
    }

    private void keep(AnalysisResult r, GithubClient.PrOutcome outcome) {
        write(new Merged(r.prNumber(), outcome.title(), outcome.mergedAt(), outcome.mergeCommitSha(),
            outcome.headSha(), System.currentTimeMillis(), TestVerdict.RULES, mergedAt(r, outcome.mergedAt()), true));
        kept.add(r.prNumber());
        runsDue.add(r.prNumber());

        log.info("PR {} was merged: kept its verdict of build {} ({} blocker(s), {} to watch, {} filtered)",
            r.prNumber(), r.buildId(), r.blockers().size(), r.watch().size(), r.filtered().size());
    }

    /** Writes a merged PR's file, or keeps it in memory when there is no directory or the disk does not take it. */
    private void write(Merged m) {
        Path d = dir;
        if (d == null) {
            inMemory.put(m.pr(), m);

            return;
        }

        try {
            Snapshots.writeAtomic(mapper, d.resolve(m.pr() + ".json"), m);
            inMemory.remove(m.pr());
        }
        catch (IOException e) {
            log.warn("could not keep the verdict of merged PR {} in {}: {}", m.pr(), d, e.toString());
            inMemory.put(m.pr(), m);
        }
    }

    private static AnalysisResult mergedAt(AnalysisResult r, long mergedAt) {
        return new AnalysisResult(r.prNumber(), r.buildId(), r.branchName(), r.computedAt(), r.blockers(), r.watch(),
            r.filtered(), r.brokenSuites(), r.shrunkSuites(), r.suitesRan(), r.suitesReused(), r.interrupted(),
            r.canceledSuites(), r.live(), r.liveBuildId(), r.queuedAt(), r.startedAt(), r.finishedAt(),
            r.branchWatermarkAt(), r.unstableSuites(), r.cancelledSuites(), r.unverified(), r.incompleteSince(),
            r.revision(), mergedAt, r.otherCodeRuns(), r.runsAtMerge());
    }

    /**
     * A merged PR's kept verdict, with what a later check against master needs: when it was merged and as which
     * commit, its head then, when the verdict was kept and under which classification rules. {@code runsAtMergeDue}
     * is whether the verdict is still due for a look at the runs queued before the merge that it did not read; null in
     * the files of releases before that look.
     */
    record Merged(int pr, String title, long mergedAt, String mergeCommit, String head, long keptAt, int rules,
        AnalysisResult verdict, Boolean runsAtMergeDue) {
    }
}
