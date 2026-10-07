package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A reviewer following a PR's "live progress & verdict" link from GitHub landed on a login form that
 * named no PR and did not say a ci2 account can be registered; the ⚙ button, shown before login,
 * answered with a bare "Unauthorized".
 */
class LoginContextTest {
    private static final String SIGNED_OUT = """
        page.route('/api/me', { status: 401, body: { error: 'Unauthorized' } });
        """;

    @Test
    void arrivalFromAPrLinkSaysWhichPrAndWhereElseTheVerdictIs() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            await page.load('?pr=13575');
            report({
                login: !page.el('loginView').classList.contains('hidden'),
                shown: !page.el('loginPr').classList.contains('hidden'),
                head: page.el('loginPrHead').textContent,
                note: page.el('loginPrNote').innerHTML,
                register: page.el('registerLink').href,
                settings: page.el('settingsBtn').classList.contains('hidden'),
            });
            """);

        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("head").asText()).startsWith("Log in to see PR #13575");
        assertThat(out.get("note").asText()).contains("href=\"https://github.com/apache/ignite/pull/13575\"");
        assertThat(out.get("register").asText()).isEqualTo("https://ci2.example/");
        assertThat(out.get("settings").asBoolean()).as("⚙ hidden before login").isTrue();
    }

    @Test
    void startAddressNamesNoPr() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            await page.load('');
            report({ shown: !page.el('loginPr').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }

    @Test
    void settingsAppearOnceSignedIn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SIGNED_OUT + """
            page.route('/api/login', { body: { username: 'alice', jira: false, github: false } }, 'POST');
            await page.load('?pr=13575');
            page.el('token').value = 'tok';
            await page.run('login')();
            await page.settle();
            report({ settings: page.el('settingsBtn').classList.contains('hidden'),
                login: page.el('loginView').classList.contains('hidden') });
            """);

        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("settings").asBoolean()).isFalse();
    }
}
