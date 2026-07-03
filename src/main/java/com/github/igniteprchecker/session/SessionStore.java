package com.github.igniteprchecker.session;

import com.github.igniteprchecker.config.SessionProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** In-memory store of active sessions. Expired sessions are evicted lazily on lookup. */
@Component
public class SessionStore {
    private final ConcurrentMap<String, UserSession> sessions = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    private final SessionProperties props;

    public SessionStore(SessionProperties props) {
        this.props = props;
    }

    public UserSession create(String username, String token) {
        String id = newId();
        UserSession session = new UserSession(id, username, token,
            Instant.now().plus(Duration.ofMinutes(props.ttlMinutes())));

        sessions.put(id, session);

        return session;
    }

    public Optional<UserSession> get(String id) {
        if (id == null)
            return Optional.empty();

        UserSession session = sessions.get(id);
        if (session == null)
            return Optional.empty();

        if (session.expired()) {
            sessions.remove(id);

            return Optional.empty();
        }

        return Optional.of(session);
    }

    public void remove(String id) {
        if (id != null)
            sessions.remove(id);
    }

    private String newId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
