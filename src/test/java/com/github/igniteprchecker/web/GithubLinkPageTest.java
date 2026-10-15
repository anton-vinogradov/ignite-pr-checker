package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * ⚙ asked for a GitHub token with no scopes to prove the login for PR commands, a token nobody knew why to create. It
 * now has Link GitHub, which goes to GitHub and back, and Unlink; it says how a link ended, and when a login an earlier
 * version kept still waits for a link.
 */
class GithubLinkPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: true, login: 'alice-v',
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false, loginUnproven: false, githubAvailable: true,
            githubLinkable: true };
        const TYPED = Object.assign({}, SETTINGS, { login: 'Vladsz83', loginUnproven: true });
        const NAMED_BY_ANOTHER = Object.assign({}, SETTINGS, { login: 'oleg-vlsk', ghStored: true,
            loginUnproven: true });
        const OFF = Object.assign({}, SETTINGS, { commands: false, login: null });
        """;

    private static final String OPEN_SETTINGS = """
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    /**
     * An earlier version kept the login without a proof: typed in by hand, or next to a token that names another
     * account now. ⚙ cannot tell which, and says only what is true of both.
     */
    @Test
    void anUnprovenLoginAsksForALink() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: TYPED }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            report({ shown: !page.el('settingsLogin').classList.contains('hidden'),
                warning: page.el('ghLoginUnproven').textContent,
                link: !page.el('ghLink').classList.contains('hidden'),
                unlink: !page.el('ghUnlink').classList.contains('hidden'),
                hint: !page.el('ghLinkHint').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("warning").asText()).isEqualTo("⚠ @Vladsz83 is not proven: an earlier version of the"
            + " checker kept no proof that it is yours. Your PR commands do nothing until you link your GitHub account"
            + " below.");
        assertThat(out.get("link").asBoolean()).isTrue();
        assertThat(out.get("unlink").asBoolean()).isTrue();
        assertThat(out.get("hint").asBoolean()).isTrue();
    }

    /** Without PR commands an unproven login costs the mention in the verdict comment, not commands. */
    @Test
    void anUnprovenLoginWithoutPrCommandsIsSaidToCostTheMention() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, TYPED, { commands: false, gh: true }) }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            report({ warning: page.el('ghLoginUnproven').textContent });
            """);

        assertThat(out.get("warning").asText()).isEqualTo("⚠ @Vladsz83 is not proven: an earlier version of the"
            + " checker kept no proof that it is yours. The verdict comments do not mention you until you link your"
            + " GitHub account below.");
    }

    @Test
    void aLoginNextToASavedTokenIsNotSaidToBeTyped() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: NAMED_BY_ANOTHER }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            report({ warning: page.el('ghLoginUnproven').textContent,
                state: page.el('ghLoginState').textContent });
            """);

        assertThat(out.get("warning").asText()).startsWith("⚠ @oleg-vlsk is not proven:").doesNotContain("typed");
        assertThat(out.get("state").asText()).isEmpty();
    }

    @Test
    void aLinkedAccountShowsUnlinkOnly() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            report({ state: page.el('ghLoginState').textContent,
                link: !page.el('ghLink').classList.contains('hidden'),
                unlink: !page.el('ghUnlink').classList.contains('hidden') });
            """);

        assertThat(out.get("state").asText()).isEqualTo("GitHub account: @alice-v, linked.");
        assertThat(out.get("link").asBoolean()).isFalse();
        assertThat(out.get("unlink").asBoolean()).isTrue();
    }

    /** Ticking PR commands with no account linked points at Link GitHub, and asks the server nothing yet. */
    @Test
    void prCommandsWithoutAnAccountPointAtLinkGithub() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            page.el('commandsToggle').checked = true;
            await page.el('commandsToggle').onchange();
            await page.settle();
            report({ shown: !page.el('settingsLogin').classList.contains('hidden'),
                link: !page.el('ghLink').classList.contains('hidden'), msg: page.el('ghLinkMsg').textContent,
                commands: page.el('commandsToggle').checked,
                posts: page.fetches('/api/auto-visa-all').filter(f => f.method === 'POST').length });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("link").asBoolean()).isTrue();
        assertThat(out.get("msg").asText())
            .isEqualTo("press Link GitHub: PR commands go on once GitHub confirms your account");
        assertThat(out.get("commands").asBoolean()).isFalse();
        assertThat(out.get("posts").asInt()).isZero();
    }

    /** The browser goes where the checker says: GitHub's page for the checker's OAuth App. */
    @Test
    void linkGithubGoesToGithub() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            page.route('/api/github/link', { body: { url: 'https://github.com/login/oauth/authorize?state=s1' } },
                'POST');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            await page.el('ghLink').onclick();
            await page.settle();
            report({ asked: page.fetches('/api/github/link').map(f => f.url), went: page.location.href });
            """);

        assertThat(out.get("asked").toString()).isEqualTo("[\"/api/github/link?tz=UTC&pr=13575\"]");
        assertThat(out.get("went").asText()).isEqualTo("https://github.com/login/oauth/authorize?state=s1");
    }

    @Test
    void aLinkTheCheckerCannotStartIsSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            page.route('/api/github/link', { status: 409,
                body: { error: 'Link GitHub is not set up on this checker' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            await page.el('ghLink').onclick();
            await page.settle();
            report({ msg: page.el('ghLinkMsg').textContent, went: page.location.href || '' });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("Link GitHub is not set up on this checker");
        assertThat(out.get("went").asText()).isEmpty();
    }

    /**
     * The user logged out of the checker in another browser, then pressed Link GitHub here: the 401 is the checker's
     * own, so the page goes to the login form and says why, as every other request does.
     */
    @Test
    void anEndedSessionGoesToTheLoginForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: TYPED }, 'GET');
            page.route('/api/github/link', { status: 401, body: { sessionEnded: true,
                error: 'You logged out of the checker, here or in another browser — log in again' } }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            await page.el('ghLink').onclick();
            await page.settle();
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent, msg: page.el('ghLinkMsg').textContent });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText())
            .isEqualTo("You logged out of the checker, here or in another browser — log in again");
        assertThat(out.get("msg").asText()).doesNotContain("logged out");
    }

    /** Back from GitHub, ⚙ opens on its own and says how the link went. */
    @Test
    void theOutcomeOfALinkIsSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575&github=linked');
            report({ open: !page.el('settingsPanel').classList.contains('hidden'),
                msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("open").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEqualTo("Linked @alice-v: PR commands are on.");
    }

    @Test
    void aDeclineOnGithubIsSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            await page.load('?github=denied');
            report({ msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("Nothing was linked: you did not authorize the checker on"
            + " GitHub.");
    }

    /** The outcome comes from the address, which anyone can write: only the checker's own words are shown. */
    @Test
    void anOutcomeTheCheckerDoesNotSendIsIgnored() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: OFF }, 'GET');
            await page.load('?github=constructor');
            report({ open: !page.el('settingsPanel').classList.contains('hidden'),
                msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("open").asBoolean()).isFalse();
        assertThat(out.get("msg").asText()).isEmpty();
    }

    /** No OAuth App on this checker: no Link GitHub, and ⚙ says what links an account instead. */
    @Test
    void withoutAnOAuthAppTheTokenForCommitsIsNamed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, OFF, { githubLinkable: false }) }, 'GET');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            page.el('commandsToggle').checked = true;
            await page.el('commandsToggle').onchange();
            await page.settle();
            report({ link: !page.el('ghLink').classList.contains('hidden'),
                hint: !page.el('ghLinkHint').classList.contains('hidden'),
                off: !page.el('ghLinkOff').classList.contains('hidden'), msg: page.el('ghLinkMsg').textContent });
            """);

        assertThat(out.get("link").asBoolean()).isFalse();
        assertThat(out.get("hint").asBoolean()).isFalse();
        assertThat(out.get("off").asBoolean()).isTrue();
        assertThat(out.get("msg").asText()).isEmpty();
    }

    @Test
    void unlinkSaysWhatWentOff() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const WITH_TOKEN = Object.assign({}, SETTINGS, { ghStored: true, suites: true });
            page.route('/api/auto-visa-all', { body: WITH_TOKEN }, 'GET');
            page.route('/api/github/unlink', { body: OFF }, 'POST');
            await page.load('?pr=13575');
            """ + OPEN_SETTINGS + """
            await page.el('ghUnlink').onclick();
            await page.settle();
            report({ msg: page.el('settingsMsg').textContent, commands: page.el('commandsToggle').checked,
                state: page.el('ghLoginState').textContent });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("Unlinked @alice-v: PR commands are off. Your GitHub token for"
            + " commits is dropped too, and the options that used it are off: it was of that account.");
        assertThat(out.get("commands").asBoolean()).isFalse();
        assertThat(out.get("state").asText()).isEmpty();
    }
}
