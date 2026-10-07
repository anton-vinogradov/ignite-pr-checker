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
 * while users have standing options, every background TeamCity read has stopped. Durable state that failed to load,
 * or keeps failing to save, counts until it is dealt with.
 */
@Component
public class ServiceHealth {
    /** The sweep starts 10 minutes after the previous one ends: a start older than this means it hangs or stopped. */
    static final Duration SWEEP_LATE = Duration.ofMinutes(25);

    /** The command poll starts a minute after the previous one ends. */
    static final Duration POLL_LATE = Duration.ofMinutes(5);

    /** A warm cycle running longer than this is taken for hung. */
    static final Duration WARM_STUCK = Duration.ofMinutes(15);

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
            warmer.cycleStartedAt(), warmer.pooledTokens(), standing.enrolledCount());

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

        if (clocks.pooledTokens() == 0 && clocks.enrolled() > 0) {
            problems.add(Problem.warn("no TeamCity token in the pool though " + clocks.enrolled()
                + " user(s) have standing options: background warming and run tracking are paused"));
        }

        if (persistence != null)
            problems.addAll(persistenceProblems(persistence));

        String health = log.health(now);
        if (problems.stream().anyMatch(p -> p.level().equals("error")))
            health = "error";
        else if (!problems.isEmpty() && health.equals("ok"))
            health = "warn";

        return new Report(health, problems);
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

    /** What the background jobs say about themselves; a time of 0 means "not yet". */
    record Clocks(long startedAt, long lastSweepAt, long lastPollAt, boolean warming, long warmStartedAt,
        int pooledTokens, int enrolled) {
    }

    /** {@code health} is the worst of the log's and the problems' levels: "ok", "warn" or "error". */
    public record Report(String health, List<Problem> problems) {
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
