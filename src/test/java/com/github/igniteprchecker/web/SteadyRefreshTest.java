package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * While a RunAll ran, the PR page went blank about once a minute: the live re-analysis hid the results,
 * rebuilt every list, dropped the scroll position and closed the failure messages the reader had open,
 * and reopening one fetched it from ci2 again. Two poll loops ran at once, a forgotten background tab
 * asked ci2 every 15 seconds around the clock, and each redraw asked again whether the PR head moved.
 */
class SteadyRefreshTest {
    private static final String RUNNING = """
        page.route('/api/runs', { body: [{ buildId: 9100, state: 'running', name: 'RunAll', btId: 'RunAll',
            webUrl: 'https://ci2.example/build/9100', pct: 40, leftSec: 3000, startSec: -1, waitedSec: 60,
            elapsedSec: 600 }] });
        """;

    @Test
    void recomputeWithNothingNewLeavesThePageAsItWas() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            const writes = page.el('blockers').htmlWrites;
            const hides = page.el('results').classLog.filter(c => c === '+hidden').length;
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 60000 }) });
            await page.tick(70000);
            report({
                analyses: page.fetches('/api/analyze').length,
                redraws: page.el('blockers').htmlWrites - writes,
                hidden: page.el('results').classLog.filter(c => c === '+hidden').length - hides,
                status: page.el('status').textContent,
                pending: page.fetches('/api/pending').length,
            });
            """);

        assertThat(out.get("analyses").asInt()).isEqualTo(2);
        assertThat(out.get("redraws").asInt()).isZero();
        assertThat(out.get("hidden").asInt()).isZero();
        assertThat(out.get("status").asText()).doesNotContain("Analyzing");
        assertThat(out.get("pending").asInt()).isEqualTo(1);
    }

    @Test
    void newFailuresAreDrawnInPlaceWithOpenMessagesKept() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');

            const box = (id, hidden) => {
                const b = page.el(id);
                b.dataset.occ = 'occ-1';
                if (hidden) b.classList.add('hidden'); else b.classList.remove('hidden');
                b.closest = () => page.el('blockers');
                const pre = page.el(id + '-pre');
                b.querySelector = () => pre;
                return b;
            };
            const read = box('read', false);
            page.run('openDetails')(read);
            await page.settle();
            const redrawn = box('redrawn', true);
            page.run('document').querySelectorAll = sel => sel === '#results .details:not(.hidden)' ? [read]
                : sel === '#results .details' ? [redrawn] : [];
            const pane = page.el('query:.result-pane');
            pane.scrollTop = 840;
            const list = page.el('blockers');
            const html = Object.getOwnPropertyDescriptor(list, 'innerHTML');
            Object.defineProperty(list, 'innerHTML', { get: html.get, set(v) { html.set.call(this, v); pane.scrollTop = 0; } });

            const writes = page.el('blockers').htmlWrites;
            const hides = page.el('results').classLog.filter(c => c === '+hidden').length;
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 60000,
                blockers: [blocker('ClientReconnectTest.testReconnect', 'occ-1'), blocker('TxRecoveryTest.testCommit', 'occ-2')] }) });
            await page.tick(70000);
            report({
                redraws: page.el('blockers').htmlWrites - writes,
                hidden: page.el('results').classLog.filter(c => c === '+hidden').length - hides,
                reopened: !redrawn.classList.contains('hidden'),
                message: page.el('redrawn-pre').textContent,
                fetchedMessages: page.fetches('/api/test-details').length,
                scroll: page.el('query:.result-pane').scrollTop,
                pending: page.fetches('/api/pending').length,
                deltas: page.fetches('/api/delta').length,
            });
            """);

        assertThat(out.get("redraws").asInt()).isEqualTo(1);
        assertThat(out.get("hidden").asInt()).isZero();
        assertThat(out.get("reopened").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).contains("expected:<1> but was:<2>");
        assertThat(out.get("fetchedMessages").asInt()).isEqualTo(1);
        assertThat(out.get("scroll").asInt()).isEqualTo(840);
        assertThat(out.get("pending").asInt()).as("same build: the head-moved note is not asked again").isEqualTo(1);
        assertThat(out.get("deltas").asInt()).isEqualTo(2);
    }

    @Test
    void newBuildAsksAgainWhetherTheHeadMoved() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            page.route('/api/analyze', { body: verdict({ buildId: 9100, computedAt: page.now() + 60000 }) });
            await page.tick(70000);
            report({ pending: page.fetches('/api/pending').length });
            """);

        assertThat(out.get("pending").asInt()).isEqualTo(2);
    }

    @Test
    void firstVerdictOfAPrWithoutRunsReplacesTheNoRunNote() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { status: 404, body: { error: 'no RunAll build found for PR 13575' } });
            await page.load('?pr=13575');
            const before = page.el('status').textContent;
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9100 }) });
            """ + RUNNING + """
            await page.tick(100000);
            report({ before, after: page.el('status').textContent,
                shown: !page.el('results').classList.contains('hidden') });
            """);

        assertThat(out.get("before").asText()).startsWith("No RunAll run for this PR yet");
        assertThat(out.get("after").asText()).isEmpty();
        assertThat(out.get("shown").asBoolean()).isTrue();
    }

    @Test
    void failedRefreshMessageGoesOnceTheNextLookAnswers() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.route('/api/refresh', { status: 502,
                body: { error: 'TeamCity is unreachable (network or DNS hiccup) — try again in a moment' } }, 'POST');
            await page.run('refresh')();
            const failed = page.el('status').textContent;
            await page.tick(5000);
            const answered = page.el('status').textContent;
            await page.run('refresh')();
            page.el('status').textContent = 'Queued 1 build(s): #9100';
            await page.tick(5000);
            report({ failed, answered, analyses: page.fetches('/api/analyze').length, queued: page.el('status').textContent });
            """);

        assertThat(out.get("failed").asText()).startsWith("TeamCity is unreachable");
        assertThat(out.get("answered").asText()).isEmpty();
        assertThat(out.get("analyses").asInt()).isEqualTo(3);
        assertThat(out.get("queued").asText()).as("a newer note outlives the look").startsWith("Queued 1 build");
    }

    @Test
    void returningToTheTabDoesNotRecomputeAFinishedVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            await page.tick(300000);
            await page.setHidden(true);
            await page.tick(3600000);
            const runs = page.fetches('/api/runs').length;
            await page.setHidden(false);
            report({ analyses: page.fetches('/api/analyze').length, runs: page.fetches('/api/runs').length - runs,
                label: page.el('freshText').textContent });
            """);

        assertThat(out.get("analyses").asInt()).as("a finished verdict is not asked again").isEqualTo(1);
        assertThat(out.get("runs").asInt()).isEqualTo(1);
        assertThat(out.get("label").asText()).contains("analysed 1h ago");
    }

    @Test
    void runThatFinishedWhileTheTabWasHiddenRefreshesTheVerdictOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9100 }) });
            await page.load('?pr=13575');
            await page.setHidden(true);
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', { body: verdict({ buildId: 9100 }) }, 'POST');
            await page.tick(3600000);
            await page.setHidden(false);
            report({ analyses: page.fetches('/api/analyze').length, refreshes: page.fetches('/api/refresh').length });
            """);

        assertThat(out.get("analyses").asInt()).isEqualTo(1);
        assertThat(out.get("refreshes").asInt()).isEqualTo(1);
    }

    /**
     * ci2 failing as the run ended once left the page on the unfinished run's verdict for good: the refresh
     * and the one quiet look after it failed, and nothing asked again once the runs were gone.
     */
    @Test
    void verdictOfTheFinishedRunArrivesAfterAnOutage() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9100 }) });
            await page.load('?pr=13575');
            const back = page.now() + 20000;
            const down = { status: 502, body: { error: 'TeamCity answered 502' } };
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', () => page.now() < back ? down : { body: verdict({ buildId: 9100 }) }, 'POST');
            page.route('/api/analyze', () => page.now() < back ? down
                : { body: verdict({ buildId: 9100, computedAt: page.now() }) });
            await page.tick(600000);
            const shown = page.run('lastResult');
            const looks = page.fetches('/api/analyze').length;
            await page.tick(600000);
            report({ build: shown.buildId, live: shown.live, fresh: page.el('freshText').textContent,
                status: page.el('status').textContent, looksAfter: page.fetches('/api/analyze').length - looks });
            """);

        assertThat(out.get("build").asInt()).isEqualTo(9100);
        assertThat(out.get("live").asBoolean()).isFalse();
        assertThat(out.get("fresh").asText()).doesNotContain("unfinished run");
        assertThat(out.get("status").asText()).isEmpty();
        assertThat(out.get("looksAfter").asInt()).as("the final verdict is not asked again").isZero();
    }

    /** A look that brings back the unfinished run's verdict, served while the server recomputes, is not the end. */
    @Test
    void verdictOfTheFinishedRunIsAwaitedPastTheUnfinishedOne() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9100 }) });
            await page.load('?pr=13575');
            const recomputed = page.now() + 200000;
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', { status: 502, body: { error: 'TeamCity answered 502' } }, 'POST');
            page.route('/api/analyze', () => ({ body: page.now() < recomputed
                ? verdict({ live: true, liveBuildId: 9100 }) : verdict({ buildId: 9100, computedAt: page.now() }) }));
            await page.tick(600000);
            const shown = page.run('lastResult');
            report({ build: shown.buildId, live: shown.live });
            """);

        assertThat(out.get("build").asInt()).isEqualTo(9100);
        assertThat(out.get("live").asBoolean()).isFalse();
    }

    @Test
    void hiddenTabStopsAskingAndCatchesUpWhenShown() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', () => ({ body: verdict({ computedAt: page.now() - 30000 }) }));
            await page.load('?pr=13575');
            const idle = page.timers().filter(t => !t.repeat).map(t => t.ms);
            await page.setHidden(true);
            await page.tick(3600000);
            const whileHidden = page.fetches('/api/runs').length;
            await page.setHidden(false);
            const afterShown = page.fetches('/api/runs').length;
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'queued', name: 'RunAll', btId: 'RunAll',
                webUrl: 'https://ci2.example/build/9100', pct: -1, leftSec: -1, startSec: 600, waitedSec: 30,
                elapsedSec: -1 }] });
            await page.tick(90000);
            const running = page.timers().filter(t => !t.repeat).map(t => t.ms);
            report({ idle, whileHidden, afterShown, running });
            """);

        assertThat(out.get("idle").toString()).isEqualTo("[90000]");
        assertThat(out.get("whileHidden").asInt()).as("only the first look, nothing while hidden").isEqualTo(1);
        assertThat(out.get("afterShown").asInt()).isEqualTo(2);
        assertThat(out.get("running").toString()).isEqualTo("[15000]");
    }

    /**
     * Unchecked tests, suites that crashed near the end and suites that never ran arrive by the same quiet look
     * as new blockers: drawn in place and escaped, with nothing hidden on the way.
     */
    @Test
    void laterPartsOfTheVerdictAreDrawnByTheQuietLook() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            const hides = page.el('results').classLog.filter(c => c === '+hidden').length;
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 60000,
                unverified: [Object.assign(blocker('CacheTest.test<b>Put</b>', 'occ-9'), { blocker: false,
                    reason: 'TeamCity answered 502' })],
                unstableSuites: [{ suite: 'IgniteTests24Java8_Snapshots6', suiteName: 'Snapshots 6', suiteBuildId: 9003,
                    problems: ['TC_EXECUTION_TIMEOUT'] }],
                interrupted: true, canceledSuites: 2, cancelledSuites: [
                    { suite: 'IgniteTests24Java8_Cache1', suiteName: 'Cache 1', suiteBuildId: 9004,
                        reason: '<i>agent lost</i>', cancelledBy: null, byTeamCity: true },
                    { suite: 'IgniteTests24Java8_Cache2', suiteName: 'Cache 2', suiteBuildId: 9005, reason: null,
                        cancelledBy: 'bob', byTeamCity: false }] }) });
            await page.tick(70000);
            report({
                hidden: page.el('results').classLog.filter(c => c === '+hidden').length - hides,
                status: page.el('status').textContent,
                unverifiedShown: !page.el('unverifiedCard').classList.contains('hidden'),
                unverified: page.el('unverified').innerHTML,
                unstableShown: !page.el('unstableCard').classList.contains('hidden'),
                unstable: page.el('unstableSuites').textContent,
                bannerShown: !page.el('interruptedBanner').classList.contains('hidden'),
                cancelled: page.el('cancelledSuites').innerHTML,
            });
            """);

        assertThat(out.get("hidden").asInt()).isZero();
        assertThat(out.get("status").asText()).doesNotContain("Analyzing");
        assertThat(out.get("unverifiedShown").asBoolean()).isTrue();
        assertThat(out.get("unverified").asText()).contains("test&lt;b&gt;Put&lt;/b&gt;", "TeamCity answered 502");
        assertThat(out.get("unstableShown").asBoolean()).isTrue();
        assertThat(out.get("unstable").asText()).contains("Snapshots 6", "TC_EXECUTION_TIMEOUT");
        assertThat(out.get("bannerShown").asBoolean()).isTrue();
        assertThat(out.get("cancelled").asText())
            .contains("cancelled by TeamCity: &lt;i&gt;agent lost&lt;/i&gt;", "1 suite cancelled by bob");
    }

    /** A line of suites someone cancelled, opened by the reader, stays open through a redraw of the same build. */
    @Test
    void anOpenedLineOfCancelledSuitesStaysOpenThroughARedraw() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            const cancelled = { interrupted: true, canceledSuites: 1, cancelledSuites: [{ suite: 'IgniteTests24Java8_Cache2',
                suiteName: 'Cache 2', suiteBuildId: 9005, reason: null, cancelledBy: 'bob', byTeamCity: false }] };
            page.route('/api/analyze', { body: verdict(cancelled) });
            await page.load('?pr=13575');
            const before = page.el('cancelledSuites').innerHTML;
            page.el('cancelledSuites').querySelectorAll = sel => sel === 'details[open]' ? [{ dataset: { who: 'bob' } }] : [];
            page.route('/api/analyze', { body: verdict(Object.assign({ computedAt: page.now() + 60000,
                blockers: [blocker('ClientReconnectTest.testReconnect', 'occ-1'), blocker('TxRecoveryTest.testCommit', 'occ-2')] },
                cancelled)) });
            await page.tick(70000);
            const sameBuild = page.el('cancelledSuites').innerHTML;
            page.route('/api/analyze', { body: verdict(Object.assign({ buildId: 9100, computedAt: page.now() + 60000 },
                cancelled)) });
            await page.tick(70000);
            report({ before, sameBuild, newBuild: page.el('cancelledSuites').innerHTML });
            """);

        assertThat(out.get("before").asText()).contains("<details data-who=\"bob\">");
        assertThat(out.get("sameBuild").asText()).contains("<details data-who=\"bob\" open>");
        assertThat(out.get("newBuild").asText()).contains("<details data-who=\"bob\">");
    }
}
