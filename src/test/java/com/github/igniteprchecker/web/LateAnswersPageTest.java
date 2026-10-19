package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A reader clicked Rerun, Run or Cancel my runs on PR 13575 and opened PR 13461 before the answer came. The answer
 * then landed on 13461: "Queued #777" in its status line and a queued chip on its suite. A refusal under the Rerun
 * buttons stayed there on the next PR, and a late answer about an earlier build's tests replaced the card of the build
 * on screen. Where the visa of 13575 stands must not show on 13461 either.
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
            page.run('paintVisaLine')();
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
    void aRunAllQueuedAfterAnotherPrOpenedIsNotToldThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            await page.load('?pr=13575');
            hold('/api/trigger', 'POST');
            const queuing = page.run('doTrigger')('trigger', 'false', page.el('runBtn'));
            await page.settle();
            const asked = page.fetches('/api/trigger')[0].url;
            await openOther();
            await release('/api/trigger', { body: { triggered: [{ buildId: 778, webUrl: 'https://ci2.example/build/778' }] } });
            await queuing;
            report({ asked, status: page.el('status').textContent });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("/api/trigger?pr=13575&top=false");
        assertThat(out.get("status").asText()).doesNotContain("Queued", "778");
    }

    @Test
    void runsCancelledAfterAnotherPrOpenedAreNotToldThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100' }] });
            await page.load('?pr=13575');
            hold('/api/cancel-all', 'POST');
            const cancelling = page.run('cancelMine')();
            await page.settle();
            const asked = page.fetches('/api/cancel-all')[0].url;
            page.route('/api/runs', { body: [] });
            await openOther();
            await release('/api/cancel-all', { body: { cancelled: 1 } });
            await cancelling;
            report({ asked, status: page.el('status').textContent });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("/api/cancel-all?pr=13575&ids=9100");
        assertThat(out.get("status").asText()).doesNotContain("Cancelled");
    }

    /** The visa of 13575 was posted; on 13461 the page could not ask where the visa stands. */
    @Test
    void theVisaLineOfOnePrIsNotShownOnTheNext() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/visa', { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049',
                url: 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1#comment-1' } });
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            await page.run('loadVisaLine')(13575);
            const posted = page.el('visaLine').textContent;
            page.route('/api/visa', { status: 502, body: {} });
            page.route('/api/analyze', { body: verdict({ prNumber: 13461, buildId: 9300 }) });
            await openOther();
            await page.run('loadVisaLine')(13461);
            report({ posted, line: page.el('visaLine').textContent,
                asked: page.fetches('/api/visa?pr=13461').length });
            """);

        assertThat(out.get("posted").asText()).isEqualTo("Visa: posted to IGNITE-29049");
        assertThat(out.get("asked").asInt()).isPositive();
        assertThat(out.get("line").asText()).doesNotContain("posted");
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
    void aVisaLineAnsweredAfterAnotherPrOpenedIsNotShownThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            hold('/api/visa');
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            page.run('loadVisaLine')(13575);
            await page.settle();
            const asked = page.fetches('/api/visa')[0].url;
            await openOther();
            await release('/api/visa', { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049',
                url: 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1#comment-1' } });
            report({ asked, line: page.el('visaLine').textContent });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("/api/visa?pr=13575&build=9001");
        assertThat(out.get("line").asText()).doesNotContain("IGNITE-29049");
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

    /** A re-run of 13575 was queued when 13461 opened: until 13461's own runs came, the page held it as 13461's. */
    @Test
    void theQueuedRunsOfThePreviousPrGoWhenAnotherOpens() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9200, buildTypeId: 'IgniteTests24Java8_Cache',
                state: 'queued', webUrl: 'https://ci2.example/build/9200' }] });
            await page.load('?pr=13575');
            const before = page.run('lastRuns').map(b => b.buildId);
            hold('/api/runs');
            await openOther();
            report({ before, after: page.run('lastRuns').map(b => b.buildId) });
            """);

        assertThat(out.get("before").toString()).isEqualTo("[9200]");
        assertThat(out.get("after").toString()).isEqualTo("[]");
    }

    /**
     * RunAll 9100 finished while the page still waited for its tests as of the running chain. That answer came after
     * the one for the finished run and put back "no runs yet: this RunAll is still going".
     */
    @Test
    void theTestsOfTheRunningChainDoNotReplaceThoseOfTheFinishedOne() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            const tests = runs => ({ buildId: 9100, note: null, classes: [
                { name: 'org.apache.ignite.NewTest', path: 'p', added: true, runs }] });
            hold('/api/pr-tests');
            page.route('/api/analyze', { body: verdict({ buildId: 9100, live: true, liveBuildId: 9100 }) });
            await page.load('?pr=13575');
            page.route('/api/pr-tests', { body: tests([{ testId: '1', name: 'org.apache.ignite.NewTest.testPut',
                status: 'SUCCESS', durationMs: 1000, suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache' }]) });
            page.route('/api/analyze', { body: verdict({ buildId: 9100, computedAt: page.now() }) });
            await page.run('analyze')(13575);
            await page.settle();
            const finished = page.el('prTests').textContent;
            await release('/api/pr-tests', { body: tests([]) });
            report({ asked: page.fetches('/api/pr-tests').map(f => f.url), finished, list: page.el('prTests').textContent });
            """);

        assertThat(out.get("asked").get(0).asText()).endsWith("&running=true");
        assertThat(out.get("finished").asText()).contains("1 passed in Cache");
        assertThat(out.get("list").asText()).contains("1 passed in Cache").doesNotContain("still going");
    }
}
