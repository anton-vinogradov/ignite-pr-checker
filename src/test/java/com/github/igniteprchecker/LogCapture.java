package com.github.igniteprchecker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Everything logged while it is open, at the levels an operator might turn on, with the levels application.yml
 * sets for single loggers kept as the service keeps them. Closing puts the levels back.
 */
public final class LogCapture implements AutoCloseable {
    private static final String LEVEL = "logging.level.";

    private final LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();

    private final Map<String, Level> before = new HashMap<>();

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    /** Captures with {@code levels} by logger name, under the levels application.yml names. */
    public LogCapture(Map<String, Level> levels) {
        levels.forEach(this::set);
        Properties yml = yml();
        for (String key : yml.stringPropertyNames()) {
            if (key.startsWith(LEVEL))
                set(key.substring(LEVEL.length()), Level.toLevel(yml.getProperty(key)));
        }
        events.start();
        root().addAppender(events);
    }

    /** Every event so far: its logger, level and text, and the stack of its exception, causes included. */
    public String text() {
        return events.list.stream().map(e -> e.getLoggerName() + " " + e.getLevel() + " " + e.getFormattedMessage()
            + (e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy())))
            .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        root().detachAppender(events);
        events.stop();
        before.forEach((name, level) -> context.getLogger(name).setLevel(level));
    }

    private void set(String name, Level level) {
        Logger logger = context.getLogger(name);
        if (!before.containsKey(name))
            before.put(name, logger.getLevel());
        logger.setLevel(level);
    }

    private Logger root() {
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static Properties yml() {
        YamlPropertiesFactoryBean yml = new YamlPropertiesFactoryBean();
        yml.setResources(new FileSystemResource("src/main/resources/application.yml"));

        return yml.getObject();
    }
}
