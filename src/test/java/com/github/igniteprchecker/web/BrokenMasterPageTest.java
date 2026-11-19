package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The page explains a master match in the verdict and shows the master build in the suite row. */
class BrokenMasterPageTest {
    /** Two suites with the same reported timeout, as TeamCity reports their PR runs. */
    private static final String BROKEN = """
        const suites = ['Node', 'Duck'].map((suite, i) => ({ suite, suiteBuildId: 9002 + i, suiteName: suite,
            tests: 0, baseline: 0, problems: ['execution timeout', 'non-zero exit code'],
            problemTypes: ['TC_EXECUTION_TIMEOUT', 'TC_EXIT_CODE'] }));
        const broken = verdict({ blockers: [], brokenSuites: suites, brokenGroups: [{ kind: 'PROBLEM',
            title: 'execution timeout · non-zero exit code', suites, cancelled: [], rerun: ['Node', 'Duck'] }] });
        page.route('/api/analyze', { body: broken });
        """;

    /** An analysis with one broken suite whose latest analysed master run had the same failure. */
    private static final String MATCHED = """
        const node = { suite: 'Node', suiteBuildId: 9002, suiteName: 'Thin client: Node.js', tests: 0, baseline: 0,
            problems: ['execution timeout'], problemTypes: ['TC_EXECUTION_TIMEOUT'] };
        const matched = verdict({ blockers: [], standing: 'UNPROVEN', brokenSuites: [node], onlyMasterFailures: true,
            brokenGroups: [{ kind: 'PROBLEM', title: 'execution timeout', suites: [node], cancelled: [], rerun: [node.suite] }],
            caveats: ['1 suite has no reliable result (execution timeout)'], revision: '0123456789abcdef' });
        page.route('/api/analyze', { body: matched });
        page.route('/api/pending', { body: { pending: false, checked: true } });
        page.route('/api/settling', { body: { phase: 'final', completedWaves: 0 } });
        const state = () => ({ head: page.el('verdictText').textContent, lines: page.el('verdictLines').textContent,
            clean: page.el('verdictCard').classList.contains('clean'), activity: page.el('settling').textContent,
            steps: page.el('steps').textContent, brokenHead: page.el('brokenHead').textContent,
            adviceHidden: page.el('brokenAdvice').classList.contains('hidden') });
        """;

    private static final String NOTHING_TO_DO = "Nothing to fix or re-run for this PR.";

    /** Given a master match, the page waits until it confirms both the PR head and the final result. */
    @ParameterizedTest
    @ValueSource(strings = {"pending", "settling"})
    void aFinalCurrentVerdictExplainsItsOnlyGapWithoutAskingForARerun(String lastCheck) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MATCHED + "const lastCheck = '" + lastCheck + "';\n" + """
            let answer;
            page.route('/api/' + lastCheck, () => new Promise(done => { answer = done; }));
            await page.load('?pr=13575');
            const checking = state();
            answer({ body: lastCheck === 'pending' ? { pending: false, checked: true } : { phase: 'final', completedWaves: 0 } });
            await page.settle();
            report({ checking, current: state() });
            """);

        assertThat(out.get("checking").get("lines").asText()).doesNotContain(NOTHING_TO_DO);
        assertThat(out.get("checking").get("activity").asText()).doesNotContain("re-run manually");

        JsonNode current = out.get("current");
        assertThat(current.get("head").asText()).isEqualTo("No blockers found");
        assertThat(current.get("lines").asText()).containsSubsequence(
            "1 suite gave no result. Its latest analysed run on master had the same kind of failure.", NOTHING_TO_DO);
        assertThat(current.get("clean").asBoolean()).isFalse();
        assertThat(current.get("activity").asText()).contains("Final verdict").doesNotContain("re-run manually");
        assertThat(current.get("steps").asText()).contains("same problem on master").doesNotContain("not proven clean");
        assertThat(current.get("brokenHead").asText()).isEqualTo("Suites with the same problem on master");
        assertThat(current.get("adviceHidden").asBoolean()).isTrue();
    }

    /** Given another reason to qualify the verdict, a master match never tells the author that no action is needed. */
    @ParameterizedTest
    @ValueSource(strings = {"unmatched suite", "another coverage gap", "mixed run", "older code", "unknown code",
        "newer run", "replaced run"})
    void aMasterMatchDoesNotHideOtherCaveats(String scenario) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MATCHED + "const scenario = '" + scenario + "';\n" + """
            if (scenario === 'unmatched suite') {
                matched.onlyMasterFailures = false;
                const duck = { ...node, suite: 'Duck', suiteBuildId: 9003, suiteName: 'Check Code Style Ducktests' };
                matched.brokenSuites.push(duck);
                matched.brokenGroups[0].suites.push(duck);
                matched.brokenGroups[0].rerun.push(duck.suite);
                matched.caveats = ['2 suites have no reliable result (2× execution timeout)'];
            }
            if (scenario === 'another coverage gap') {
                matched.onlyMasterFailures = false;
                matched.interrupted = true;
                matched.canceledSuites = 1;
                matched.caveats.unshift('the RunAll was interrupted — 1 suite never ran');
            }
            if (scenario === 'mixed run') {
                matched.onlyMasterFailures = false;
                matched.otherCodeRuns = [{ suite: 'Cache', suiteName: 'Cache', buildId: 9003, revision: 'fedcba9' }];
            }
            if (scenario === 'older code')
                page.route('/api/pending', { body: { pending: true, ahead: 1, builtSha: '0123456', headSha: 'fedcba9' } });
            if (scenario === 'unknown code')
                page.route('/api/pending', { body: { pending: false, checked: false } });
            if (scenario === 'newer run')
                page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running', name: 'RunAll' }] });
            if (scenario === 'replaced run')
                page.route('/api/settling', { body: { phase: 'final', reason: 'replaced' } });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("lines").asText()).doesNotContain(NOTHING_TO_DO);
    }

    /** Given matching master problems, opening the PR shows the match and evidence without clearing its caveat. */
    @Test
    void matchingMasterProblemsAreShownWithTheirRunAndTime() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BROKEN + """
            page.route('/api/master-suite', { body: { buildId: 9100, finishedAt: RUN_FINISHED,
                status: 'FAILURE', problemTypes: ['TC_EXIT_CODE', 'TC_EXECUTION_TIMEOUT', 'TC_EXIT_CODE'] } });
            await page.load('?pr=13575');
            report({ node: page.el('master-suite-Node').innerHTML, group: page.el('master-group-0').textContent,
                advice: page.el('brokenAdvice').textContent, caveat: page.el('verdictText').textContent,
                requests: page.fetches('/api/master-suite').map(r => r.url) });
            """);

        assertThat(out.get("node").asText()).contains("Master: same reported problems", "7 Oct 11:00",
            "https://ci2.example/buildConfiguration/Node/9100", "does not prove the cause is the same");
        assertThat(out.get("group").asText()).isEqualTo("Same reported problems on master: 2 of 2 suites.");
        assertThat(out.get("advice").asText()).contains("Check master before rerunning").doesNotContain("Re-run these first");
        assertThat(out.get("caveat").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("requests").toString()).isEqualTo("[\"/api/master-suite?suite=Node\",\"/api/master-suite?suite=Duck\"]");
    }

    /** Given a different, missing or unavailable master result, the page never claims a matching failure. */
    @ParameterizedTest
    @CsvSource({
        "passed, Master: passed",
        "different, Master: failed",
        "empty, Master: failed",
        "unknownType, Master: failed",
        "missing, Master: no completed run found",
        "error, Master: could not be checked"
    })
    void otherMasterResultsStayDistinct(String scenario, String label) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BROKEN + "const scenario = '" + scenario + "';\n" + """
            const master = { buildId: 9100, finishedAt: RUN_FINISHED, status: 'FAILURE',
                problemTypes: ['TC_EXIT_CODE'] };
            if (scenario === 'passed') master.status = 'SUCCESS';
            if (scenario === 'empty') master.problemTypes = [];
            if (scenario === 'unknownType') master.problemTypes = ['', 'TC_EXECUTION_TIMEOUT', 'TC_EXIT_CODE'];
            if (scenario === 'missing') Object.assign(master, { buildId: 0, finishedAt: 0, status: null, problemTypes: [] });
            page.route('/api/master-suite', { status: scenario === 'error' ? 502 : 200, body: master });
            await page.load('?pr=13575');
            report({ node: page.el('master-suite-Node').innerHTML, group: page.el('master-group-0').textContent });
            """);

        assertThat(out.get("node").asText()).contains(label).doesNotContain("same reported problems");
        assertThat(out.get("group").asText()).isEmpty();
    }

    /** Given a delayed master answer, refreshing the PR keeps the newer comparison when the old answer arrives. */
    @Test
    void aLateAnswerCannotReplaceTheRefreshedComparison() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BROKEN + """
            let finish;
            page.route('/api/master-suite', () => new Promise(done => {
                finish = () => done({ body: { buildId: 9100, finishedAt: RUN_FINISHED, status: 'FAILURE',
                    problemTypes: ['TC_EXECUTION_TIMEOUT', 'TC_EXIT_CODE'] } });
            }));
            await page.load('?pr=13575');
            const loading = page.el('brokenSuites').innerHTML;
            page.route('/api/master-suite', { body: { buildId: 9200, finishedAt: RUN_FINISHED + 60,
                status: 'SUCCESS', problemTypes: [] } });
            page.route('/api/refresh', { body: Object.assign({}, broken, { computedAt: page.now() }) });
            await page.el('refreshBtn').onclick();
            await page.settle();
            finish();
            await page.settle();
            report({ loading, node: page.el('master-suite-Node').innerHTML });
            """);

        assertThat(out.get("loading").asText()).contains("Master: checking…");
        assertThat(out.get("node").asText()).contains("Master: passed", "/Node/9200").doesNotContain("/Node/9100");
    }

    /** Given a pending lookup, leaving its view stops it even if the user returns to the same PR before it answers. */
    @ParameterizedTest
    @ValueSource(strings = {"logout", "history"})
    void leavingTheViewDiscardsItsPendingLookups(String action) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + BROKEN + "const action = '" + action + "';\n" + """
            let finish;
            page.route('/api/master-suite', () => new Promise(done => {
                finish = () => done({ body: { buildId: 9100, status: 'SUCCESS', problemTypes: [] } });
            }));
            await page.load('?pr=13575');
            if (action === 'logout') await page.el('logout').onclick();
            else {
                page.route('/api/analyze', () => new Promise(() => {}));
                page.location.search = '?pr=13461';
                await page.popstate();
                page.location.search = '?pr=13575';
                await page.popstate();
            }
            finish();
            await page.settle();
            report({ requests: page.fetches('/api/master-suite').length, writes: page.el('master-suite-Node').htmlWrites });
            """);

        assertThat(out.get("requests").asInt()).isEqualTo(1);
        assertThat(out.get("writes").asInt()).isZero();
    }
}
