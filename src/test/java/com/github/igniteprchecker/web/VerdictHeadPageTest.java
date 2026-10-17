package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The page's head decided "No blockers 🎉" by a rule of its own, beside the server's for the PR list, the visa and
 * the PR comment, and its caveats were its own wording of the server's; one standing read "No test blockers" whether
 * tests had started failing or the run could not prove the PR clean. The verdict card's heading now goes by the
 * standing the server sends with the analysis, in the words of the PR comment and the visa, says the server's
 * caveats under it, and takes the one about commits pushed since from /api/pending.
 */
class VerdictHeadPageTest {
    private static final String HEAD = """
        report({ head: page.el('verdictText').textContent, clean: page.el('verdictCard').classList.contains('clean'),
            lines: page.el('verdictLines').textContent, title: page.run('document').title });
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
            .isEqualTo("Nothing in this run looks caused by your PR. You don't need to fix or rerun anything.");
        assertThat(out.get("title").asText()).startsWith("✅ no blockers");
    }

    /** The server's rule, not the page's: a standing it does not call clean is not, whatever the page would make of it. */
    @Test
    void theHeadFollowsTheServersStanding() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN',
                caveats: ['1 suite has no reliable result (compilation error, timeout, crash)'] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("lines").asText()).isEqualTo("This run can't prove the PR is clean: 1 suite has no "
            + "reliable result (compilation error, timeout, crash).");
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
        assertThat(out.get("lines").asText()).isEqualTo("1 test started failing on the code this run tested. Rerun "
            + "their suites under Recently started failing: the rerun shows whether these tests are blockers.");
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
            .isEqualTo("This run can't prove the PR is clean: it tested older code (see the note at the top).");
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
        assertThat(out.get("lines").asText()).isEqualTo("2 tests started failing on the code this run tested. Rerun "
            + "their suites under Recently started failing: the rerun shows whether these tests are blockers."
            + "This run also can't prove the PR is clean: it tested older code (see the note at the top).");
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
        assertThat(out.get("lines").asText())
            .isEqualTo("This run doesn't cover the PR fully: the RunAll was interrupted — 3 suites never ran.");
        assertThat(out.get("title").asText()).startsWith("❌ 1 blocker");
    }
}
