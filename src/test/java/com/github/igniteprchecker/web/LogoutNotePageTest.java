package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** A user with standing options logged out and met the login form with nothing saying the options go on. */
class LogoutNotePageTest {
    @Test
    void aLogoutSaysTheOptionsGoOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/logout', { body: { note: NOTE } });
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent });
            """.replace("NOTE", new ObjectMapper().writeValueAsString(LoginController.OPTIONS_GO_ON)));

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo(LoginController.OPTIONS_GO_ON);
    }

    @Test
    void aLogoutWithoutOptionsSaysNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/logout', { body: {} });
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report({ message: page.el('loginErr').textContent });
            """);

        assertThat(out.get("message").asText()).isEmpty();
    }
}
