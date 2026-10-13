package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.suitefix.SuiteFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Adding PRs' tests to suites works only with the operator's Anthropic API key. Without it the switch in ⚙ is greyed
 * out with a note saying why, the server refuses it with that reason rather than asking for a token, and the status
 * page says the option is off; with it, the switch works like the others.
 */
class SuiteFixSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class),
        mock(GithubClient.class), mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        mock(BlockerAnalyzer.class), new SessionCodec(new SessionProperties(false, "test-secret"), mapper),
        mock(VisaService.class), mock(VisaSubscriptions.class), standing, mock(GithubClient.class),
        mock(PendingCommits.class), false);

    @Test
    void withoutTheKeyTheServerRefusesItWithTheReason() {
        ResponseEntity<?> res = controller.standingVisa(null, null, null, null, null, true, "tc", "alice", null,
            "gh-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isEqualTo(Map.of("error",
            "this checker has no Anthropic API key, so it can't pick suites: ask its operator"));
        assertThat(mapper.convertValue(controller.standingVisaStatus("alice"), Map.class))
            .containsEntry("suites", false).containsEntry("suitesAvailable", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStatusPageGetsWhatTheOptionDoes() {
        SuiteFixService suiteFix = mock(SuiteFixService.class);
        when(suiteFix.stats()).thenReturn(Map.of("available", false, "users", 0, "attempts", 0, "pushed", 0,
            "lastSweepAt", 0L));
        Warmer warmer = mock(Warmer.class);
        StandingVisas standingMock = mock(StandingVisas.class);
        PrCommands commands = mock(PrCommands.class);
        CacheStore store = mock(CacheStore.class);
        StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer,
            mock(GithubClient.class), new LogTracker(mapper),
            new ServiceHealth(warmer, standingMock, commands, store, mock(EffectiveConfig.class)),
            mock(RerunTracker.class), mock(VisaSubscriptions.class), standingMock, commands,
            mock(AuthInterceptor.class), mock(AdminActions.class), store, mock(EffectiveConfig.class),
            mock(ObjectProvider.class), suiteFix);

        Map<String, Object> watcher = (Map<String, Object>)status.status(new MockHttpServletRequest()).get("watcher");

        assertThat(watcher.get("suiteFix")).isEqualTo(suiteFix.stats());
    }

    @Test
    void theStatusCardSaysWhenTheOptionIsOff() throws Exception {
        JsonNode cards = PageFunctions.run("status.js", """
            const ago = ms => ms ? '2m ago' : 'never';
            out({ off: suiteFixCard({ available: false, users: 0, pushed: 0 }, ago),
                on: suiteFixCard({ available: true, users: 2, pushed: 3, lastSweepAt: 1 }, ago),
                old: suiteFixCard(undefined, ago) });
            """, "card", "suiteFixCard");

        assertThat(cards.get("off").asText()).contains("Suite fixes", ">off <small>no ANTHROPIC_API_KEY: the option"
            + " is not offered</small>");
        assertThat(cards.get("on").asText()).contains(">3 <small>commits · 2 users · sweep 2m ago</small>");
        assertThat(cards.get("old").asText()).isEmpty();
    }

    @Test
    void withoutTheKeyTheSwitchIsGreyedOutAndSaysWhy() throws Exception {
        JsonNode page = PageScript.run("index.html", PageScript.SIGNED_IN + settings(false) + """
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ disabled: page.el('suiteFixToggle').disabled,
                noteShown: !page.el('suitesOff').classList.contains('hidden'),
                note: page.el('suitesOff').textContent, styleDisabled: page.el('styleFixToggle').disabled });
            """);

        assertThat(page.get("disabled").asBoolean()).isTrue();
        assertThat(page.get("noteShown").asBoolean()).isTrue();
        assertThat(page.get("styleDisabled").asBoolean()).isFalse();
    }

    @Test
    void withTheKeyTheSwitchPostsItsOwnFlagOnly() throws Exception {
        JsonNode page = PageScript.run("index.html", PageScript.SIGNED_IN + settings(true) + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { suites: true }) }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            const before = { disabled: page.el('suiteFixToggle').disabled,
                noteShown: !page.el('suitesOff').classList.contains('hidden') };
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            report({ before, posts: page.fetches('/api/auto-visa-all?').map(f => f.url),
                checked: page.el('suiteFixToggle').checked, msg: page.el('settingsMsg').textContent });
            """);

        assertThat(page.at("/before/disabled").asBoolean()).isFalse();
        assertThat(page.at("/before/noteShown").asBoolean()).isFalse();
        assertThat(page.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?suites=true\"]");
        assertThat(page.get("checked").asBoolean()).isTrue();
        assertThat(page.get("msg").asText()).isEqualTo("On: checkstyle autofix · tests to suites.");
    }

    /** The settings the server answers: the checkstyle autofix on, on a stored GitHub token. */
    private static String settings(boolean available) {
        return "const SETTINGS = { visa: false, rerun: false, gh: false, style: true, commands: false, login: 'alice-v',"
            + " jiraStored: false, ghStored: true, jiraTokenRejected: false, ghTokenRejected: false,"
            + " tcTokenRejected: false, suites: false, suitesAvailable: " + available + " };\n"
            + "page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');\n";
    }
}
