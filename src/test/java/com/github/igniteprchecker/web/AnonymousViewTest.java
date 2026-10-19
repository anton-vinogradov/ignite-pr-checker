package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The public /api/status listed the log's warnings word for word, TeamCity logins included, and the public
 * /api/prs said who triggered each PR's run. Anonymous viewers now get counters only. Signed in, anyone read the
 * warnings and who restarted or flushed last, and anyone may register on ci2: only the operators get those now.
 */
class AnonymousViewTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(true, "test-secret"), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final UserDirectory users = new UserDirectory(mapper);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, new Logins(mapper, users),
        mock(StandingVisas.class));

    private final LogTracker logs = new LogTracker(mapper);

    private final AdminActions admin = new AdminActions(new AdminProperties(List.of("avinogradov")), mapper);

    private final CacheStore store = mock(CacheStore.class);

    private final EffectiveConfig config = mock(EffectiveConfig.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StatusController status = statusFor(admin);

    @SuppressWarnings("unchecked")
    private StatusController statusFor(AdminActions admin) {
        return new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer, github, logs,
            new ServiceHealth(warmer, mock(StandingVisas.class), mock(PrCommands.class), store, config),
            mock(RerunTracker.class), mock(StandingVisas.class), mock(PrCommands.class),
            auth, admin, store, config, mock(ObjectProvider.class));
    }

    @BeforeEach
    void start() {
        logs.start();

        LoggingEvent e = new LoggingEvent();
        e.setLevel(Level.WARN);
        e.setLoggerName("com.github.igniteprchecker.jira.StandingVisas");
        e.setMessage("PR 13655 skipped: TeamCity rejected the token of nsamelchev");
        e.setTimeStamp(System.currentTimeMillis());
        logs.doAppend(e);

        admin.claim("avinogradov", AdminActions.Action.FLUSH);
    }

    /** The operator's request. */
    private MockHttpServletRequest signedIn() {
        return signedInAs("avinogradov");
    }

    private MockHttpServletRequest signedInAs(String user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(user, "tok-" + user)));

        return req;
    }

    @Test
    void statusShowsAnonymousViewersCountersOnly() throws Exception {
        Map<String, Object> res = status.status(new MockHttpServletRequest());

        assertThat(mapper.writeValueAsString(res)).doesNotContain("nsamelchev", "avinogradov")
            .contains("\"warnings\":1");
        assertThat(res).containsEntry("operator", false).containsEntry("operatorsNamed", true)
            .doesNotContainKey("admin");
    }

    @Test
    void statusShowsTheOperatorTheMessagesAndWhoActed() throws Exception {
        Map<String, Object> res = status.status(signedIn());

        assertThat(mapper.writeValueAsString(res)).contains("nsamelchev");
        assertThat(res).containsEntry("operator", true).containsEntry("operatorsNamed", true);
        assertThat(res.get("admin")).asString().contains("flush=Use[by=avinogradov");
    }

    /**
     * The operator's status page counted the one-shot Auto visas armed and posted. Visas come only from the standing
     * option now: the operator's view keeps the messages and who acted, and counts the standing options alone.
     */
    @Test
    @SuppressWarnings("unchecked")
    void statusCountsNoOneShotAutoVisasForTheOperator() {
        Map<String, Object> res = status.status(signedIn());

        assertThat(res).containsEntry("operator", true).containsKey("admin");
        assertThat((Map<String, Object>) res.get("watcher")).containsKeys("standingEnrolled", "standingPosted")
            .allSatisfy((key, value) -> assertThat(key).doesNotStartWith("autoVisa"));
    }

    /** Anyone may register on ci2 and log in; the warning names nsamelchev, and the flush its operator. */
    @Test
    void statusShowsOtherSignedInViewersTheCountsOnly() throws Exception {
        Map<String, Object> res = status.status(signedInAs("stranger"));

        assertThat(mapper.writeValueAsString(res)).doesNotContain("nsamelchev", "avinogradov")
            .contains("\"warnings\":1");
        assertThat(res).containsEntry("signedIn", true).containsEntry("operator", false)
            .containsEntry("operatorsNamed", true).doesNotContainKey("admin");
    }

    /** Without PRC_ADMINS nobody is an operator, and the page is told so, to say how to name one. */
    @Test
    void withoutOperatorsNobodyGetsTheMessages() throws Exception {
        Map<String, Object> res = statusFor(new AdminActions(new AdminProperties(List.of()), mapper))
            .status(signedIn());

        assertThat(mapper.writeValueAsString(res)).doesNotContain("nsamelchev").contains("\"warnings\":1");
        assertThat(res).containsEntry("operator", false).containsEntry("operatorsNamed", false)
            .doesNotContainKey("admin");
    }

    /** A full disk: the save's error names the path on the server. */
    @Test
    void statusTellsAnonymousViewersThatSomethingIsWrongButNotWhat() throws Exception {
        String why = "java.nio.file.FileSystemException: /opt/ignite-pr-checker/cache/standing-visas.json.tmp: "
            + "No space left on device";
        when(store.status()).thenReturn(new CacheStore.Status(true, true, null, List.of(),
            Map.of("standing-visas.json", why), "cache-2026-10-07.zip"));
        when(store.summary()).thenReturn(new CacheStore.Summary(true, true, 1, "cache-2026-10-07.zip"));

        Map<String, Object> anonymous = status.status(new MockHttpServletRequest());

        assertThat(mapper.writeValueAsString(anonymous)).doesNotContain("/opt/ignite-pr-checker", "No space left");
        assertThat(anonymous.get("health")).isEqualTo("error");
        assertThat(anonymous.get("healthProblems")).isEqualTo(List.of(new ServiceHealth.Problem("error", null)));
        assertThat(anonymous.get("persistence"))
            .isEqualTo(new CacheStore.Summary(true, true, 1, "cache-2026-10-07.zip"));
        assertThat(mapper.writeValueAsString(status.status(signedIn()))).contains(why);
    }

    /** The settings name paths on the server, and to the operator, the operators. */
    @Test
    void statusShowsTheSettingsToSignedInViewersOnly() throws Exception {
        when(config.settings()).thenReturn(Map.of("PRC_CACHE_DIR", "/opt/ignite-pr-checker/cache",
            "PRC_ADMINS", "avinogradov"));

        assertThat(status.status(new MockHttpServletRequest())).doesNotContainKey("config");
        assertThat(status.status(signedIn())).containsEntry("config", Map.of("PRC_CACHE_DIR",
            "/opt/ignite-pr-checker/cache", "PRC_ADMINS", "avinogradov"));
    }

    /** Anyone may register on ci2: the operators' names would tell a stranger whose login opens Restart and Update. */
    @Test
    void statusCountsTheOperatorsToOtherSignedInViewers() throws Exception {
        when(config.settings()).thenReturn(Map.of("PRC_ADMINS", "avinogradov"));
        when(config.settingsWithoutOperatorNames()).thenReturn(Map.of("PRC_ADMINS", "1 name, shown to operators only"));

        Map<String, Object> res = status.status(signedInAs("stranger"));

        assertThat(mapper.writeValueAsString(res)).doesNotContain("avinogradov");
        assertThat(res).containsEntry("config", Map.of("PRC_ADMINS", "1 name, shown to operators only"));
    }

    /** Whether the server's GitHub token can push to apache/ignite says how much it is worth to whoever reads it. */
    @Test
    void statusTellsWhetherTheAppAccountCanPushToSignedInViewersOnly() throws Exception {
        when(github.appAccount()).thenReturn(new GithubClient.AppAccount("ok", "ignite-pr-checker-bot", true));

        assertThat(mapper.writeValueAsString(status.status(new MockHttpServletRequest())))
            .contains("\"login\":\"ignite-pr-checker-bot\"").doesNotContain("\"canPush\":true");
        assertThat(mapper.writeValueAsString(status.status(signedIn()))).contains("\"canPush\":true");
    }

    /** What GitHub said refusing a write, and whose writes reached a cap, are for signed-in viewers. */
    @Test
    void statusTellsAnonymousViewersThatWritesFailButNotWhyOrWhose() throws Exception {
        when(github.writeState()).thenReturn(new GithubClient.WriteState(0, "GitHub answered 403 to a write: blocked",
            1L, List.of("PR 13800", "user nsamelchev")));

        String anonymous = mapper.writeValueAsString(status.status(new MockHttpServletRequest()));

        assertThat(anonymous).contains("\"githubWrites\"", "\"refusedAt\":1", "PR 13800", "a user")
            .doesNotContain("blocked", "user nsamelchev");
        assertThat(mapper.writeValueAsString(status.status(signedIn()))).contains("blocked", "user nsamelchev");
    }

    /** What the check of the server's token found says as much of its worth: anyone sees how bad, not what. */
    @Test
    void statusShowsTheChecksOfTheAppTokenToSignedInViewersOnly() throws Exception {
        when(github.appAccount()).thenReturn(new GithubClient.AppAccount("ok", "ignite-pr-checker", false, 234567L,
            List.of(new GithubClient.AppAccount.Check("warn", "GITHUB_TOKEN has scopes beyond public_repo (repo)"))));

        assertThat(mapper.writeValueAsString(status.status(new MockHttpServletRequest())))
            .contains("\"checks\":[{\"level\":\"warn\",\"text\":null}]").doesNotContain("scopes beyond");
        assertThat(mapper.writeValueAsString(status.status(signedIn()))).contains("scopes beyond public_repo (repo)");
    }

    /**
     * The card and the problem of the server's GitHub token named the day it expires and the days it had left to every
     * signed-in viewer. The operators replace the token, so only they get the day; the others, whether it is two weeks
     * away or less.
     */
    @Test
    @SuppressWarnings("unchecked")
    void statusNamesTheDayTheAppTokenExpiresToTheOperatorsOnly() throws Exception {
        when(github.appAccount()).thenReturn(new GithubClient.AppAccount("ok", "ignite-pr-checker", false, 234567L,
            List.of(new GithubClient.AppAccount.Check("warn", "GITHUB_TOKEN expires on 2026-10-28, in 9 days: make a"
                + " new one", "GITHUB_TOKEN expires in 14 days or less: an operator has to replace it"))));
        StatusController checked = new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer, github,
            logs, new ServiceHealth(warmer, mock(StandingVisas.class), mock(PrCommands.class), store, config, github),
            mock(RerunTracker.class), mock(StandingVisas.class), mock(PrCommands.class), auth, admin, store, config,
            mock(ObjectProvider.class));

        Map<String, Object> operator = checked.status(signedIn());
        GithubClient.AppAccount card = (GithubClient.AppAccount)((Map<String, Object>)operator.get("app"))
            .get("githubAccount");
        String stranger = mapper.writeValueAsString(checked.status(signedInAs("stranger")));

        assertThat(card.checks()).extracting(GithubClient.AppAccount.Check::text)
            .containsExactly("GITHUB_TOKEN expires on 2026-10-28, in 9 days: make a new one");
        assertThat((List<ServiceHealth.Problem>)operator.get("healthProblems")).extracting(ServiceHealth.Problem::text)
            .containsExactly("GITHUB_TOKEN expires on 2026-10-28, in 9 days: make a new one");
        assertThat(mapper.writeValueAsString(operator)).doesNotContain("dateless");
        assertThat(stranger).doesNotContain("2026-10-28", "9 days", "dateless")
            .contains("\"checks\":[{\"level\":\"warn\",\"text\":\"GITHUB_TOKEN expires in 14 days or less: an"
                + " operator has to replace it\"}]");
        assertThat((List<ServiceHealth.Problem>)checked.status(signedInAs("stranger")).get("healthProblems"))
            .containsExactly(new ServiceHealth.Problem("warn", "GITHUB_TOKEN expires in 14 days or less: an operator"
                + " has to replace it"));
        assertThat(mapper.writeValueAsString(checked.status(new MockHttpServletRequest())))
            .doesNotContain("GITHUB_TOKEN expires");
    }

    @Test
    void prListNamesWhoTriggeredARunToSignedInViewersOnly() {
        GithubClient github = mock(GithubClient.class);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13655, "IGNITE-1 x", "u", null, null, null)));
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.triggeredBy(13655)).thenReturn("nsamelchev");
        PrsController prs = new PrsController(github, analyzer, auth);

        assertThat(prs.prs(new MockHttpServletRequest())).singleElement()
            .extracting(PrSummary::triggeredBy).isNull();
        assertThat(prs.prs(signedIn())).singleElement()
            .extracting(PrSummary::triggeredBy).isEqualTo("nsamelchev");
    }
}
