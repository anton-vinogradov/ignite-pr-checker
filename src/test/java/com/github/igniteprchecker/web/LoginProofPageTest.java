package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * ⚙ took the GitHub login for PR commands as typed, so anyone could claim someone else's. It now asks for a token
 * with no scopes, sent once and kept nowhere, the field included, and says when a login an earlier version kept
 * waits for that proof.
 */
class LoginProofPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: true, login: 'alice-v',
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false, loginUnproven: false };
        const TYPED = Object.assign({}, SETTINGS, { login: 'Vladsz83', loginUnproven: true });
        const NAMED_BY_ANOTHER = Object.assign({}, SETTINGS, { login: 'oleg-vlsk', ghStored: true,
            loginUnproven: true });
        """;

    /**
     * An earlier version kept the login without a proof: typed in by hand, or next to a token that names another
     * account now. ⚙ cannot tell which, and says only what is true of both.
     */
    @Test
    void anUnprovenLoginAsksForTheProof() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: TYPED }, 'GET');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ shown: !page.el('settingsLogin').classList.contains('hidden'),
                warned: !page.el('ghLoginUnproven').classList.contains('hidden'),
                warning: page.el('ghLoginUnproven').textContent,
                form: !page.el('ghLoginProof').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("warned").asBoolean()).isTrue();
        assertThat(out.get("warning").asText()).isEqualTo("⚠ @Vladsz83 is not proven: an earlier version of the"
            + " checker kept no proof that it is yours. Your PR commands do nothing until you prove your GitHub account"
            + " below.");
        assertThat(out.get("form").asBoolean()).isTrue();
    }

    @Test
    void aLoginNextToASavedTokenIsNotSaidToBeTyped() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: NAMED_BY_ANOTHER }, 'GET');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ warning: page.el('ghLoginUnproven').textContent,
                state: page.el('ghLoginState').textContent });
            """);

        assertThat(out.get("warning").asText()).startsWith("⚠ @oleg-vlsk is not proven:").doesNotContain("typed");
        assertThat(out.get("state").asText()).isEmpty();
    }

    @Test
    void theTokenGoesOnceAndStaysNowhere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: TYPED }, 'GET');
            page.route('/api/github-login', { body: SETTINGS }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('ghLoginToken').value = ' ghp_noScopes ';
            await page.el('ghLoginSave').onclick();
            await page.settle();
            report({ sent: page.fetches('/api/github-login').map(f => JSON.parse(f.body)),
                field: page.el('ghLoginToken').value, state: page.el('ghLoginState').textContent,
                warned: !page.el('ghLoginUnproven').classList.contains('hidden'),
                form: !page.el('ghLoginProof').classList.contains('hidden'),
                msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("sent").toString()).isEqualTo("[{\"token\":\"ghp_noScopes\"}]");
        assertThat(out.get("field").asText()).isEmpty();
        assertThat(out.get("state").asText()).isEqualTo("GitHub login: @alice-v, proven.");
        assertThat(out.get("warned").asBoolean()).isFalse();
        assertThat(out.get("form").asBoolean()).isFalse();
        assertThat(out.get("msg").asText())
            .isEqualTo("Proven: @alice-v — PR commands are on. You may delete the token on GitHub now.");
    }

    /** GitHub refused what was pasted, a login typed in out of habit say: the reason is shown, nothing is linked. */
    @Test
    void aRefusedTokenIsSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const OFF = Object.assign({}, SETTINGS, { commands: false, login: null });
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            page.route('/api/github-login', { status: 422, body: { error: 'GitHub rejected the token' } }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('commandsToggle').checked = true;
            await page.el('commandsToggle').onchange();
            await page.settle();
            const asked = page.el('ghLoginMsg').textContent;
            page.el('ghLoginToken').value = 'alice-v';
            await page.el('ghLoginSave').onclick();
            await page.settle();
            report({ asked, msg: page.el('ghLoginMsg').textContent,
                login: page.el('loginView').classList.contains('hidden'), commands: page.el('commandsToggle').checked });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("paste a token with no scopes and press Prove");
        assertThat(out.get("msg").asText()).isEqualTo("GitHub rejected the token");
        assertThat(out.get("login").asBoolean()).as("still signed in").isTrue();
        assertThat(out.get("commands").asBoolean()).isFalse();
    }

    /**
     * The user logged out of the checker in another browser, then pressed Prove here: the 401 is the checker's
     * own, so the page goes to the login form and says why, as every other request does.
     */
    @Test
    void anEndedSessionIsNoRefusedToken() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: TYPED }, 'GET');
            page.route('/api/github-login', { status: 401, body: { sessionEnded: true,
                error: 'You logged out of the checker, here or in another browser — log in again' } }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('ghLoginToken').value = 'ghp_noScopes';
            await page.el('ghLoginSave').onclick();
            await page.settle();
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent, msg: page.el('ghLoginMsg').textContent });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText())
            .isEqualTo("You logged out of the checker, here or in another browser — log in again");
        assertThat(out.get("msg").asText()).doesNotContain("logged out");
    }
}
