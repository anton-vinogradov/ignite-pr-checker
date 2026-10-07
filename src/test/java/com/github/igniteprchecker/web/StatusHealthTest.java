package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Prod reported health "warn" for weeks after its last warning: the flag followed counters that never go
 * down, and most of what they counted were scanners calling endpoints with the wrong HTTP method. A dot
 * that never returns to green signals nothing, so it follows recent problems of the service only.
 */
class StatusHealthTest {
    private static final String TC = "com.github.igniteprchecker.tc.TcClient";

    private final LogTracker logs = new LogTracker(new ObjectMapper());

    private final Warmer warmer = mock(Warmer.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final PrCommands commands = mock(PrCommands.class);

    private final CacheStore store = mock(CacheStore.class);

    private final AuthInterceptor auth = mock(AuthInterceptor.class);

    @SuppressWarnings("unchecked")
    private final StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class),
        warmer, mock(GithubClient.class), logs, new ServiceHealth(warmer, standing, commands, store),
        mock(RerunTracker.class), mock(VisaSubscriptions.class), standing, commands, auth, mock(AdminActions.class),
        store, mock(ObjectProvider.class));

    @BeforeEach
    void start() {
        logs.start();
    }

    private void log(Level level, String logger, String message, Duration ago) {
        LoggingEvent e = new LoggingEvent();
        e.setLevel(level);
        e.setLoggerName(logger);
        e.setMessage(message);
        e.setTimeStamp(System.currentTimeMillis() - ago.toMillis());
        logs.doAppend(e);
    }

    private Object health() {
        return status.status(new MockHttpServletRequest()).get("health");
    }

    @Test
    void aWarningFromHoursAgoNoLongerColoursHealth() {
        log(Level.WARN, TC, "TeamCity poll failed: Connection reset", Duration.ofHours(2));

        assertThat(health()).isEqualTo("ok");
    }

    @Test
    void aFreshWarningDoes() {
        log(Level.WARN, TC, "TeamCity poll failed: Connection reset", Duration.ofMinutes(10));

        assertThat(health()).isEqualTo("warn");
    }

    @Test
    void anErrorStaysRedForHours() {
        log(Level.ERROR, TC, "Snapshot write failed", Duration.ofHours(2));

        assertThat(health()).isEqualTo("error");
    }

    @Test
    void anErrorFromLastNightNoLongerColoursHealth() {
        log(Level.ERROR, TC, "Snapshot write failed", Duration.ofHours(7));

        assertThat(health()).isEqualTo("ok");
    }

    @Test
    void aWrongHttpMethodIsTheCallersMistakeNotTheServices() {
        log(Level.WARN, "org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver",
            "Resolved [org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'POST' is not supported]",
            Duration.ZERO);

        assertThat(health()).isEqualTo("ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSweepThatStoppedTurnsHealthRedThoughTheLogIsClean() {
        when(standing.lastSweepAt()).thenReturn(System.currentTimeMillis() - Duration.ofMinutes(40).toMillis());
        when(commands.lastPollAt()).thenReturn(System.currentTimeMillis());
        MockHttpServletRequest signedIn = new MockHttpServletRequest();
        when(auth.signedIn(signedIn)).thenReturn(Optional.of(new SessionCodec.Session("alice", "tok", null, null)));

        Map<String, Object> out = status.status(signedIn);

        assertThat(out.get("health")).isEqualTo("error");
        assertThat(out.get("logHealth")).isEqualTo("ok");
        assertThat((List<Object>) out.get("healthProblems"))
            .containsExactly(new ServiceHealth.Problem("error", "standing-visa sweep last started 40 min ago"));
    }
}
