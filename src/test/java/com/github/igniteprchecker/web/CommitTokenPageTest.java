package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * ⚙ asked for a classic token with the public_repo scope for the PR comment too, which a committer's token turns into
 * push rights to apache/ignite on the server, and put it into the session cookie for good. The checker's own account
 * writes in PRs now: the PR comment asks for no token, and the token of an option that commits goes with its switch
 * and into no cookie. Without an account to write with, the options that write to GitHub are off and ⚙ says why.
 */
class CommitTokenPageTest {
    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: true, login: 'alice-v',
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false, loginUnproven: false, githubAvailable: true,
            appLogin: 'ignite-pr-checker', appPushes: false, ghTokenNote: null };
        """;

    @Test
    void thePrCommentAsksForNoToken() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { gh: true }) }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('ghCommentToggle').checked = true;
            await page.el('ghCommentToggle').onchange();
            await page.settle();
            report({ posts: page.fetches('/api/auto-visa-all?').map(f => f.url),
                asked: !page.el('settingsGh').classList.contains('hidden'),
                app: page.el('ghCommentApp').textContent, checked: page.el('ghCommentToggle').checked });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/auto-visa-all?gh=true&tz=UTC\"]");
        assertThat(out.get("asked").asBoolean()).isFalse();
        assertThat(out.get("app").asText()).isEqualTo(" @ignite-pr-checker, which cannot push to the repo,");
        assertThat(out.get("checked").asBoolean()).isTrue();
    }

    @Test
    void theTokenGoesWithTheSwitchAndIntoNoCookie() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const ON = Object.assign({}, SETTINGS, { suites: true, ghStored: true });
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: ON }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            const asked = !page.el('settingsGh').classList.contains('hidden');
            page.el('settingsGhToken').value = ' github_pat_fork ';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ asked, posts: page.fetches('/api/auto-visa-all?').map(f => ({ url: f.url, body: f.body })),
                cookie: page.fetches('/api/github-token').length, field: page.el('settingsGhToken').value,
                panel: !page.el('settingsGh').classList.contains('hidden'),
                checked: page.el('suiteFixToggle').checked });
            """);

        assertThat(out.get("asked").asBoolean()).isTrue();
        assertThat(out.get("posts").toString()).isEqualTo("[{\"url\":\"/api/auto-visa-all?suites=true&tz=UTC\","
            + "\"body\":\"{\\\"githubToken\\\":\\\"github_pat_fork\\\"}\"}]");
        assertThat(out.get("cookie").asInt()).isZero();
        assertThat(out.get("field").asText()).isEmpty();
        assertThat(out.get("panel").asBoolean()).isFalse();
        assertThat(out.get("checked").asBoolean()).isTrue();
    }

    /** The server refuses a classic token that could administer or delete; the reason is shown at the field. */
    @Test
    void aTokenThatDoesTooMuchIsRefusedWithTheReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const WHY = 'this classic token has delete_repo: with it, anyone who reads it could administer, delete'
                + ' repositories or change workflows, and the checker only commits.';
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { status: 412, body: { need: 'github', error: WHY } }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('suiteFixToggle').checked = true;
            await page.el('suiteFixToggle').onchange();
            await page.settle();
            page.el('settingsGhToken').value = 'ghp_everything';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            const first = page.el('settingsGhMsg').textContent;
            page.el('settingsGhToken').value = 'ghp_everything';
            await page.el('settingsGhSave').onclick();
            await page.settle();
            report({ first, posts: page.fetches('/api/auto-visa-all?').length,
                panel: !page.el('settingsGh').classList.contains('hidden'),
                checked: page.el('suiteFixToggle').checked });
            """);

        assertThat(out.get("first").asText()).startsWith("this classic token has delete_repo");
        assertThat(out.get("posts").asInt()).as("the next Save posts for the same switch").isEqualTo(2);
        assertThat(out.get("panel").asBoolean()).isTrue();
        assertThat(out.get("checked").asBoolean()).isFalse();
    }

    @Test
    void whatTheServerSaysOfTheSavedTokenIsShown() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const NOTE = 'Your saved token is a classic one: it can push to every public repository you can push to.';
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { style: true, ghStored: true,
                ghTokenNote: NOTE }) }, 'GET');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ shown: !page.el('ghTokenNote').classList.contains('hidden'),
                note: page.el('ghTokenNote').textContent });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("note").asText()).startsWith("Your saved token is a classic one");
    }

    /** A checker with no GITHUB_TOKEN: what writes to GitHub can only go off, and ⚙ says why. */
    @Test
    void withoutAnAccountTheGithubOptionsAreOff() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            const NONE = Object.assign({}, SETTINGS, { commands: true, githubAvailable: false, appLogin: null });
            page.route('/api/auto-visa-all', { body: NONE }, 'GET');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ said: !page.el('githubOff').classList.contains('hidden'),
                gh: page.el('ghCommentToggle').disabled, style: page.el('styleFixToggle').disabled,
                suites: page.el('suiteFixToggle').disabled, commands: page.el('commandsToggle').disabled,
                visa: page.el('standingVisaToggle').disabled, app: page.el('ghCommentApp').textContent });
            """);

        assertThat(out.get("said").asBoolean()).isTrue();
        assertThat(out.get("gh").asBoolean()).isTrue();
        assertThat(out.get("style").asBoolean()).isTrue();
        assertThat(out.get("suites").asBoolean()).isTrue();
        assertThat(out.get("commands").asBoolean()).as("on, so it can be switched off").isFalse();
        assertThat(out.get("visa").asBoolean()).isFalse();
        assertThat(out.get("app").asText()).isEmpty();
    }
}
