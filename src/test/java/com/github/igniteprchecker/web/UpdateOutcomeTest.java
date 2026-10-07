package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * After Update the page reloaded at the first answer of the service, whichever version answered: an update that
 * failed (a download that did not match, GitHub not answering) came back on the old version, and the page said
 * nothing. Runs the page with the service going down and coming back.
 */
class UpdateOutcomeTest {
    private static final String OPERATOR = PageScript.SIGNED_IN + """
        page.route('/api/me', { body: { username: 'alice', jira: false, github: false, admin: true } });
        page.route('/api/version', { body: { current: '1.21.0', latest: '1.21.1', updateAvailable: true } });
        page.route('/api/update', { body: { status: 'updating' } }, 'POST');
        let reloads = 0;
        page.location.reload = () => reloads++;
        await page.load('');
        const offered = page.el('updateBtn').textContent;
        page.el('updateBtn').onclick({ preventDefault() {} });
        await page.settle();
        """;

    @Test
    void anUpdateThatDidNotHappenSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", OPERATOR + """
            await page.tick(2000);
            const beforeRestart = reloads;
            page.route('/api/version', { status: 503, body: {} });
            await page.tick(20000);
            page.route('/api/version', { body: { current: '1.21.0', latest: '1.21.1', updateAvailable: true,
                updateFailed: { version: '1.21.1', at: page.now(),
                    reason: "the download's sha256 1f0c is not the release's 9a3e" } } });
            await page.tick(2000);
            report({ offered, beforeRestart, reloads, text: page.el('updateBtn').textContent });
            """);

        assertThat(out.get("offered").asText()).isEqualTo("Update to v1.21.1");
        assertThat(out.get("beforeRestart").asInt()).as("the old service still answers before it exits").isZero();
        assertThat(out.get("reloads").asInt()).isZero();
        assertThat(out.get("text").asText())
            .isEqualTo("Update to v1.21.1 failed: the download's sha256 1f0c is not the release's 9a3e");
    }

    @Test
    void anUpdateThatHappenedReloadsThePage() throws Exception {
        JsonNode out = PageScript.run("index.html", OPERATOR + """
            page.route('/api/version', { status: 503, body: {} });
            await page.tick(20000);
            page.route('/api/version', { body: { current: '1.21.1', latest: '1.21.1', updateAvailable: false } });
            await page.tick(2000);
            report({ reloads });
            """);

        assertThat(out.get("reloads").asInt()).isEqualTo(1);
    }

    @Test
    void aFailedUpdateIsOfferedAsARetryWithItsReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: false, github: false, admin: true } });
            page.route('/api/version', { body: { current: '1.21.0', latest: '1.21.1', updateAvailable: true,
                updateFailed: { version: '1.21.1', at: page.now(), reason: 'GitHub did not describe release v1.21.1' } } });
            await page.load('');
            report({ text: page.el('updateBtn').textContent, title: page.el('updateBtn').title });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Retry update to v1.21.1");
        assertThat(out.get("title").asText()).isEqualTo("Update to v1.21.1 failed: GitHub did not describe release v1.21.1");
    }

    /** The Update button said which version, not what it changes; the release notes were three clicks away. */
    @Test
    void theReleaseNotesAreNextToTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: false, github: false, admin: true } });
            page.route('/api/version', { body: { current: '1.21.0', latest: '1.21.1', updateAvailable: true,
                notesUrl: 'https://github.com/anton-vinogradov/ignite-pr-checker/releases/tag/v1.21.1' } });
            await page.load('');
            report({ href: page.el('updateNotes').href, shown: !page.el('updateNotes').classList.contains('hidden') });
            """);

        assertThat(out.get("href").asText())
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases/tag/v1.21.1");
        assertThat(out.get("shown").asBoolean()).isTrue();
    }
}
