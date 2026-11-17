package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The broken-suite card compares its PR failure with a separate, current master lookup. */
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
