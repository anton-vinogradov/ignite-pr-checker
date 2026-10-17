package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * A user with standing options logged out and met the login form with nothing saying the options go on. Once the
 * form said it, the note stood in red, the colour of a failed login, as did "Log in with a fresh TeamCity token".
 */
class LogoutNotePageTest {
    /** What the line under the login form says, and whether it is red or muted. */
    private static final String LINE = "{ message: page.el('loginErr').textContent, "
        + "red: page.el('loginErr').classList.contains('err'), muted: page.el('loginErr').classList.contains('muted') }";

    @Test
    void aLogoutSaysTheOptionsGoOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/logout', { body: { note: NOTE } });
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report(Object.assign(LINE, { loginShown: !page.el('loginView').classList.contains('hidden') }));
            """.replace("NOTE", new ObjectMapper().writeValueAsString(LoginController.OPTIONS_GO_ON))
            .replace("LINE", LINE));

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo(LoginController.OPTIONS_GO_ON);
        assertThat(out.get("red").asBoolean()).isFalse();
        assertThat(out.get("muted").asBoolean()).isTrue();
    }

    /** A logout everywhere on a token TeamCity refuses ends no other session; the user is told why, in red. */
    @Test
    void aLogoutOnARefusedTokenSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/logout', { status: 401, body: { error: ERROR, tokenRejected: true } });
            await page.load('?pr=13575');
            await page.el('logout').onclick();
            report(LINE);
            """.replace("ERROR", new ObjectMapper().writeValueAsString(LoginController.OTHERS_STAY_IN))
            .replace("LINE", LINE));

        assertThat(out.get("message").asText()).isEqualTo(LoginController.OTHERS_STAY_IN);
        assertThat(out.get("red").asBoolean()).isTrue();
        assertThat(out.get("muted").asBoolean()).isFalse();
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

    /** Log in again in ⚙ leads to the form with a muted note; a login without a token after it is an error again. */
    @Test
    void logInAgainLeavesANoteAndAnEmptyTokenAnError() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            await page.el('tcRelogin').onclick();
            const note = LINE;
            await page.el('loginBtn').onclick();
            report({ note, empty: LINE });
            """.replace("LINE", LINE));

        assertThat(out.get("note").get("message").asText())
            .isEqualTo("Log in with a fresh TeamCity token: your options resume with it.");
        assertThat(out.get("note").get("red").asBoolean()).isFalse();
        assertThat(out.get("note").get("muted").asBoolean()).isTrue();
        assertThat(out.get("empty").get("message").asText()).isEqualTo("Enter a token.");
        assertThat(out.get("empty").get("red").asBoolean()).isTrue();
        assertThat(out.get("empty").get("muted").asBoolean()).isFalse();
    }
}
