package com.github.igniteprchecker.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.CacheStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Health followed the log alone. Warmer failures are logged at debug, and a job that hangs or stops logs nothing,
 * so a sweep stuck for hours, a dead command poll or a warm cycle "warming…" forever all left the dot green, and
 * the owner learned of it from users.
 */
class ServiceHealthTest {
    private static final long NOW = 1_800_000_000_000L;

    private static final LogTracker.Snapshot CLEAN_LOG = new LogTracker.Snapshot(0, 0, 0, 0, 0, List.of());

    private static final CacheStore.Status SAVING = new CacheStore.Status(true, true, null, List.of(), Map.of(),
        "cache-2027-01-15.zip");

    private static long ago(Duration d) {
        return NOW - d.toMillis();
    }

    /**
     * Started an hour ago; every job ran on time, one PR of 50 failed in the last warm cycle; two users with
     * standing options, their tokens pooled.
     */
    private static ServiceHealth.Clocks onTime() {
        return new ServiceHealth.Clocks(ago(Duration.ofHours(1)), ago(Duration.ofMinutes(12)),
            ago(Duration.ofMinutes(1)), true, ago(Duration.ofMinutes(4)), 50, 1, 2, 2);
    }

    private static ServiceHealth.Report assess(ServiceHealth.Clocks clocks) {
        return ServiceHealth.assess(CLEAN_LOG, clocks, SAVING, NOW);
    }

    @Test
    void jobsRunningOnTimeKeepItGreen() {
        assertThat(assess(onTime())).isEqualTo(new ServiceHealth.Report("ok", "ok", List.of()));
    }

    @Test
    void aSweepThatHangsOrStopped() {
        ServiceHealth.Clocks c = onTime();

        assertThat(assess(new ServiceHealth.Clocks(c.startedAt(), ago(Duration.ofMinutes(26)), c.lastPollAt(),
            c.warming(), c.warmStartedAt(), c.warmTried(), c.warmFailed(), c.pooledTokens(), c.enrolled())))
            .isEqualTo(new ServiceHealth.Report("error", "ok",
                List.of(new ServiceHealth.Problem("error", "standing-visa sweep last started 26 min ago"))));
    }

    @Test
    void aCommandPollThatHangsOrStopped() {
        ServiceHealth.Clocks c = onTime();

        assertThat(assess(new ServiceHealth.Clocks(c.startedAt(), c.lastSweepAt(), ago(Duration.ofMinutes(6)),
            c.warming(), c.warmStartedAt(), c.warmTried(), c.warmFailed(), c.pooledTokens(), c.enrolled())).problems())
            .containsExactly(new ServiceHealth.Problem("error", "PR command poll last started 6 min ago"));
    }

    @Test
    void aWarmCycleThatHangs() {
        ServiceHealth.Clocks c = onTime();

        assertThat(assess(new ServiceHealth.Clocks(c.startedAt(), c.lastSweepAt(), c.lastPollAt(), true,
            ago(Duration.ofMinutes(16)), c.warmTried(), c.warmFailed(), c.pooledTokens(), c.enrolled())).problems())
            .containsExactly(new ServiceHealth.Problem("error", "warm cycle running for 16 min"));
    }

    /** TeamCity answering 503 to every call: the warmer counts each PR as failed and logs nothing above debug. */
    @Test
    void aWarmCycleInWhichNearlyEveryPrFailed() {
        assertThat(afterWarmCycle(0, 0, 50).problems()).containsExactly(
            new ServiceHealth.Problem("error", "the last warm cycle failed on 50 of the 50 PRs it tried"));
        assertThat(afterWarmCycle(1, 3, 46).problems()).extracting(ServiceHealth.Problem::text).containsExactly(
            "the last warm cycle failed on 46 of the 50 PRs it tried");
        assertThat(afterWarmCycle(10, 30, 10).health()).isEqualTo("ok");
    }

    private static ServiceHealth.Report afterWarmCycle(int recomputed, int cached, int failed) {
        Warmer warmer = mock(Warmer.class);
        when(warmer.lastWarmed()).thenReturn(recomputed);
        when(warmer.lastCached()).thenReturn(cached);
        when(warmer.lastFailed()).thenReturn(failed);
        when(warmer.pooledTokens()).thenReturn(2);
        StandingVisas standing = mock(StandingVisas.class);
        when(standing.lastSweepAt()).thenReturn(System.currentTimeMillis());
        when(standing.enrolledCount()).thenReturn(2);
        PrCommands commands = mock(PrCommands.class);
        when(commands.lastPollAt()).thenReturn(System.currentTimeMillis());
        CacheStore store = mock(CacheStore.class);
        when(store.status()).thenReturn(SAVING);

        return new ServiceHealth(warmer, standing, commands, store).report(CLEAN_LOG, System.currentTimeMillis());
    }

    @Test
    void anEmptyTokenPoolWhileUsersHaveStandingOptions() {
        ServiceHealth.Clocks c = onTime();

        ServiceHealth.Report report = assess(new ServiceHealth.Clocks(c.startedAt(), c.lastSweepAt(),
            c.lastPollAt(), false, c.warmStartedAt(), c.warmTried(), c.warmFailed(), 0, 2));

        assertThat(report.health()).isEqualTo("warn");
        assertThat(report.problems()).extracting(ServiceHealth.Problem::text).containsExactly(
            "no TeamCity token in the pool though 2 user(s) have standing options: background warming and run "
                + "tracking are paused");
    }

    @Test
    void anEmptyPoolWithNobodyEnrolledIsNormal() {
        ServiceHealth.Clocks c = onTime();

        assertThat(assess(new ServiceHealth.Clocks(c.startedAt(), c.lastSweepAt(), c.lastPollAt(), false,
            c.warmStartedAt(), c.warmTried(), c.warmFailed(), 0, 0)).health()).isEqualTo("ok");
    }

    @Test
    void jobsThatHaveNotRunYetAreLateOnlyCountingFromTheStart() {
        ServiceHealth.Clocks fresh = new ServiceHealth.Clocks(ago(Duration.ofMinutes(3)), 0, 0, false, 0, 0, 0, 0, 0);
        ServiceHealth.Clocks stuck = new ServiceHealth.Clocks(ago(Duration.ofMinutes(30)), 0, 0, false, 0, 0, 0, 0, 0);

        assertThat(assess(fresh).health()).isEqualTo("ok");
        assertThat(assess(stuck).problems()).extracting(ServiceHealth.Problem::text).containsExactly(
            "standing-visa sweep has not run in the 30 min since the start",
            "PR command poll has not run in the 30 min since the start");
    }

    @Test
    void stateThatCouldNotBeReadOrSaved() {
        CacheStore.Status broken = new CacheStore.Status(true, true, null,
            List.of(new CacheStore.Unreadable("standing-visas.json", "standing-visas.json.bad-20270115-101500")),
            Map.of("pr-commands.json", "java.io.IOException: No space left on device"), null);

        ServiceHealth.Report report = ServiceHealth.assess(CLEAN_LOG, onTime(), broken, NOW);

        assertThat(report.health()).isEqualTo("error");
        assertThat(report.problems()).extracting(ServiceHealth.Problem::text).containsExactly(
            "standing-visas.json could not be read at startup; kept as standing-visas.json.bad-20270115-101500, "
                + "its state started empty",
            "saving pr-commands.json fails: java.io.IOException: No space left on device");
    }

    @Test
    void snapshotsOffThoughEnabled() {
        CacheStore.Status off = new CacheStore.Status(true, false, "directory /opt/ignite-pr-checker/cache is not "
            + "writable", List.of(), Map.of(), null);
        CacheStore.Status disabled = new CacheStore.Status(false, false, "disabled (persist.enabled=false)",
            List.of(), Map.of(), null);

        assertThat(ServiceHealth.assess(CLEAN_LOG, onTime(), off, NOW).problems()).containsExactly(
            new ServiceHealth.Problem("warn", "snapshots are off (directory /opt/ignite-pr-checker/cache is not "
                + "writable): a restart loses all state"));
        assertThat(ServiceHealth.assess(CLEAN_LOG, onTime(), disabled, NOW).health()).isEqualTo("ok");
    }

    @Test
    void aProblemDoesNotSoftenAnErrorInTheLog() {
        LogTracker.Snapshot erred = new LogTracker.Snapshot(1, 0, 0, ago(Duration.ofMinutes(5)), 0, List.of());
        ServiceHealth.Clocks c = onTime();

        ServiceHealth.Report report = ServiceHealth.assess(erred, new ServiceHealth.Clocks(c.startedAt(),
            c.lastSweepAt(), c.lastPollAt(), false, c.warmStartedAt(), c.warmTried(), c.warmFailed(), 0, 2), SAVING,
            NOW);

        assertThat(report.health()).isEqualTo("error");
        assertThat(report.logHealth()).isEqualTo("error");
        assertThat(report.problems()).extracting(ServiceHealth.Problem::level).containsExactly("warn");
    }
}
