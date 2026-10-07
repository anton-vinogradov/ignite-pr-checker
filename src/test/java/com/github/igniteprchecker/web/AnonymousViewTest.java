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
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaSubscriptions;
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
 * /api/prs said who triggered each PR's run. Anonymous viewers now get counters only.
 */
class AnonymousViewTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(true, "test-secret"), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, new UserDirectory(mapper), mock(StandingVisas.class));

    private final LogTracker logs = new LogTracker(mapper);

    private final AdminActions admin = new AdminActions(new AdminProperties(List.of("avinogradov")), mapper);

    private final CacheStore store = mock(CacheStore.class);

    @SuppressWarnings("unchecked")
    private final StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class),
        warmer, mock(GithubClient.class), logs,
        new ServiceHealth(warmer, mock(StandingVisas.class), mock(PrCommands.class), store),
        mock(RerunTracker.class), mock(VisaSubscriptions.class), mock(StandingVisas.class), mock(PrCommands.class),
        auth, admin, store, mock(ObjectProvider.class));

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

    private MockHttpServletRequest signedIn() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode("avinogradov", "tok")));

        return req;
    }

    @Test
    void statusShowsAnonymousViewersCountersOnly() throws Exception {
        String json = mapper.writeValueAsString(status.status(new MockHttpServletRequest()));

        assertThat(json).doesNotContain("nsamelchev", "avinogradov").contains("\"warnings\":1");
    }

    @Test
    void statusShowsSignedInViewersTheMessagesAndWhoActed() throws Exception {
        Map<String, Object> res = status.status(signedIn());

        assertThat(mapper.writeValueAsString(res)).contains("nsamelchev");
        assertThat(res.get("admin")).asString().contains("canAdminister=true", "flush=Use[by=avinogradov");
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
