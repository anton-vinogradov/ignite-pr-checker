package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.tc.RerunTracker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/** The status page showed the heap in use at the moment only; how much the service held at most was nowhere. */
class StatusMemoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void theStatusTellsTheMemoryPeaks() {
        Warmer warmer = mock(Warmer.class);
        StandingVisas standing = mock(StandingVisas.class);
        PrCommands commands = mock(PrCommands.class);
        CacheStore store = mock(CacheStore.class);
        StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer,
            mock(GithubClient.class), new LogTracker(new ObjectMapper()),
            new ServiceHealth(warmer, standing, commands, store, mock(EffectiveConfig.class)),
            mock(RerunTracker.class), mock(VisaSubscriptions.class), standing, commands, mock(AuthInterceptor.class),
            mock(AdminActions.class), store, mock(EffectiveConfig.class), mock(ObjectProvider.class));

        Map<String, Object> jvm = (Map<String, Object>) status.status(new MockHttpServletRequest()).get("jvm");

        assertThat((long) jvm.get("heapPeakMb")).isPositive();
        assertThat((long) jvm.get("hostMemMb")).isPositive();
        assertThat(jvm).containsKeys("rssMb", "rssPeakMb");
    }
}
