package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A reviewer following a PR's "live progress & verdict" link from GitHub landed on a login form that
 * named no PR and did not say a ci2 account can be registered; the ⚙ button, shown before login,
 * answered with a bare "Unauthorized".
 */
class LoginContextTest {
    private static final String SIGNED_OUT = """
        page.route('/api/me', { status: 401, body: { error: 'Unauthorized' } });
        """;

    /** Given a signed-out PR link, the login form names that PR and keeps the token notice visible. */
    @Test
    void arrivalFromAPrLinkSaysWhichPrAndWhereElseTheVerdictIs() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            await page.load('?pr=13575');
            report({
                login: !page.el('loginView').classList.contains('hidden'),
                shown: !page.el('loginPr').classList.contains('hidden'),
                introHidden: page.el('loginIntro').classList.contains('hidden'),
                tokenNoteHidden: page.el('loginTokenNote').classList.contains('hidden'),
                head: page.el('loginPrHead').textContent,
                note: page.el('loginPrNote').innerHTML,
                register: page.el('registerLink').href,
                settings: page.el('settingsBtn').classList.contains('hidden'),
            });
            """);

        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("introHidden").asBoolean()).as("the PR context replaces the general introduction").isTrue();
        assertThat(out.get("tokenNoteHidden").asBoolean()).isFalse();
        assertThat(out.get("head").asText()).startsWith("Log in to see PR #13575");
        assertThat(out.get("note").asText()).contains("href=\"https://github.com/apache/ignite/pull/13575\"");
        assertThat(out.get("register").asText()).isEqualTo("https://ci2.example/");
        assertThat(out.get("settings").asBoolean()).as("⚙ hidden before login").isTrue();
    }

    /** Given the home address without a PR, the login form shows the general introduction. */
    @Test
    void startAddressNamesNoPr() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            await page.load('');
            report({ shown: !page.el('loginPr').classList.contains('hidden'),
                introHidden: page.el('loginIntro').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
        assertThat(out.get("introHidden").asBoolean()).as("the home page still introduces the checker").isFalse();
    }

    /** Given commands off, login leaves settings closed; opening ⚙ offers commands and Link GitHub. */
    @Test
    void aLoginLeavesCommandsInSettings() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            page.route('/api/login', { body: { username: 'alice', jira: false, github: false } }, 'POST');
            page.route('/api/auto-visa-all', { body: { commands: false, githubAvailable: true, githubLinkable: true } });
            await page.load('?pr=13575');
            page.el('token').value = 'tok';
            await page.run('login')();
            await page.settle();
            const afterLogin = { settings: page.el('settingsBtn').classList.contains('hidden'),
                login: page.el('loginView').classList.contains('hidden'),
                panelHidden: page.el('settingsPanel').classList.contains('hidden'),
                settingsReads: page.fetches('/api/auto-visa-all').length };
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ afterLogin, opened: !page.el('settingsPanel').classList.contains('hidden'),
                settingsReads: page.fetches('/api/auto-visa-all').length,
                commandsDisabled: page.el('commandsToggle').disabled,
                commands: page.el('commandsToggle').checked, linkHidden: page.el('ghLink').classList.contains('hidden') });
            """);

        assertThat(out.get("afterLogin").get("login").asBoolean()).isTrue();
        assertThat(out.get("afterLogin").get("settings").asBoolean()).isFalse();
        assertThat(out.get("afterLogin").get("panelHidden").asBoolean()).isTrue();
        assertThat(out.get("afterLogin").get("settingsReads").asInt())
            .as("login does not check whether to offer commands").isZero();
        assertThat(out.get("opened").asBoolean()).isTrue();
        assertThat(out.get("settingsReads").asInt()).isOne();
        assertThat(out.get("commandsDisabled").asBoolean()).isFalse();
        assertThat(out.get("commands").asBoolean()).isFalse();
        assertThat(out.get("linkHidden").asBoolean())
            .as("commands remain available through Link GitHub in settings").isFalse();
    }
}
