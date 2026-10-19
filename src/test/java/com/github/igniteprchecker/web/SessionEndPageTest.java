package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.Jackson2Defaults;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A login ended from another browser sent this one to the login form without a word of why. The logout the page
 * makes after TeamCity refused this browser's token must end this browser's login only: the user's other logins may
 * run on other tokens.
 */
class SessionEndPageTest {
    @Test
    void aSessionALogoutEndedSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { status: 401, body: { sessionEnded: true, error: ENDED } });
            await page.load('?pr=13575');
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent, logouts: page.fetches('/api/logout').length });
            """.replace("ENDED", Jackson2Defaults.mapper().writeValueAsString(AuthInterceptor.ENDED)));

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo(AuthInterceptor.ENDED);
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

        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout\"]");
    }

    @Test
    void logOutEndsThisBrowsersLogin() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report({ logouts: page.fetches('/api/logout').map(f => f.url) });
            """);

        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout\"]");
    }
}
