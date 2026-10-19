package com.github.igniteprchecker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.persist.CacheStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * The texts the server writes for the status page, made by its own code: the problems of the service's health, what
 * the checks of GITHUB_TOKEN found and why an operator's action was refused. The code that makes the first two is
 * package-private, so this reaches it by reflection.
 * The settings in effect and their problems, which EffectiveConfig reads from the environment, are written out here as
 * it writes them.
 */
final class StatusTexts {
    /** The moment of the scenarios. */
    static final long NOW = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli();

    private static final long MINUTE = 60_000;

    /** What EffectiveConfig finds wrong with settings, one of each kind. */
    static final List<String> CONFIG_PROBLEMS = List.of(
        "SERVER_ADDRESS is not set: port 8080 takes plain HTTP on every network interface — set it to 127.0.0.1 behind"
            + " an HTTPS proxy, or to 0.0.0.0 if that is meant",
        "APP_PUBLIC_URL is empty: links this instance posts to PRs and JIRA lead nowhere — set it to the https address"
            + " users open",
        "APP_PUBLIC_URL is empty: links this instance posts to PRs and JIRA lead nowhere, and Link GitHub is off — set"
            + " it to the https address users open",
        "GITHUB_OAUTH_CLIENT_SECRET is not set: users cannot link their GitHub accounts with Link GitHub — set both, or"
            + " neither",
        "TC_BASE_URL is empty: no PR can be analysed",
        "TC_RUN_ALL_BUILD_TYPE is empty: no RunAll chain can be found",
        "GITHUB_REPO is empty; using apache/ignite",
        "ANALYSIS_CONCURRENCY=0 is out of range; using 8");

    /** The settings in effect as EffectiveConfig shows them, each kind of value once. */
    static final Map<String, String> SETTINGS = Map.of("SERVER_ADDRESS", "not set (all interfaces)",
        "SERVER_PORT", "8080", "APP_PUBLIC_URL", "empty", "GITHUB_TOKEN", "set",
        "GITHUB_OAUTH_CLIENT_SECRET", "not set", "PRC_ADMINS", "avinogradov,alice",
        "PRC_LOG_FILE", "not set (console only)", "TEAMCITY_READ_TIMEOUT", "30s");

    /** The words EffectiveConfig shows for a setting with no value of its own. */
    static final List<String> SETTING_WORDS = List.of("not set (all interfaces)", "not set (console only)", "not set",
        "set", "empty", "none");

    /** The settings for a viewer who is not an operator: how many operators there are, not who. */
    static final List<String> OPERATORS_UNNAMED = List.of("1 name, shown to operators only",
        "2 names, shown to operators only");

    /** Why snapshots are off. */
    static final List<String> SNAPSHOTS_OFF = List.of("disabled (persist.enabled=false)",
        "directory /var/cache/ignite-pr-checker is not writable");

    /** What GitHub answered a write of the app account that it refused for another reason than its limits. */
    static final String WRITE_REFUSED = "GitHub answered 403 to a write: Forbidden";

    private StatusTexts() {
    }

    /** The problems of a service whose jobs stopped, whose state is not saved and whose GitHub account fails. */
    static List<ServiceHealth.Problem> problems() throws Exception {
        CacheStore.Status persistence = new CacheStore.Status(true, false, SNAPSHOTS_OFF.get(1),
            List.of(new CacheStore.Unreadable("pr-commands.json", "pr-commands.json.unreadable-20261007-115900")),
            Map.of("users.json", "java.nio.file.AccessDeniedException: /var/cache/ignite-pr-checker/users.json"),
            null);
        GithubClient.WriteState writes = new GithubClient.WriteState(NOW + 20 * MINUTE, WRITE_REFUSED, NOW - MINUTE,
            List.of("PR 13575", "user alice"));
        List<ServiceHealth.Problem> out = new ArrayList<>(assess(clocks(NOW - 40 * MINUTE, NOW - 30 * MINUTE, 0, true,
            NOW - 20 * MINUTE, 10, 9, 0, 3), persistence, CONFIG_PROBLEMS, account(checks()), writes).problems());
        out.addAll(assess(clocks(NOW - 40 * MINUTE, 0, NOW - 6 * MINUTE, false, 0, 0, 0, 0, 1),
            new CacheStore.Status(true, false, SNAPSHOTS_OFF.get(0), List.of(), Map.of(), null), List.of(),
            new GithubClient.AppAccount("refused", null, null), null).problems());

        return out;
    }

    /** The problems as a viewer who is not an operator reads them: without the day GITHUB_TOKEN expires. */
    static List<ServiceHealth.Problem> problemsWithoutDates() throws Exception {
        return new ServiceHealth.Report("error", "ok", problems()).withoutDates();
    }

    /** What the checks of GITHUB_TOKEN find of several tokens and accounts. */
    static List<GithubClient.AppAccount.Check> checks() throws Exception {
        String classic = "ghp_0123456789abcdefghijABCDEFGHIJ012345";
        List<GithubClient.AppAccount.Check> out = new ArrayList<>();
        out.addAll(of("github_pat_11ABCDEFG0123456789_abcdefghij", headers(null, null), true, null));
        out.addAll(of(classic, headers(null, "2026-10-07 18:00:00 UTC"), false, false));
        out.addAll(of(classic, headers("gist", "2026-10-08 13:00:00 UTC"), false, true));
        out.addAll(of(classic, headers("", "2026-10-17 12:00:00 UTC"), false, false));
        out.addAll(of(classic, headers("public_repo, repo, workflow", "2026-12-06 12:00:00 UTC"), false, false));
        out.addAll(of(classic, headers("public_repo", "soon"), false, false));

        return out;
    }

    /** The checks as a viewer who is not an operator reads them. */
    static List<GithubClient.AppAccount.Check> checksWithoutDates() throws Exception {
        return account(checks()).withoutDates().checks();
    }

    /** What the server answers a user who is not an operator and tries this action. */
    static String refusal(AdminActions.Action action) {
        return new AdminActions(new AdminProperties(null), new ObjectMapper()).claim("alice", action).orElseThrow()
            .message();
    }

    private static GithubClient.AppAccount account(List<GithubClient.AppAccount.Check> checks) {
        return new GithubClient.AppAccount("ok", "ignite-pr-checker", false, 1L, checks);
    }

    private static Object clocks(long startedAt, long lastSweepAt, long lastPollAt, boolean warming,
        long warmStartedAt, int warmTried, int warmFailed, int pooledTokens, int enrolled) throws Exception {
        Constructor<?> make = Class.forName("com.github.igniteprchecker.health.ServiceHealth$Clocks")
            .getDeclaredConstructors()[0];
        make.setAccessible(true);

        return make.newInstance(startedAt, lastSweepAt, lastPollAt, warming, warmStartedAt, warmTried, warmFailed,
            pooledTokens, enrolled);
    }

    private static ServiceHealth.Report assess(Object clocks, CacheStore.Status persistence, List<String> config,
        GithubClient.AppAccount app, GithubClient.WriteState writes) throws Exception {
        Method assess = ServiceHealth.class.getDeclaredMethod("assess", LogTracker.Snapshot.class, clocks.getClass(),
            CacheStore.Status.class, List.class, GithubClient.AppAccount.class, GithubClient.WriteState.class,
            long.class);
        assess.setAccessible(true);

        return (ServiceHealth.Report)assess.invoke(null, new LogTracker.Snapshot(0, 0, 0, 0, 0, List.of()), clocks,
            persistence, config, app, writes, NOW);
    }

    @SuppressWarnings("unchecked")
    private static List<GithubClient.AppAccount.Check> of(String token, HttpHeaders user, Boolean canPush,
        Boolean canPushSelf) throws Exception {
        Method of = Class.forName("com.github.igniteprchecker.github.AppAccountChecks").getDeclaredMethod("of",
            String.class, HttpHeaders.class, String.class, Boolean.class, Boolean.class, long.class);
        of.setAccessible(true);

        return (List<GithubClient.AppAccount.Check>)of.invoke(null, token, user, "apache/ignite", canPush, canPushSelf,
            NOW);
    }

    private static HttpHeaders headers(String scopes, String expiration) {
        HttpHeaders h = new HttpHeaders();
        if (scopes != null)
            h.add("X-OAuth-Scopes", scopes);
        if (expiration != null)
            h.add("github-authentication-token-expiration", expiration);

        return h;
    }
}
