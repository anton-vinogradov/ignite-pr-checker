package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Token entry starts with the option that needs it; saved or missing credentials keep their recovery actions. */
class TokenOptionFirstPageTest {
    /** Given no saved tokens or waiting options, when settings open, then no standalone Save button is offered. */
    @Test
    void emptyTokenSettingsOfferNoStandaloneSave() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { githubAvailable: true } }, 'GET');
            await page.load('/settings?repo=ignite');
            await page.settle();
            report({ jira: !page.el('jiraTokenBtn').classList.contains('hidden'),
                github: !page.el('ghTokenBtn').classList.contains('hidden') });
            """);

        assertThat(out.get("jira").asBoolean()).isFalse();
        assertThat(out.get("github").asBoolean()).isFalse();
    }

    /** Given saved tokens, when settings open, then Replace stays available even without the checker's GH account. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void savedTokensStillOfferReplace(boolean githubAvailable) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { visa: true, suites: true, jiraStored: true, ghStored: true,
                githubAvailable: %s } }, 'GET');
            await page.load('/settings?repo=ignite');
            await page.settle();
            report({ jira: !page.el('jiraTokenBtn').classList.contains('hidden'),
                github: !page.el('ghTokenBtn').classList.contains('hidden') });
            """.formatted(githubAvailable));

        assertThat(out.get("jira").asBoolean()).isTrue();
        assertThat(out.get("github").asBoolean()).isTrue();
    }

    /**
     * Given rejected, unreadable or restored-away tokens, when the user opens their recovery actions, then both forms
     * and warnings are visible without selecting the options again.
     */
    @ParameterizedTest
    @ValueSource(strings = {"rejected", "unreadable", "removed"})
    void missingTokensKeepTheirRecoveryActions(String reason) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const reason = '%s';
            const SETTINGS = { visa: reason !== 'rejected', suites: reason !== 'rejected', githubAvailable: true };
            if (reason === 'rejected') Object.assign(SETTINGS, { jiraTokenRejected: true, ghTokenRejected: true });
            if (reason === 'unreadable') Object.assign(SETTINGS, { tokensUnread: ['jira', 'github'],
                tokensUnreadWhy: 'key-changed' });
            if (reason === 'removed') SETTINGS.tokensRemoved = ['jira', 'github'];
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('/settings?repo=ignite');
            await page.settle();
            const actions = ['jiraTokenBtn', 'ghTokenBtn'].map(id => !page.el(id).classList.contains('hidden'));
            await page.el('jiraTokenBtn').onclick();
            await page.el('ghTokenBtn').onclick();
            const warningIds = reason === 'rejected' ? ['jiraTokenGone', 'ghTokenGone']
                : reason === 'removed' ? ['jiraTokenRemoved', 'ghTokenRemoved'] : ['tokensUnread'];
            report({ actions, forms: ['settingsJira', 'settingsGh'].map(id => !page.el(id).classList.contains('hidden')),
                warnings: warningIds.map(id => !page.el(id).classList.contains('hidden')),
                posts: page.fetches('/api/auto-visa-all').filter(f => f.method === 'POST').length });
            """.formatted(reason));

        assertThat(out.get("actions").toString()).isEqualTo("[true,true]");
        assertThat(out.get("forms").toString()).isEqualTo("[true,true]");
        for (JsonNode warning : out.get("warnings"))
            assertThat(warning.asBoolean()).isTrue();
        assertThat(out.get("posts").asInt()).isZero();
    }
}
