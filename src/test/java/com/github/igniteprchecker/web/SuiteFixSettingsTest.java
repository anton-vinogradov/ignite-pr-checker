package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.suitefix.SuiteFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Adding PRs' tests to suites takes the user's GitHub token and nothing of the operator's: the suite is picked by a
 * rule in the checker. The switch in ⚙ works like the others, the server takes it with the token, and the status page
 * counts what the option did.
 */
class SuiteFixSettingsTest {
    /** The card and what it needs to say when the sweep ran. */
    private static final String[] CARD_FUNCTIONS = {"card", "spanShort", "firstPassState", "lateTitle", "passText",
        "suiteFixCard"};

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = AppWriting.github();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class),
        github, mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        standing, mock(MergedVerdicts.class), mock(UserDirectory.class));

    @Test
    void theServerTakesItWithTheGithubToken() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("alice-v"),
            null)));

        ResponseEntity<?> res = controller.standingVisa(null, null, null, null, null, true, "tc", "alice", null,
            "gh-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.convertValue(controller.standingVisaStatus("alice"), Map.class))
            .containsEntry("suites", true).containsEntry("ghStored", true).doesNotContainKey("suitesAvailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStatusPageGetsWhatTheOptionDoes() {
        SuiteFixService suiteFix = mock(SuiteFixService.class);
        when(suiteFix.stats()).thenReturn(Map.of("users", 0, "attempts", 0, "pushed", 0, "lastSweepAt", 0L));
        Warmer warmer = mock(Warmer.class);
        StandingVisas standingMock = mock(StandingVisas.class);
        PrCommands commands = mock(PrCommands.class);
        CacheStore store = mock(CacheStore.class);
        StatusController status = new StatusController(mock(Metrics.class), mock(AnalysisCache.class), warmer,
            mock(GithubClient.class), new LogTracker(mapper),
            new ServiceHealth(warmer, standingMock, commands, store, mock(EffectiveConfig.class)),
            mock(RerunTracker.class), standingMock, commands,
            mock(AuthInterceptor.class), mock(AdminActions.class), store, mock(EffectiveConfig.class),
            mock(ObjectProvider.class), suiteFix);

        Map<String, Object> watcher = (Map<String, Object>)status.status(new MockHttpServletRequest()).get("watcher");

        assertThat((Map<String, Object>)watcher.get("suiteFix")).containsAllEntriesOf(suiteFix.stats())
            .containsEntry("firstSweepAt", 0L);
    }

    @Test
    void theStatusCardCountsWhatTheOptionDid() throws Exception {
        JsonNode cards = PageFunctions.run("status.js", """
            out({ idle: suiteFixCard({ users: 0, attempts: 0, pushed: 0, lastSweepAt: 0 }),
                busy: suiteFixCard({ users: 2, attempts: 4, pushed: 3, lastSweepAt: Date.now() - 120000 }),
                old: suiteFixCard(undefined) });
            """, CARD_FUNCTIONS);

        assertThat(cards.get("idle").asText()).contains("Tests added to suites");
        assertThat(cards.get("idle").asText().replaceAll("<[^>]+>", "").replace('\u00a0', ' '))
            .contains("0 commits · 0 users with the option on · sweep never");
        assertThat(cards.get("busy").asText().replaceAll("<[^>]+>", "").replace('\u00a0', ' '))
            .contains("3 commits · 2 users with the option on · sweep 2m ago");
        assertThat(cards.get("old").asText()).isEmpty();
    }

    /** The card said nothing of the users with the option on whom the last sweep did not act for. */
    @Test
    void theStatusCardSaysHowManyArePaused() throws Exception {
        JsonNode cards = PageFunctions.run("status.js", """
            const swept = Date.now() - 120000;
            out({ paused: suiteFixCard({ users: 3, acting: 2, pushed: 1, lastSweepAt: swept }),
                acting: suiteFixCard({ users: 2, acting: 2, pushed: 1, lastSweepAt: swept }),
                starting: suiteFixCard({ users: 1, acting: 0, pushed: 0, lastSweepAt: 0 }) });
            """, CARD_FUNCTIONS);

        assertThat(cards.get("paused").asText()).contains("class=\"v warn\"",
            "title=\"The last sweep did not act for one user with the option on. The reason is one of these:"
                + " TeamCity or GitHub refuses their token, a GitHub account or token is missing, or they switched the"
                + " option on after the sweep.\"");
        assertThat(cards.get("paused").asText().replaceAll("<[^>]+>", "").replace('\u00a0', ' '))
            .contains("1 commit · 3 users with the option on · 1 paused · sweep 2m ago");
        assertThat(cards.get("acting").asText()).doesNotContain("paused", "warn", "title=");
        assertThat(cards.get("starting").asText().replaceAll("<[^>]+>", "").replace('\u00a0', ' '))
            .contains("0 commits · 1 user with the option on · sweep never").doesNotContain("paused");
    }

    @Test
    void theSwitchPostsItsOwnFlagOnly() throws Exception {
        JsonNode page = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const SETTINGS = { visa: false, rerun: false, gh: false, style: true, commands: false, login: 'alice-v',
                jiraStored: false, ghStored: true, jiraTokenRejected: false, ghTokenRejected: false,
                tcTokenRejected: false, suites: false };
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { suites: true }) }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            const disabled = page.el('suiteFixToggle').disabled;
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            report({ disabled, posts: page.fetches('/api/auto-visa-all?').map(f => f.url),
                checked: page.el('suiteFixToggle').checked, msg: page.el('settingsMsg').textContent });
            """);

        assertThat(page.get("disabled").asBoolean()).isFalse();
        assertThat(page.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?suites=true&tz=UTC\"]");
        assertThat(page.get("checked").asBoolean()).isTrue();
        assertThat(page.get("msg").asText()).isEqualTo("On: checkstyle autofix · tests to suites.");
    }

    @Test
    void theSwitchSaysHowTheSuiteIsPicked() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        String label = html.substring(html.indexOf("id=\"suiteFixToggle\""), html.indexOf("</label>",
            html.indexOf("id=\"suiteFixToggle\""))).replaceAll("\\s+", " ");

        assertThat(label).contains("into the suite of the nearest class in its module").doesNotContain("Claude",
            "Anthropic");
        assertThat(Files.readString(Path.of("docs/features.md"), StandardCharsets.UTF_8).replaceAll("\\s+", " "))
            .contains("each goes next to the class of its module nearest to it: a class of the same package first")
            .doesNotContain("Claude", "Anthropic");
    }
}
