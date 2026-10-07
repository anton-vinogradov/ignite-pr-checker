package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The page's head decided "No blockers 🎉" by a rule of its own, beside the server's for the PR list, the visa and
 * the PR comment, and its caveats were its own wording of the server's. The list once ticked PRs whose pages said
 * "No test blockers". The head now goes by the standing the server sends with the analysis, says the server's
 * caveats, and takes the one about commits pushed since from /api/pending.
 */
class VerdictHeadPageTest {
    private static final String HEAD = """
        report({ head: page.el('blockersHead').textContent, clean: page.el('blockersTitle').classList.contains('clean'),
            caveat: page.el('verdictCaveat').classList.contains('hidden') ? null : page.el('verdictCaveat').textContent,
            title: page.run('document').title });
        """;

    @Test
    void aCleanStandingIsTheOnlyNoBlockers() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN', caveats: [] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No blockers 🎉");
        assertThat(out.get("clean").asBoolean()).isTrue();
        assertThat(out.get("caveat").isNull()).isTrue();
    }

    /** The server's rule, not the page's: a standing it does not call clean is not, whatever the page would make of it. */
    @Test
    void theHeadFollowsTheServersStanding() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'UNPROVEN',
                caveats: ['1 suite(s) have no reliable result (compilation error, timeout, crash)'] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No test blockers");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("caveat").asText()).isEqualTo("Nothing here was blamed on this PR, but this run can't prove "
            + "it is clean: 1 suite(s) have no reliable result (compilation error, timeout, crash).");
        assertThat(out.get("title").asText()).startsWith("⚠ no test blockers");
    }

    @Test
    void testsToWatchAreNoTestBlockers() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'WATCH', caveats: [],
                watch: [Object.assign(blocker('TxRecoveryTest.testRecovery', 'occ-3'), { blocker: false, watch: true,
                    branchRuns: 'PPF', codeRuns: 1, doubts: ['ONE_RUN'] })] }) });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No test blockers");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("caveat").isNull()).isTrue();
    }

    /** PR 13636 had 28 commits pushed after its clean run: old code, in the server's words. */
    @Test
    void aCleanRunOfOlderCodeIsOldCode() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN', caveats: [] }) });
            page.route('/api/pending', { body: { pending: true, ahead: 28, builtSha: '0a1b2c3', headSha: '5be1c0d',
                rewritten: false, caveat: '28 commit(s) pushed since this run — it tested older code' } });
            await page.load('?pr=13575');
            """ + HEAD);

        assertThat(out.get("head").asText()).isEqualTo("No test blockers");
        assertThat(out.get("clean").asBoolean()).isFalse();
        assertThat(out.get("caveat").asText()).endsWith(": 28 commit(s) pushed since this run — it tested older code.");
    }
}
