package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Enter on "Rerun 2 suites" in Broken suites: Chrome took the focus off the button as the page disabled it for the
 * request, so the focus fell to the page; the answer "⚠ Not queued…" went into the note under the buttons and the
 * status line, and the page had no live region to read it out. The answer to Rerun, Run and Cancel my runs is read out
 * from one polite live region, emptied first so the same answer twice is heard twice; the focus comes back to the
 * button, on the status page too.
 */
class SpokenAnswersPageTest {
    /** A button that loses the focus when it is disabled, as in a browser; the scenario focuses it. */
    private static final String BUTTONS = """
        const doc = page.run('document');
        function focused(id) {
            const b = page.el(id);
            let off = false;
            Object.defineProperty(b, 'disabled', { get: () => off,
                set(v) { off = !!v; if (off && doc.activeElement === b) doc.activeElement = doc.body; } });
            b.focus();
            return b;
        }
        const answer = () => page.el('actAnswer').textContent;
        """;

    @Test
    void aRerunAnswerIsReadOutAndTheFocusComesBack() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BUTTONS + """
            page.route('/api/rerun-suites', { status: 403, body: { error: 'ci2 refused: no permission' } }, 'POST');
            await page.load('?pr=13575');
            const btn = focused('btn');
            await page.run('rerunSuites')(['IgniteTests24Java8_Cache'], false, btn, page.el('brokenActsNote'));
            await page.tick(16);
            const first = { answer: answer(), back: doc.activeElement === btn };
            await page.run('rerunSuites')(['IgniteTests24Java8_Cache'], false, btn, page.el('brokenActsNote'));
            const emptied = answer();
            await page.tick(16);
            report({ first, emptied, again: answer(), status: page.el('status').getAttribute('role') });
            """);

        assertThat(out.at("/first/answer").asText()).isEqualTo("⚠ Not queued: ci2 refused: no permission");
        assertThat(out.at("/first/back").asBoolean()).as("the focus is back on the button").isTrue();
        assertThat(out.get("emptied").asText()).isEmpty();
        assertThat(out.get("again").asText()).isEqualTo("⚠ Not queued: ci2 refused: no permission");
        assertThat(out.get("status").isNull()).as("the status line stays quiet: it also shows progress").isTrue();
    }

    /** The Rerun of one suite row: a refusal and a queued build are both read out, and the focus comes back. */
    @Test
    void aRerunOfOneSuiteIsReadOutAndTheFocusComesBack() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BUTTONS + """
            page.route('/api/rerun-suite', { status: 403, body: { error: 'ci2 refused: no permission' } }, 'POST');
            await page.load('?pr=13575');
            const btn = focused('rowRerun');
            const press = async () => {
                await page.run('rerunSuite')('IgniteTests24Java8_Cache', false, btn);
                await page.tick(16);
                return { answer: answer(), back: doc.activeElement === btn };
            };
            const refused = await press();
            page.route('/api/rerun-suite', { body: { triggered: [{ buildId: 777,
                webUrl: 'https://ci2.example/build/777' }] } }, 'POST');
            report({ refused, queued: await press() });
            """);

        assertThat(out.at("/refused/answer").asText()).isEqualTo("⚠ Not queued: ci2 refused: no permission");
        assertThat(out.at("/queued/answer").asText()).isEqualTo("Queued #777.");
        assertThat(out.at("/refused/back").asBoolean()).as("the focus is back after a refusal").isTrue();
        assertThat(out.at("/queued/back").asBoolean()).as("the focus is back after a queued build").isTrue();
    }

    /**
     * Run and Cancel my runs ask ci2 for the PR's runs first. The answer that the runs could not be checked, and that
     * none of the user's runs is still going, are read out too.
     */
    @Test
    void anAnswerBeforeTheRequestIsReadOutToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BUTTONS + """
            page.route('/api/runs', { status: 503, body: {} });
            await page.load('?pr=13575');
            await page.run('doTrigger')('trigger', 'false', focused('runBtn'));
            await page.tick(16);
            const unchecked = answer();
            page.route('/api/runs', { body: [] });
            focused('cancelMine');
            await page.run('cancelMine')();
            await page.tick(16);
            report({ unchecked, none: answer() });
            """);

        assertThat(out.get("unchecked").asText())
            .isEqualTo("⚠ Could not check the runs of this PR. Try again in a moment.");
        assertThat(out.get("none").asText()).isEqualTo("None of your runs of this PR is still going.");
    }

    @Test
    void runAndCancelAreReadOutToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BUTTONS + """
            page.route('/api/runs', { body: [{ buildId: 201, state: 'running', name: 'RunAll', btId: 'RunAll',
                webUrl: 'https://ci2.example/build/201', pct: -1, by: 'alice', mine: true, runAll: true }] });
            page.route('/api/trigger', { body: { triggered: [{ buildId: 301, webUrl: 'https://ci2.example/build/301' }],
                replaced: true } }, 'POST');
            page.route('/api/cancel-all', { body: { cancelled: 1 } }, 'POST');
            await page.load('?pr=13575');
            const heard = {};
            const press = async (name, id, act) => {
                const b = focused(id);
                await act(b);
                await page.tick(16);
                heard[name] = { answer: answer(), back: doc.activeElement === b };
            };
            await press('run', 'runBtn', b => page.run('doTrigger')('trigger', 'false', b));
            await press('cancel', 'cancelMine', () => page.run('cancelMine')());
            report(heard);
            """);

        assertThat(out.at("/run/answer").asText())
            .isEqualTo("Queued 1 build: #301. Your previous RunAll was cancelled.");
        assertThat(out.at("/cancel/answer").asText()).isEqualTo("Cancelled 1 run.");
        for (String button : new String[] {"run", "cancel"})
            assertThat(out.at("/" + button + "/back").asBoolean()).as(button).isTrue();
    }

    @Test
    void flushingTheCachesGivesTheFocusBack() throws Exception {
        JsonNode out = PageScript.run("status.html", BUTTONS + """
            page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
            page.route('/api/status', { status: 503, body: {} });
            page.route('/api/flush-caches', { body: { results: 3, history: 1 } }, 'POST');
            await page.load('');
            const btn = focused('flushBtn');
            await btn.listeners.click[0]();
            report({ msg: page.el('flushMsg').textContent, back: doc.activeElement === btn });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("cleared 3 results, 1 history entry");
        assertThat(out.get("back").asBoolean()).isTrue();
    }

    @Test
    void aRefusedRestartGivesTheFocusBack() throws Exception {
        JsonNode out = PageScript.run("status.html", BUTTONS + """
            page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
            page.route('/api/status', { status: 503, body: {} });
            page.route('/api/restart', { status: 403, body: { error: 'Only an admin can restart the service.' } },
                'POST');
            await page.load('');
            const btn = focused('restartBtn');
            await btn.listeners.click[0]();
            report({ msg: page.el('restartMsg').textContent, back: doc.activeElement === btn });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("Only an admin can restart the service.");
        assertThat(out.get("back").asBoolean()).isTrue();
    }
}
