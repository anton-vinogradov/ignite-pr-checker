package com.github.igniteprchecker.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring logs every request it turns away at WARN. On prod those were mostly scanners calling endpoints
 * with the wrong HTTP method, and they kept the status page yellow. They stay listed, but they are not
 * warnings about the service. The list itself lived in memory: a restart, the moment after something went
 * wrong, wiped what went wrong, and journald on prod keeps under two days.
 */
class LogTrackerTest {
    private static final String RESOLVER = "org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver";

    private static final long NOW = 1_800_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final LogTracker logs = new LogTracker(mapper);

    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe(@RequestParam("n") int n) {
            return "ok";
        }
    }

    private void log(Level level, String logger, String message, Duration ago) {
        LoggingEvent e = new LoggingEvent();
        e.setLevel(level);
        e.setLoggerName(logger);
        e.setMessage(message);
        e.setTimeStamp(NOW - ago.toMillis());
        logs.append(e);
    }

    @Test
    void springTurningAwayABadRequestIsNotAServiceWarning() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs.attach();
        try {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).build();
            mvc.perform(post("/probe")).andExpect(status().isMethodNotAllowed());
            mvc.perform(get("/probe").param("n", "x")).andExpect(status().isBadRequest());
        }
        finally {
            root.detachAppender(logs);
        }

        LogTracker.Snapshot snap = logs.snapshot();

        assertThat(snap.warnings()).isZero();
        assertThat(snap.clientMistakes()).isEqualTo(2);
        assertThat(snap.health(System.currentTimeMillis())).isEqualTo("ok");
        assertThat(snap.recent()).allMatch(LogTracker.Entry::client);
        assertThat(snap.recent()).extracting(LogTracker.Entry::message).anySatisfy(m ->
            assertThat(m).contains("HttpRequestMethodNotSupportedException"));
        assertThat(snap.recent()).extracting(LogTracker.Entry::message).anySatisfy(m ->
            assertThat(m).contains("MethodArgumentTypeMismatchException"));
    }

    @Test
    void whatTheResolverAnswersWith500IsTheServicesOwnProblem() {
        log(Level.WARN, RESOLVER,
            "Resolved [org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class X]]",
            Duration.ZERO);

        LogTracker.Snapshot snap = logs.snapshot();

        assertThat(snap.health(NOW)).isEqualTo("warn");
        assertThat(snap.warnings()).isEqualTo(1);
        assertThat(snap.recent()).noneMatch(LogTracker.Entry::client);
    }

    @Test
    void theSameTextFromAnotherLoggerIsAServiceWarning() {
        log(Level.WARN, "com.github.igniteprchecker.web.ApiExceptionHandler",
            "Resolved [org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'POST' is not supported]",
            Duration.ZERO);

        assertThat(logs.snapshot().health(NOW)).isEqualTo("warn");
    }

    @Test
    void anOldErrorGivesWayToAFreshWarning() {
        log(Level.ERROR, "TcClient", "Snapshot write failed", Duration.ofHours(7));
        log(Level.WARN, "TcClient", "TeamCity poll failed", Duration.ofMinutes(59));

        assertThat(logs.snapshot().health(NOW)).isEqualTo("warn");
        assertThat(logs.snapshot().health(NOW + Duration.ofMinutes(2).toMillis())).isEqualTo("ok");
    }

    @Test
    void theCountsSinceStartOutliveHealth() {
        log(Level.ERROR, "TcClient", "Snapshot write failed", Duration.ofDays(3));
        log(Level.WARN, "TcClient", "TeamCity poll failed", Duration.ofDays(3));

        LogTracker.Snapshot snap = logs.snapshot();

        assertThat(snap.health(NOW)).isEqualTo("ok");
        assertThat(snap.errors()).isEqualTo(1);
        assertThat(snap.warnings()).isEqualTo(1);
        assertThat(snap.recent()).hasSize(2);
    }
    @Test
    void theProblemsOfTheLastRunOutliveARestart(@TempDir Path dir) throws Exception {
        log(Level.WARN, "StandingVisas", "standing auto-visa sweep: PR 13655 skipped", Duration.ofDays(3));
        log(Level.ERROR, "CacheStore", "could not save standing-visas.json: No space left on device",
            Duration.ofHours(1));
        Path file = dir.resolve(logs.fileName());
        logs.saveTo(file);

        LogTracker restarted = new LogTracker(mapper);
        restarted.loadFrom(file);
        LogTracker.Snapshot snap = restarted.snapshot();

        assertThat(snap.recent()).extracting(LogTracker.Entry::message).containsExactly(
            "could not save standing-visas.json: No space left on device",
            "standing auto-visa sweep: PR 13655 skipped");
        assertThat(snap.health(NOW)).isEqualTo("error");
        assertThat(snap.errors()).isZero();
        assertThat(restarted.durable()).isTrue();
    }

    @Test
    void problemsLoggedSinceTheStartComeFirst(@TempDir Path dir) throws Exception {
        log(Level.WARN, "TcClient", "before the restart", Duration.ofHours(2));
        Path file = dir.resolve(logs.fileName());
        logs.saveTo(file);

        LogTracker restarted = new LogTracker(mapper);
        LoggingEvent e = new LoggingEvent();
        e.setLevel(Level.WARN);
        e.setLoggerName("TcClient");
        e.setMessage("after the restart");
        e.setTimeStamp(NOW);
        restarted.append(e);
        restarted.loadFrom(file);

        assertThat(restarted.snapshot().recent()).extracting(LogTracker.Entry::message)
            .containsExactly("after the restart", "before the restart");
    }

    @Test
    void aWeekOfProblemsIsKeptAndScannersCannotPushThemOut() {
        for (int i = 0; i < 7 * 40; i++)
            log(Level.WARN, "StandingVisas", "problem " + i, Duration.ofMinutes(7L * 24 * 60 - i * 36L));
        for (int i = 0; i < 500; i++)
            log(Level.WARN, RESOLVER, "Resolved [org.springframework.web.HttpRequestMethodNotSupportedException: "
                + "Request method 'POST' is not supported]", Duration.ZERO);

        LogTracker.Snapshot snap = logs.snapshot();

        assertThat(snap.recent()).filteredOn(en -> !en.client()).hasSize(7 * 40);
        assertThat(snap.recent()).filteredOn(LogTracker.Entry::client).hasSize(LogTracker.MAX_CLIENT_MISTAKES);
    }
}
