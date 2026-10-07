package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import org.junit.jupiter.api.Test;

/**
 * One click on a section's "Rerun top" put all 62 suites of PR 13655 at the head of the queue everybody
 * shares, and RunAll queued a chain of ~150 suites next to the user's own running one; neither button
 * said how much it was about to queue. A PR whose RunAll still waited in the queue was told to "trigger
 * one with RunAll above".
 */
class LargeRunsTest {
    private static final String SETUP = """
        const run = (id, state, by, extra) => Object.assign({ buildId: id, state, name: 'RunAll', btId: 'RunAll',
            webUrl: 'https://ci2.example/build/' + id, pct: -1, leftSec: -1, startSec: -1, waitedSec: -1,
            elapsedSec: -1, by, mine: by === 'alice', runAll: true }, extra);
        const asked = [];
        let answer = true;
        page.run('window').confirm = q => { asked.push(q); return answer; };
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120, runAllSuites: 150 } });
        page.route('/api/trigger', { body: { triggered: [{ buildId: 301, webUrl: 'https://ci2.example/build/301' }],
            replaced: 0 } }, 'POST');
        page.route('/api/rerun-suites', { body: { triggered: [] } }, 'POST');
        const inSuites = n => Array.from({ length: n }, (_, i) =>
            Object.assign(blocker('Test' + i + '.test', 'occ-' + i), { suite: 'Suite' + i, suiteBuildId: 9100 + i }));
        const rerun = page.el('rerunAll');
        rerun.dataset.top = 'false';
        const rerunTop = page.el('rerunAllTop');
        rerunTop.dataset.top = 'true';
        page.el('blockerActs').querySelectorAll = () => [rerun, rerunTop];
        const click = b => b.onclick({ stopPropagation() {}, preventDefault() {} });
        """;

    @Test
    void sectionButtonsSayHowManySuitesTheyQueue() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { body: verdict({ blockers: inSuites(6) }) });
            await page.load('?pr=13575');
            report({ rerun: rerun.textContent, top: rerunTop.textContent, title: rerunTop.title,
                label: page.el('runAllLabel').textContent });
            """);

        assertThat(out.get("rerun").asText()).isEqualTo("Rerun (6)");
        assertThat(out.get("top").asText()).isEqualTo("Rerun top (6)");
        assertThat(out.get("title").asText()).isEqualTo("Re-run the 6 suites of this section, at the top of the queue");
        assertThat(out.get("label").asText()).isEqualTo("RunAll (~150 suites):");
    }

    @Test
    void largeSectionRerunAsksFirst() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { body: verdict({ blockers: inSuites(6) }) });
            await page.load('?pr=13575');
            answer = false;
            await click(rerunTop);
            const declined = page.fetches('/api/rerun-suites').length;
            answer = true;
            await click(rerunTop);
            report({ asked, declined, queued: page.fetches('/api/rerun-suites').length });
            """);

        assertThat(out.get("asked").get(0).asText())
            .isEqualTo("Re-run 6 suites of PR #13575 at the top of the ci2 queue, ahead of everyone else's builds?");
        assertThat(out.get("declined").asInt()).isZero();
        assertThat(out.get("queued").asInt()).isEqualTo(1);
    }

    @Test
    void smallSectionRerunGoesStraightAway() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { body: verdict({ blockers: inSuites(4) }) });
            await page.load('?pr=13575');
            await click(rerun);
            report({ asked: asked.length, queued: page.fetches('/api/rerun-suites').length });
            """);

        assertThat(out.get("asked").asInt()).isZero();
        assertThat(out.get("queued").asInt()).isEqualTo(1);
    }

    @Test
    void runAllAsksFirstAndSaysHowManySuites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            await page.load('?pr=13575');
            answer = false;
            await page.run('doTrigger')('trigger', 'true', page.el('btn'));
            const declined = page.fetches('/api/trigger').length;
            answer = true;
            await page.run('doTrigger')('trigger', 'false', page.el('btn'));
            report({ asked, declined, triggers: page.fetches('/api/trigger').map(f => f.url) });
            """);

        assertThat(out.get("asked").get(0).asText()).isEqualTo("Queue RunAll for PR #13575 at the top of the ci2 "
            + "queue, ahead of everyone else's builds? That is the whole chain, ~150 suites (suites already run on "
            + "this code may be reused).");
        assertThat(out.get("declined").asInt()).isZero();
        assertThat(out.get("triggers")).hasSize(1);
        assertThat(out.get("triggers").get(0).asText()).isEqualTo("/api/trigger?pr=13575&top=false");
    }

    @Test
    void ownRunningRunAllIsReplacedNotDoubled() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            await page.load('?pr=13575');
            page.route('/api/runs', { body: [run(201, 'running', 'alice', { elapsedSec: 2700, leftSec: 3900 })] });
            await page.run('doTrigger')('trigger', 'false', page.el('btn'));
            report({ asked, triggers: page.fetches('/api/trigger').map(f => f.url) });
            """);

        assertThat(out.get("asked").get(0).asText()).endsWith("\n\nYour RunAll for this PR is still running 45m · "
            + "~1h 05m left. OK cancels it and queues the new one.");
        assertThat(out.get("triggers").get(0).asText()).isEqualTo("/api/trigger?pr=13575&top=false&replace=true");
    }

    @Test
    void serverSaysHowManySuitesMastersLatestRunAllHad() {
        SuiteBaseline baseline = mock(SuiteBaseline.class);
        when(baseline.chainSuites()).thenReturn(152);
        ConfigController config = new ConfigController(new TeamcityProperties("https://ci2.example/"),
            new GithubProperties(null, null, null), mock(GithubClient.class),
            new AnalysisProperties(null, "RunAll", null, null, null, 300, null), baseline);

        assertThat(config.config()).containsEntry("runAllSuites", 152);
    }

    @Test
    void failedReplacementShowsThePreviousRunAllGone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            await page.load('?pr=13575');
            page.route('/api/runs', () => ({ body: page.fetches('/api/trigger').length
                ? [] : [run(201, 'running', 'alice', { elapsedSec: 2700, leftSec: 3900 })] }));
            page.route('/api/trigger', { status: 502, body: { error: 'Your previous RunAll was cancelled, but '
                + 'queuing the new one failed: ci2 refused the request (403).', replaced: 1 } }, 'POST');
            page.route('/api/refresh', { body: verdict() }, 'POST');
            await page.run('doTrigger')('trigger', 'false', page.el('btn'));
            const status = page.el('status').textContent;
            await page.settle();
            report({ status, runs: page.el('runs').textContent,
                cancel: !page.el('cancelMine').classList.contains('hidden') });
            """);

        assertThat(out.get("status").asText()).isEqualTo("Your previous RunAll was cancelled, but queuing the new "
            + "one failed: ci2 refused the request (403).");
        assertThat(out.get("runs").asText()).as("the cancelled chain is off the runs row").isEmpty();
        assertThat(out.get("cancel").asBoolean()).isFalse();
    }

    @Test
    void someoneElsesRunAllIsNamedAndLeftAlone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            await page.load('?pr=13575');
            page.route('/api/runs', { body: [run(203, 'queued', 'bob', { startSec: 720 })] });
            await page.run('doTrigger')('trigger', 'false', page.el('btn'));
            report({ asked, triggers: page.fetches('/api/trigger').map(f => f.url) });
            """);

        assertThat(out.get("asked").get(0).asText())
            .endsWith("\n\nbob's RunAll for this PR is queued · starts ~12m; it stays, and yours runs alongside it.");
        assertThat(out.get("triggers").get(0).asText()).isEqualTo("/api/trigger?pr=13575&top=false");
    }

    @Test
    void queuedRunAllIsNotReportedAsMissing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { status: 404, body: { error: 'no RunAll build found for PR 13575' } });
            await page.load('?pr=13575');
            const none = page.el('status').textContent;
            page.route('/api/runs', { body: [run(203, 'queued', 'bob', { startSec: 720 })] });
            await page.tick(90000);
            report({ none, queued: page.el('status').textContent });
            """);

        assertThat(out.get("none").asText())
            .isEqualTo("No RunAll run for this PR yet — start one with the RunAll Rerun button above. (TeamCity "
                + "may have cleaned up an old one.)");
        assertThat(out.get("queued").asText())
            .isEqualTo("RunAll is queued for this PR, starts ~12m — the verdict shows up here once it starts.");
    }

    @Test
    void recomputingAPrWithoutARunShowsTheSameNote() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { status: 404, body: { error: 'no RunAll build found for PR 13575' } });
            page.route('/api/runs', { body: [run(203, 'queued', 'bob', { startSec: 720 })] });
            await page.load('?pr=13575');
            page.route('/api/refresh', { status: 404, body: { error: 'no RunAll build found for PR 13575' } }, 'POST');
            await page.run('refresh')();
            report({ status: page.el('status').textContent });
            """);

        assertThat(out.get("status").asText())
            .isEqualTo("RunAll is queued for this PR, starts ~12m — the verdict shows up here once it starts.");
    }

    @Test
    void theNoRunNoteDoesNotOverwriteWhatTheUserJustDid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETUP + """
            page.route('/api/analyze', { status: 404, body: { error: 'no RunAll build found for PR 13575' } });
            await page.load('?pr=13575');
            page.route('/api/runs', () => ({ body: page.fetches('/api/trigger').length
                ? [run(301, 'queued', 'alice', { startSec: 720 })] : [] }));
            await page.run('doTrigger')('trigger', 'false', page.el('btn'));
            await page.settle();
            await page.tick(15000);
            report({ status: page.el('status').textContent, runs: page.fetches('/api/runs').length });
            """);

        assertThat(out.get("status").asText()).isEqualTo("Queued 1 build(s): #301TC");
        assertThat(out.get("runs").asInt()).as("load, before the question, after queueing, next poll").isEqualTo(4);
    }
}
