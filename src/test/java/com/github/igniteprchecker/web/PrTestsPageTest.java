package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * TcpDiscoveryClientTopologyGapTest came in with PR 13327 taking 298 s, passed there once, and now fails 18 of 100
 * master runs, noise in 77 PRs. The page now shows how the PR's own new and changed test classes ran, and warns
 * about tests over a minute long and tests master never ran.
 */
class PrTestsPageTest {
    private static final String PR_TESTS = """
        function prRun(id, name, status, durationMs, masterRuns) {
            return { testId: id, name: 'IgniteControlUtilityTestSuite: org.apache.ignite.' + name, suite: 'Control',
                suiteBuildId: 9002, suiteName: 'Control Utility', occurrenceId: 'occ-' + id, status, durationMs,
                masterRuns };
        }
        page.route('/api/pr-tests', { body: { buildId: 9001, note: null, classes: [
            { name: 'org.apache.ignite.TcpDiscoveryClientTopologyGapTest', path: 'p1', added: true,
                runs: [prRun('1', 'TcpDiscoveryClientTopologyGapTest.testGap', 'SUCCESS', 298000, null)] },
            { name: 'org.apache.ignite.GridCommandHandlerTest', path: 'p2', added: false, runs: [
                prRun('2', 'GridCommandHandlerTest.testCacheIdle', 'SUCCESS', 1200, 85),
                prRun('3', 'GridCommandHandlerTest.testNewOption', 'FAILURE', 3000, 0)] },
            { name: 'org.apache.ignite.AbstractGapTest', path: 'p3', added: true, runs: [] }] } });
        """;

    @Test
    void thePrsOwnTestsShowHowTheyRanWithWarnings() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PR_TESTS + """
            await page.load('?pr=13575');
            report({ shown: !page.el('prTestsCard').classList.contains('hidden'),
                count: page.el('prTestsCount').textContent, warn: page.el('prTestsWarn').textContent,
                list: page.el('prTests').textContent, asked: page.fetches('/api/pr-tests')[0].url });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("asked").asText()).isEqualTo("/api/pr-tests?pr=13575&build=9001");
        assertThat(out.get("count").asText()).isEqualTo("(3 classes, 3 tests)");
        assertThat(out.get("warn").asText()).isEqualTo("⚠ 1 test ran longer than 60 s; 2 tests have no master history: "
            + "this run is all there is to judge them by.");
        assertThat(out.get("list").asText())
            .contains("TcpDiscoveryClientTopologyGapTestnew", "1 passed in Control Utility · longest 4 m 58 s",
                "TcpDiscoveryClientTopologyGapTest.testGap4 m 58 s")
            .contains("GridCommandHandlerTestchanged", "1 passed, 1 failed in Control Utility · longest 3 s",
                "GridCommandHandlerTest.testNewOptionfailedno master history")
            .doesNotContain("testCacheIdle")
            .contains("AbstractGapTestnew", "no runs in this RunAll");
    }

    @Test
    void noCardWhenThePrChangesNoTest() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/pr-tests', { body: { buildId: 9001, note: null, classes: [] } });
            await page.load('?pr=13575');
            report({ shown: !page.el('prTestsCard').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }
}
