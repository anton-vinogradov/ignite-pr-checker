package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

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

    /** JIRA refused the token she pasted: the server's reason stands at the form, and the visa stays off. */
    @Test
    void aJiraTokenTheServerRefusesIsSaidAtTheForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { status: 412, body: { need: 'jira',
                error: 'JIRA rejected the token — paste a new one' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-dead';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ form: !page.el('settingsJira').classList.contains('hidden'),
                msg: page.el('settingsJiraMsg').textContent, bottom: page.el('settingsMsg').textContent,
                state: page.el('jiraState').textContent, on: page.el('standingVisaToggle').checked });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected the token — paste a new one");
        assertThat(out.get("bottom").asText()).isEmpty();
        assertThat(out.get("state").asText()).isEqualTo("not saved");
        assertThat(out.get("on").asBoolean()).isFalse();
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

    /** She pasted the token, then switched on the autofix before PR commands: nothing went, so the token stays. */
    @Test
    void aPastedTokenSurvivesTheRefusedAutofixSwitch() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('settingsGhToken').value = 'github_pat_fork';
            page.el('styleFixToggle').checked = true;
            await page.el('styleFixToggle').onchange();
            await page.settle();
            report({ field: page.el('settingsGhToken').value, msg: page.el('settingsMsg').textContent,
                posts: posts().length });
            """);

        assertThat(out.get("msg").asText())
            .isEqualTo("Fix checkstyle before my commanded runs needs PR commands: switch them on first.");
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("field").asText()).isEqualTo("github_pat_fork");
    }

    /**
     * She pressed Replace while the autofix ran, switched PR commands off, which took the autofix with them, and saved
     * the new token: the autofix cannot go on, so the token stays in the field.
     */
    @Test
    void aTokenSavedForTheAutofixWithoutCommandsStays() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { style: true, commands: true,
                ghStored: true, login: 'alice-v' }) }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { login: 'alice-v' }) }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('commandsToggle').checked = false;
            await page.el('commandsToggle').onchange();
            await page.settle();
            page.el('settingsGhToken').value = 'github_pat_new';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ field: page.el('settingsGhToken').value, msg: page.el('settingsGhMsg').textContent,
                posts: posts().map(p => p.url) });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?commands=false&tz=UTC\"]");
        assertThat(out.get("msg").asText())
            .isEqualTo("Fix checkstyle before my commanded runs needs PR commands: switch them on first.");
        assertThat(out.get("field").asText()).isEqualTo("github_pat_new");
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

    /**
     * The visa ran on the stored JIRA token; a new one pasted went into the cookie and nowhere else. It goes to the
     * server with the visa's switch.
     */
    @Test
    void aReplacedJiraTokenGoesToTheServerWhileTheVisaRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { visa: true, jiraStored: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { body: ON }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-new';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ cookie: page.fetches('/api/jira-token').length, posts: posts(),
                form: !page.el('settingsJira').classList.contains('hidden'), field: page.el('settingsJiraToken').value });
            """);

        assertThat(out.get("cookie").asInt()).isZero();
        assertThat(out.get("posts").toString()).isEqualTo("[{\"url\":\"/api/auto-visa-all?visa=true&tz=UTC\","
            + "\"body\":\"{\\\"jiraToken\\\":\\\"jira-pat-new\\\"}\"}]");
        assertThat(out.get("form").asBoolean()).isFalse();
        assertThat(out.get("field").asText()).isEmpty();
    }

    /**
     * JIRA refused the token she pasted to replace the stored one, and the server kept the old token. The page showed
     * the visa off: it is on, with the saved token. The reason stands at the form.
     */
    @Test
    void aReplacedJiraTokenTheServerRefusesLeavesTheVisaOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { visa: true, jiraStored: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { status: 412, body: { need: 'jira',
                error: 'JIRA rejected the token — paste a new one' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-dead';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ form: !page.el('settingsJira').classList.contains('hidden'),
                msg: page.el('settingsJiraMsg').textContent, state: page.el('jiraState').textContent,
                on: page.el('standingVisaToggle').checked });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("JIRA rejected the token — paste a new one");
        assertThat(out.get("state").asText()).isEqualTo("saved");
        assertThat(out.get("on").asBoolean()).isTrue();
    }

    /** The same while JIRA could not check the new token: the server says so, and she tries again later. */
    @Test
    void aReplacedJiraTokenJiraCouldNotCheckLeavesTheVisaOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { visa: true, jiraStored: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { status: 502, body: {
                error: 'JIRA could not check the token right now — try again in a minute' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-new';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ form: !page.el('settingsJira').classList.contains('hidden'),
                msg: page.el('settingsJiraMsg').textContent, on: page.el('standingVisaToggle').checked });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText())
            .isEqualTo("JIRA could not check the token right now — try again in a minute");
        assertThat(out.get("on").asBoolean()).isTrue();
    }

    /**
     * GitHub refused the token she pasted to replace the stored one. The form closed as if the token were saved, since
     * the option was on before; the reason now stands at the form, and the option stays on with the saved token.
     */
    @Test
    void aReplacedGithubTokenTheServerRefusesStaysAtTheForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { suites: true, ghStored: true, login: 'alice-v' });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { status: 412, body: { need: 'github',
                error: 'GitHub rejected the token — paste a new one' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('settingsGhToken').value = 'github_pat_dead';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ form: !page.el('settingsGh').classList.contains('hidden'),
                msg: page.el('settingsGhMsg').textContent, state: page.el('ghTokenState').textContent,
                on: page.el('suiteFixToggle').checked });
            """);

        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("GitHub rejected the token — paste a new one");
        assertThat(out.get("state").asText()).isEqualTo("saved");
        assertThat(out.get("on").asBoolean()).isTrue();
    }

    /** Visas come from the option alone: Save in the JIRA group switches it on with the token. */
    @Test
    void aSavedJiraTokenSwitchesTheVisaOnWithIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { visa: true, jiraStored: true }) },
                'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ posts: posts(), visa: page.el('standingVisaToggle').checked,
                state: page.el('jiraState').textContent, msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[{\"url\":\"/api/auto-visa-all?visa=true&tz=UTC\","
            + "\"body\":\"{\\\"jiraToken\\\":\\\"jira-pat\\\"}\"}]");
        assertThat(out.get("visa").asBoolean()).isTrue();
        assertThat(out.get("state").asText()).isEqualTo("saved");
        assertThat(out.get("msg").asText()).isEqualTo("On: JIRA visa.");
    }

    /**
     * GitHub refused alice's stored token, so its form stood open. She switched the visa on, which asked for her JIRA
     * token, and saved it. The GitHub token she pasted next went with the visa's switch, which has no use for it: the
     * form closed, the field emptied, and no option kept the token.
     */
    @Test
    void aGithubTokenAfterTheJiraTokenWaitsForTheOptionThatUsesIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const REFUSED = Object.assign({}, SETTINGS, { ghTokenRejected: true });
            page.route('/api/auto-visa-all', { body: REFUSED }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, REFUSED, { visa: true, jiraStored: true }) },
                'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            page.el('standingVisaToggle').checked = true;
            await page.el('standingVisaToggle').onchange();
            await page.settle();
            page.el('settingsJiraToken').value = 'jira-pat';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            page.el('settingsGhToken').value = 'github_pat_fork';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ posts: posts().length, form: !page.el('settingsGh').classList.contains('hidden'),
                field: page.el('settingsGhToken').value, msg: page.el('settingsGhMsg').textContent });
            """);

        assertThat(out.get("posts").asInt()).as("the visa's switch alone").isEqualTo(1);
        assertThat(out.get("form").asBoolean()).isTrue();
        assertThat(out.get("field").asText()).isEqualTo("github_pat_fork");
        assertThat(out.get("msg").asText())
            .isEqualTo("switch on the option below that should use it: the token goes with that switch");
    }

    /** The same for a GitHub token: Replace, then the option that ran on it off, then Save. */
    @Test
    void aCommitOptionSwitchedOffDuringReplaceStaysOff() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { suites: true, ghStored: true,
                login: 'alice-v' }) }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { login: 'alice-v' }) }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('ghTokenBtn').onclick();
            page.el('suiteFixToggle').checked = false;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            page.el('settingsGhToken').value = 'github_pat_new';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ posts: posts().map(p => p.url), suites: page.el('suiteFixToggle').checked,
                msg: page.el('settingsGhMsg').textContent });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?suites=false&tz=UTC\"]");
        assertThat(out.get("suites").asBoolean()).isFalse();
        assertThat(out.get("msg").asText())
            .isEqualTo("switch on the option below that should use it: the token goes with that switch");
    }

    /** JIRA refused the token she saved here after the server had taken it: ⚙ says so when she opens it again. */
    @Test
    void aTokenJiraRejectsAfterItWasReplacedShowsRejected() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { visa: true, jiraStored: true });
            page.route('/api/auto-visa-all', { body: ON }, 'GET');
            page.route('/api/auto-visa-all', { body: ON }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN + """
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-new';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { jiraTokenRejected: true }) },
                'GET');
            await page.el('settingsBtn').onclick();
            """ + OPEN + """
            report({ state: page.el('jiraState').textContent,
                gone: !page.el('jiraTokenGone').classList.contains('hidden') });
            """);

        assertThat(out.get("state").asText()).isEqualTo("rejected");
        assertThat(out.get("gone").asBoolean()).isTrue();
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
        assertThat(out.get("logouts").toString()).isEqualTo("[\"/api/logout\"]");
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

        assertThat(out.get("status").asText()).isEqualTo("⚠ Not queued: " + ApiExceptionHandler.QUEUE_FORBIDDEN)
            .contains("your ci2 account may not run builds here");
    }
}
