package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * TcpDiscoveryClientTopologyGapTest came in with PR 13327 taking 298 s, passed there once, and now fails 18 of 100
 * master runs, noise in 77 PRs. The page now shows how the PR's own new and changed test classes ran, and warns
 * about tests over a minute long. A new test that passed is no warning: having no master history is what new means.
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
        assertThat(out.get("warn").asText()).isEqualTo("⚠ 1 test ran longer than 60 s.");
        assertThat(out.get("list").asText())
            .contains("TcpDiscoveryClientTopologyGapTestnew", "1 passed in Control Utility · longest 4 m 58 s",
                "TcpDiscoveryClientTopologyGapTest.testGap4 m 58 s")
            .contains("GridCommandHandlerTestchanged", "1 passed, 1 failed in Control Utility · longest 3 s",
                "GridCommandHandlerTest.testNewOptionfailedno master history")
            .doesNotContain("testCacheIdle")
            .contains("AbstractGapTestnew", "no runs in this RunAll");
    }

    /**
     * PR 13335 adds four SSL test classes and changes one: every test passed in under a second. The card said "6 tests
     * have no master history" in red, as if passing new tests were a problem.
     */
    @Test
    void newTestsThatPassedQuicklyRaiseNoWarning() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            function prRun(id, name, durationMs, masterRuns) {
                return { testId: id, name: 'IgniteControlUtilityTestSuite: org.apache.ignite.' + name, suite: 'Control',
                    suiteBuildId: 9002, suiteName: 'Control Utility 1', occurrenceId: 'occ-' + id, status: 'SUCCESS',
                    durationMs, masterRuns };
            }
            page.route('/api/pr-tests', { body: { buildId: 9001, note: null, classes: [
                { name: 'org.apache.ignite.GridCommandHandlerSslReloadTest', path: 'p1', added: true,
                    runs: [prRun('1', 'GridCommandHandlerSslReloadTest.testReload', 900, null)] },
                { name: 'org.apache.ignite.CommandHandlerParsingTest', path: 'p2', added: false, runs: [
                    prRun('2', 'CommandHandlerParsingTest.testParse', 100, 85),
                    prRun('3', 'CommandHandlerParsingTest.testParseSslReload', 100, 0)] }] } });
            await page.load('?pr=13575');
            report({ warnHidden: page.el('prTestsWarn').classList.contains('hidden'),
                warn: page.el('prTestsWarn').textContent, list: page.el('prTests').textContent });
            """);

        assertThat(out.get("warnHidden").asBoolean()).isTrue();
        assertThat(out.get("warn").asText()).isEmpty();
        assertThat(out.get("list").asText())
            .contains("GridCommandHandlerSslReloadTestnew", "1 passed in Control Utility 1",
                "CommandHandlerParsingTestchanged", "2 passed in Control Utility 1")
            .doesNotContain("no master history", "testParseSslReload");
    }

    /**
     * On PR 13327's first RunAll the analysed chain is the one still going: the SPI suite that runs the new class has
     * not finished, which is not "a class no suite runs". The answer is asked for as one that can still change.
     */
    @Test
    void aClassOfARunAllStillGoingHasNoRunsYet() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PR_TESTS + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9001 }) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent, asked: page.fetches('/api/pr-tests')[0].url });
            """);

        assertThat(out.get("asked").asText()).isEqualTo("/api/pr-tests?pr=13575&build=9001&running=true");
        assertThat(out.get("list").asText()).contains("AbstractGapTestnew", "no runs yet: this RunAll is still going")
            .doesNotContain("a class no suite runs");
    }

    /** Once the chain finishes, its tests are asked for as those of a finished run, and the card shows them. */
    @Test
    void aRunAllThatFinishedIsAskedForAgainAsFinished() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PR_TESTS + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9001,
                computedAt: page.now() - 200000 }) });
            await page.load('?pr=13575');
            page.route('/api/analyze', { body: verdict() });
            page.route('/api/pr-tests', { body: { buildId: 9001, note: null, classes: [
                { name: 'org.apache.ignite.AbstractGapTest', path: 'p3', added: true,
                    runs: [prRun('4', 'AbstractGapTest.testGap', 'FAILURE', 298000, null)] }] } });
            await page.tick(30000);
            report({ list: page.el('prTests').textContent,
                asked: page.fetches('/api/pr-tests').map(f => f.url) });
            """);

        assertThat(out.get("asked")).extracting(JsonNode::asText).containsExactly(
            "/api/pr-tests?pr=13575&build=9001&running=true", "/api/pr-tests?pr=13575&build=9001");
        assertThat(out.get("list").asText()).contains("1 failed in Control Utility · longest 4 m 58 s")
            .doesNotContain("no runs");
    }

    /** A class added by a commit pushed after the analysed run was not in the code that ran. */
    @Test
    void aNewClassWithNoRunsMayComeFromCommitsPushedSince() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PR_TESTS + """
            page.route('/api/pending', { body: { pending: true, ahead: 2, builtSha: '5be1c0d', headSha: '9a8b7c6' } });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent });
            """);

        assertThat(out.get("list").asText()).contains(
            "AbstractGapTestnewno runs in this RunAll: added by a commit pushed since, an abstract base, or a class no "
                + "suite runs");
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
