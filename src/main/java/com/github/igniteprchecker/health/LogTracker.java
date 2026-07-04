package com.github.igniteprchecker.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures WARN/ERROR log events into an in-memory ring buffer (with running counts) so the status
 * page can surface recent problems without shell access to the server log. Attaches itself to the
 * Logback root logger on startup. In-memory only — resets on restart.
 */
@Component
public class LogTracker extends AppenderBase<ILoggingEvent> {
    private static final int MAX_RECENT = 50;

    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final ConcurrentLinkedDeque<Entry> recent = new ConcurrentLinkedDeque<>();

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
        if (level == Level.ERROR)
            errors.incrementAndGet();
        else if (level == Level.WARN)
            warnings.incrementAndGet();
        else
            return;

        recent.addFirst(new Entry(e.getTimeStamp(), level.toString(), shortName(e.getLoggerName()), message(e)));
        while (recent.size() > MAX_RECENT)
            recent.pollLast();
    }

    public Snapshot snapshot() {
        return new Snapshot(errors.get(), warnings.get(), new ArrayList<>(recent));
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

    public record Snapshot(long errors, long warnings, List<Entry> recent) {
    }

    public record Entry(long t, String level, String logger, String message) {
    }
}
