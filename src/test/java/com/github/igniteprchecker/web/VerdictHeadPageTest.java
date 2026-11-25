package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The page's head decided "No blockers 🎉" by a rule of its own, beside the server's for the PR list, the visa and
 * the PR comment, and its caveats were its own wording of the server's; one standing read "No test blockers" whether
 * tests had started failing or the run could not prove the PR clean. The verdict card's heading now goes by the
 * standing the server sends with the analysis, in the words of the PR comment and the visa, says the server's
 * caveats under it, and takes the one about commits pushed since from /api/pending.
 */
class VerdictHeadPageTest {
    /** Given retained live flags on a merged PR, the page still explains that this is a retained verdict. */
    @Test
    void aRetainedMergedVerdictDoesNotPromiseToWaitForANewResult() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN', live: true,
                liveBuildId: 9001, mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, help: page.el('verdictHelp').title });
            """);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("help").asText()).contains("This PR is merged", "retained verdict");
    }

    /** Given blockers found during the first run, the page reports them while the run continues. */
    @Test
    void aFirstRunKeepsFoundBlockersVisible() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ standing: 'BLOCKERS', live: true, liveBuildId: 9001 }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, hidden: page.el('blockersCard').classList.contains('hidden') });
            """);

        assertThat(out.get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("hidden").asBoolean()).isFalse();
    }

    /** Given a completed result and a newer running build, the heading continues to describe the completed result. */
    @Test
    void aNewerRunDoesNotReplaceThePreviousRunsVerdictWithAnEmptyState() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN', live: true,
                liveBuildId: 9100, caveats: ['a newer run is still going — its unfinished suites can still fail'] }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, lines: page.el('verdictLines').textContent });
            """);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("lines").asText()).contains("a newer run is still going");
    }

    /** Given a first run with a failure under observation, the waiting state does not hide that finding. */
    @Test
    void aFirstRunKeepsFailuresThatNeedConfirmationVisible() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'WATCH', live: true,
                liveBuildId: 9001, watch: [Object.assign(blocker('TxRecoveryTest.testRecovery', 'occ-3'), {
                    blocker: false, watch: true, branchRuns: 'PPF', codeRuns: 1, doubts: ['ONE_RUN'] })] }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, hidden: page.el('watchCard').classList.contains('hidden') });
            """);

        assertThat(out.get("head").asText()).isEqualTo("No proven blockers yet");
        assertThat(out.get("hidden").asBoolean()).isFalse();
    }

    /** Given the first unfinished run, its empty failure list means wait, even after some suites have finished. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void anUnfinishedFirstRunDoesNotClaimItFoundNoBlockers(int suitesRan) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const suitesRan = " + suitesRan + ";\n" + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN', live: true,
                liveBuildId: 9001, suitesRan, suitesReused: 0, finishedAt: 0,
                caveats: ['this run is still going — its unfinished suites can still fail'] }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, html: page.el('verdictLines').innerHTML,
                help: page.el('verdictHelp').title, checking: page.el('verdictCard').classList.contains('checking') });
            """);

        assertThat(out.get("head").asText()).isEqualTo("Run in progress — waiting for results");
        assertThat(out.get("html").asText()).isEqualTo("<p class=\"busy\">Wait for the run to finish to get a result.</p>"
            + "<ul class=\"verdict-reasons\"><li>this run is still going — its unfinished suites can still fail</li></ul>");
        assertThat(out.get("help").asText()).contains("has not finished", "Wait for its results")
            .doesNotContain("No blockers", "No failures");
        assertThat(out.get("checking").asBoolean()).isTrue();
    }

    /** Given a finished result and a newer running build, the page still describes the finished result. */
    @Test
    void aNewerRunDoesNotReplaceTheEarlierVerdictWithAWaitingHeadline() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN', live: true,
                liveBuildId: 9100, caveats: ['a newer run is still going — its unfinished suites can still fail'] }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent });
            """);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
    }

    /** Given a blocker already found in the first running build, it remains visible as a preliminary finding. */
    @Test
    void anUnfinishedRunKeepsTheBlockersItAlreadyFound() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ standing: 'BLOCKERS', live: true, liveBuildId: 9001,
                caveats: ['this run is still going — its unfinished suites can still fail'] }) });
            await page.load('?pr=13575');
            report({ head: page.el('verdictText').textContent, blockers: page.el('blockersCard').classList.contains('hidden') });
            """);

        assertThat(out.get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("blockers").asBoolean()).isFalse();
    }

    private static final String HEAD = """
        report({ head: page.el('verdictText').textContent, clean: page.el('verdictCard').classList.contains('clean'),
            lines: page.el('verdictLines').textContent, html: page.el('verdictLines').innerHTML,
            title: page.run('document').title });
        """;

    @Test
    void aCleanStandingIsTheOnlyNoBlockers() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN', caveats: [] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("✓ No blockers");
        assertThat(out.get("clean").asBoolean()).isTrue();
        assertThat(out.get("lines").asText())
            .isEqualTo("Nothing in this run looks caused by your PR. You don't need to fix or re-run anything.");
        assertThat(out.get("title").asText()).startsWith("✅ no blockers");
    }

    /** The server's rule, not the page's: a standing it does not call clean is not, whatever the page would make of it. */
    @Test
    void theHeadFollowsTheServersStanding() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN',
                caveats: ['2 suites have no reliable result (compilation error; execution timeout)',
                    '1 failed test could not be checked (TeamCity errors)'] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("html").asText()).isEqualTo("<ul class=\"verdict-reasons\">"
            + "<li>2 suites have no reliable result (compilation error; execution timeout)</li>"
            + "<li>1 failed test could not be checked (TeamCity errors)</li></ul>");
        assertThat(out.get("title").asText()).startsWith("⚠ not proven clean");
    }

    @Test
    void testsToWatchAreNoProvenBlockersYet() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'WATCH', caveats: [],
                watch: [Object.assign(blocker('TxRecoveryTest.testRecovery', 'occ-3'), { blocker: false, watch: true,
                    branchRuns: 'PPF', codeRuns: 1, doubts: ['ONE_RUN'] })] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No proven blockers yet");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("html").asText()).isEqualTo("<ul class=\"verdict-reasons\">"
            + "<li>1 test started failing on the code this run tested.</li></ul>"
            + "<p>Re-run their suites under “Recently started failing”: the re-run shows whether these tests "
            + "are blockers.</p>");
        assertThat(out.get("title").asText()).startsWith("⚠ started failing");
    }

    /** PR 13636 had 28 commits pushed after its clean run: old code, said once, in the note at the top. */
    @Test
    void aCleanRunOfOlderCodeIsOldCode() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN', caveats: [] }) });
            page.route('/api/pending', { body: { pending: true, ahead: 28, builtSha: '0a1b2c3', headSha: '5be1c0d',
                rewritten: false, caveat: '28 commits pushed since this run — it tested older code' } });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("lines").asText())
            .isEqualTo("it tested older code (see the note at the top)");
    }

    /** Tests that started failing on code the PR has moved past: both said, each once. */
    @Test
    void testsToWatchOnOlderCodeSayBoth() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'WATCH', caveats: [],
                watch: [Object.assign(blocker('TxRecoveryTest.testRecovery', 'occ-3'), { blocker: false, watch: true }),
                    Object.assign(blocker('TxRecoveryTest.testRollback', 'occ-4'), { blocker: false, watch: true })] }) });
            page.route('/api/pending', { body: { pending: true, ahead: 2, builtSha: '0a1b2c3', headSha: '5be1c0d',
                rewritten: false } });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No proven blockers yet");
        assertThat(out.get("html").asText()).isEqualTo("<ul class=\"verdict-reasons\">"
            + "<li>2 tests started failing on the code this run tested.</li>"
            + "<li>it tested older code (see the note at the top)</li></ul>"
            + "<p>Re-run their suites under “Recently started failing”: the re-run shows whether these tests "
            + "are blockers.</p>");
    }

    /** With blockers, the caveats are said too, as the PR comment says them. */
    @Test
    void blockersComeWithTheCaveats() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ standing: 'BLOCKERS',
                caveats: ['the RunAll was interrupted — 3 suites never ran'] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("html").asText()).isEqualTo("<p>This run doesn&#39;t cover the PR fully.</p>"
            + "<ul class=\"verdict-reasons\"><li>the RunAll was interrupted — 3 suites never ran</li></ul>");
        assertThat(out.get("title").asText()).startsWith("❌ 1 blocker");
    }
}
