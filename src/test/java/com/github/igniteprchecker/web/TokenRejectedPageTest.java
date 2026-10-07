package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * With a revoked TeamCity token the PR page wrote "service is busy or restarting" six times, sent the
 * dead token six more times and never said what was wrong. The server now answers such a request with
 * 401 and {@code tokenRejected}; the page must end the session and say why it shows the login form.
 */
class TokenRejectedPageTest {
    @Test
    void revokedTokenLeadsToTheLoginFormWithTheReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { status: 401, body: { tokenRejected: true,
                error: 'TeamCity rejected your token (revoked or expired) — log in again' } });
            await page.load('?pr=13575');
            await page.tick(60000);
            report({
                loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent,
                logouts: page.fetches('/api/logout').length,
                analyses: page.fetches('/api/analyze').length,
            });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("TeamCity rejected your token (revoked or expired) — log in again");
        assertThat(out.get("logouts").asInt()).isEqualTo(1);
        assertThat(out.get("analyses").asInt()).as("no retries with a dead token").isEqualTo(1);
    }

    /**
     * A token revoked while the page is open is refused by the session guard itself (the background warm saw
     * the 401), with the guard's own wording: the poll must end the session there too, say why, stop asking,
     * and take away the operator's Update button, which the periodic version check would otherwise bring back
     * on the login form.
     */
    @Test
    void aSessionTheGuardRefusesMidReadingEndsWithTheReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: false, github: false, admin: true } });
            page.route('/api/version', { body: { updateAvailable: true, latest: '1.21.0' } });
            await page.load('?pr=13575');
            const updateShown = !page.el('updateBtn').classList.contains('hidden');
            page.route('/api/runs', { status: 401, body: { error: 'TeamCity rejected your token — log in again',
                tokenRejected: true } });
            await page.tick(90000);
            const polls = page.fetches('/api/runs').length;
            await page.tick(10 * 60000);
            report({
                updateShown,
                loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent,
                logouts: page.fetches('/api/logout').length,
                pollsAfter: page.fetches('/api/runs').length - polls,
                updateAfter: !page.el('updateBtn').classList.contains('hidden'),
                settings: !page.el('settingsBtn').classList.contains('hidden'),
            });
            """);

        assertThat(out.get("updateShown").asBoolean()).isTrue();
        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("TeamCity rejected your token — log in again");
        assertThat(out.get("logouts").asInt()).isEqualTo(1);
        assertThat(out.get("pollsAfter").asInt()).isZero();
        assertThat(out.get("updateAfter").asBoolean()).isFalse();
        assertThat(out.get("settings").asBoolean()).isFalse();
    }

    @Test
    void aRevokedSessionIsToldWhyOnArrival() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { status: 401, body: { error: 'TeamCity rejected your token — log in again',
                tokenRejected: true } });
            await page.load('?pr=13575');
            report({
                loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent,
                logouts: page.fetches('/api/logout').length,
                analyses: page.fetches('/api/analyze').length,
            });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("TeamCity rejected your token — log in again");
        assertThat(out.get("logouts").asInt()).isEqualTo(1);
        assertThat(out.get("analyses").asInt()).isZero();
    }

    @Test
    void theSettingsPanelEndsARevokedSessionLikewise() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { status: 401, body: { error: 'TeamCity rejected your token — log in again',
                tokenRejected: true } });
            await page.load('?pr=13575');
            page.el('settingsBtn').onclick();
            await page.settle();
            report({
                loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent,
                logouts: page.fetches('/api/logout').length,
                panelHidden: page.el('settingsPanel').classList.contains('hidden'),
            });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("TeamCity rejected your token — log in again");
        assertThat(out.get("logouts").asInt()).isEqualTo(1);
        assertThat(out.get("panelHidden").asBoolean()).isTrue();
    }

    @Test
    void anExpiredSessionStillJustShowsTheLoginForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { status: 401, body: { error: 'Unauthorized' } });
            await page.load('?pr=13575');
            report({
                loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent,
                logouts: page.fetches('/api/logout').length,
            });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEmpty();
        assertThat(out.get("logouts").asInt()).isZero();
    }
}
