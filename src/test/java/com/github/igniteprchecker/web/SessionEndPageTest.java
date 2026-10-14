package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A logout now ends the user's sessions in every browser. Another browser then met the login form without a word of
 * why, and the logout the page makes after TeamCity refused this session's token would have ended the user's other
 * sessions too, which may run on other tokens.
 */
class SessionEndPageTest {
    @Test
    void aSessionALogoutEndedSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { status: 401, body: { sessionEnded: true,
                error: 'You logged out of the checker, here or in another browser — log in again' } });
            await page.load('?pr=13575');
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent, logouts: page.fetches('/api/logout').length });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText())
            .isEqualTo("You logged out of the checker, here or in another browser — log in again");
        assertThat(out.get("logouts").asInt()).isZero();
    }

    @Test
    void aRefusedTokenEndsThisBrowsersSessionOnly() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { status: 401, body: { tokenRejected: true,
                error: 'TeamCity rejected your token (revoked or expired) — log in again' } });
            await page.load('?pr=13575');
            report({ logouts: page.fetches('/api/logout').map(f => f.url) });
            """);

        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout?everywhere=false\"]");
    }

    @Test
    void logOutEndsTheLoginEverywhere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report({ logouts: page.fetches('/api/logout').map(f => f.url) });
            """);

        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout\"]");
    }
}
