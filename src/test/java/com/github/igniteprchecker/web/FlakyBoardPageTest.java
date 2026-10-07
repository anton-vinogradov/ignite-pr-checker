package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The flaky board as the audit found it: CacheNearReaderUpdateTest, which fails by design with fail("IGNITE-627"),
 * sat among flaky tests with a prompt to "stabilise" it; a row counted "252 open PRs" when 40 were open; and next
 * to master's fail rate stood the pass/fail strip of one PR's branch. Flaky tests come first, tests broken on
 * master follow under their own heading and prompt, PRs count for 14 days, and the branch strip is gone.
 */
class FlakyBoardPageTest {
    private static final String BOARD = """
        const copied = [];
        page.run('navigator').clipboard.writeText = async text => { copied.push(text); };
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite' } });
        page.route('/api/reruns', { body: [] });
        page.route('/api/test-details', { body: { kind: 'assertion',
            details: 'java.lang.AssertionError: IGNITE-627' } });
        const nearReader = { testId: '103', name: 'org.apache.ignite.CacheNearReaderUpdateTest.testUpdate',
            suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache', suiteBuildId: 9388970, occurrenceId: 'occ-1',
            masterFails: 100, masterRuns: 100, failStreak: 100, broken: true, prCount: 3, prs: [13583, 13655, 13660],
            masterFailures: [{ btId: 'IgniteTests24Java8_Cache', buildId: 9380300, occ: 'occ-m' }] };
        const rebalance = { testId: '102', name: 'org.apache.ignite.RebalanceTest.testRebalance',
            suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache', suiteBuildId: 9388970, occurrenceId: 'occ-2',
            masterFails: 3, masterRuns: 100, failStreak: 0, broken: false, prCount: 1, prs: [13655],
            branchRuns: 'FPF', masterFailures: [] };
        const recent = Object.assign({}, nearReader, { testId: '104', name: 'org.apache.ignite.TxTest.testCommit',
            masterFails: 14, failStreak: 12, prCount: 0, prs: [] });
        page.route('/api/top-flaky', { body: { tracked: 5, muted: 2, prDays: 14,
            tests: [rebalance, nearReader, recent] } });
        await page.load('');
        """;

    @Test
    void flakyTestsComeFirstAndBrokenOnesFollowUnderTheirOwnHeading() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            report({ board: page.el('flaky').textContent, html: page.el('flaky').innerHTML,
                intro: page.el('intro').textContent });
            """);

        String board = out.get("board").asText();
        assertThat(board).containsSubsequence("Flaky on master (1)", "RebalanceTest.testRebalance",
            "3/100 on master", "3% · 1 PR in 14 days",
            "Broken on master (2)", "CacheNearReaderUpdateTest.testUpdate", "failed all 100 recent master runs",
            "3 PRs in 14 days", "TxTest.testCommit", "failed the last 12 of 100 master runs");
        assertThat(board).doesNotContain("open PR");
        assertThat(out.get("html").asText()).doesNotContain("class=\"strip\"");
        assertThat(out.get("intro").asText())
            .contains("Flaky tests come first")
            .contains("2 tests muted on TeamCity are left out.")
            .contains("PR counts cover the last 14 days.");
    }

    @Test
    void aTestBrokenOnMasterGetsAPromptToFindTheCommitThatBrokeIt() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            await page.run('aiFlakyPrompt')(page.el('ai'), nearReader);
            await page.run('aiFlakyPrompt')(page.el('ai'), rebalance);
            report({ broken: copied[0], flaky: copied[1] });
            """);

        assertThat(out.get("broken").asText())
            .startsWith("Find the commit that broke a test on Apache Ignite master (Java) — it failed all 100 recent "
                + "master runs.")
            .contains("- Failed in 3 pull request(s) in the last 14 days")
            .contains("shows the last run it passed and the first it failed")
            .contains("If the test fails on purpose (an explicit fail(\"IGNITE-NNNN\")")
            .doesNotContain("Stabilise");
        assertThat(out.get("flaky").asText())
            .startsWith("Stabilise a flaky test in Apache Ignite (Java)")
            .contains("- Failed in 1 pull request(s) in the last 14 days")
            .doesNotContain("open pull request");
    }

    @Test
    void aTestThatBrokeRecentlyIsNotToldARerunMayPass() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            page.route('/api/test-details', { body: { kind: 'environment', details: 'Node has not joined' } });
            const btn = page.el('why'), row = page.el('row'), box = page.el('box'), pre = page.el('pre');
            btn.dataset.occ = 'occ-1';
            btn.closest = () => row;
            row.querySelector = () => box;
            box.classList.add('hidden');
            box.querySelector = () => pre;
            await page.run('toggleDetails')(btn, recent);
            await page.settle();
            report({ shown: pre.textContent });
            """);

        assertThat(out.get("shown").asText())
            .startsWith("♻ environment/timing — but it failed the last 12 master runs, so a re-run alone is unlikely "
                + "to pass");
    }
}
