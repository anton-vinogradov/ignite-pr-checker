package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A one-shot Auto visa whose JIRA token JIRA refused went silently, its button reading as if nothing had been armed;
 * and the times of a /run-all story came from the JIRA profile, which ⚙ never asked for. The button marks the dropped
 * visa, and ⚙ sends the browser's time zone with every change.
 */
class TokenRefusalPageTest {
    @Test
    void anAutoVisaDroppedWithItsTokenIsMarked() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa', { body: { armed: false, others: [], tokenRejectedFor: 'IGNITE-29049' } });
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            page.run('loadAutoVisa')(13575);
            await page.settle();
            report({ text: page.el('autoVisaBtn').textContent, title: page.el('autoVisaBtn').title });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Auto visa ⚠");
        assertThat(out.get("title").asText()).isEqualTo("JIRA rejected your token, so your auto visa for IGNITE-29049"
            + " was dropped with it. Click to arm it again; a new token is asked for if needed.");
    }

    /** JIRA rejected the token of a manual JIRA visa: the panel opened for a new one, and did not say why. */
    @Test
    void aVisaWhoseTokenJiraRejectedSaysSo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: true, github: false } });
            page.route('/api/jira-visa', { status: 412, body: { error: 'JIRA rejected your token — paste a new one' } },
                'POST');
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            await page.run('postVisa')();
            report({ msg: page.el('jiraMsg').textContent, open: !page.el('jiraPanel').classList.contains('hidden') });
            """);

        assertThat(out.get("open").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected your token — paste a new one");
    }

    @Test
    void anAutoVisaWhoseTokenJiraRejectedSaysSo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: true, github: false } });
            page.route('/api/auto-visa?pr=13575&issue', { status: 412,
                body: { error: 'JIRA rejected the stored token — re-enter it' } }, 'POST');
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            await page.run('armAutoVisa')();
            report({ msg: page.el('jiraMsg').textContent, open: !page.el('jiraPanel').classList.contains('hidden') });
            """);

        assertThat(out.get("open").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected the stored token — re-enter it");
    }

    @Test
    void linkingTheLoginSendsTheBrowsersTimeZone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: true, login: 'alice-v',
                jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
                tcTokenRejected: false, suites: false };
            page.route('/api/github-login', { body: SETTINGS }, 'POST');
            await page.load('?pr=13575');
            page.el('ghLoginInput').value = 'alice-v';
            await page.el('ghLoginSave').onclick();
            report({ posts: page.fetches('/api/github-login').map(f => f.url) });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/github-login?tz=UTC\"]");
    }
}
