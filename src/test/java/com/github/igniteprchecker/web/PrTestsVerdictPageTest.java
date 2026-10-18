package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The owner, on PR 13335: the failed tests in "This PR's tests" scare the author, who has to guess from the green
 * visa that they are not the PR's fault. Each failed test now says what the verdict did with it, with a link to its
 * row in the card that says why, and the class line sums it up.
 */
class PrTestsVerdictPageTest {
    /**
     * PR 13335's GridCommandHandlerSslTest with a failure of each kind the verdict knows, one TeamCity muted, one of a
     * suite that timed out, and one the verdict has not judged.
     */
    private static final String SSL = """
        function prRun(id, test, over = {}) {
            return Object.assign({ testId: id, name: 'IgniteControlUtilityTestSuite: org.apache.ignite.util.' + test,
                suite: 'IgniteTests24Java8_ControlUtility', suiteBuildId: 9002, suiteName: 'Control Utility',
                occurrenceId: 'occ-' + id, status: 'FAILURE', durationMs: 3000, masterRuns: 40, muted: false }, over);
        }
        function judged(run, over) {
            return Object.assign({ testId: run.testId, name: run.name, suite: run.suite, suiteBuildId: 9010,
                suiteName: run.suiteName, occurrenceId: 'later-' + run.testId, blocker: false, watch: false,
                reason: null, branchRuns: 'F', codeRuns: 1 }, over);
        }
        const runs = {
            blocker: prRun('21', 'GridCommandHandlerSslTest.testBlocker'),
            watch: prRun('22', 'GridCommandHandlerSslTest.testWatch'),
            unverified: prRun('23', 'GridCommandHandlerSslTest.testUnverified'),
            filtered: prRun('24', 'GridCommandHandlerSslTest.testFiltered'),
            muted: prRun('25', 'GridCommandHandlerSslTest.testMuted', { muted: true }),
            broken: prRun('26', 'GridCommandHandlerSslTest.testBroken', { suite: 'IgniteTests24Java8_Snapshots6',
                suiteName: 'Snapshots 6', suiteBuildId: 9003 }),
            later: prRun('27', 'GridCommandHandlerSslTest.testLater'),
        };
        const verdicts = {
            blockers: [judged(runs.blocker, { blocker: true, reason: 'failed all 3 runs on this branch' })],
            watch: [judged(runs.watch, { watch: true, reason: 'failed the only run on commit abc1234' })],
            unverified: [judged(runs.unverified, { reason: 'could not verify (TeamCity error: 502 Bad Gateway)' })],
            filtered: [judged(runs.filtered, { reason: 'pre-existing: fails 3/100 on master' })],
            brokenSuites: [{ suite: 'IgniteTests24Java8_Snapshots6', suiteBuildId: 9003, suiteName: 'Snapshots 6',
                problems: ['execution timeout'] }],
        };
        const ssl = { name: 'org.apache.ignite.util.GridCommandHandlerSslTest', path: 'GridCommandHandlerSslTest.java',
            added: false, notInSuite: false, atRun: null, runs: Object.values(runs) };
        page.route('/api/pr-tests', { body: { buildId: 9001, note: null, runsKnown: true, classes: [ssl] } });
        // The line under the failed test in the card's HTML.
        const lineOf = (html, test) => new RegExp('>GridCommandHandlerSslTest\\\\.' + test
            + '</a>.*?<div class="reason">(.*?)</div>').exec(html)[1];
        """;

    @Test
    void eachFailedTestSaysWhatTheVerdictDidWithIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            page.route('/api/analyze', { body: verdict(verdicts) });
            await page.load('?pr=13575');
            const html = page.el('prTests').innerHTML;
            const lines = {};
            for (const test of ['testBlocker', 'testWatch', 'testUnverified', 'testFiltered', 'testMuted', 'testBroken',
                'testLater'])
                lines[test] = lineOf(html, test);
            report({ lines, summary: /<div class="reason">(\\d+ failed \\([^)]*\\))/.exec(html)[1] });
            """);

        JsonNode lines = out.get("lines");
        assertThat(lines.get("testBlocker").asText()).isEqualTo("<a class=\"seen-at\" href=\"#blockersCard\" "
            + "data-place=\"blockers\" data-name=\"IgniteControlUtilityTestSuite: org.apache.ignite.util."
            + "GridCommandHandlerSslTest.testBlocker\" data-suite=\"IgniteTests24Java8_ControlUtility\" "
            + "title=\"Shows this test in “Blockers”.\">blocks this PR</a>");
        assertThat(lines.get("testWatch").asText()).startsWith("<a class=\"seen-at\" href=\"#watchCard\" "
            + "data-place=\"watch\"").endsWith("title=\"Shows this test in “Recently started failing”.\">"
            + "recently started failing</a>: failed the only run on commit abc1234");
        assertThat(lines.get("testUnverified").asText()).startsWith("<a class=\"seen-at\" href=\"#unverifiedCard\"")
            .endsWith(">could not be checked</a>");
        assertThat(lines.get("testFiltered").asText()).startsWith("<a class=\"seen-at\" href=\"#filteredCard\"")
            .endsWith("title=\"Shows this test in “Not blamed on this PR”.\">not blamed</a>: "
                + "pre-existing: fails 3/100 on master");
        assertThat(lines.get("testMuted").asText()).isEqualTo("not checked: TeamCity muted it");
        assertThat(lines.get("testBroken").asText()).isEqualTo("not checked: <a class=\"seen-at\" href=\"#brokenCard\" "
            + "data-place=\"broken\" title=\"Shows its suite in “Broken suites”.\">its suite broke</a>");
        assertThat(lines.get("testLater").asText()).isEqualTo("not checked yet");
        assertThat(out.get("summary").asText()).isEqualTo("7 failed (1 blocks this PR, 1 recently started failing, "
            + "1 could not be checked, 3 not checked, 1 not blamed)");
    }

    /** All three failures of the class are in Not blamed: the class line says the verdict blamed none of them. */
    @Test
    void theClassLineSaysWhenNoneIsBlamed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            ssl.runs = [prRun('31', 'GridCommandHandlerSslTest.testOne'),
                prRun('32', 'GridCommandHandlerSslTest.testTwo'), prRun('33', 'GridCommandHandlerSslTest.testThree')];
            page.route('/api/analyze', { body: verdict({ filtered: ssl.runs.map(r => judged(r,
                { reason: 'flaky on other PR branches: fails 12/100 in 5 other PRs' })) }) });
            await page.load('?pr=13575');
            report({ text: page.el('prTests').textContent });
            """);

        assertThat(out.get("text").asText()).contains("3 failed (none blamed on this PR) in Control Utility");
    }

    /**
     * Not blamed on this PR is folded under the card: the link opens it and takes the reader to the test's row, found
     * by the row's "copy AI prompt" button, which every row has.
     */
    @Test
    void aLinkOpensTheFoldedCardAtTheTestsRow() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const row = page.el('row'), ai = page.el('ai'), fold = page.el('fold'), link = page.el('link');
            ai.dataset.name = 'IgniteControlUtilityTestSuite: org.apache.ignite.util.'
                + 'GridCommandHandlerSslTest.testFiltered';
            ai.dataset.suite = 'IgniteTests24Java8_ControlUtility';
            ai.closest = sel => sel === 'li.test-row' ? row : null;
            page.el('filtered').querySelectorAll = sel => sel === '.why.ai' ? [ai] : [];
            page.el('filteredCard').querySelector = sel => sel === 'details' ? fold : null;
            let scrolled = null;
            row.scrollIntoView = o => { scrolled = o; };
            Object.assign(link.dataset, { place: 'filtered', name: ai.dataset.name, suite: ai.dataset.suite });
            const box = page.el('box');
            box.querySelectorAll = sel => sel === 'a.seen-at' ? [link] : [];
            page.run('wireSeenAt')(box);
            let prevented = false;
            link.onclick({ preventDefault() { prevented = true; } });
            report({ prevented, open: fold.open, scrolled, focused: page.run('document.activeElement').id,
                tabindex: row.getAttribute('tabindex') });
            """);

        assertThat(out.get("prevented").asBoolean()).isTrue();
        assertThat(out.get("open").asBoolean()).isTrue();
        assertThat(out.get("scrolled").toString()).isEqualTo("{\"block\":\"center\"}");
        assertThat(out.get("focused").asText()).isEqualTo("row");
        assertThat(out.get("tabindex").asText()).isEqualTo("-1");
    }

    /** The reader looks at Blockers by root cause, where a test has no row per suite: the link brings Suites back. */
    @Test
    void aBlockerLinkLeavesRootCausesForSuites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/causes', { body: { clusters: [], noMessage: [], unread: [], sampled: 1, total: 1 } });
            await page.load('?pr=13575');
            page.run('setBlockerView')('causes');
            const card = page.el('blockersCard'), link = page.el('link');
            card.scrollIntoView = () => {};
            Object.assign(link.dataset, { place: 'blockers', name: 'org.apache.ignite.Gone.test', suite: 'Cache' });
            page.run('showVerdictRow')(link);
            report({ view: page.run('blockerView'), blockersHidden: page.el('blockers').classList.contains('hidden'),
                focused: page.run('document.activeElement').id });
            """);

        assertThat(out.get("view").asText()).isEqualTo("suites");
        assertThat(out.get("blockersHidden").asBoolean()).isFalse();
        assertThat(out.get("focused").asText()).as("no row of that test: the card").isEqualTo("blockersCard");
    }

    /** The tours quote every line the card writes under a failed test, and its class line. */
    @Test
    void theToursQuoteTheLines() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/static/index.js"), UTF_8);
        List<String> lines = List.of("blocks this PR", "recently started failing", "could not be checked", "not blamed",
            "not checked: TeamCity muted it", "not checked yet", "its suite broke", "none blamed on this PR");

        for (String line : lines)
            assertThat(script).contains("'" + line + "'");
        for (Path tour : List.of(Path.of("docs/features.md"), Path.of("docs/features.ru.md"))) {
            String text = Files.readString(tour, UTF_8);
            for (String line : List.of("`blocks this PR`", "`recently started failing: …`", "`could not be checked`",
                "`not blamed: …`", "`not checked: its suite broke`", "`not checked: TeamCity muted it`",
                "`not checked yet`", "`3 failed (none blamed on this PR)`"))
                assertThat(text).as(tour.toString()).contains(line);
        }
    }
}
