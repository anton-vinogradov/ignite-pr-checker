package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.suitefix.SuiteFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * For the 3 minutes after a restart the status page said "sweep never" of the standing sweep and of tests added to
 * suites, and /api/status gave it nothing to say better: it knew when a pass last started, not when the first is due.
 */
class FirstPassTimesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final Warmer warmer = mock(Warmer.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final PrCommands commands = mock(PrCommands.class);

    private final CacheStore store = mock(CacheStore.class);

    private final EffectiveConfig config = mock(EffectiveConfig.class);

    private final SuiteFixService suiteFix = mock(SuiteFixService.class);

    @SuppressWarnings("unchecked")
    private final StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class),
        warmer, mock(GithubClient.class), new LogTracker(mapper),
        new ServiceHealth(warmer, standing, commands, store, config), mock(RerunTracker.class),
        mock(VisaSubscriptions.class), standing, commands, mock(AuthInterceptor.class),
        new AdminActions(new AdminProperties(List.of()), mapper), store, config, mock(ObjectProvider.class), suiteFix);

    @Test
    void eachFirstPassIsDueItsJobsInitialDelayAfterTheJobsAreScheduled() {
        when(suiteFix.stats()).thenReturn(Map.of("users", 1, "lastSweepAt", 0L));
        when(suiteFix.sweeps()).thenReturn(true);
        ContextRefreshedEvent scheduled = new ContextRefreshedEvent(mock(ApplicationContext.class));
        status.jobsScheduled(scheduled);
        long at = scheduled.getTimestamp();

        Map<String, Object> watcher = watcher();

        assertThat(watcher).containsEntry("firstRefreshAt", at + 10_000)
            .containsEntry("standingFirstSweepAt", at + 180_000).containsEntry("prCommandsFirstPollAt", at + 30_000);
        assertThat(watcher.get("suiteFix")).isEqualTo(Map.of("users", 1, "lastSweepAt", 0L,
            "firstSweepAt", at + 180_000));
    }

    /** With automation off, tests are never added to suites: no time is due, and the page keeps a grey "never". */
    @Test
    void aSweepThatDoesNotRunHereIsNeverDue() {
        when(suiteFix.stats()).thenReturn(Map.of());
        status.jobsScheduled(new ContextRefreshedEvent(mock(ApplicationContext.class)));

        assertThat(watcher().get("suiteFix")).isEqualTo(Map.of("firstSweepAt", 0L));
    }

    /** A context of its own for management refreshes too, a moment apart: the times stay those of the first. */
    @Test
    void aLaterRefreshMovesNothing() throws Exception {
        ContextRefreshedEvent first = new ContextRefreshedEvent(mock(ApplicationContext.class));
        status.jobsScheduled(first);
        Thread.sleep(5);
        status.jobsScheduled(new ContextRefreshedEvent(mock(ApplicationContext.class)));

        assertThat(watcher()).containsEntry("standingFirstSweepAt", first.getTimestamp() + 180_000);
    }

    @Test
    void beforeTheJobsAreScheduledNothingIsDue() {
        assertThat(watcher()).containsEntry("firstRefreshAt", 0L).containsEntry("standingFirstSweepAt", 0L)
            .containsEntry("prCommandsFirstPollAt", 0L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> watcher() {
        return (Map<String, Object>)status.status(new MockHttpServletRequest()).get("watcher");
    }
}
