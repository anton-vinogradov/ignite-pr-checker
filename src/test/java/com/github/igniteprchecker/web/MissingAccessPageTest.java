package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Switching on an option without its token opened a form at the bottom of ⚙ and said nothing, a token typed before
 * the switch was refused with "switch on the option that needs it", a token replaced while its option ran stayed in
 * the cookie only, options paused on a refused TeamCity token offered no way back but Log out, and a Run ci2 refused
 * said its firewall may be at fault, not the account. Each attempt now says what it needs in one sentence and shows
 * the form or button that gives it. Alice has nothing saved or linked.
 */
class MissingAccessPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: false, login: null,
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false, loginUnproven: false, githubAvailable: true,
            appLogin: 'ignite-pr-checker', githubLinkable: true, ghTokenNote: null };
        const posts = () => page.fetches('/api/auto-visa-all?').map(f => ({ url: f.url, body: f.body || null }));
        """;

    private static final String OPEN = """
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    @Test
    void theVisaWithoutAJiraTokenOpensItsForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN + """
            page.el('standingVisaToggle').checked = true;
            await page.el('standingVisaToggle').onchange();
            await page.settle();
            report({ form: !page.el('settingsJira').classList.contains('hidden'),
                msg: page.el('settingsJiraMsg').textContent, focused: page.run('document.activeElement.id'),
                checked: page.el('standingVisaToggle').checked, posts: posts().length });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText())
            .isEqualTo("Visa my runs in JIRA needs your JIRA token: paste it here and press Save.");
        assertThat(out.get("focused").asText()).isEqualTo("settingsJiraToken");
        assertThat(out.get("checked").asBoolean()).isFalse();
        assertThat(out.get("posts").asInt()).isZero();
    }

    @Test
    void anOptionThatCommitsWithoutATokenOpensItsForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN + """
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            report({ form: !page.el('settingsGh').classList.contains('hidden'),
                msg: page.el('settingsGhMsg').textContent, focused: page.run('document.activeElement.id') });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("Add my PR's tests that no suite runs needs your GitHub token for"
            + " commits: paste it here and press Save.");
        assertThat(out.get("focused").asText()).isEqualTo("settingsGhToken");
    }

    /** JIRA refused the token the session carried: the server's reason stands at the form. */
    @Test
    void aJiraTokenTheServerRefusesIsSaidAtTheForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/me', { body: { username: 'alice', jira: true, github: false } });
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { status: 412, body: { need: 'jira',
                error: 'JIRA rejected the token — paste a new one' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            page.el('standingVisaToggle').checked = true;
            await page.el('standingVisaToggle').onchange();
            await page.settle();
            report({ form: !page.el('settingsJira').classList.contains('hidden'),
                msg: page.el('settingsJiraMsg').textContent, bottom: page.el('settingsMsg').textContent,
                state: page.el('jiraState').textContent });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected the token — paste a new one");
        assertThat(out.get("bottom").asText()).isEmpty();
        assertThat(out.get("state").asText()).as("the session's token is no good").isEqualTo("not saved");
    }

    /** She pasted the token first and switched the option on after: the token goes with the switch. */
    @Test
    void aTokenPastedBeforeTheSwitchGoesWithIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { suites: true, ghStored: true }) },
                'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('settingsGhToken').value = 'github_pat_fork';
            await page.el('settingsGhSave').onclick();
            const told = page.el('settingsGhMsg').textContent;
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            report({ told, posts: posts(), field: page.el('settingsGhToken').value,
                state: page.el('ghTokenState').textContent });
            """);

        assertThat(out.get("told").asText())
            .isEqualTo("switch on the option below that should use it: the token goes with that switch");
        assertThat(out.get("posts").toString()).isEqualTo("[{\"url\":\"/api/auto-visa-all?suites=true&tz=UTC\","
            + "\"body\":\"{\\\"githubToken\\\":\\\"github_pat_fork\\\"}\"}]");
        assertThat(out.get("field").asText()).isEmpty();
        assertThat(out.get("state").asText()).isEqualTo("saved");
    }

    /** The server keeps only a token a switch brought: Replace sends the new one with the option it runs. */
    @Test
    void aReplacedGithubTokenGoesWithTheOptionThatRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { suites: true, ghStored: true, login: 'alice-v' });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { body: ON }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('settingsGhToken').value = 'github_pat_new';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ posts: posts(), form: !page.el('settingsGh').classList.contains('hidden') });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[{\"url\":\"/api/auto-visa-all?suites=true&tz=UTC\","
            + "\"body\":\"{\\\"githubToken\\\":\\\"github_pat_new\\\"}\"}]");
        assertThat(out.get("form").asBoolean()).isFalse();
    }

    /** The visa ran on the stored JIRA token; a new one pasted went into the cookie and nowhere else. */
    @Test
    void aReplacedJiraTokenGoesToTheServerWhileTheVisaRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { visa: true, jiraStored: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { body: ON }, 'POST');
            page.route('/api/jira-token', { body: { jiraUser: 'alice' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-new';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ saved: page.fetches('/api/jira-token').length, posts: posts().map(p => p.url) });
            """);

        assertThat(out.get("saved").asInt()).isEqualTo(1);
        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?visa=true&tz=UTC\"]");
    }

    /** Her options pause on a token TeamCity refuses: Log in again ends this browser's session, and the form asks. */
    @Test
    void pausedOptionsOfferToLogInAgain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { rerun: true,
                tcTokenRejected: true }) }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN + """
            const offered = !page.el('tcTokenGone').classList.contains('hidden');
            await page.el('tcRelogin').onclick();
            await page.settle();
            report({ offered, logouts: page.fetches('/api/logout').map(f => f.url),
                login: !page.el('loginView').classList.contains('hidden'), why: page.el('loginErr').textContent });
            """);

        assertThat(out.get("offered").asBoolean()).isTrue();
        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout?everywhere=false\"]");
        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("why").asText())
            .isEqualTo("Log in with a fresh TeamCity token: your options resume with it.");
    }

    @Test
    void aRunCi2RefusesSaysTheAccountMayNotRunBuilds() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.run('window').confirm = () => true;
            page.route('/api/trigger', { status: 502, body: { error: `QUEUE_FORBIDDEN` } }, 'POST');
            await page.load('?pr=13575');
            await page.run('doTrigger')('trigger', 'false', page.el('runBtn'));
            report({ status: page.el('status').textContent });
            """.replace("QUEUE_FORBIDDEN", ApiExceptionHandler.QUEUE_FORBIDDEN));

        assertThat(out.get("status").asText()).isEqualTo(ApiExceptionHandler.QUEUE_FORBIDDEN)
            .contains("your ci2 account may not run builds here");
    }
}
