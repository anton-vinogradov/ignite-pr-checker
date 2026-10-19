package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator changed SESSION_SECRET after a leak and asked the users to save new tokens. Nothing told them who had
 * not done it yet. The status page now shows the operator how many users' options still wait for new tokens.
 */
class KeyChangeStatusTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final Warmer warmer = mock(Warmer.class);

    private final UserDirectory users = new UserDirectory(mapper);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final CacheStore store = mock(CacheStore.class);

    private final EffectiveConfig config = mock(EffectiveConfig.class);

    private final StatusController status = status(List.of("avinogradov"));

    @Test
    void theOperatorSeesHowManyUsersWaitForNewTokens() {
        when(standing.waitingAfterKeyChange()).thenReturn(2);

        assertThat(watcher(status, signedIn("avinogradov"))).containsEntry("waitingAfterKeyChange", 2);
        assertThat(watcher(status, signedIn("nizhikov"))).doesNotContainKey("waitingAfterKeyChange");
        assertThat(watcher(status, new MockHttpServletRequest())).doesNotContainKey("waitingAfterKeyChange");
    }

    /**
     * The count names no user, but it is the operator's to act on, like the log: with no operator named in PRC_ADMINS,
     * the server sends it to nobody.
     */
    @Test
    void withoutOperatorsNobodyGetsTheCount() {
        when(standing.waitingAfterKeyChange()).thenReturn(2);
        StatusController noOperators = status(List.of());

        assertThat(watcher(noOperators, signedIn("avinogradov"))).doesNotContainKey("waitingAfterKeyChange");
        assertThat(watcher(noOperators, new MockHttpServletRequest())).doesNotContainKey("waitingAfterKeyChange");
    }

    @Test
    void theCardShowsOnlyWhileSomeoneWaits() throws Exception {
        JsonNode two = cards(2);

        assertThat(two.get("text").asText()).contains("New tokens wanted 2 users whose options wait");
        assertThat(two.get("html").asText()).contains("title=\"The server key was changed, so the checker could no"
            + " longer read the tokens it stored. The options of these users wait until they log in again and save"
            + " the tokens ⚙ asks them for.\"");
        assertThat(cards(0).get("html").asText()).doesNotContain("New tokens wanted");
    }

    /** The watcher's cards of the status page when {@code waiting} users wait for new tokens. */
    private static JsonNode cards(int waiting) throws Exception {
        return PageScript.run("status.html", """
            page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
            const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
            page.route('/api/status', { body: { version: '1.38.0', signedIn: true, uptimeSeconds: 60,
                startedAt: page.now() - 60000, health: 'ok', logHealth: 'ok', healthProblems: [],
                teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
                jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 }, app: { openPrs: 3, pooledTokens: 1 },
                log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] },
                watcher: { waitingAfterKeyChange: %d } } });
            await page.load('');
            const html = page.el('watcherCards').innerHTML;
            report({ html, text: html.replace(/<[^>]*>/g, ' ').replace(/\\s+/g, ' ') });
            """.formatted(waiting));
    }

    /** The status page of a server whose PRC_ADMINS names the {@code operators}. */
    @SuppressWarnings("unchecked")
    private StatusController status(List<String> operators) {
        return new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer, mock(GithubClient.class),
            new LogTracker(mapper), new ServiceHealth(warmer, standing, mock(PrCommands.class), store, config),
            mock(RerunTracker.class), standing, mock(PrCommands.class),
            new AuthInterceptor(codec, warmer, users, new Logins(mapper, users), standing),
            new AdminActions(new AdminProperties(operators), mapper), store, config, mock(ObjectProvider.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> watcher(StatusController status, MockHttpServletRequest req) {
        return (Map<String, Object>)status.status(req).get("watcher");
    }

    private MockHttpServletRequest signedIn(String user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(user, user + "-tc")));

        return req;
    }
}
