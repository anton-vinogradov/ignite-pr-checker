package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * After a restore from a backup older than two days, the visa and the options that commit stayed switched on in ⚙ and
 * did nothing, and nothing said why: the backup no longer held the JIRA and GitHub tokens. ⚙ now says which token to
 * save again, and the switches stay on.
 */
class RemovedTokensPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: true, rerun: false, gh: false, style: false, commands: true, login: 'avinogradov-gh',
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: true, githubAvailable: true, tokensRemoved: %s };
        page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
        await page.load('?pr=13575');
        await page.el('settingsBtn').onclick();
        await page.settle();
        report({ jira: !page.el('jiraTokenRemoved').classList.contains('hidden'),
            gh: !page.el('ghTokenRemoved').classList.contains('hidden'),
            visa: page.el('standingVisaToggle').checked, suites: page.el('suiteFixToggle').checked });
        """;

    @Test
    void settingsSayWhichTokenToSaveAgainAndKeepTheSwitchesOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS.formatted("['jira', 'github']"));

        assertThat(out.get("jira").asBoolean()).isTrue();
        assertThat(out.get("gh").asBoolean()).isTrue();
        assertThat(textOf("jiraTokenRemoved")).isEqualTo("⚠ The checker's state was restored from a backup older "
            + "than 2 days, which keeps no tokens. Visa my runs in JIRA is paused until you save your JIRA token again.");
        assertThat(textOf("ghTokenRemoved")).isEqualTo("⚠ The checker's state was restored from a backup older "
            + "than 2 days, which keeps no tokens. The options that commit are paused until you save your GitHub token "
            + "for commits again.");
        assertThat(out.get("visa").asBoolean()).isTrue();
        assertThat(out.get("suites").asBoolean()).isTrue();
    }

    /**
     * Her session still carried a JIRA token she had pasted before, so ⚙ said "JIRA token: saved" with Replace under
     * the note that the visa waits for her JIRA token. The server keeps no JIRA token of hers: ⚙ says so, and the
     * token she saves goes to the server with the visa.
     */
    @Test
    void aJiraTokenInTheSessionIsNotTheOneTheVisaWaitsFor() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: true, github: false } });
            page.route('/api/jira-token', { body: { jiraUser: 'alice' } }, 'POST');
            page.route('/api/auto-visa-all', { body: { visa: true, commands: true, login: 'avinogradov-gh',
                jiraStored: true, githubAvailable: true, tokensRemoved: [] } }, 'POST');
            """ + SETTINGS.formatted("['jira']") + """
            const before = { state: page.el('jiraState').textContent, button: page.el('jiraTokenBtn').textContent };
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ before, posts: page.fetches('/api/auto-visa-all?').map(f => f.url),
                note: !page.el('jiraTokenRemoved').classList.contains('hidden'),
                state: page.el('jiraState').textContent });
            """);

        assertThat(out.get("before").get("state").asText()).isEqualTo("not saved");
        assertThat(out.get("before").get("button").asText()).isEqualTo("Save");
        assertThat(out.get("posts").toString()).contains("/api/auto-visa-all?visa=true");
        assertThat(out.get("note").asBoolean()).isFalse();
        assertThat(out.get("state").asText()).isEqualTo("saved");
    }

    @Test
    void nothingIsSaidWhenNoTokenIsMissing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS.formatted("[]"));

        assertThat(out.get("jira").asBoolean()).isFalse();
        assertThat(out.get("gh").asBoolean()).isFalse();
    }

    /** The page's harness keeps no text of the HTML: it is read from the file. */
    private static String textOf(String id) throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as(id).isNotNegative();

        return html.substring(html.indexOf('>', start) + 1, html.indexOf("</p>", start)).replaceAll("\\s+", " ")
            .strip();
    }
}
