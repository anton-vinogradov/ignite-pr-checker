package com.github.igniteprchecker.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The settings this instance runs with, by the environment variable that sets each, for the log at startup and the
 * status page. Settings come from yml defaults, the env file and relaxed variable names, and an out-of-range value
 * falls back to its default, so without this list an operator could not tell what a typo did. Secrets show only
 * whether they are set. A value replaced by its default, an empty required value, an empty public URL and a port open
 * on every interface are problems: the service runs, but not as meant.
 */
@Component
public class EffectiveConfig {
    private static final Logger log = LoggerFactory.getLogger(EffectiveConfig.class);

    private final Environment env;

    /** Variable name -> the value in effect, in the order of application.yml. */
    private final Map<String, String> settings = new LinkedHashMap<>();

    private final List<String> problems = new ArrayList<>();

    public EffectiveConfig(Environment env, TeamcityProperties teamcity, GithubProperties github,
        AnalysisProperties analysis, PersistProperties persist, WarmProperties warm, UpdateProperties update,
        AdminProperties admin, SessionProperties session) {
        this.env = env;

        String address = env.getProperty("server.address");
        show("SERVER_ADDRESS", address == null ? "not set (all interfaces)" : address);
        String port = env.getProperty("server.port", "8080");
        show("SERVER_PORT", port);
        if (address == null)
            problem("SERVER_ADDRESS is not set: port " + port + " takes plain HTTP on every network interface — set"
                + " it to 127.0.0.1 behind an HTTPS proxy, or to 0.0.0.0 if that is meant");

        String publicUrl = env.getProperty("app.public-url", "");
        show("APP_PUBLIC_URL", publicUrl.isBlank() ? "empty" : publicUrl);
        if (publicUrl.isBlank())
            problem("APP_PUBLIC_URL is empty: links this instance posts to PRs and JIRA lead nowhere — set it to the"
                + " https address users open");

        required("TC_BASE_URL", teamcity.baseUrl(), "no PR can be analysed");
        show("TEAMCITY_READ_TIMEOUT", duration(teamcity.readTimeout()));

        text("github.repo", "GITHUB_REPO", github.repo());
        show("GITHUB_TOKEN", secret(github.token()));
        number("github.cache-seconds", "GITHUB_CACHE_SECONDS", github.cacheSeconds());
        show("GITHUB_READ_TIMEOUT", duration(github.readTimeout()));
        text("github.api-url", "GITHUB_API_URL", github.apiUrl());

        show("JIRA_BASE_URL", env.getProperty("jira.base-url", ""));
        show("JIRA_READ_TIMEOUT", env.getProperty("jira.read-timeout", ""));

        show("ANALYSIS_BASE_BRANCH", analysis.baseBranch());
        required("TC_RUN_ALL_BUILD_TYPE", analysis.runAllBuildType(), "no RunAll chain can be found");
        number("analysis.history-depth", "MASTER_HISTORY_DEPTH", analysis.historyDepth());
        number("analysis.concurrency", "ANALYSIS_CONCURRENCY", analysis.concurrency());
        number("analysis.cache-ttl-minutes", "ANALYSIS_CACHE_TTL_MINUTES", analysis.cacheTtlMinutes());
        number("analysis.refresh-after-seconds", "ANALYSIS_REFRESH_AFTER_SECONDS", analysis.refreshAfterSeconds());
        number("analysis.blocker-fail-streak", "BLOCKER_FAIL_STREAK", analysis.blockerFailStreak());

        show("PRC_PERSIST_ENABLED", String.valueOf(persist.enabled()));
        text("persist.dir", "PRC_CACHE_DIR", persist.dir());
        number("persist.interval-minutes", "PERSIST_INTERVAL_MINUTES", persist.intervalMinutes());

        show("WARM_ENABLED", String.valueOf(warm.enabled()));
        number("warm.count", "WARM_COUNT", warm.count());
        number("warm.interval-minutes", "WARM_INTERVAL_MINUTES", warm.intervalMinutes());
        number("warm.token-ttl-minutes", "WARM_TOKEN_TTL_MINUTES", warm.tokenTtlMinutes());

        show("AUTOMATION_ENABLED", env.getProperty("automation.enabled", "true"));

        show("UPDATE_ENABLED", String.valueOf(update.enabled()));
        text("update.jar-path", "UPDATE_JAR_PATH", update.jarPath());

        show("PRC_ADMINS", admin.logins().isEmpty() ? "none" : String.join(",", admin.logins()));
        show("SESSION_COOKIE_SECURE", String.valueOf(session.cookieSecure()));
        show("SESSION_SECRET", secret(session.secret()));

        String logFile = env.getProperty("logging.file.name", "");
        show("PRC_LOG_FILE", logFile.isBlank() ? "not set (console only)" : logFile);
    }

    /** Variable name -> the value in effect; secrets read "set" or "not set". */
    public Map<String, String> settings() {
        return settings;
    }

    /** What is set but not as meant, for the operator to fix. */
    public List<String> problems() {
        return problems;
    }

    /** The settings as one line, as the log has them. */
    public String line() {
        return settings.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        log.info("effective config: {}", line());
        problems.forEach(p -> log.warn("config: {}", p));
    }

    private void show(String var, String value) {
        settings.put(var, value);
    }

    private void problem(String text) {
        problems.add(text);
    }

    /** A number the properties replace with their default when it is out of range. */
    private void number(String key, String var, int effective) {
        show(var, String.valueOf(effective));

        String raw = env.getProperty(key);
        if (raw == null)
            return;

        try {
            if (Integer.parseInt(raw.trim()) == effective)
                return;
        }
        catch (NumberFormatException ignored) {
            // binding would have failed the start; anything else that differs was replaced
        }

        problem(var + "=" + raw.trim() + " is out of range; using " + effective);
    }

    /** A text the properties replace with their default when it is empty. */
    private void text(String key, String var, String effective) {
        show(var, effective);

        String raw = env.getProperty(key);
        if (raw != null && raw.isBlank() && !effective.isBlank())
            problem(var + " is empty; using " + effective);
    }

    /** A text that has no fallback for an empty value. */
    private void required(String var, String effective, String consequence) {
        boolean empty = effective == null || effective.isBlank();
        show(var, empty ? "empty" : effective);
        if (empty)
            problem(var + " is empty: " + consequence);
    }

    private static String secret(String value) {
        return value == null || value.isBlank() ? "not set" : "set";
    }

    private static String duration(Duration d) {
        return d.toSeconds() + "s";
    }
}
