package com.github.igniteprchecker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.session.SessionCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Who has been using the tool: usernames seen on authenticated requests, with first/last activity
 * and login counts, when each last ended all their other logins, and when a cookie of theirs last carried a JIRA token
 * the checker does not use. Persisted within a second, so the answer to "who logged in?" and an end of the logins
 * survive restarts: the cookies of earlier versions, which name no login, issued before it stay refused. No tokens
 * here — names only. The logins themselves are {@link Logins}.
 */
@Component
public class UserDirectory implements SnapshotCache {
    /**
     * How long after a cookie of the user last carried a JIRA token ⚙ tells them that visas need Visa my runs in JIRA.
     * Such a cookie loses the token at its next request, so without the mark the user would learn it only from the
     * visas that do not come.
     */
    static final Duration JIRA_IN_COOKIE_TOLD_FOR = Duration.ofDays(30);

    private final ObjectMapper mapper;
    private final ConcurrentMap<String, Info> users = new ConcurrentHashMap<>();

    public UserDirectory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** An authenticated request from this user (called by the auth interceptor; cheap). */
    public void touch(String username) {
        info(username).lastSeen = System.currentTimeMillis();
    }

    /** A successful login (token validated against TeamCity). */
    public void touchLogin(String username) {
        Info i = info(username);
        i.lastSeen = System.currentTimeMillis();
        i.logins.incrementAndGet();
    }

    /** A request whose cookie carries a JIRA token, which the checker does not use; see {@link #jiraInCookie}. */
    public void sawJiraInCookie(String username) {
        info(username).jiraInCookieAt = System.currentTimeMillis();
    }

    /**
     * Whether a cookie of the user carried a JIRA token within {@link #JIRA_IN_COOKIE_TOLD_FOR}: they pasted it into an
     * earlier version, and get visas only once they switch on Visa my runs in JIRA with it.
     */
    public boolean jiraInCookie(String username) {
        Info i = users.get(username);

        return i != null && i.jiraInCookieAt > 0
            && System.currentTimeMillis() - i.jiraInCookieAt <= JIRA_IN_COOKIE_TOLD_FOR.toMillis();
    }

    /** The user's visa option has a JIRA token of its own: nothing is left to tell of the one in their cookie. */
    public void forgetJiraInCookie(String username) {
        Info i = users.get(username);
        if (i != null)
            i.jiraInCookieAt = 0;
    }

    /**
     * Every cookie of the user's issued until now that names no login ends: Log out everywhere else, or Log out of an
     * earlier version, which ended the user's sessions in every browser.
     */
    public void endSessions(String username) {
        Info i = info(username);
        synchronized (i) {
            i.sessionsEndedAt = System.currentTimeMillis();
        }
    }

    /**
     * Whether the session was issued before its user last ended their sessions; {@link Logins} asks it for a cookie
     * that names no login. An end this check does not see takes its time after the check, so a cookie issued from the
     * session with a time taken before the check is ended by it.
     */
    public boolean ended(SessionCodec.Session session) {
        Info i = users.get(session.username());
        if (i == null)
            return false;

        synchronized (i) {
            return i.sessionsEndedAt > 0 && session.issuedAt() <= i.sessionsEndedAt;
        }
    }

    /** Everyone ever seen, most recently active first. */
    public List<UserView> list() {
        List<UserView> out = new ArrayList<>();
        users.forEach((name, i) -> out.add(new UserView(name, i.firstSeen, i.lastSeen, i.logins.get(),
            i.sessionsEndedAt, i.jiraInCookieAt)));
        out.sort(Comparator.comparingLong(UserView::lastSeen).reversed());

        return out;
    }

    private Info info(String username) {
        return users.computeIfAbsent(username, k -> new Info(System.currentTimeMillis()));
    }

    @Override
    public String fileName() {
        return "users.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<UserView> snap = list();
        Snapshots.writeAtomic(mapper, file, snap);
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (UserView u : mapper.readValue(file.toFile(), UserView[].class)) {
            Info i = new Info(u.firstSeen());
            i.lastSeen = u.lastSeen();
            i.logins.set(u.logins());
            i.sessionsEndedAt = u.sessionsEndedAt();
            i.jiraInCookieAt = u.jiraInCookieAt();
            users.put(u.name(), i);
        }
    }

    private static final class Info {
        final long firstSeen;
        volatile long lastSeen;
        final AtomicLong logins = new AtomicLong();
        volatile long sessionsEndedAt;
        volatile long jiraInCookieAt;

        Info(long firstSeen) {
            this.firstSeen = firstSeen;
        }
    }

    /**
     * One user of the tool: identity and activity summary (no secrets), when they last ended their sessions, and when
     * a cookie of theirs last carried a JIRA token (0: never; both absent from the files of earlier versions).
     */
    public record UserView(String name, long firstSeen, long lastSeen, long logins, long sessionsEndedAt,
        long jiraInCookieAt) {
    }
}
