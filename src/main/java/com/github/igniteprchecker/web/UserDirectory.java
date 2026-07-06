package com.github.igniteprchecker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Who has been using the tool: usernames seen on authenticated requests, with first/last activity
 * and login counts. Persisted, so the answer to "who logged in?" survives restarts. No tokens here —
 * names only.
 */
@Component
public class UserDirectory implements SnapshotCache {
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

    /** Everyone ever seen, most recently active first. */
    public List<UserView> list() {
        List<UserView> out = new ArrayList<>();
        users.forEach((name, i) -> out.add(new UserView(name, i.firstSeen, i.lastSeen, i.logins.get())));
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
            users.put(u.name(), i);
        }
    }

    private static final class Info {
        final long firstSeen;
        volatile long lastSeen;
        final AtomicLong logins = new AtomicLong();

        Info(long firstSeen) {
            this.firstSeen = firstSeen;
        }
    }

    /** One user of the tool: identity and activity summary (no secrets). */
    public record UserView(String name, long firstSeen, long lastSeen, long logins) {
    }
}
