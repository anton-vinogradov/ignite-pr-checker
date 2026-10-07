package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * About 14 of 51 blockers carried "environment/timing — a re-run may pass", testGroupReservation's
 * "expected:&lt;false&gt; but was:&lt;true&gt;" among them: the page searched the whole output for "timeout",
 * and every Ignite test's stdout has one. The label now comes from what the server read in the message and
 * the stack trace, and a test that failed every run of its code is not told a re-run may pass. Runs the
 * checker never compared by revision, as for a test failing on master too, are not called runs of one code.
 */
class FailureLabelTest {
    /** {@code why(list)} opens the failure message of the first test there and returns what it shows. */
    private static final String WHY = """
        const unesc = v => v.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>').replace(/&amp;/g, '&');
        async function why(list) {
            const attrs = page.el(list).innerHTML.match(new RegExp('<div class="details hidden"([^>]*)>'))[1];
            const box = page.el('box-' + list);
            for (const a of attrs.matchAll(/data-([a-z]+)="([^"]*)"/g)) box.dataset[a[1]] = unesc(a[2]);
            box.querySelector = () => page.el('pre-' + list);
            page.el('pre-' + list).dataset.loaded = '';
            await page.run('openDetails')(box);
            await page.settle();
            return page.el('pre-' + list).textContent;
        }
        """;

    @Test
    void assertionWhoseStdoutMentionsTimeoutsReadsAsAnAssertion() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + WHY + """
            page.route('/api/test-details', { body: { kind: 'assertion', details: 'expected:<false> but was:<true>\\n'
                + '------- Stdout: -------\\n[01:16:38] ignoredFailureTypes=[SYSTEM_CRITICAL_OPERATION_TIMEOUT]' } });
            await page.load('?pr=13575');
            report({ shown: await why('blockers') });
            """);

        assertThat(out.get("shown").asText())
            .startsWith("⚖ assertion — likely a real logic failure\n\nexpected:<false>");
    }

    @Test
    void timingFailureOfEveryRunOfItsCodeIsNotToldToReRun() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + WHY + """
            page.route('/api/test-details', { body: { kind: 'environment', details: 'Failed to wait for topology' } });
            await page.load('?pr=13575');
            const always = await why('blockers');
            const once = Object.assign(blocker('TxRecoveryTest.testCommit', 'occ-2'), { branchRuns: 'PPF', codeRuns: 1 });
            page.route('/api/analyze', { body: verdict({ buildId: 9100, blockers: [once] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ always, once: await why('blockers') });
            """);

        assertThat(out.get("always").asText())
            .startsWith("♻ environment/timing — but it failed all 3 runs of this code, so a re-run alone is unlikely "
                + "to pass")
            .doesNotContain("a re-run may pass");
        assertThat(out.get("once").asText()).startsWith("♻ environment/timing — a re-run may pass");
    }

    @Test
    void testFailingOnMasterTooIsNotSaidToFailEveryRunOfItsCode() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + WHY + """
            const preExisting = Object.assign(blocker('TxRecoveryTest.testCommit', 'occ-9'), { blocker: false,
                reason: 'pre-existing: fails 12/100 on master', branchRuns: 'FF', codeRuns: 0 });
            page.route('/api/analyze', { body: verdict({ filtered: [preExisting] }) });
            page.route('/api/test-details', { body: { kind: 'environment', details: 'Failed to wait for topology' } });
            await page.load('?pr=13575');
            report({ shown: await why('filtered') });
            """);

        assertThat(out.get("shown").asText()).startsWith("♻ environment/timing — a re-run may pass");
    }

    @Test
    void hangPointsAtTheThreadDump() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + WHY + """
            page.route('/api/test-details', { body: { kind: 'hang',
                details: 'Test has been timed out [test=testPutAllAsyncFailoverManyThreads, timeout=120000]' } });
            await page.load('?pr=13575');
            report({ shown: await why('blockers') });
            """);

        assertThat(out.get("shown").asText()).startsWith("⌛ hang — the test ran out of time; the test's thread in the "
            + "thread dump shows where it waits");
    }

    @Test
    void flakyBoardDoesNotSuggestReRunningATestThatFailsEveryMasterRun() throws Exception {
        JsonNode out = PageScript.run("flaky.html", """
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite' } });
            page.route('/api/top-flaky', { body: { tests: [] } });
            page.route('/api/reruns', { body: [] });
            page.route('/api/test-details', { body: { kind: 'environment', details: 'Node has not joined' } });
            await page.load('');
            async function why(entry) {
                const btn = page.el('why'), row = page.el('row'), box = page.el('box'), pre = page.el('pre');
                btn.dataset.occ = 'occ-1';
                btn.closest = () => row;
                row.querySelector = () => box;
                box.classList.add('hidden');
                box.querySelector = () => pre;
                pre.dataset.loaded = '';
                await page.run('toggleDetails')(btn, entry);
                await page.settle();
                return pre.textContent;
            }
            report({ broken: await why({ masterFails: 100, masterRuns: 100 }),
                flaky: await why({ masterFails: 3, masterRuns: 100 }) });
            """);

        assertThat(out.get("broken").asText())
            .startsWith("♻ environment/timing — but it failed all 100 recent master runs, so a re-run alone is "
                + "unlikely to pass");
        assertThat(out.get("flaky").asText()).startsWith("♻ environment/timing — a re-run may pass");
    }
}
