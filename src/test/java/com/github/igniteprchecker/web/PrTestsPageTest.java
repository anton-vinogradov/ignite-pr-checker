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

    /** PR 13335's new SslRenewalTest with no runs, and what Ignite's check of the head 9a8b7c6 says. */
    private static final String SSL_TESTS = """
        function sslTests(state, ssl, elsewhere = [], reason = null) {
            return { buildId: 9001, note: null, classes: [Object.assign({ name: 'org.apache.ignite.internal.ssl.SslRenewalTest',
                path: 'modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java', added: true,
                runs: [], notInSuite: false, atRun: null }, ssl)],
                suiteCheck: { state, sha: '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b',
                    url: 'https://github.com/apache/ignite/actions/runs/18001/job/51234567890', reason, elsewhere } };
        }
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

    /**
     * PR 13335: SslRenewalTest had no runs in the RunAll the page showed while a newer RunAll went, and the page said
     * "an abstract base, or a class no suite runs". SecurityTestSuite held it, as Ignite's own check of the head says,
     * and a commit pushed after the run had moved it to the package it has now.
     */
    @Test
    void aClassAddedAfterTheRunSaysSoWhileANewerRunAllGoes() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9002 }) });
            page.route('/api/pr-tests', { body: sslTests('PASSED', { atRun: 'ABSENT' }) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent, check: page.el('prTestsCheck').textContent,
                warnHidden: page.el('prTestsWarn').classList.contains('hidden') });
            """);

        assertThat(out.get("list").asText())
            .contains("SslRenewalTestnewno runs in this RunAll: added under this name by a commit pushed after it")
            .doesNotContain("no suite runs", "abstract base", "not in any suite");
        assertThat(out.get("check").asText()).isEmpty();
        assertThat(out.get("warnHidden").asBoolean()).isTrue();
    }

    /**
     * The PR added the class before the run, and a commit after the run only changed it: the run had it, and Ignite's
     * check of the run passed it. That the PR adds it, as against master, says nothing of the run.
     */
    @Test
    void aClassTheRunHadHasTheOtherReasons() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('PASSED', { atRun: 'PASSED_CHECK' }) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent });
            """);

        assertThat(out.get("list").asText()).contains(
            "SslRenewalTestnewno runs in this RunAll: its suite did not run or broke, or it is a base or @Ignore class")
            .doesNotContain("added", "after it");
    }

    /**
     * The class was there at the run but in no suite, as Ignite's check of the run's revision says; a commit after it
     * put the class in SecurityTestSuite, and the check of the head passed.
     */
    @Test
    void aClassInNoSuiteAtTheRunSaysSo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('PASSED', { atRun: 'IN_NO_SUITE' }) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent });
            """);

        assertThat(out.get("list").asText()).contains("SslRenewalTestnewno runs in this RunAll: it was in no test "
            + "suite then; a commit pushed after it fixed that").doesNotContain("base or @Ignore");
    }

    /** After a rebase neither GitHub's comparison nor Ignite's check of the run told: every reason stays open. */
    @Test
    void aClassTheRunTellsNothingOfHasEveryReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('PASSED', {}) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent });
            """);

        assertThat(out.get("list").asText()).contains("SslRenewalTestnewno runs in this RunAll: its suite did not run "
            + "or broke, it is a base or @Ignore class, or it came into a suite after this run");
    }

    /** CI never runs a class Ignite's check finds in no suite: the card says so, and what to do. */
    @Test
    void aClassInNoSuiteIsFlaggedWithWhatToDo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('FAILED', { notInSuite: true },
                ['org.apache.ignite.ssl.SslSessionTest']) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent, warn: page.el('prTestsWarn').textContent,
                warnHtml: page.el('prTestsWarn').innerHTML,
                warnHidden: page.el('prTestsWarn').classList.contains('hidden'),
                checkHidden: page.el('prTestsCheck').classList.contains('hidden') });
            """);

        assertThat(out.get("list").asText())
            .contains("SslRenewalTestnewnot in any suiteno runs in this RunAll"
                + "CI never runs it: add it to a test suite or mark it @Ignore.")
            .contains("SslSessionTestnot in any suiteCI never runs it: add it to a test suite or mark it @Ignore.");
        assertThat(out.get("warnHidden").asBoolean()).isFalse();
        assertThat(out.get("warn").asText()).isEqualTo("⚠ 2 test classes are in no test suite, so CI never runs them "
            + "(Ignite's abandoned-tests check on 9a8b7c6).");
        assertThat(out.get("warnHtml").asText())
            .contains("href=\"https://github.com/apache/ignite/actions/runs/18001/job/51234567890\"");
        assertThat(out.get("checkHidden").asBoolean()).isTrue();
    }

    /** Ignite's check still going says nothing of a class: the card does not guess. */
    @Test
    void whileIgnitesCheckRunsNothingIsConcluded() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('RUNNING', {}) });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent, check: page.el('prTestsCheck').textContent });
            """);

        assertThat(out.get("list").asText())
            .contains("SslRenewalTestnewno runs in this RunAll; whether a suite runs it is not known yet")
            .doesNotContain("base", "commit");
        assertThat(out.get("check").asText()).isEqualTo("Ignite's abandoned-tests check is still running on 9a8b7c6.");
    }

    /** Checkstyle failed first, so the abandoned-tests step never ran on the head. */
    @Test
    void aSkippedCheckSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL_TESTS + """
            page.route('/api/pr-tests', { body: sslTests('SKIPPED', {}, [], 'an earlier step of its job failed') });
            await page.load('?pr=13575');
            report({ list: page.el('prTests').textContent, check: page.el('prTestsCheck').textContent });
            """);

        assertThat(out.get("list").asText())
            .contains("SslRenewalTestnewno runs in this RunAll; whether a suite runs it is not known")
            .doesNotContain("not known yet");
        assertThat(out.get("check").asText())
            .isEqualTo("Ignite's abandoned-tests check did not run on 9a8b7c6: an earlier step of its job failed.");
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
