package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The checkstyle autofix runs only from a PR command, yet ⚙ switched it on with PR commands off, where it never ran,
 * and said nothing when PR commands went off and took it along. Alice has the PR comment on.
 */
class AutofixNeedsCommandsPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: true, style: false, commands: false, login: 'alice-v',
            jiraStored: false, ghStored: true, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false };
        """;

    @Test
    void theAutofixAsksForPrCommandsFirst() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('styleFixToggle').checked = true;
            await page.el('styleFixToggle').onchange();
            await page.settle();
            report({ posts: page.fetches('/api/auto-visa-all?').length, checked: page.el('styleFixToggle').checked,
                msg: page.el('settingsMsg').textContent, focused: page.run('document.activeElement.id') });
            """);

        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("checked").asBoolean()).isFalse();
        assertThat(out.get("focused").asText()).as("the switch it needs").isEqualTo("commandsToggle");
        assertThat(out.get("msg").asText())
            .isEqualTo("Fix checkstyle before my commanded runs needs PR commands: switch them on first.");
    }

    @Test
    void prCommandsOffSayTheAutofixWentWithThem() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { style: true, commands: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('commandsToggle').checked = false;
            await page.el('commandsToggle').onchange();
            await page.settle();
            report({ style: page.el('styleFixToggle').checked, msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("style").asBoolean()).isFalse();
        assertThat(out.get("msg").asText()).isEqualTo("On: GitHub comment. Fix checkstyle before my commanded runs "
            + "went off with PR commands.");
    }
}
