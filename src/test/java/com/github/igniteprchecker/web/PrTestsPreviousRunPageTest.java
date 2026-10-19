package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * PR 13575: in RunAll 9001 ClientReconnectTest.testReconnect failed and stood in Blockers. RunAll 9002 finished, and a
 * quiet look brought its verdict with no blockers. "This PR's tests" kept the answer about RunAll 9001 with "1 failed
 * (blocks this PR)" under a head that said no blockers, and kept it when the answer about RunAll 9002 failed. The card
 * now keeps its place with the tests of 9001, says they are of an earlier RunAll and drops what that run's verdict did
 * with them; it goes when the answer about the new RunAll does not come.
 */
class PrTestsPreviousRunPageTest {
    private static final String RECONNECT = """
        const failure = { testId: '5272433775095107011', name: 'org.apache.ignite.ClientReconnectTest.testReconnect',
            suite: 'IgniteTests24Java8_Cache', suiteBuildId: 9002, suiteName: 'Cache', occurrenceId: 'occ-1',
            status: 'FAILURE', durationMs: 3000, masterRuns: 40, muted: false };
        const reconnect = { name: 'org.apache.ignite.ClientReconnectTest', path: 'ClientReconnectTest.java',
            added: false, notInSuite: false, atRun: null, runs: [failure] };
        let answer9002 = null;
        page.route('/api/pr-tests', url => /build=9002/.test(url)
            ? new Promise(done => { answer9002 = done; })
            : { body: { buildId: 9001, note: null, runsKnown: true, classes: [reconnect] } });
        const card = () => ({ hidden: page.el('prTestsCard').classList.contains('hidden'),
            previous: !page.el('prTestsPrevious').classList.contains('hidden'),
            text: page.el('prTests').textContent });
        """;

    /** The tests of RunAll 9001 come at once, and the tests of a newer RunAll are still on the way. */
    private static final String NEWER_TESTS_LOADING = """
        page.route('/api/pr-tests', url => /build=9001/.test(url)
            ? { body: { buildId: 9001, note: null, runsKnown: true, classes: [reconnect] } }
            : new Promise(() => {}));
        """;

    @Test
    void aNewRunShowsThePreviousTestsWithoutTheirVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + """
            await page.load('?pr=13575');
            const before = card();
            page.run('renderResult')(verdict({ buildId: 9002, blockers: [] }), { quiet: true });
            await page.settle();
            const waiting = card();
            const passed = Object.assign({}, failure, { status: 'SUCCESS' });
            answer9002({ body: { buildId: 9002, note: null, runsKnown: true,
                classes: [Object.assign({}, reconnect, { runs: [passed] })] } });
            await page.settle();
            report({ before, waiting, after: card() });
            """);

        assertThat(out.at("/before/text").asText()).contains("1 failed (blocks this PR)");
        assertThat(out.at("/before/previous").asBoolean()).isFalse();
        assertThat(out.at("/waiting/hidden").asBoolean()).as("the card keeps its place").isFalse();
        assertThat(out.at("/waiting/previous").asBoolean()).isTrue();
        assertThat(out.at("/waiting/text").asText()).contains("1 failed in Cache").doesNotContain("blocks this PR");
        assertThat(out.at("/after/previous").asBoolean()).isFalse();
        assertThat(out.at("/after/text").asText()).contains("1 passed in Cache");
    }

    @Test
    void thePreviousTestsGoWhenTheNewOnesDoNotCome() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + """
            await page.load('?pr=13575');
            page.run('renderResult')(verdict({ buildId: 9002, blockers: [] }), { quiet: true });
            await page.settle();
            answer9002({ status: 502, body: {} });
            await page.settle();
            report({ after: card() });
            """);

        assertThat(out.at("/after/hidden").asBoolean()).isTrue();
    }

    /** The answer about RunAll 9002 never comes: the connection broke while the service restarted. */
    @Test
    void thePreviousTestsGoWhenTheNewOnesFailToLoad() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + """
            let fail9002 = null;
            page.route('/api/pr-tests', url => /build=9002/.test(url)
                ? new Promise((done, fail) => { fail9002 = fail; })
                : { body: { buildId: 9001, note: null, runsKnown: true, classes: [reconnect] } });
            await page.load('?pr=13575');
            page.run('renderResult')(verdict({ buildId: 9002, blockers: [] }), { quiet: true });
            await page.settle();
            const waiting = card();
            fail9002(new TypeError('Failed to fetch'));
            await page.settle();
            report({ waiting, after: card() });
            """);

        assertThat(out.at("/waiting/previous").asBoolean()).isTrue();
        assertThat(out.at("/after/hidden").asBoolean()).isTrue();
    }

    /**
     * The tab with PR 13575 on RunAll 9001 was hidden while RunAlls 9002 and 9003 finished. Back in the tab, the
     * verdict of 9003 replaced the verdict of 9001: the card's note does not call 9001 the RunAll before 9003.
     */
    @Test
    void theNoteDoesNotSayWhichRunTheTestsAreOf() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + NEWER_TESTS_LOADING + """
            await page.load('?pr=13575');
            page.run('renderResult')(verdict({ buildId: 9003, blockers: [] }), { quiet: true });
            await page.settle();
            report({ card: card(), note: page.text('prTestsPrevious') });
            """);

        assertThat(out.at("/card/previous").asBoolean()).isTrue();
        assertThat(out.get("note").asText())
            .isEqualTo("These are the tests of an earlier RunAll. The tests of this RunAll are still loading.");
    }

    /**
     * The reader left PR 13575 on RunAll 9001 and came back to it in the same tab after RunAlls 9002 and 9003. The card
     * called the tests of 9001 the tests of the previous RunAll, which was 9002. It keeps its place above "Not blamed on
     * this PR" and says the tests are of an earlier RunAll.
     */
    @Test
    void aReturnToThePrKeepsTheCardInItsPlace() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + NEWER_TESTS_LOADING + """
            await page.load('?pr=13575');
            page.run('goHome')(false);
            page.route('/api/analyze', { body: verdict({ buildId: 9003, blockers: [] }) });
            page.run('openPr')(13575, false);
            await page.settle();
            report({ back: card(), note: page.text('prTestsPrevious'),
                notBlamed: !page.el('filteredCard').classList.contains('hidden') });
            """);

        assertThat(out.at("/back/hidden").asBoolean()).isFalse();
        assertThat(out.at("/back/previous").asBoolean()).isTrue();
        assertThat(out.at("/back/text").asText()).contains("1 failed in Cache").doesNotContain("blocks this PR");
        assertThat(out.get("note").asText()).startsWith("These are the tests of an earlier RunAll.");
        assertThat(out.get("notBlamed").asBoolean()).isTrue();
    }

    /** The reader opens the class's list on the previous RunAll's card: its rows say nothing of that run's verdict. */
    @Test
    void anOpenedClassOfThePreviousRunHasNoVerdictLines() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RECONNECT + """
            reconnect.runs.push(Object.assign({}, failure, { testId: '7', name: failure.name + 'Twice',
                status: 'SUCCESS' }));
            await page.load('?pr=13575');
            page.run('renderResult')(verdict({ buildId: 9002, blockers: [] }), { quiet: true });
            await page.settle();
            const list = page.el('prtTests0'), btn = page.el('btn');
            btn.dataset.cls = reconnect.name;
            btn.setAttribute('aria-expanded', 'false');
            btn.setAttribute('aria-controls', 'prtTests0');
            page.run('togglePrTestClass')(btn);
            report({ rows: list.innerHTML });
            """);

        assertThat(out.get("rows").asText()).contains("testReconnect").doesNotContain("blocks this PR")
            .doesNotContain("class=\"reason\"");
    }
}
