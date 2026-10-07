package com.github.igniteprchecker.health;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.CacheStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether the service works right now. The log tells of calls that failed, but a background job that hangs or
 * stops logs nothing, so the clocks the jobs keep are held against how often they run. Without a pooled token
 * while users have standing options, every background TeamCity read has stopped; a warm cycle in which nearly every
 * PR failed means TeamCity calls fail across the board, which the warmer logs at debug only. Durable state that
 * failed to load, or keeps failing to save, counts until it is dealt with.
 */
@Component
public class ServiceHealth {
    /** The sweep starts 10 minutes after the previous one ends: a start older than this means it hangs or stopped. */
    static final Duration SWEEP_LATE = Duration.ofMinutes(25);

    /** The command poll starts a minute after the previous one ends. */
    static final Duration POLL_LATE = Duration.ofMinutes(5);

    /** A warm cycle running longer than this is taken for hung. */
    static final Duration WARM_STUCK = Duration.ofMinutes(15);

    /** A few PRs fail on their own; nine in ten failing means TeamCity is down or refuses the pooled tokens. */
    static final double WARM_FAILING = 0.9;

    private final Warmer warmer;
    private final StandingVisas standing;
    private final PrCommands commands;
    private final CacheStore store;
    private final long startedAt = System.currentTimeMillis();

    public ServiceHealth(Warmer warmer, StandingVisas standing, PrCommands commands, CacheStore store) {
        this.warmer = warmer;
        this.standing = standing;
        this.commands = commands;
        this.store = store;
    }

    public Report report(LogTracker.Snapshot log, long now) {
        Clocks clocks = new Clocks(startedAt, standing.lastSweepAt(), commands.lastPollAt(), warmer.warming(),
            warmer.cycleStartedAt(), warmer.lastWarmed() + warmer.lastCached() + warmer.lastFailed(),
            warmer.lastFailed(), warmer.pooledTokens(), standing.enrolledCount());

        return assess(log, clocks, store.status(), now);
    }

    static Report assess(LogTracker.Snapshot log, Clocks clocks, CacheStore.Status persistence, long now) {
        List<Problem> problems = new ArrayList<>();

        long sweepAgo = now - (clocks.lastSweepAt() > 0 ? clocks.lastSweepAt() : clocks.startedAt());
        if (sweepAgo > SWEEP_LATE.toMillis()) {
            problems.add(Problem.error(clocks.lastSweepAt() > 0
                ? "standing-visa sweep last started " + minutes(sweepAgo) + " ago"
                : "standing-visa sweep has not run in the " + minutes(sweepAgo) + " since the start"));
        }

        long pollAgo = now - (clocks.lastPollAt() > 0 ? clocks.lastPollAt() : clocks.startedAt());
        if (pollAgo > POLL_LATE.toMillis()) {
            problems.add(Problem.error(clocks.lastPollAt() > 0
                ? "PR command poll last started " + minutes(pollAgo) + " ago"
                : "PR command poll has not run in the " + minutes(pollAgo) + " since the start"));
        }

        long warmFor = now - clocks.warmStartedAt();
        if (clocks.warming() && warmFor > WARM_STUCK.toMillis())
            problems.add(Problem.error("warm cycle running for " + minutes(warmFor)));

        if (clocks.warmFailed() > 0 && clocks.warmFailed() >= WARM_FAILING * clocks.warmTried()) {
            problems.add(Problem.error("the last warm cycle failed on " + clocks.warmFailed() + " of the "
                + clocks.warmTried() + " PRs it tried"));
        }

        if (clocks.pooledTokens() == 0 && clocks.enrolled() > 0) {
            problems.add(Problem.warn("no TeamCity token in the pool though " + clocks.enrolled()
                + " user(s) have standing options: background warming and run tracking are paused"));
        }

        if (persistence != null)
            problems.addAll(persistenceProblems(persistence));

        String logHealth = log.health(now);
        String health = logHealth;
        if (problems.stream().anyMatch(p -> p.level().equals("error")))
            health = "error";
        else if (!problems.isEmpty() && health.equals("ok"))
            health = "warn";

        return new Report(health, logHealth, problems);
    }

    private static List<Problem> persistenceProblems(CacheStore.Status persistence) {
        List<Problem> problems = new ArrayList<>();
        if (persistence.enabled() && !persistence.active())
            problems.add(Problem.warn("snapshots are off (" + persistence.off() + "): a restart loses all state"));

        for (CacheStore.Unreadable u : persistence.unreadable()) {
            problems.add(Problem.error(u.file() + " could not be read at startup; kept as " + u.keptAs()
                + ", its state started empty"));
        }

        persistence.failingSaves().forEach((file, why) -> problems.add(Problem.error("saving " + file + " fails: "
            + why)));

        return problems;
    }

    private static String minutes(long ms) {
        return Duration.ofMillis(ms).toMinutes() + " min";
    }

    /**
     * What the background jobs say about themselves; a time of 0 means "not yet". {@code warmTried} and
     * {@code warmFailed} count the PRs of the last finished warm cycle.
     */
    record Clocks(long startedAt, long lastSweepAt, long lastPollAt, boolean warming, long warmStartedAt,
        int warmTried, int warmFailed, int pooledTokens, int enrolled) {
    }

    /**
     * {@code health} is the worst of {@code logHealth}, the log's alone, and the problems' levels: "ok", "warn" or
     * "error".
     */
    public record Report(String health, String logHealth, List<Problem> problems) {
        /** The problems without their texts, which name files and errors on the server: what anyone may see. */
        public List<Problem> levelsOnly() {
            return problems.stream().map(p -> new Problem(p.level(), null)).toList();
        }
    }

    /** One thing wrong now; {@code level} is "warn" or "error". */
    public record Problem(String level, String text) {
        static Problem warn(String text) {
            return new Problem("warn", text);
        }

        static Problem error(String text) {
            return new Problem("error", text);
        }
    }
}
