package com.github.igniteprchecker.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Captures WARN/ERROR log events into a ring buffer (with running counts) so the status page can surface
 * recent problems without shell access to the server log. Attaches itself to the Logback root logger on
 * startup. The ring is snapshotted to disk and outlives a restart, which is when it is needed most; the
 * counts are since start.
 */
@Component
public class LogTracker extends AppenderBase<ILoggingEvent> implements SnapshotCache {
    /** Room for a week of problems at 40 a day. */
    static final int MAX_RECENT = 300;

    /** Scanners send bursts of bad requests; they must not push the service's own problems out of the ring. */
    static final int MAX_CLIENT_MISTAKES = 20;

    /** A warning colours health for the hour that the rest of the status page reports on. */
    private static final long WARN_WINDOW_MS = TimeUnit.HOURS.toMillis(1);

    /**
     * An error means something actually broke, so it stays red until the next look at the page is likely
     * to catch it, yet a one-off still clears within the working day.
     */
    private static final long ERROR_WINDOW_MS = TimeUnit.HOURS.toMillis(6);

    /** Spring logs each request it turns away under the resolver's class name. */
    private static final String RESOLVER = DefaultHandlerExceptionResolver.class.getName();

    private static final String RESOLVED = "Resolved [";

    /**
     * What Spring answers with a 4xx when a caller gets this app's endpoints wrong: the wrong method (what
     * scanners mostly do), a missing or malformed parameter, an unreadable body, a content type the endpoint
     * does not take or cannot produce. The rest the resolver logs, such as a 500 for a response it could not
     * write, is the service's own problem.
     */
    private static final Set<String> CLIENT_MISTAKES = Stream.of(HttpRequestMethodNotSupportedException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class)
        .map(Class::getName)
        .collect(Collectors.toUnmodifiableSet());

    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final AtomicLong clientMistakes = new AtomicLong();
    private final AtomicLong lastErrorAt = new AtomicLong();
    private final AtomicLong lastWarningAt = new AtomicLong();
    private final ConcurrentLinkedDeque<Entry> recent = new ConcurrentLinkedDeque<>();
    private final ObjectMapper mapper;

    public LogTracker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void attach() {
        ch.qos.logback.classic.Logger root =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        setName("statusLogTracker");
        start();
        root.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent e) {
        Level level = e.getLevel();
        if (level != Level.ERROR && level != Level.WARN)
            return;

        String msg = message(e);
        boolean client = clientMistake(e.getLoggerName(), msg);
        if (client)
            clientMistakes.incrementAndGet();
        else if (level == Level.ERROR) {
            errors.incrementAndGet();
            lastErrorAt.accumulateAndGet(e.getTimeStamp(), Math::max);
        }
        else {
            warnings.incrementAndGet();
            lastWarningAt.accumulateAndGet(e.getTimeStamp(), Math::max);
        }

        recent.addFirst(new Entry(e.getTimeStamp(), level.toString(), shortName(e.getLoggerName()), msg, client));
        trim();
    }

    private void trim() {
        long clients = recent.stream().filter(Entry::client).count();
        Iterator<Entry> oldestFirst = recent.descendingIterator();
        while (clients > MAX_CLIENT_MISTAKES && oldestFirst.hasNext()) {
            if (oldestFirst.next().client()) {
                oldestFirst.remove();
                clients--;
            }
        }

        while (recent.size() > MAX_RECENT)
            recent.pollLast();
    }

    public Snapshot snapshot() {
        return new Snapshot(errors.get(), warnings.get(), clientMistakes.get(), lastErrorAt.get(), lastWarningAt.get(),
            new ArrayList<>(recent));
    }

    @Override
    public String fileName() {
        return "problems.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, new ArrayList<>(recent));
    }

    /** The problems of earlier runs go behind those logged since start; only the counts start from zero. */
    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        for (Entry e : mapper.readValue(file.toFile(), Entry[].class)) {
            recent.addLast(e);
            if (e.client())
                continue;

            if ("ERROR".equals(e.level()))
                lastErrorAt.accumulateAndGet(e.t(), Math::max);
            else
                lastWarningAt.accumulateAndGet(e.t(), Math::max);
        }

        trim();
    }

    /** Whether Spring turned the request away as the caller's mistake: "Resolved [exception class: message]". */
    private static boolean clientMistake(String logger, String message) {
        if (!RESOLVER.equals(logger) || !message.startsWith(RESOLVED))
            return false;

        int end = message.indexOf(':', RESOLVED.length());

        return end > 0 && CLIENT_MISTAKES.contains(message.substring(RESOLVED.length(), end));
    }

    private static String message(ILoggingEvent e) {
        String msg = e.getFormattedMessage();
        if (e.getThrowableProxy() != null)
            msg += " (" + e.getThrowableProxy().getClassName() + ": " + e.getThrowableProxy().getMessage() + ")";

        return msg;
    }

    private static String shortName(String logger) {
        int dot = logger.lastIndexOf('.');

        return dot >= 0 ? logger.substring(dot + 1) : logger;
    }

    /**
     * Counts are since start and never go down, so they say how much went wrong, not whether anything is
     * wrong now; health follows only when the service last warned or failed. Client mistakes are counted
     * and listed apart and never touch health.
     */
    public record Snapshot(long errors, long warnings, long clientMistakes, long lastErrorAt, long lastWarningAt,
        List<Entry> recent) {
        public String health(long now) {
            if (now - lastErrorAt < ERROR_WINDOW_MS)
                return "error";

            return now - lastWarningAt < WARN_WINDOW_MS ? "warn" : "ok";
        }

        /** The same without the messages, which name users and PRs: what anonymous viewers may see. */
        public Snapshot countsOnly() {
            return new Snapshot(errors, warnings, clientMistakes, lastErrorAt, lastWarningAt, List.of());
        }
    }

    /** One logged problem; {@code client} marks a request Spring turned away as the caller's mistake. */
    public record Entry(long t, String level, String logger, String message, boolean client) {
    }
}
