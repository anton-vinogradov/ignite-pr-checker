package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FlakyHistoryPageTest {
    /** The board API supplies the same dated run and attention records as FlakyStats. No login is needed. */
    private static final String BOARD = """
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite' } });
        page.route('/api/reruns', { body: [] });
        const at = day => Date.UTC(2026, 10, day, 12) / 1000;
        const run = (buildId, status, finishedAt) => ({ buildId, btId: 'Cache', occ: 'occ-' + buildId,
            status, finishedAt });
        const history = [run(300, 'P', at(1)), run(100, 'F', at(2)), run(200, 'F', at(3))];
        const candidate = { testId: '102', name: 'org.apache.ignite.CandidateTest.testIt', suite: 'Cache',
            suiteName: 'Cache', suiteBuildId: 9001, occurrenceId: '', masterFails: 2, masterRuns: 100,
            failStreak: 2, broken: false, prCount: 1, prs: [13655], masterFailures: [history[2], history[1]],
            masterHistory: history, historyCheckedAt: at(4) * 1000,
            attention: { lastPass: history[0], firstFailure: history[1], failedRuns: 2 } };
        """;

    /** Given P→FF master observations, when the board loads, then one candidate group shows dated master links. */
    @Test
    void aCandidateHasItsOwnGroupAndDatedMasterLinks() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const flaky = Object.assign({}, candidate, { testId: '103', name: 'RecoveredTest.testIt',
                attention: null, masterHistory: [], historyCheckedAt: 0 });
            const broken = Object.assign({}, flaky, { testId: '104', name: 'LongBrokenTest.testIt', broken: true,
                failStreak: 100, masterFails: 100 });
            page.route('/api/top-flaky', { body: { tests: [candidate, flaky, broken], attentionCount: 5,
                flakyCount: 1, brokenCount: 1 } });
            await page.load('');
            report({ text: page.el('flaky').textContent, html: page.el('flaky').innerHTML });
            """);

        assertThat(out.get("text").asText()).containsSubsequence("Failing after a pass (1 of 5)",
            "Candidates to inspect", "CandidateTest.testIt", "Last observed pass:", "1 Nov 2026",
            "first observed failure after it:", "2 Nov 2026", "2 failed runs in a row",
            "Flaky on master (1)", "RecoveredTest.testIt", "Broken on master (1)", "LongBrokenTest.testIt");
        String html = out.get("html").asText();
        assertThat(html).containsOnlyOnce("data-key=\"102@Cache\"")
            .contains("aria-label=\"Passed; build #300; 1 Nov 2026", "aria-label=\"Failed; build #100; 2 Nov 2026",
                "https://ci2.example/buildConfiguration/Cache/300?buildTab=tests", ">P</a>", ">F</a>");
        assertThat(firstPoints(html)).containsSubsequence("/Cache/300?", "/Cache/100?", "/Cache/200?");
    }

    /** Given a 50-run window, when it is drawn, then only twelve points precede the native full-history disclosure. */
    @Test
    void aLongHistoryHasASmallPreviewAndANativeFullDisclosure() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const many = Array.from({ length: 50 }, (_, i) => run(1000 + i, i % 2 ? 'F' : 'P', at(1) + i * 60));
            page.route('/api/top-flaky', { body: { tests: [Object.assign({}, candidate,
                { masterHistory: many, attention: null })] } });
            await page.load('');
            report({ html: page.el('flaky').innerHTML });
            """);

        String html = out.get("html").asText();
        assertThat(pointsIn(firstPoints(html))).isEqualTo(12);
        assertThat(html).contains("Showing the latest 12 of 50 dated runs.",
            "<details class=\"history-all\"><summary>Show all 50 dated runs</summary>");
        assertThat(pointsIn(html.substring(html.indexOf("<details class=\"history-all\">")))).isEqualTo(50);
    }

    /** Given old anchors, undated runs and an all-red window, when shown, then none invents a dated start. */
    @Test
    void anIncompleteWindowDoesNotInventWhenFailuresBegan() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const old = Object.assign({}, candidate, { attention: null, historyCheckedAt: 0,
                masterHistory: [run(90, 'F', null)] });
            const undated = Object.assign({}, old, { testId: '103', historyCheckedAt: at(4) * 1000 });
            const allRed = Object.assign({}, old, { testId: '104', historyCheckedAt: at(4) * 1000,
                masterHistory: history.slice(1), masterRuns: 2, masterFails: 2, failStreak: 2, broken: true });
            page.route('/api/top-flaky', { body: { tests: [old, undated, allRed] } });
            await page.load('');
            report({ text: page.el('flaky').textContent });
            """);

        assertThat(out.get("text").asText()).contains("Master history has not been checked yet.",
            "Runs without a finish date: 1; the transition is unknown.",
            "No earlier pass is visible in this window; the start of the failures is unknown.")
            .doesNotContain("Last observed pass:", "Failing after a pass", "1970");
    }

    /** Given an attention candidate, when its AI prompt is prepared, then it asks for evidence of a regression. */
    @Test
    void aCandidatePromptAsksForEvidenceOfARegression() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            page.route('/api/top-flaky', { body: { tests: [candidate] } });
            await page.load('');
            report({ prompt: page.run('flakyPrompt')(candidate, '') });
            """);

        assertThat(out.get("prompt").asText()).startsWith("Investigate a possible regression")
            .contains("2026-11-01T12:00:00.000Z", "2026-11-02T12:00:00.000Z",
                "whether this is intermittent flakiness or a code regression", "dates do not prove the exact start")
            .doesNotContain("Find the commit that broke a test");
    }

    /** Given an expanded native history, when refreshed rows replace it, then the user's disclosure stays open. */
    @Test
    void anExpandedHistoryStaysOpenWhenTheBoardChanges() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const longHistory = Array.from({ length: 13 }, (_, i) => run(1000 + i, i % 2 ? 'F' : 'P', at(1) + i * 60));
            const rowData = Object.assign({}, candidate, { masterHistory: longHistory, attention: null,
                masterFails: 6, masterRuns: 13, failStreak: 0 });
            page.route('/api/top-flaky', { body: { tests: [rowData] } });
            await page.load('');
            const board = page.el('flaky'), old = page.el('oldRow'), fresh = page.el('freshRow');
            const oldHistory = page.el('oldHistory'), freshHistory = page.el('freshHistory');
            old.dataset.key = fresh.dataset.key = '102@Cache';
            oldHistory.open = true;
            freshHistory.open = false;
            old.querySelector = sel => sel === '.history-all' ? oldHistory : null;
            fresh.querySelector = sel => sel === '.history-all' ? freshHistory : null;
            board.querySelectorAll = sel => sel === '.lbrow' ? [old] : [];
            const kept = page.run('keptState')(board);
            board.querySelectorAll = sel => sel === '.lbrow' ? [fresh] : [];
            page.run('restoreState')(board, [rowData], kept);
            report({ open: freshHistory.open });
            """);

        assertThat(out.get("open").asBoolean()).isTrue();
    }

    /** The first point group is the visible preview, before any native disclosure. */
    private static String firstPoints(String html) {
        Matcher found = Pattern.compile("<div class=\"history-points\">(.*?)</div>", Pattern.DOTALL).matcher(html);
        assertThat(found.find()).isTrue();
        return found.group(1);
    }

    private static long pointsIn(String html) {
        return Pattern.compile("class=\"history-run ").matcher(html).results().count();
    }
}
