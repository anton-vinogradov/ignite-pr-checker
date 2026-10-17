package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A reader clicked Rerun or Post now on PR 13575 and opened PR 13461 before the answer came. The answer then landed on
 * 13461: "Queued #777" in its status line and a queued chip on its suite, "Visa posted to IGNITE-28972" for a visa
 * that went to IGNITE-29049, an Auto visa shown as armed on the wrong PR. A refusal under the Rerun buttons stayed
 * there on the next PR, and a late answer about an earlier build's tests replaced the card of the build on screen.
 */
class LateAnswersPageTest {
    /** Holds the answers to {@code prefix}; {@code release(prefix, answer)} lets the earliest one go. */
    private static final String HELD = """
        const held = {};
        function hold(prefix, method) {
            page.route(prefix, () => new Promise(resolve => { (held[prefix] ||= []).push(resolve); }), method);
        }
        async function release(prefix, answer) {
            held[prefix].shift()(answer);
            await page.settle();
        }
        async function openOther() {
            page.run('openPr')(13461, true);
            await page.settle();
            page.run('setPrTitle')(13461, 'IGNITE-28972 Check marshalled kind', null);
            page.run('paintVisaGroup')();
        }
        """;

    @Test
    void aRerunAnsweredAfterAnotherPrOpenedLeavesThatPrAlone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            await page.load('?pr=13575');
            hold('/api/rerun-suites', 'POST');
            hold('/api/rerun-suite', 'POST');
            const sect = page.run('rerunSuites')(['IgniteTests24Java8_Cache'], false, page.el('btn'),
                page.el('blockerActsNote'));
            const row = page.run('rerunSuite')('IgniteTests24Java8_Cache', false, page.el('rowBtn'));
            await page.settle();
            await openOther();
            await release('/api/rerun-suites', { body: { triggered: [{ buildId: 777, btId: 'IgniteTests24Java8_Cache',
                webUrl: 'https://ci2.example/build/777' }] } });
            await release('/api/rerun-suite', { status: 409, body: { error: 'Already queued' } });
            await sect;
            await row;
            report({ status: page.el('status').textContent, note: page.el('blockerActsNote').textContent,
                chips: page.run('lastRuns').map(b => b.buildId) });
            """);

        assertThat(out.get("status").asText()).doesNotContain("777", "Not queued");
        assertThat(out.get("note").asText()).isEmpty();
        assertThat(out.get("chips").toString()).isEqualTo("[]");
    }

    @Test
    void aRefusalUnderTheButtonsGoesWithItsPr() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/rerun-suites', { status: 403, body: { error: 'ci2 refused: no permission' } }, 'POST');
            await page.load('?pr=13575');
            await page.run('rerunSuites')(['IgniteTests24Java8_Cache'], false, page.el('btn'), page.el('blockerActsNote'));
            const refused = !page.el('blockerActsNote').classList.contains('hidden');
            await openOther();
            report({ refused, after: !page.el('blockerActsNote').classList.contains('hidden') });
            """);

        assertThat(out.get("refused").asBoolean()).isTrue();
        assertThat(out.get("after").asBoolean()).isFalse();
    }

    @Test
    void aVisaPostedAfterAnotherPrOpenedIsNotToldThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/me', { body: { username: 'alice', jira: true } });
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            page.run('paintVisaGroup')();
            hold('/api/jira-visa', 'POST');
            const posting = page.run('postVisa')();
            await page.settle();
            const asked = page.fetches('/api/jira-visa')[0].url;
            await openOther();
            await release('/api/jira-visa', { body: { url: 'https://issues.apache.org/jira/browse/IGNITE-29049#c1' } });
            await posting;
            report({ asked, note: !page.el('visaNote').classList.contains('hidden'),
                status: page.el('status').textContent });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("/api/jira-visa?pr=13575&issue=IGNITE-29049");
        assertThat(out.get("note").asBoolean()).isFalse();
        assertThat(out.get("status").asText()).doesNotContain("Visa posted");
    }

    @Test
    void anAutoVisaArmedAfterAnotherPrOpenedIsNotShownThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/me', { body: { username: 'alice', jira: true } });
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            page.run('paintVisaGroup')();
            hold('/api/auto-visa');
            const arming = page.run('armAutoVisa')();
            await page.settle();
            await openOther();
            await release('/api/auto-visa', { body: { armed: true, issue: 'IGNITE-29049' } });
            await arming;
            report({ armed: page.run('autoVisa').armed, button: page.el('autoVisaBtn').textContent,
                note: !page.el('visaNote').classList.contains('hidden') });
            """);

        assertThat(out.get("armed").asBoolean()).isFalse();
        assertThat(out.get("button").asText()).isEqualTo("Auto visa");
        assertThat(out.get("note").asBoolean()).isFalse();
    }

    /** The tests of RunAll 9001 came after the page had moved on to RunAll 9100 of the same PR. */
    @Test
    void theTestsOfAnEarlierBuildDoNotReplaceTheCard() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            hold('/api/pr-tests');
            await page.load('?pr=13575');
            page.route('/api/pr-tests', { body: { buildId: 9100, note: null, classes: [
                { name: 'org.apache.ignite.NewerTest', path: 'p', added: true, runs: [] }] } });
            page.route('/api/analyze', { body: verdict({ buildId: 9100, computedAt: page.now() }) });
            await page.run('analyze')(13575);
            await page.settle();
            await release('/api/pr-tests', { body: { buildId: 9001, note: null, classes: [
                { name: 'org.apache.ignite.OlderTest', path: 'p', added: true, runs: [] }] } });
            report({ shown: page.run('lastPrTests').buildId, list: page.el('prTests').textContent });
            """);

        assertThat(out.get("shown").asLong()).isEqualTo(9100L);
        assertThat(out.get("list").asText()).contains("NewerTest").doesNotContain("OlderTest");
    }

    /** The re-runs ci2 tracks for 13575 came after 13461 opened: they are not drawn as 13461's chips. */
    @Test
    void theReRunsOfThePreviousPrDoNotBecomeChips() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            await page.load('?pr=13575');
            hold('/api/reruns');
            const polling = page.run('loadRuns')(13575);
            await page.settle();
            await openOther();
            await release('/api/reruns', { body: [{ pr: 13575, buildId: 9200, buildTypeId: 'IgniteTests24Java8_Cache',
                state: 'queued', webUrl: 'https://ci2.example/build/9200' }] });
            await polling;
            report({ chips: page.run('lastRuns').map(b => b.buildId) });
            """);

        assertThat(out.get("chips").toString()).doesNotContain("9200");
    }
}
