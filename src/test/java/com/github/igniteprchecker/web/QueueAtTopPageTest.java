package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * ci2 lets only some accounts reorder the build queue. "Run at top" and "Rerun at top" from anyone else got their
 * builds queued at the end of the queue, and the page said the trigger had failed. The server now marks such a build
 * {@code belowTop}, and the page says where it went. On PR 13575 alice may not reorder ci2's queue.
 */
class QueueAtTopPageTest {
    private static final String TRIGGERS = """
        page.run('window').confirm = () => true;
        const build = (id, extra) => Object.assign({ buildId: id, state: 'queued', name: 'Cache', btId: 'Cache',
            webUrl: 'https://ci2.example/build/' + id, pct: -1, leftSec: -1, startSec: -1, waitedSec: -1,
            elapsedSec: -1 }, extra);
        const BELOW = { belowTop: true, topRefusal: 403 };
        """;

    private static final String NOTE = "at the end of the queue: ci2 did not let your account move it to the top.";

    @Test
    void runAtTopSaysTheChainWaitsAtTheEnd() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TRIGGERS + """
            page.route('/api/trigger', { body: { triggered: [build(501, BELOW)], replaced: 0 } }, 'POST');
            await page.load('?pr=13575');
            await page.run('doTrigger')('trigger', 'true', page.el('runTop'));
            report({ status: page.el('status').textContent });
            """);

        assertThat(out.get("status").asText()).isEqualTo("Queued 1 build: #501, " + NOTE);
    }

    @Test
    void rerunAtTopSaysWhichSuitesWaitAtTheEnd() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TRIGGERS + """
            page.route('/api/rerun-suites', { body: { triggered: [build(501, BELOW), build(502)] } }, 'POST');
            page.route('/api/rerun-suite', { body: { triggered: [build(503, BELOW)] } }, 'POST');
            await page.load('?pr=13575');
            await page.run('rerunSuites')(['Cache', 'Query'], true, page.el('sectRerun'), page.el('sectNote'));
            const section = page.el('status').textContent;
            await page.run('rerunSuite')('Cache', true, page.el('suiteRerun'));
            report({ section, suite: page.el('status').textContent, note: page.el('sectNote').textContent,
                noteHidden: page.el('sectNote').classList.contains('hidden') });
            """);

        assertThat(out.get("section").asText()).isEqualTo("Queued #501 · #502; 1 of them " + NOTE);
        assertThat(out.get("note").asText()).as("under the buttons").isEqualTo("1 of them queued " + NOTE);
        assertThat(out.get("noteHidden").asBoolean()).isFalse();
        assertThat(out.get("suite").asText()).isEqualTo("Queued #503, " + NOTE);
    }

    /** TeamCity answered 500 to the move: that says nothing about what the account may do. */
    @Test
    void aRefusalOtherThanForbiddenDoesNotBlameTheAccount() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TRIGGERS + """
            page.route('/api/rerun-suite', { body: { triggered: [build(503, { belowTop: true, topRefusal: 500 })] } },
                'POST');
            await page.load('?pr=13575');
            await page.run('rerunSuite')('Cache', true, page.el('suiteRerun'));
            report({ status: page.el('status').textContent });
            """);

        assertThat(out.get("status").asText())
            .isEqualTo("Queued #503, at the end of the queue: TeamCity refused to move it to the top.");
    }

    @Test
    void aBuildAtTheTopGetsNoNote() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TRIGGERS + """
            page.route('/api/trigger', { body: { triggered: [build(501)], replaced: 0 } }, 'POST');
            await page.load('?pr=13575');
            await page.run('doTrigger')('trigger', 'true', page.el('runTop'));
            report({ status: page.el('status').textContent });
            """);

        assertThat(out.get("status").asText()).isEqualTo("Queued 1 build: #501.");
    }
}
