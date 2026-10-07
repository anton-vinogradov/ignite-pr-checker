package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.update.UpdateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/**
 * Restart (a System.exit), Update, Flush (re-warming 50 PRs on other users' tokens, thousands of TeamCity calls)
 * and the user list were open to anyone who logged in, and anyone may register on ci2. Nothing said who pressed
 * them, and nothing stopped pressing them again and again.
 */
class AdminActionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_760_000_000_000L);

    private final UpdateService update = mock(UpdateService.class);

    private final AnalysisCache cache = mock(AnalysisCache.class);

    private AdminActions admin(String... operators) {
        return new AdminActions(new AdminProperties(List.of(operators)), mapper, now::get);
    }

    private static int status(ResponseEntity<?> res) {
        return res.getStatusCode().value();
    }

    private static Object error(ResponseEntity<?> res) {
        return ((Map<?, ?>)res.getBody()).get("error");
    }

    @Test
    void namedOperatorsAloneMayActAndNeedNoCooldown() {
        AdminActions admin = admin("AVinogradov");
        UpdateController ctl = new UpdateController(update, admin);
        CacheController flush = new CacheController(cache, mock(Warmer.class), admin);

        assertThat(status(ctl.restart("stranger"))).isEqualTo(403);
        assertThat(status(flush.flush("stranger"))).isEqualTo(403);
        assertThat(error(ctl.restart("stranger"))).isEqualTo("Only the operator can restart the service.");
        verify(update, never()).restart();
        verify(cache, never()).clear();

        assertThat(status(ctl.restart("avinogradov"))).isEqualTo(200);
        assertThat(status(ctl.restart("avinogradov"))).isEqualTo(200);
        verify(update, times(2)).restart();
    }

    @Test
    void namedOperatorsAloneSeeTheUsers() {
        LoginController login = new LoginController(null, null, null, null, new UserDirectory(mapper), null,
            admin("avinogradov"));

        assertThat(status(login.users("stranger"))).isEqualTo(403);
        assertThat(status(login.users("avinogradov"))).isEqualTo(200);
    }

    @Test
    void withoutOperatorsRestartAndUpdateShareATenMinuteCooldown() throws Exception {
        AdminActions admin = admin();
        UpdateController ctl = new UpdateController(update, admin);

        assertThat(status(ctl.restart("alice"))).isEqualTo(200);

        now.addAndGet(Duration.ofMinutes(3).toMillis());
        ResponseEntity<?> again = ctl.update("bob");

        assertThat(status(again)).isEqualTo(429);
        assertThat(error(again)).isEqualTo("Restarted 3 min ago by alice; try again in 7 min.");
        verify(update, never()).performUpdate();

        now.addAndGet(Duration.ofMinutes(7).toMillis());

        assertThat(status(ctl.update("bob"))).isEqualTo(200);
        assertThat(admin.lastUses()).containsOnlyKeys("restart", "update");
        assertThat(admin.lastUses().get("update").by()).isEqualTo("bob");
    }

    @Test
    void withoutOperatorsFlushRunsOncePerHour() {
        CacheController flush = new CacheController(cache, mock(Warmer.class), admin());

        assertThat(status(flush.flush("alice"))).isEqualTo(200);

        now.addAndGet(Duration.ofMinutes(59).toMillis());

        assertThat(status(flush.flush("bob"))).isEqualTo(429);

        now.addAndGet(Duration.ofMinutes(1).toMillis());

        assertThat(status(flush.flush("bob"))).isEqualTo(200);
        verify(cache, times(2)).clear();
    }

    @Test
    void aFailedUpdateLeavesNoCooldown() throws Exception {
        AdminActions admin = admin();
        UpdateController ctl = new UpdateController(update, admin);
        doThrow(new IllegalStateException("no update available")).when(update).performUpdate();

        assertThat(status(ctl.update("alice"))).isEqualTo(400);
        assertThat(admin.refusal("alice", AdminActions.Action.RESTART)).isEmpty();
    }

    /** A restart exits the JVM: its record has to come back from disk, or the cooldown would never hold. */
    @Test
    void theLastUsesSurviveTheRestartTheyCause(@TempDir Path dir) throws IOException {
        AdminActions before = admin();
        new UpdateController(update, before).restart("alice");
        Path file = dir.resolve(before.fileName());
        before.saveTo(file);

        AdminActions after = admin();
        after.loadFrom(file);

        assertThat(after.refusal("bob", AdminActions.Action.RESTART)).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(429);
            assertThat(r.message()).contains("by alice");
        });
    }

    @Test
    void readsTheFileFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("admin-actions.json");
        Files.writeString(file, "{\"restart\":{\"by\":\"alice\",\"at\":1760000000000},"
            + "\"flush\":{\"by\":\"bob\",\"at\":1759999000000},\"unknown\":{\"by\":\"x\",\"at\":1}}");

        AdminActions admin = admin();
        admin.loadFrom(file);

        assertThat(admin.lastUses()).containsOnlyKeys("restart", "flush");
        assertThat(admin.lastUses().get("flush")).isEqualTo(new AdminActions.Use("bob", 1_759_999_000_000L));
        assertThat(admin.allowedAt(AdminActions.Action.UPDATE))
            .isEqualTo(1_760_000_000_000L + Duration.ofMinutes(10).toMillis());
    }
}
