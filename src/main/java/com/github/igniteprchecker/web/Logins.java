package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The logins of each user: one per browser that logged in, with a label of the browser and its system, when it logged
 * in and when it was last seen. No token and no address: the TeamCity token stays in the browser's cookie. The cookie
 * names its login, so ending a login logs out that browser and every copy of its cookie, and nothing else; the user's
 * options are the account's and stay as they are.
 *
 * <p>A cookie of an earlier version names no login. Its first request makes one for it, unless a logout of that version
 * ended it, and a copy of the same cookie gets the same login. Once such a login ends, it is kept as ended until its
 * cookie cannot be around any more, so that a copy cannot make it anew. Past that, a browser has dropped the cookie, so
 * only a copy can bring it, and it gets no login.
 *
 * <p>Each login names the key of the cookies it was made under. A cookie made under another {@code session.secret}
 * cannot be read, so the logins made under it are not loaded.
 *
 * <p>Saved within a second ({@code logins.json}): a login the file lost would log its browser out.
 */
@Component
public class Logins implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(Logins.class);

    /** How stale a login's last-seen time gets before a request moves it: the file is not written on every request. */
    static final Duration SEEN_EVERY = Duration.ofMinutes(5);

    /** How long a login unseen is kept: past the 400 days a browser keeps the cookie, with a day to spare. */
    static final Duration KEPT_UNSEEN = SessionCookies.MAX_AGE.plusDays(1);

    /** The most logins a user has: a new one beyond it ends another, see {@link #endBeyondKept}. */
    static final int KEPT_PER_USER = 50;

    private final ObjectMapper mapper;

    private final UserDirectory users;

    /** The fingerprint of the cookies' key, see {@link SessionCodec#keyCheck}; null when the logins name no key. */
    private final String keyCheck;

    private final LongSupplier clock;

    private final SecureRandom random = new SecureRandom();

    /** Every login by its id, the ended ones of earlier versions' cookies included. */
    private final Map<String, Login> byId = new ConcurrentHashMap<>();

    /** The ids of the logins made for cookies of earlier versions, by {@link #earlierKey}. */
    private final Map<String, String> byEarlierCookie = new ConcurrentHashMap<>();

    /** Hears of the logins that end: the user and the ids. */
    private volatile BiConsumer<String, Set<String>> ends = (user, ids) -> {
    };

    @Autowired
    public Logins(ObjectMapper mapper, UserDirectory users, SessionCodec codec) {
        this(mapper, users, codec.keyCheck(), System::currentTimeMillis);
    }

    /** Logins that name no key: each one in the file counts as made under the key of the cookies. */
    public Logins(ObjectMapper mapper, UserDirectory users) {
        this(mapper, users, null, System::currentTimeMillis);
    }

    Logins(ObjectMapper mapper, UserDirectory users, LongSupplier clock) {
        this(mapper, users, null, clock);
    }

    Logins(ObjectMapper mapper, UserDirectory users, String keyCheck, LongSupplier clock) {
        this.mapper = mapper;
        this.users = users;
        this.keyCheck = keyCheck;
        this.clock = clock;
    }

    /** Has {@code listener} hear of every login that ends, whatever ends it: the user and the ids. */
    public void whenEnded(BiConsumer<String, Set<String>> listener) {
        ends = listener;
    }

    /** The login a login form starts for the user, in a browser with this label. */
    public synchronized Login start(String user, String browser) {
        long now = clock.getAsLong();
        prune(now);
        Login login = new Login(newId(), user, browser, now, now, null, null, keyCheck);
        byId.put(login.id(), login);
        endBeyondKept(login, now);

        return login;
    }

    /**
     * The login of a request's session, seen now; empty once it ended. A cookie of an earlier version gets its login
     * here, labelled {@code browser}.
     */
    public Optional<Login> enter(SessionCodec.Session session, String browser) {
        Optional<Login> login = session.login() != null ? of(session) : madeFor(session, browser);

        return login.map(this::seen);
    }

    /** The login of a session, if it goes on; a cookie of an earlier version gets none here. */
    public Optional<Login> of(SessionCodec.Session session) {
        Login login = session.login() != null ? byId.get(session.login()) : earlier(session);

        return Optional.ofNullable(login).filter(l -> l.on() && l.user().equals(session.username()));
    }

    /** Whether the session goes on: its login does, or it is a cookie of an earlier version no logout ended. */
    public boolean live(SessionCodec.Session session) {
        if (session.login() != null)
            return of(session).isPresent();
        if (outlived(session, clock.getAsLong()))
            return false;
        if (earlier(session) != null)
            return of(session).isPresent();

        return !users.ended(session);
    }

    /** Whether the login {@code id} goes on. */
    public boolean goesOn(String id) {
        Login login = byId.get(id);

        return login != null && login.on();
    }

    /** The user's logins that go on, the one seen last first. */
    public List<Login> of(String user) {
        return byId.values().stream()
            .filter(l -> l.on() && l.user().equals(user))
            .sorted(Comparator.comparingLong(Login::lastSeen).reversed().thenComparing(Login::id))
            .toList();
    }

    /** Ends those of {@code ids} that are the user's logins and go on; answers the ids it ended. */
    public synchronized Set<String> end(String user, Set<String> ids) {
        List<Login> ending = new ArrayList<>();
        for (String id : ids) {
            Login login = byId.get(id);
            if (login != null && login.on() && login.user().equals(user))
                ending.add(login);
        }

        return end(user, ending, clock.getAsLong());
    }

    /**
     * Ends every login of the user but {@code kept}, and every cookie of an earlier version of theirs that got no login
     * yet; answers the ids it ended.
     */
    public synchronized Set<String> endAllBut(String user, String kept) {
        users.endSessions(user);

        return end(user, of(user).stream().filter(l -> !l.id().equals(kept)).toList(), clock.getAsLong());
    }

    /**
     * The login of a cookie of an earlier version: the one an earlier request of it got, or a new one, unless a logout
     * of that version ended the cookie's session or the cookie is older than a browser keeps one.
     */
    private synchronized Optional<Login> madeFor(SessionCodec.Session session, String browser) {
        long now = clock.getAsLong();
        if (outlived(session, now))
            return Optional.empty();
        Login known = earlier(session);
        if (known != null)
            return of(session);
        if (users.ended(session))
            return Optional.empty();

        prune(now);
        Login login = new Login(newId(), session.username(), browser, now, now, session.issuedAt(), null, keyCheck);
        byId.put(login.id(), login);
        byEarlierCookie.put(earlierKey(session.username(), session.issuedAt()), login.id());
        endBeyondKept(login, now);

        return Optional.of(login);
    }

    /**
     * Whether a cookie of an earlier version was issued longer ago than a browser keeps a cookie. Every cookie that
     * says when it was issued asked the browser to keep it {@link SessionCookies#MAX_AGE}. One issued before cookies
     * said so has no age to tell.
     */
    private static boolean outlived(SessionCodec.Session session, long now) {
        return session.issuedAt() > 0 && now - session.issuedAt() > KEPT_UNSEEN.toMillis();
    }

    private Login earlier(SessionCodec.Session session) {
        String id = byEarlierCookie.get(earlierKey(session.username(), session.issuedAt()));

        return id == null ? null : byId.get(id);
    }

    /**
     * The login, with its last-seen time moved to now when older than {@link #SEEN_EVERY}. A login ended meanwhile
     * stays ended; this request still passes, the next one does not.
     */
    private Login seen(Login login) {
        long now = clock.getAsLong();
        if (now - login.lastSeen() < SEEN_EVERY.toMillis())
            return login;

        Login updated = byId.computeIfPresent(login.id(), (id, cur) -> cur.on() ? cur.seenAt(now) : cur);

        return updated == null ? login : updated;
    }

    /**
     * A login made for a cookie of an earlier version stays as ended, or a copy of that cookie would make it anew; the
     * others go.
     */
    private void end(Login login, long now) {
        if (login.earlierIssuedAt() == null)
            byId.remove(login.id());
        else
            byId.put(login.id(), login.endedAt(now));
    }

    /** Ends the logins, has {@link #whenEnded} hear of them and answers their ids. */
    private Set<String> end(String user, List<Login> ending, long now) {
        ending.forEach(l -> end(l, now));
        Set<String> ids = ending.stream().map(Login::id).collect(Collectors.toUnmodifiableSet());
        if (!ids.isEmpty())
            ends.accept(user, ids);

        return ids;
    }

    /**
     * Ends the user's logins beyond {@link #KEPT_PER_USER}, each the one seen longest ago among those labelled as the
     * {@code added} one, and only then among the others: a script that logs in on every run ends its own earlier
     * logins, not a browser.
     */
    private void endBeyondKept(Login added, long now) {
        List<Login> others = of(added.user()).stream().filter(l -> !l.id().equals(added.id())).toList();
        int over = others.size() + 1 - KEPT_PER_USER;
        if (over <= 0)
            return;

        end(added.user(), others.stream()
            .sorted(Comparator.comparing((Login l) -> !l.browser().equals(added.browser()))
                .thenComparingLong(Login::lastSeen).thenComparing(Login::id))
            .limit(over)
            .toList(), now);
    }

    /**
     * Forgets the logins no cookie can name any more: their browsers dropped the cookie. An ended login made for a
     * cookie that does not say when it was issued stays: no age tells a copy of that cookie, see {@link #outlived}.
     */
    private void prune(long now) {
        byId.values().removeIf(l -> now - l.lastSeen() > KEPT_UNSEEN.toMillis() && !l.endedUndated());
        byEarlierCookie.values().removeIf(id -> !byId.containsKey(id));
    }

    private String newId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String earlierKey(String user, long issuedAt) {
        return user + '\n' + issuedAt;
    }

    @Override
    public String fileName() {
        return "logins.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Login> all = byId.values().stream()
            .sorted(Comparator.comparingLong(Login::createdAt).thenComparing(Login::id))
            .toList();
        Snapshots.writeAtomic(mapper, file, all);
    }

    /**
     * Loads the logins made under the key of the cookies. A login of an earlier version names no key: it was made under
     * this key, as a change of the key logs everyone out, and is marked so.
     */
    @Override
    public synchronized void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        int otherKey = 0;
        for (Login read : mapper.readValue(file.toFile(), Login[].class)) {
            if (keyCheck != null && read.keyCheck() != null && !keyCheck.equals(read.keyCheck())) {
                otherKey++;
                continue;
            }

            Login login = read.keyCheck() == null ? read.under(keyCheck) : read;
            byId.put(login.id(), login);
            if (login.earlierIssuedAt() != null)
                byEarlierCookie.put(earlierKey(login.user(), login.earlierIssuedAt()), login.id());
        }
        if (otherKey > 0)
            log.info("{} logins made under another session.secret dropped: no cookie of theirs can be read", otherKey);
        prune(clock.getAsLong());
    }

    /**
     * A login: its id, which the cookie carries; whose it is; the label of its browser and system; when it logged in
     * and when it was last seen, within {@link #SEEN_EVERY}. {@code earlierIssuedAt}: when the cookie of an earlier
     * version this login was made for was issued, null for a login made at the login form. {@code endedAt}: when the
     * login made for such a cookie ended; null while it goes on. {@code keyCheck}: the fingerprint of the key its
     * cookie was made under; null in a file of an earlier version.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Login(String id, String user, String browser, long createdAt, long lastSeen, Long earlierIssuedAt,
        Long endedAt, String keyCheck) {
        boolean on() {
            return endedAt == null;
        }

        /** Ended, and made for a cookie of an earlier version that did not say when it was issued. */
        boolean endedUndated() {
            return !on() && earlierIssuedAt != null && earlierIssuedAt == 0;
        }

        Login seenAt(long at) {
            return new Login(id, user, browser, createdAt, at, earlierIssuedAt, endedAt, keyCheck);
        }

        Login endedAt(long at) {
            return new Login(id, user, browser, createdAt, lastSeen, earlierIssuedAt, at, keyCheck);
        }

        Login under(String key) {
            return new Login(id, user, browser, createdAt, lastSeen, earlierIssuedAt, endedAt, key);
        }
    }
}
