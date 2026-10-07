package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.update.UpdateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

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

    /** The page shows Restart, Update and Flush only to whom /api/me or the login answer calls an admin. */
    @Test
    void onlyNamedOperatorsAreToldTheyAreAdmins() {
        SessionProperties props = new SessionProperties(true, "test-secret");
        SessionCodec codec = new SessionCodec(props, mapper);
        TcClient tc = mock(TcClient.class);
        when(tc.currentUsername("stranger-token")).thenReturn(Optional.of("stranger"));
        when(tc.currentUsername("operator-token")).thenReturn(Optional.of("AVinogradov"));
        LoginController login = new LoginController(tc, codec, props, mock(Warmer.class), new UserDirectory(mapper),
            new LoginThrottle(), admin("avinogradov"));

        assertThat(toldAdmin(login.login(token("stranger-token"), new MockHttpServletRequest()))).isFalse();
        assertThat(toldAdmin(login.login(token("operator-token"), new MockHttpServletRequest()))).isTrue();
        assertThat(toldAdmin(login.me(codec.encode("stranger", "stranger-token")))).isFalse();
        assertThat(toldAdmin(login.me(codec.encode("AVinogradov", "operator-token")))).isTrue();
    }

    private static LoginController.LoginRequest token(String token) {
        return new LoginController.LoginRequest(token);
    }

    private static boolean toldAdmin(ResponseEntity<?> res) {
        return ((LoginController.UserResponse)res.getBody()).admin();
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
        assertThat(admin.lastUses()).isEmpty();
    }

    @Test
    void aFailedUpdateKeepsTheLastOneThatHappened() throws Exception {
        AdminActions admin = admin();
        UpdateController ctl = new UpdateController(update, admin);
        long carolsAt = now.get();

        assertThat(status(ctl.update("carol"))).isEqualTo(200);

        now.addAndGet(Duration.ofMinutes(11).toMillis());
        doThrow(new IllegalStateException("no update available")).when(update).performUpdate();

        assertThat(status(ctl.update("bob"))).isEqualTo(400);
        assertThat(admin.lastUses().get("update")).isEqualTo(new AdminActions.Use("carol", carolsAt));
    }

    /** Both presses passed the check before either was recorded, and both cleared the caches. */
    @Test
    void twoFlushesAtOnceRunOnce() throws Exception {
        CountDownLatch bothChecked = new CountDownLatch(2);
        AdminActions admin = new AdminActions(new AdminProperties(List.of()), mapper, now::get) {
            @Override
            public Optional<Refusal> refusal(String user, Action action) {
                Optional<Refusal> res = super.refusal(user, action);
                bothChecked.countDown();
                try {
                    // An atomic check-and-record holds the other press out until this wait gives up.
                    bothChecked.await(500, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return res;
            }
        };
        CacheController flush = new CacheController(cache, mock(Warmer.class), admin);
        ExecutorService presses = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<?>> bob = presses.submit(() -> flush.flush("bob"));
            Future<ResponseEntity<?>> carol = presses.submit(() -> flush.flush("carol"));

            assertThat(List.of(status(bob.get()), status(carol.get()))).containsExactlyInAnyOrder(200, 429);
            verify(cache, times(1)).clear();
        }
        finally {
            presses.shutdownNow();
        }
    }

    /** Fetching the release takes a while, and a restart pressed meanwhile would cut the update short. */
    @Test
    void anUpdateUnderWayHoldsTheCooldown() throws Exception {
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch fetched = new CountDownLatch(1);
        doAnswer(inv -> {
            updating.countDown();
            fetched.await();

            return null;
        }).when(update).performUpdate();
        UpdateController ctl = new UpdateController(update, admin());
        ExecutorService alice = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<?>> updated = alice.submit(() -> ctl.update("alice"));
            updating.await();

            ResponseEntity<?> restart = ctl.restart("bob");
            fetched.countDown();

            assertThat(status(restart)).isEqualTo(429);
            assertThat(error(restart)).isEqualTo("Updated 1 min ago by alice; try again in 10 min.");
            assertThat(status(updated.get())).isEqualTo(200);
            verify(update, never()).restart();
        }
        finally {
            alice.shutdownNow();
        }
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
