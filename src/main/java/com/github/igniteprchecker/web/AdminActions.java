package com.github.igniteprchecker.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Restart, update, cache flush and the user list, for the operators: the TeamCity users {@code PRC_ADMINS} names. A
 * restart exits the JVM, a flush re-warms 50 PRs (thousands of TeamCity calls on other users' tokens), and anyone may
 * register on ci2, so without {@code PRC_ADMINS} nobody may use these. Each use is logged with its user and kept on
 * disk, so the status page can tell the operators who did it last, after the restart it causes too.
 */
@Component
public class AdminActions implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(AdminActions.class);

    /** An operator's action. */
    public enum Action {
        RESTART("restart the service"),
        UPDATE("update the service"),
        FLUSH("flush the caches");

        private final String what;

        Action(String what) {
            this.what = what;
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

    /** Whether {@code PRC_ADMINS} names anyone: without it nobody is an operator. */
    public boolean operatorsNamed() {
        return !props.logins().isEmpty();
    }

    /** Whether this user is an operator: {@code PRC_ADMINS} names them. */
    public boolean mayAdminister(String user) {
        return user != null && props.logins().contains(user.toLowerCase(Locale.ROOT));
    }

    /** Lets this user do this now and records the use, or says why not. */
    public synchronized Optional<Refusal> claim(String user, Action action) {
        if (!mayAdminister(user))
            return Optional.of(new Refusal(403, "Only the operator can " + action.what + "."));

        beforeClaim.put(action, last.put(action, new Use(user, nowMs.getAsLong())));
        log.info("{} requested by {}", action.key(), user);

        return Optional.empty();
    }

    /** Takes back this user's claim of an action that did not happen: the status page names who did it last. */
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

    /** The last use of each action, by action key ("restart", "update", "flush"). */
    public synchronized Map<String, Use> lastUses() {
        Map<String, Use> out = new LinkedHashMap<>();
        last.forEach((a, u) -> out.put(a.key(), u));

        return out;
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

    /** One use of an operator's action: who and when (epoch ms). */
    public record Use(String by, long at) {
    }

    /** A refused action: the HTTP status to answer with and a short text for the user. */
    public record Refusal(int status, String message) {
        public ResponseEntity<?> response() {
            return ResponseEntity.status(status).body(Map.of("error", message));
        }
    }
}
