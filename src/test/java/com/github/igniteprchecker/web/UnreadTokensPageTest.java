package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The operator changed the server key, and alice's ⚙ showed Visa my runs in JIRA on with no JIRA token and no word of
 * why. Her visas waited for nothing she knew of. ⚙ now says that the key was changed and which tokens to save again,
 * opens their forms, and Save sends each token with the option that waits for it. The visa line of her run says what
 * the visa waits for.
 */
class UnreadTokensPageTest {
    private static final String SETTINGS = """
        page.route('/api/auto-visa-all', { body: { visa: true, commands: true, suites: true, login: 'alice-gh',
            githubLinkable: true, tokensUnread: ['jira', 'github'], tokensUnreadWhy: 'key-changed' } });
        page.route('/api/auto-visa-all?', { body: { visa: true, commands: true, suites: true, login: 'alice-gh',
            jiraStored: true, ghStored: true } }, 'POST');
        """;

    private static final String OPEN = """
        await page.load('?pr=13575');
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    @Test
    void settingsSayWhichTokensToSaveAgainAndOpenTheirForms() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + OPEN + """
            report({ note: page.el('tokensUnread').textContent,
                shown: !page.el('tokensUnread').classList.contains('hidden'),
                jiraForm: !page.el('settingsJira').classList.contains('hidden'),
                ghForm: !page.el('settingsGh').classList.contains('hidden'),
                visa: page.el('standingVisaToggle').checked, suites: page.el('suiteFixToggle').checked });
            """);

        assertThat(out.get("note").asText()).isEqualTo("⚠ The server key was changed, so the checker could no longer"
            + " read your stored JIRA token and GitHub token for commits. Visa my runs in JIRA stays on and waits for a"
            + " new JIRA token. Add my PR's tests that no suite runs stays on and waits for a new GitHub token for"
            + " commits. Save the new tokens below.");
        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("jiraForm").asBoolean()).isTrue();
        assertThat(out.get("ghForm").asBoolean()).isTrue();
        assertThat(out.get("visa").asBoolean()).as("the option stays on").isTrue();
        assertThat(out.get("suites").asBoolean()).isTrue();
    }

    @Test
    void saveSendsEachTokenWithTheOptionThatWaitsForIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + OPEN + """
            page.el('settingsGhToken').value = 'github_pat_new';
            await page.el('settingsGhSave').onclick();
            page.el('settingsJiraToken').value = 'jira-new';
            await page.el('settingsJiraSave').onclick();
            report({ posts: page.fetches('/api/auto-visa-all?').filter(f => f.method === 'POST')
                .map(f => f.url.split('&')[0] + ' ' + f.body),
                note: !page.el('tokensUnread').classList.contains('hidden') });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?suites=true"
            + " {\\\"githubToken\\\":\\\"github_pat_new\\\"}\",\"/api/auto-visa-all?visa=true"
            + " {\\\"jiraToken\\\":\\\"jira-new\\\"}\"]");
        assertThat(out.get("note").asBoolean()).as("the server holds both tokens now").isFalse();
    }

    /**
     * alice's JIRA token got damaged, and the rest of her tokens read. The note names that one token and the one
     * option that waits for it: Re-run failed suites of my runs goes on working.
     */
    @Test
    void aDamagedTokenIsNotCalledAKeyChange() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { visa: true, rerun: true, tokensUnread: ['jira'],
                tokensUnreadWhy: 'damaged' } });
            """ + OPEN + """
            report({ note: page.el('tokensUnread').textContent });
            """);

        assertThat(out.get("note").asText()).isEqualTo("⚠ The checker could not read your stored JIRA token. Visa my"
            + " runs in JIRA stays on and waits for a new JIRA token. Save it below.");
    }

    /** Both options that commit wait for the one GitHub token. */
    @Test
    void twoOptionsThatWaitForOneTokenAreNamedTogether() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { commands: true, style: true, suites: true, login: 'alice-gh',
                githubLinkable: true, tokensUnread: ['github'], tokensUnreadWhy: 'key-changed' } });
            """ + OPEN + """
            report({ note: page.el('tokensUnread').textContent });
            """);

        assertThat(out.get("note").asText()).isEqualTo("⚠ The server key was changed, so the checker could no longer"
            + " read your stored GitHub token for commits. Fix checkstyle before my commanded runs and Add my PR's tests"
            + " that no suite runs stay on. They wait for a new GitHub token for commits. Save it below.");
    }

    /**
     * alice pastes an expired JIRA token for the visa that waits. JIRA refuses it, and the server keeps the visa on and
     * waiting: the switch stays on, and the form says why the token did not go in.
     */
    @Test
    void aRefusedTokenLeavesTheWaitingVisaOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all?', { status: 412, body: { need: 'jira',
                error: 'JIRA rejected the token — paste a new one' } }, 'POST');
            """ + OPEN + """
            page.el('settingsJiraToken').value = 'jira-expired';
            await page.el('settingsJiraSave').onclick();
            report({ visa: page.el('standingVisaToggle').checked, msg: page.el('settingsJiraMsg').textContent,
                form: !page.el('settingsJira').classList.contains('hidden') });
            """);

        assertThat(out.get("visa").asBoolean()).as("the server keeps the visa on").isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected the token — paste a new one");
        assertThat(out.get("form").asBoolean()).isTrue();
    }

    /** The same with a GitHub token that GitHub refuses: the option that waits for it stays on, and so does its form. */
    @Test
    void aRefusedTokenLeavesTheWaitingCommitOptionOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all?', { status: 412, body: { need: 'github',
                error: 'GitHub rejected the token — paste a new one' } }, 'POST');
            """ + OPEN + """
            page.el('settingsGhToken').value = 'github_pat_expired';
            await page.el('settingsGhSave').onclick();
            report({ suites: page.el('suiteFixToggle').checked, msg: page.el('settingsGhMsg').textContent,
                form: !page.el('settingsGh').classList.contains('hidden') });
            """);

        assertThat(out.get("suites").asBoolean()).as("the server keeps the option on").isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("GitHub rejected the token — paste a new one");
        assertThat(out.get("form").asBoolean()).isTrue();
    }

    @Test
    void theVisaLineSaysWhatTheVisaWaitsFor() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            const say = unread => page.run('visaLineText')({ state: 'waits', starter: 'alice', mine: true, unread },
                'IGNITE-29049');
            report({ key: say('key-changed'), damaged: say('damaged') });
            """);

        assertThat(out.get("key").asText())
            .isEqualTo("Visa: waits for a new JIRA token in ⚙, because the server key was changed");
        assertThat(out.get("damaged").asText())
            .isEqualTo("Visa: waits for a new JIRA token in ⚙, because the checker could not read the stored one");
    }
}
