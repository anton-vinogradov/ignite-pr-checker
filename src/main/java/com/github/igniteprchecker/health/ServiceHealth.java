package com.github.igniteprchecker.health;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.CacheStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether the service works right now. The log tells of calls that failed, but a background job that hangs or
 * stops logs nothing, so the clocks the jobs keep are held against how often they run. Without a pooled token
 * while users have standing options, every background TeamCity read has stopped; a warm cycle in which nearly every
 * PR failed means TeamCity calls fail across the board, which the warmer logs at debug only. Durable state that
 * failed to load, or keeps failing to save, counts until it is dealt with, and so does a setting that is not as meant,
 * a GITHUB_TOKEN GitHub refuses, what its last check found wrong with it, and writes of its account that GitHub
 * refuses or makes wait.
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
    private final EffectiveConfig config;

    /** Whose GitHub account the checker writes as; null for a health that does not look at it. */
    private final GithubClient github;

    private final long startedAt = System.currentTimeMillis();

    /** A health that does not look at the GitHub app account. */
    public ServiceHealth(Warmer warmer, StandingVisas standing, PrCommands commands, CacheStore store,
        EffectiveConfig config) {
        this(warmer, standing, commands, store, config, null);
    }

    @Autowired
    public ServiceHealth(Warmer warmer, StandingVisas standing, PrCommands commands, CacheStore store,
        EffectiveConfig config, GithubClient github) {
        this.warmer = warmer;
        this.standing = standing;
        this.commands = commands;
        this.store = store;
        this.config = config;
        this.github = github;
    }

    public Report report(LogTracker.Snapshot log, long now) {
        Clocks clocks = new Clocks(startedAt, standing.lastSweepAt(), commands.lastPollAt(), warmer.warming(),
            warmer.cycleStartedAt(), warmer.lastWarmed() + warmer.lastCached() + warmer.lastFailed(),
            warmer.lastFailed(), warmer.pooledTokens(), standing.enrolledCount());
        GithubClient.AppAccount app = github == null ? null : github.appAccount();
        GithubClient.WriteState writes = github == null ? null : github.writeState();

        return assess(log, clocks, store.status(), config.problems(), app, writes, now);
    }

    static Report assess(LogTracker.Snapshot log, Clocks clocks, CacheStore.Status persistence,
        List<String> configProblems, long now) {
        return assess(log, clocks, persistence, configProblems, null, now);
    }

    /** {@code app}: whose GitHub account the checker writes as, null when not known. */
    static Report assess(LogTracker.Snapshot log, Clocks clocks, CacheStore.Status persistence,
        List<String> configProblems, GithubClient.AppAccount app, long now) {
        return assess(log, clocks, persistence, configProblems, app, null, now);
    }

    /** {@code writes}: how the writes of that account go, null when not known. */
    static Report assess(LogTracker.Snapshot log, Clocks clocks, CacheStore.Status persistence,
        List<String> configProblems, GithubClient.AppAccount app, GithubClient.WriteState writes, long now) {
        List<Problem> problems = new ArrayList<>();

        long sweepAgo = now - (clocks.lastSweepAt() > 0 ? clocks.lastSweepAt() : clocks.startedAt());
        if (sweepAgo > SWEEP_LATE.toMillis()) {
            problems.add(Problem.error(clocks.lastSweepAt() > 0
                ? "standing sweep last started " + minutes(sweepAgo) + " ago"
                : "standing sweep has not run in the " + minutes(sweepAgo) + " since the start"));
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
                + (clocks.enrolled() == 1 ? " user has" : " users have")
                + " standing options: background warming and run tracking are paused"));
        }

        if (persistence != null)
            problems.addAll(persistenceProblems(persistence));

        configProblems.forEach(p -> problems.add(Problem.warn(p)));

        if (app != null && app.refused()) {
            problems.add(Problem.error("GitHub refuses GITHUB_TOKEN: every comment, edit and reaction of the checker "
                + "in PRs fails"));
        }
        if (app != null && !app.refused())
            problems.addAll(appAccountProblems(app));
        if (writes != null)
            problems.addAll(writeProblems(writes));

        String logHealth = log.health(now);
        String health = logHealth;
        if (problems.stream().anyMatch(p -> p.level().equals("error")))
            health = "error";
        else if (!problems.isEmpty() && health.equals("ok"))
            health = "warn";

        return new Report(health, logHealth, problems);
    }

    /** What was found wrong with GITHUB_TOKEN or its account: a token that cannot write, expires soon, can push. */
    private static List<Problem> appAccountProblems(GithubClient.AppAccount app) {
        return app.checks().stream()
            .filter(c -> c.level().equals(GithubClient.AppAccount.Check.WARN)
                || c.level().equals(GithubClient.AppAccount.Check.ERROR))
            .map(c -> new Problem(c.level(), c.text())).toList();
    }

    /**
     * The app account cannot write as it should: GitHub made its writes wait, refused one for another reason than its
     * limits (a block, a locked PR), or the writes for a PR or a user reached their hourly cap. The writes that wait go
     * out later, so a wait is a warning.
     */
    private static List<Problem> writeProblems(GithubClient.WriteState writes) {
        List<Problem> problems = new ArrayList<>();
        if (writes.pausedUntil() > 0) {
            Instant until = Instant.ofEpochMilli(writes.pausedUntil()).truncatedTo(ChronoUnit.SECONDS);
            problems.add(Problem.warn("GitHub rate-limits the writes of the app account until " + until
                + ": they wait and go out then"));
        }
        if (writes.refused() != null)
            problems.add(Problem.error(writes.refused() + " — the account may be blocked, or the PR locked"));
        if (!writes.capped().isEmpty()) {
            problems.add(Problem.warn("the writes of the app account reached their hourly cap for "
                + String.join(", ", writes.capped()) + ": the rest wait"));
        }

        return problems;
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
