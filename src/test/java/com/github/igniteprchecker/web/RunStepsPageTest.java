package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The run on screen is shown as four steps: the queue, RunAll, auto re-runs, the final verdict. A step that has builds
 * of the PR on TeamCity opens them in a panel under the steps.
 */
class RunStepsPageTest {
    /**
     * What the steps say: each one's state, name and detail, the tags left out. A step that opens its builds has ▸
     * before its name, and ▾ while they are open.
     */
    private static final String STEPS = """
        function steps() {
            return page.el('steps').classList.contains('hidden') ? 'hidden'
                : page.el('steps').innerHTML.split('</li>').filter(Boolean)
                    .map(li => li.match(/class="step (\\w+)"/)[1] + ' '
                        + (li.includes('aria-expanded="true"') ? '▾' : li.includes('<button') ? '▸' : '')
                        + li.replace(/<span class="step-dot"[^>]*>[^<]*<\\/span>/, '').replace(/<\\/span>/g, '|')
                            .replace(/<[^>]+>/g, '').replace(/\\|$/, ''))
                    .join('; ');
        }
        function panel() {
            return page.el('stepPanel').classList.contains('hidden') ? 'hidden' : page.el('stepPanel').textContent;
        }
        function shown(id) {
            return !page.el(id).classList.contains('hidden');
        }
        """;

    /** The builds of PR 13575: alice's running chain, bob's queued one, a re-run of the wave and bob's own re-run. */
    private static final String BUILDS = """
        const BUILDS = [
            { buildId: 9001, state: 'running', name: 'Run :: All', runAll: true, leftSec: 55 * 60, mine: true },
            { buildId: 9100, state: 'queued', name: 'Run :: All', runAll: true, startSec: 300, by: 'bob' },
            { buildId: 9003, state: 'running', name: 'Cache', mine: true },
            { buildId: 9007, state: 'queued', name: 'PDS 1', by: 'bob' }];
        page.route('/api/analyze', url => ({ body: verdict({ live: true, liveBuildId: 9001,
            prNumber: url.includes('pr=13461') ? 13461 : 13575 }) }));
        page.route('/api/runs', { body: BUILDS });
        page.route('/api/settling', { body: { phase: 'running', wave: 1, of: 2, rerunBuildIds: [9003] } });
        """;

    /** Given a queued RunAll, the first step is under way and says when the chain starts. */
    @Test
    void aQueuedRunAllWaitsInTheFirstStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'queued', name: 'Run :: All', runAll: true,
                startSec: 300, mine: true }] });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText())
            .isEqualTo("now ▸Queue|starts ~5m; todo RunAll|; todo Auto re-run|; todo Final verdict|");
    }

    /** Given a running RunAll whose failed suites are re-run already, both steps are under way. */
    @Test
    void aRunningRunAllAndItsEarlyReRunsAreBothUnderWay() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9001 }) });
            page.route('/api/runs', { body: [{ buildId: 9001, state: 'running', name: 'Run :: All', runAll: true,
                leftSec: 55 * 60, mine: true }, { buildId: 9003, state: 'running', name: 'Cache' }] });
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9003, state: 'running' },
                { pr: 13575, buildId: 9004, state: 'queued' }] });
            page.route('/api/settling', { body: { phase: 'running', wave: 1, of: 2, rerunBuildIds: [9003, 9004] } });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("done Queue|waited 20m; now ▸RunAll|~55m left;"
            + " now ▸Auto re-run|wave 1 of 2 · 1 running, 1 queued; todo Final verdict|");
    }

    /** Given a wave of auto re-runs after the chain, the third step names the wave and its estimate. */
    @Test
    void aWaveOfAutoReRunsIsTheThirdStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 2, of: 2, rerunBuildIds: [9003],
                etaEpochSec: Math.floor(page.now() / 1000) + 25 * 60 } });
            page.route('/api/runs', { body: [{ buildId: 9003, state: 'running', name: 'Cache' }] });
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9003, state: 'running' }] });
            await page.load('?pr=13575');
            const wave = steps();
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2 } });
            page.route('/api/runs', { body: [] });
            page.route('/api/reruns', { body: [] });
            await page.tick(90000);
            report({ wave, deciding: steps() });
            """);

        assertThat(out.get("wave").asText()).isEqualTo("done Queue|waited 20m; done RunAll|;"
            + " now ▸Auto re-run|wave 2 of 2 · 1 running, 0 queued · ~25m left; todo Final verdict|");
        assertThat(out.get("deciding").asText())
            .isEqualTo("done Queue|waited 20m; done RunAll|; now Auto re-run|deciding; todo Final verdict|");
    }

    /** Given a final verdict, every step is done and the last one says how the verdict stands. */
    @Test
    void aFinalVerdictClosesEveryStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("done Queue|waited 20m; done RunAll|;"
            + " done Auto re-run|2 waves; bad Final verdict|1 blocker");
    }

    /** Given a final verdict of a run with auto re-runs off, the third step is skipped and says why. */
    @Test
    void autoReRunsThatAreOffAreASkippedStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'BROKEN' }) });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 0, reason: 'off' } });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("done Queue|waited 20m; done RunAll|;"
            + " off Auto re-run|off for this run; warn Final verdict|not proven clean");
    }

    /**
     * Given a re-run the checker skipped while the verdict is still being updated, the third step says so instead of
     * "deciding", and the last one is under way.
     */
    @Test
    void aSkippedReRunIsNotAPendingDecision() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2, reason: 'too-many-suites',
                rerunBuildIds: [] } });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("done Queue|waited 20m; done RunAll|;"
            + " off Auto re-run|skipped: too many suites; now Final verdict|updating");
    }

    /**
     * Given builds of the PR on TeamCity, each stands under its step: a queued chain under the first, a running one
     * under the second, the wave's re-runs under the third. bob's re-run by hand stands with them, under the last
     * step under way: the final verdict is still to come, and opens nothing. A click on a step opens its builds, one
     * panel at a time, and a second click closes them.
     */
    @Test
    void aStepOpensItsBuilds() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + BUILDS + """
            await page.load('?pr=13575');
            const closed = { steps: steps(), panel: panel() };
            const opened = {};
            for (const [i, name] of ['queue', 'runAll', 'auto', 'verdict'].entries()) {
                page.run('toggleStep')(i);
                opened[name] = panel();
            }
            page.run('toggleStep')(2);
            const last = { steps: steps(), html: page.el('stepPanel').innerHTML };
            page.run('toggleStep')(2);
            report({ closed, opened, last, again: panel() });
            """);

        assertThat(out.at("/closed/steps").asText()).isEqualTo("done ▸Queue|waited 20m; now ▸RunAll|~55m left;"
            + " now ▸Auto re-run|wave 1 of 2 · 1 running, 0 queued; todo Final verdict|");
        assertThat(out.at("/closed/panel").asText()).isEqualTo("hidden");
        assertThat(out.at("/opened/queue").asText())
            .isEqualTo("Queue on TeamCity:Run :: Allby bobqueued · starts ~5mtakes latest commit at start");
        assertThat(out.at("/opened/runAll").asText()).isEqualTo("RunAll on TeamCity:Run :: Allrunning · ~55m left");
        assertThat(out.at("/opened/auto").asText())
            .isEqualTo("Auto re-run on TeamCity:CacherunningPDS 1by bobqueuedtakes latest commit at start");
        assertThat(out.at("/opened/verdict").asText()).as("a step still to come has no builds to open")
            .isEqualTo("hidden");
        assertThat(out.at("/last/steps").asText()).as("one panel at a time").isEqualTo("done ▸Queue|waited 20m;"
            + " now ▸RunAll|~55m left; now ▾Auto re-run|wave 1 of 2 · 1 running, 0 queued; todo Final verdict|");
        assertThat(out.at("/last/html").asText())
            .contains("title=\"Started by bob — Cancel my runs leaves it alone\"")
            .contains("<a class=\"live queued\"");
        assertThat(out.get("again").asText()).isEqualTo("hidden");
    }

    /**
     * Given a re-run someone started by hand, it stands under the last step under way, never under one still to come:
     * under the queue while a newer RunAll waits there, under RunAll while the chain runs with no wave of auto
     * re-runs, and under the step the checker reached last when it does not know how far the auto re-runs are.
     */
    @Test
    void aReRunByHandStandsUnderTheLastStepUnderWay() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            const byHand = { buildId: 9007, state: 'running', name: 'PDS 1', by: 'bob' };
            const chain = state => ({ buildId: 9100, state, name: 'Run :: All', runAll: true, mine: true });
            const seen = async () => {
                await page.tick(15000);
                const closed = steps();
                const at = closed.split('; ').findIndex(step => step.includes('▸'));
                page.run('toggleStep')(at);
                const opened = panel();
                page.run('toggleStep')(at);
                return { steps: closed, panel: opened };
            };
            page.route('/api/runs', { body: [chain('queued'), byHand] });
            await page.load('?pr=13575');
            const queued = await seen();
            page.route('/api/runs', { body: [chain('running'), byHand] });
            page.route('/api/settling', { body: { phase: 'running', wave: 0, of: 2 } });
            const running = await seen();
            page.route('/api/runs', { body: [byHand] });
            page.route('/api/settling', { status: 502, body: {} });
            report({ queued, running, unknown: await seen() });
            """);

        assertThat(out.at("/queued/steps").asText())
            .isEqualTo("now ▸Queue|; todo RunAll|; todo Auto re-run|; todo Final verdict|");
        assertThat(out.at("/queued/panel").asText()).isEqualTo("Queue on TeamCity:Run :: Allqueued"
            + "takes latest commit at startPDS 1by bobrunning");
        assertThat(out.at("/running/steps").asText())
            .isEqualTo("done Queue|; now ▸RunAll|; todo Auto re-run|; todo Final verdict|");
        assertThat(out.at("/running/panel").asText())
            .isEqualTo("RunAll on TeamCity:Run :: AllrunningPDS 1by bobrunning");
        assertThat(out.at("/unknown/steps").asText()).isEqualTo("done Queue|waited 20m; done ▸RunAll|;"
            + " todo Auto re-run|status unknown; todo Final verdict|");
        assertThat(out.at("/unknown/panel").asText()).isEqualTo("RunAll on TeamCity:PDS 1by bobrunning");
    }

    /** Given an open panel, it closes when its step has no builds any more, and when another PR is opened. */
    @Test
    void anOpenPanelClosesWithItsBuildsAndWithItsPr() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + BUILDS + """
            await page.load('?pr=13575');
            page.run('toggleStep')(2);
            page.route('/api/runs', { body: BUILDS.slice(0, 2) });
            await page.tick(15000);
            const gone = panel();
            page.route('/api/runs', { body: BUILDS });
            await page.tick(15000);
            const back = panel();
            page.run('toggleStep')(1);
            const opened = panel();
            page.run('openPr')(13461, false);
            await page.settle();
            report({ gone, back, opened, other: { steps: steps(), panel: panel() } });
            """);

        assertThat(out.get("gone").asText()).isEqualTo("hidden");
        assertThat(out.get("back").asText()).as("builds that come back do not open it").isEqualTo("hidden");
        assertThat(out.get("opened").asText()).startsWith("RunAll on TeamCity:");
        assertThat(out.at("/other/steps").asText()).contains("now ▸RunAll|~55m left");
        assertThat(out.at("/other/panel").asText()).isEqualTo("hidden");
    }

    /**
     * Given steps on screen, Cancel my runs and the chips' help stand at the end of the status line, and the row of
     * runs is hidden: the steps open the builds.
     */
    @Test
    void cancelMyRunsStandsInTheStatusLineWhileTheStepsShow() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + BUILDS + """
            await page.load('?pr=13575');
            report({ row: shown('runsRow'), status: shown('settlingRow'), acts: shown('runActs'),
                cancel: shown('cancelMine'), in: page.el('runActs').parentNode.id });
            """);

        assertThat(out.get("row").asBoolean()).as("On TeamCity").isFalse();
        assertThat(out.get("status").asBoolean()).isTrue();
        assertThat(out.get("acts").asBoolean()).isTrue();
        assertThat(out.get("cancel").asBoolean()).isTrue();
        assertThat(out.get("in").asText()).isEqualTo("settlingRow");
    }

    /**
     * Given a merged PR, which has no steps, the row of runs lists its builds, with Cancel my runs and the chips'
     * help at its end.
     */
    @Test
    void withoutStepsTheRowOfRunsListsTheBuilds() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + BUILDS + """
            page.route('/api/analyze', { body: verdict({ mergedAt: page.now() / 1000 - 60 }) });
            await page.load('?pr=13575');
            report({ steps: steps(), panel: panel(), row: shown('runsRow'), runs: page.el('runs').textContent,
                cancel: shown('cancelMine'), in: page.el('runActs').parentNode.id });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("hidden");
        assertThat(out.get("panel").asText()).isEqualTo("hidden");
        assertThat(out.get("row").asBoolean()).isTrue();
        assertThat(out.get("runs").asText()).startsWith("On TeamCity: Run :: Allrunning · ~55m left")
            .contains("Cacherunning", "PDS 1by bobqueued");
        assertThat(out.get("cancel").asBoolean()).isTrue();
        assertThat(out.get("in").asText()).isEqualTo("runsRow");
    }

    /** Given a PR whose first RunAll is only queued, there is no verdict and no step yet: the row of runs names it. */
    @Test
    void beforeTheFirstVerdictTheRowOfRunsNamesTheQueuedChain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/analyze', { status: 404, body: { noRun: true } });
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'queued', name: 'Run :: All', runAll: true,
                startSec: 300, mine: true }] });
            await page.load('?pr=13575');
            report({ results: shown('results'), row: shown('runsRow'), runs: page.el('runs').textContent,
                cancel: shown('cancelMine'), in: page.el('runActs').parentNode.id });
            """);

        assertThat(out.get("results").asBoolean()).isFalse();
        assertThat(out.get("row").asBoolean()).isTrue();
        assertThat(out.get("runs").asText())
            .isEqualTo("On TeamCity: Run :: Allqueued · starts ~5mtakes latest commit at start");
        assertThat(out.get("cancel").asBoolean()).isTrue();
        assertThat(out.get("in").asText()).isEqualTo("runsRow");
    }

    /** Given runs the page could not read, it shows no steps rather than guess where the run stands. */
    @Test
    void unreadRunsShowNoSteps() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STEPS + """
            page.route('/api/runs', { status: 502, body: {} });
            await page.load('?pr=13575');
            report({ steps: steps() });
            """);

        assertThat(out.get("steps").asText()).isEqualTo("hidden");
    }
}
