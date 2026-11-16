package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Settings reuse their forms and APIs on a separate screen, with the originating project and PR in the address. */
class SettingsPageTest {
    /**
     * Given a signed-in user, when settings open directly or from a project's home or PR,
     * then only the settings screen loads and its return link keeps that context.
     */
    @ParameterizedTest
    @CsvSource({"/settings,/ignite", "/settings?repo=ignite,/ignite", "/settings?repo=ignite&pr=42,/ignite?pr=42",
        "/settings?repo=python,/python", "/settings?repo=python&pr=42,/python?pr=42",
        "/settings?repo=php,/php", "/settings?repo=php&pr=42,/php?pr=42",
        "/settings?repo=nodejs,/nodejs", "/settings?repo=nodejs&pr=42,/nodejs?pr=42",
        "/settings?repo=extensions,/extensions", "/settings?repo=extensions&pr=42,/extensions?pr=42"})
    void settingsLoadWithoutPrWorkAndKeepTheReturnAddress(String address, String back) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', lang: 'en', githubLinked: true } });
            page.route('/api/auto-visa-all', { body: { login: 'alice-gh', commands: true, githubLinkable: true } });
            await page.load('%s');
            await page.tick(120000);
            const prApis = ['prs', 'your-prs', 'analyze', 'runs', 'settling', 'visa', 'progress', 'pending', 'delta', 'reruns'];
            report({ settings: !page.el('settingsPanel').classList.contains('hidden'),
                prsHidden: page.el('analyzeView').classList.contains('hidden'),
                projectsHidden: page.el('projectTabs').classList.contains('hidden'),
                selectedPr: page.run('selectedPr'), back: page.el('settingsBack').href,
                settingsReads: page.fetches('/api/auto-visa-all').length,
                prWork: page.fetches('/api/').filter(f => f.url.startsWith('/api/client/')
                    || prApis.includes(f.url.split('?')[0].slice(5))).map(f => f.url) });
            """.formatted(address));

        assertThat(out.get("settings").asBoolean()).isTrue();
        assertThat(out.get("prsHidden").asBoolean()).isTrue();
        assertThat(out.get("projectsHidden").asBoolean()).isTrue();
        assertThat(out.get("selectedPr").isNull()).isTrue();
        assertThat(out.get("back").asText()).isEqualTo(back);
        assertThat(out.get("settingsReads").asInt()).isEqualTo(1);
        assertThat(out.get("prWork").toString()).isEqualTo("[]");
    }

    /** Given an open PR, when its menu is rendered, then Settings is a real link preserving the project and PR. */
    @ParameterizedTest
    @ValueSource(strings = {"ignite", "python", "php", "nodejs", "extensions"})
    void theSettingsMenuIsAProjectAwarePageLink(String project) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { status: 404, body: { noRun: true } });
            page.route('/api/client/prs', { body: [] });
            page.route('/api/client/pr-title', { body: { title: 'Preview PR' } });
            page.route('/api/client/analyze', { status: 404, body: { noRun: true } });
            page.route('/api/client/runs', { body: [] });
            await page.load('/%s?pr=42');
            report({ tag: page.el('settingsBtn').tagName, href: page.el('settingsBtn').href,
                inline: !page.el('settingsPanel').classList.contains('hidden') });
            """.formatted(project));

        assertThat(out.get("tag").asText()).isEqualTo("A");
        assertThat(out.get("href").asText()).isEqualTo("/settings?repo=" + project + "&pr=42");
        assertThat(out.get("inline").asBoolean()).isFalse();
    }

    /**
     * Given no session, an ended session or a revoked token, when the user signs in on settings,
     * then its login explains the event and returns to settings with the original project and PR.
     */
    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "ended", "revoked"})
    void signingInOnSettingsPreservesItsDestination(String event) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const event = '%s';
            const refusal = event === 'anonymous' ? {} : { error: 'Preview session ended',
                sessionEnded: event === 'ended', tokenRejected: event === 'revoked' };
            page.route('/api/me', { status: 401, body: refusal });
            page.route('/api/login', { body: { username: 'alice', lang: 'en' } }, 'POST');
            await page.load('/settings?repo=extensions&pr=370&section=jira');
            const login = { shown: !page.el('loginView').classList.contains('hidden'),
                intro: page.el('loginIntro').textContent, message: page.el('loginErr').textContent,
                settingsReads: page.fetches('/api/auto-visa-all').length };
            page.el('token').value = 'preview-token';
            await page.el('loginBtn').onclick();
            await page.settle();
            report({ login, settings: !page.el('settingsPanel').classList.contains('hidden'),
                back: page.el('settingsBack').href, focus: page.run('document.activeElement.id'),
                logouts: page.fetches('/api/logout').length });
            """.formatted(event));

        assertThat(out.get("login").get("shown").asBoolean()).isTrue();
        assertThat(out.get("login").get("intro").asText()).isEqualTo("Log in to manage your account settings.");
        assertThat(out.get("login").get("message").asText())
            .isEqualTo(event.equals("anonymous") ? "" : "Preview session ended");
        assertThat(out.get("login").get("settingsReads").asInt()).isZero();
        assertThat(out.get("settings").asBoolean()).isTrue();
        assertThat(out.get("back").asText()).isEqualTo("/extensions?pr=370");
        assertThat(out.get("focus").asText()).isEqualTo("groupJira");
        assertThat(out.get("logouts").asInt()).isEqualTo(event.equals("revoked") ? 1 : 0);
    }

    /**
     * Given an OAuth return, when settings load or fail to load, then their outcome keeps the return context
     * and never replaces a loading error with a success message.
     */
    @ParameterizedTest
    @ValueSource(ints = {200, 502})
    void oauthOutcomeStaysOnSettingsAndKeepsTheReturnContext(int status) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { status: %s, body: %s === 200
                ? { login: 'alice-gh', commands: true, githubLinkable: true } : { error: 'Preview failure' } });
            await page.load('/settings?repo=extensions&pr=370&section=github&github=linked');
            report({ address: page.location.pathname + page.location.search, back: page.el('settingsBack').href,
                message: page.el('settingsMsg').textContent, locked: page.el('commandsToggle').disabled,
                reads: page.fetches('/api/auto-visa-all').length });
            """.formatted(status, status));

        assertThat(out.get("address").asText()).isEqualTo("/settings?repo=extensions&pr=370&section=github"
            + (status == 200 ? "" : "&github=linked"));
        assertThat(out.get("back").asText()).isEqualTo("/extensions?pr=370");
        assertThat(out.get("message").asText()).isEqualTo(status == 200 ? "Linked @alice-gh: PR commands are on."
            : "Could not load your settings — reload this page.");
        assertThat(out.get("locked").asBoolean()).isEqualTo(status != 200);
        assertThat(out.get("reads").asInt()).isEqualTo(1);
    }
}
