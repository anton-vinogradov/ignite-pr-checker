package com.github.igniteprchecker.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Restart, update, cache flush and the user list. A restart exits the JVM and a flush re-warms 50 PRs (thousands of
 * TeamCity calls on other users' tokens), so with operators named in {@code PRC_ADMINS} only they may use these;
 * without that any logged-in user may, but restart or update at most once per 10 minutes and flush once per
 * hour. Each use is logged with its user and kept on disk, so the status page can say who did it last and the
 * cooldown survives the restart it causes.
 */
@Component
public class AdminActions implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(AdminActions.class);

    static final Duration RESTART_COOLDOWN = Duration.ofMinutes(10);

    static final Duration FLUSH_COOLDOWN = Duration.ofHours(1);

    /** An admin-only action; restart and update share a cooldown, as both restart the service. */
    public enum Action {
        RESTART("restart the service", "Restarted"),
        UPDATE("update the service", "Updated"),
        FLUSH("flush the caches", "Caches flushed");

        private final String what;

        private final String done;

        Action(String what, String done) {
            this.what = what;
            this.done = done;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final AdminProperties props;

    private final ObjectMapper mapper;

    private final LongSupplier nowMs;

    private final Map<Action, Use> last = new EnumMap<>(Action.class);

    /** What {@link #last} held before each claim, so a claim of an action that did not happen can be taken back. */
    private final Map<Action, Use> beforeClaim = new EnumMap<>(Action.class);

    @Autowired
    public AdminActions(AdminProperties props, ObjectMapper mapper) {
        this(props, mapper, System::currentTimeMillis);
    }

    AdminActions(AdminProperties props, ObjectMapper mapper, LongSupplier nowMs) {
        this.props = props;
        this.mapper = mapper;
        this.nowMs = nowMs;
    }

    /** Whether operators are named; without them everyone logged in may, within cooldowns. */
    public boolean operatorsNamed() {
        return !props.logins().isEmpty();
    }

    /** Whether this user may use the admin actions at all (cooldowns aside). */
    public boolean mayAdminister(String user) {
        return !operatorsNamed() || (user != null && props.logins().contains(user.toLowerCase(Locale.ROOT)));
    }

    /** Why this user may not do this now, or empty when they may. */
    public synchronized Optional<Refusal> refusal(String user, Action action) {
        if (!mayAdminister(user))
            return Optional.of(new Refusal(403, "Only the operator can " + action.what + "."));

        long now = nowMs.getAsLong();
        long allowedAt = allowedAt(action);
        if (allowedAt <= now)
            return Optional.empty();

        Action prev = cooldownStart(action);
        Use use = last.get(prev);

        return Optional.of(new Refusal(429, prev.done + " " + minutes(now - use.at()) + " ago by " + use.by()
            + "; try again in " + minutes(allowedAt - now) + "."));
    }

    /**
     * Lets this user do this now and records the use, or says why not. Checked and recorded in one step: two
     * presses at once would otherwise both pass the check before either was recorded.
     */
    public synchronized Optional<Refusal> claim(String user, Action action) {
        Optional<Refusal> refused = refusal(user, action);
        if (refused.isPresent())
            return refused;

        beforeClaim.put(action, last.put(action, new Use(user, nowMs.getAsLong())));
        log.info("{} requested by {}", action.key(), user);

        return Optional.empty();
    }

    /** Takes back this user's claim of an action that did not happen, so it starts no cooldown. */
    public synchronized void withdraw(String user, Action action) {
        Use claimed = last.get(action);
        if (claimed == null || !claimed.by().equals(user))
            return;

        Use before = beforeClaim.remove(action);
        if (before == null)
            last.remove(action);
        else
            last.put(action, before);
    }

    /** Epoch-ms when this action is allowed again; 0 when it never ran, or operators are named (no cooldown). */
    public synchronized long allowedAt(Action action) {
        Action prev = operatorsNamed() ? null : cooldownStart(action);

        return prev == null ? 0 : last.get(prev).at() + cooldown(action).toMillis();
    }

    /** The last use of each action, by action key ("restart", "update", "flush"). */
    public synchronized Map<String, Use> lastUses() {
        Map<String, Use> out = new LinkedHashMap<>();
        last.forEach((a, u) -> out.put(a.key(), u));

        return out;
    }

    /** The last use that this action's cooldown runs from, or null if there was none. */
    private Action cooldownStart(Action action) {
        if (action == Action.FLUSH)
            return last.containsKey(Action.FLUSH) ? Action.FLUSH : null;

        Use restart = last.get(Action.RESTART);
        Use update = last.get(Action.UPDATE);
        if (restart == null)
            return update == null ? null : Action.UPDATE;

        return update == null || restart.at() >= update.at() ? Action.RESTART : Action.UPDATE;
    }

    private static Duration cooldown(Action action) {
        return action == Action.FLUSH ? FLUSH_COOLDOWN : RESTART_COOLDOWN;
    }

    private static String minutes(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);

        return min + " min";
    }

    @Override
    public String fileName() {
        return "admin-actions.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, lastUses());
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Map<String, Use> saved = mapper.readValue(file.toFile(), new TypeReference<Map<String, Use>>() {
        });

        synchronized (this) {
            for (Action a : Action.values()) {
                Use u = saved.get(a.key());
                if (u != null)
                    last.put(a, u);
            }
        }
    }

    /** One use of an admin action: who and when (epoch ms). */
    public record Use(String by, long at) {
    }

    /** A refused admin action: the HTTP status to answer with and a short text for the user. */
    public record Refusal(int status, String message) {
        public ResponseEntity<?> response() {
            return ResponseEntity.status(status).body(Map.of("error", message));
        }
    }
}
