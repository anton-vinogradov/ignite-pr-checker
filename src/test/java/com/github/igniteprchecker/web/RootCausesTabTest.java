package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * PR 13654 after a re-run: 23 blockers, while the header and the Root causes tab still counted the groups
 * made for the 24 before it, as the page kept them per build. Seven blockers TeamCity no longer had a message
 * for were one more "cause" with an "ai" button. And every PR with two blockers made the server fetch up to
 * 80 failure messages for the header count, whether or not anyone opened the tab. A message TeamCity did not
 * answer for is asked again, and only runs older than a week are said to have lost theirs.
 */
class RootCausesTabTest {
    /** Three blockers of PR 13575; {@code groups(over)} is the server's answer for them. */
    private static final String THREE = """
        const b1 = Object.assign(blocker('ClientReconnectTest.testReconnect', 'occ-1'), { testId: '1' });
        const b2 = Object.assign(blocker('TxRecoveryTest.testCommit', 'occ-2'), { testId: '2' });
        const b3 = Object.assign(blocker('TxRecoveryTest.testRollback', 'occ-3'), { testId: '3' });
        const member = b => ({ testId: b.testId, suite: b.suite });
        function groups(over) {
            return Object.assign({ total: 3, sampled: 3, noMessage: [], unread: [], runStartedAt: {},
                clusters: [{ signature: 'Partition N lost', count: 1, tests: [member(b1)] },
                    { signature: 'Lock was not released', count: 1, tests: [member(b2)] }] }, over);
        }
        page.route('/api/analyze', { body: verdict({ blockers: [b1, b2, b3] }) });
        """;

    @Test
    void openingAPrDoesNotMakeTheServerGroupItsBlockers() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + THREE + """
            page.route('/api/causes', url => url.includes('cached=true') ? { status: 204 } : { body: groups() });
            await page.load('?pr=13575');
            const asked = page.fetches('/api/causes').map(f => f.url);
            const header = page.el('blockerCount').textContent;
            page.run('setBlockerView')('causes');
            await page.settle();
            report({ asked, header, after: page.el('blockerCount').textContent,
                grouped: page.fetches('/api/causes').filter(f => !f.url.includes('cached=true')).length });
            """);

        assertThat(out.get("asked")).hasSize(1);
        assertThat(out.get("asked").get(0).asText()).isEqualTo("/api/causes?pr=13575&cached=true");
        assertThat(out.get("header").asText()).isEqualTo("(1 suite, 3 tests)");
        assertThat(out.get("grouped").asInt()).isEqualTo(1);
        assertThat(out.get("after").asText()).isEqualTo("(1 suite, 3 tests, 2 causes)");
    }

    @Test
    void blockersWithoutAMessageAreListedApartAndNotCounted() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + THREE + """
            const sep23 = Date.parse('2026-09-23T01:16:38Z') / 1000;
            page.route('/api/causes', { body: groups({ clusters: [{ signature: 'Partition N lost', count: 1,
                tests: [member(b1)] }], noMessage: [member(b2), member(b3)], runStartedAt: { 9002: sep23 } }) });
            await page.load('?pr=13575');
            page.run('setBlockerView')('causes');
            await page.settle();
            const html = page.el('causesView').innerHTML;
            report({ header: page.el('blockerCount').textContent, ai: html.split('ai-cause').length - 1,
                text: page.el('causesView').textContent });
            """);

        assertThat(out.get("header").asText()).isEqualTo("(1 suite, 3 tests, ≥1 cause)");
        assertThat(out.get("ai").asInt()).isEqualTo(1);
        assertThat(out.get("text").asText())
            .contains("2× no failure message — TeamCity no longer keeps the failure messages of runs from 23 Sep — "
                + "re-run the suite to see why they fail")
            .doesNotContain("not sampled");
    }

    @Test
    void runsWithoutAMessageAreDatedOnlyWhenTheirDateIsKnown() throws Exception {
        String scenario = THREE + """
            page.route('/api/causes', { body: groups({ clusters: [{ signature: 'Partition N lost', count: 1,
                tests: [member(b1)] }], noMessage: [member(b2)], runStartedAt: STARTED }) });
            await page.load('?pr=13575');
            page.run('setBlockerView')('causes');
            await page.settle();
            report({ text: page.el('causesView').textContent });
            """;

        JsonNode recent = PageScript.run("index.html", PageScript.SIGNED_IN
            + "const STARTED = { 9002: Date.parse('2026-10-06T09:00:00Z') / 1000 };\n" + scenario);
        JsonNode undated = PageScript.run("index.html", PageScript.SIGNED_IN + "const STARTED = {};\n" + scenario);

        assertThat(recent.get("text").asText()).contains("1× no failure message — TeamCity has no failure message for "
            + "these runs (from 6 Oct) — re-run the suite to see why it fails");
        assertThat(undated.get("text").asText()).contains("1× no failure message — TeamCity has no failure message for "
            + "these runs — re-run the suite to see why it fails");
    }

    @Test
    void messageTeamCityDidNotAnswerForIsAskedAgainOnTheNextVisit() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + THREE + """
            page.route('/api/causes', url => url.includes('cached=true') ? { status: 204 }
                : { body: groups({ unread: [member(b3)] }) });
            await page.load('?pr=13575');
            page.run('setBlockerView')('causes');
            await page.settle();
            const first = page.el('causesView').textContent;
            page.route('/api/causes', url => url.includes('cached=true') ? { status: 204 } : { body: groups({
                clusters: [...groups().clusters, { signature: 'Node left', count: 1, tests: [member(b3)] }] }) });
            page.run('setBlockerView')('suites');
            page.run('setBlockerView')('causes');
            await page.settle();
            report({ first, ai: page.el('causesView').innerHTML.split('ai-cause').length - 1,
                again: page.el('causesView').textContent,
                grouped: page.fetches('/api/causes').filter(f => !f.url.includes('cached=true')).length });
            """);

        assertThat(out.get("first").asText())
            .contains("1× failure message not loaded — TeamCity did not answer; switch tabs to try again");
        assertThat(out.get("grouped").asInt()).isEqualTo(2);
        assertThat(out.get("again").asText()).contains("Node left").doesNotContain("not loaded");
        assertThat(out.get("ai").asInt()).isEqualTo(3);
    }

    @Test
    void reRunThatClearedABlockerIsRegrouped() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + THREE + """
            page.route('/api/causes', { body: groups() });
            await page.load('?pr=13575');
            const before = page.el('blockerCount').textContent;
            page.route('/api/causes', { body: groups({ total: 2, sampled: 2,
                clusters: [{ signature: 'Partition N lost', count: 1, tests: [member(b1)] }] }) });
            page.route('/api/analyze', { body: verdict({ computedAt: page.now(), blockers: [b1, b3] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ before, after: page.el('blockerCount').textContent });
            """);

        assertThat(out.get("before").asText()).isEqualTo("(1 suite, 3 tests, 2 causes)");
        assertThat(out.get("after").asText()).isEqualTo("(1 suite, 2 tests, 1 cause)");
    }
}
