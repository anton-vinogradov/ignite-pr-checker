package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

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
            + "data-place=\"broken\" data-suite=\"IgniteTests24Java8_Snapshots6\" "
            + "title=\"Shows its suite in “Broken suites”.\">its suite broke</a>");
        assertThat(lines.get("testLater").asText()).as("a final verdict").isEqualTo("not checked");
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
     * In Russian, the class line of IgniteCacheGroupsTest read "1 упал (не вменено)" beside "1 не вменён" and "3 упали
     * (ни одно не вменено этому PR)" in the same card: each says it of a test, so each takes its gender.
     */
    @Test
    void theRussianClassLineSpeaksOfATest() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            await page.load('?pr=13575');
            const summaryOf = classRuns => {
                const card = { buildId: 9001, note: null, runsKnown: true,
                    classes: [Object.assign({}, ssl, { runs: classRuns })] };
                page.run('renderPrTests')(card, verdict({ filtered: classRuns.map(r => judged(r,
                    { reason: 'pre-existing: fails 3/100 on master' })) }));
                return /<div class="reason">(\\d+ упа[^(]*\\([^)]*\\))/.exec(page.el('prTests').innerHTML)[1];
            };
            const more = [prRun('31', 'GridCommandHandlerSslTest.testOne'),
                prRun('32', 'GridCommandHandlerSslTest.testTwo')];
            report({ one: summaryOf([runs.filtered]), all: summaryOf([runs.filtered, ...more]) });
            """);

        assertThat(out.get("one").asText()).isEqualTo("1 упал (не вменён)");
        assertThat(out.get("all").asText()).isEqualTo("3 упали (ни один не вменён этому PR)");
    }

    /**
     * Snapshots 6 timed out in the RunAll, and the checker re-ran it: the re-run passed. The verdict, final now, no
     * longer lists the suite as broken and does not judge the failure from the RunAll, so the line promises no check.
     * While the verdict can still change, the line says "not checked yet": the RunAll or a newer one is still going,
     * or TeamCity lookups are missing and the verdict is computed again.
     */
    @Test
    void notCheckedYetOnlyWhileTheVerdictCanChange() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            await page.load('?pr=13575');
            const card = { buildId: 9001, note: null, runsKnown: true,
                classes: [Object.assign({}, ssl, { runs: [runs.later] })] };
            const said = {};
            const states = { final: {}, running: { live: true, liveBuildId: 9001 },
                newerRunGoing: { live: true, liveBuildId: 9050 }, incomplete: { incompleteSince: page.now() } };
            for (const [name, over] of Object.entries(states)) {
                page.run('renderPrTests')(card, verdict(over));
                const html = page.el('prTests').innerHTML;
                const summary = /<div class="reason">(1 failed \\([^)]*\\))/.exec(html)[1];
                said[name] = lineOf(html, 'testLater') + ' | ' + summary;
            }
            report(said);
            """);

        assertThat(out.get("final").asText()).isEqualTo("not checked | 1 failed (not checked)");
        assertThat(out.get("running").asText()).isEqualTo("not checked yet | 1 failed (not checked yet)");
        assertThat(out.get("newerRunGoing").asText()).isEqualTo("not checked yet | 1 failed (not checked yet)");
        assertThat(out.get("incomplete").asText()).isEqualTo("not checked yet | 1 failed (not checked yet)");
    }

    /**
     * PR 13575 was merged while its verdict was incomplete after TeamCity errors, or while a RunAll was going. The page
     * says that no run changes this verdict, so a failure it did not judge is "not checked", not "not checked yet".
     */
    @Test
    void aMergedPrsVerdictPromisesNoCheck() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            await page.load('?pr=13575');
            const card = { buildId: 9001, note: null, runsKnown: true,
                classes: [Object.assign({}, ssl, { runs: [runs.later] })] };
            const merged = page.now() / 1000 - 3600;
            const said = {};
            const states = { incomplete: { mergedAt: merged, incompleteSince: page.now() - 60000 },
                running: { mergedAt: merged, live: true, liveBuildId: 9001 } };
            for (const [name, over] of Object.entries(states)) {
                page.run('renderPrTests')(card, verdict(over));
                const html = page.el('prTests').innerHTML;
                said[name] = lineOf(html, 'testLater') + ' | '
                    + /<div class="reason">(1 failed \\([^)]*\\))/.exec(html)[1];
            }
            report(said);
            """);

        assertThat(out.get("incomplete").asText()).isEqualTo("not checked | 1 failed (not checked)");
        assertThat(out.get("running").asText()).isEqualTo("not checked | 1 failed (not checked)");
    }

    /**
     * A RunAll of PR 13575 is still going, and two failures of GridCommandHandlerSslTest wait for its verdict. Each
     * says "not checked yet", and the class line says the same as with one failure; a muted failure stays "not
     * checked".
     */
    @Test
    void theClassLineCountsTheFailuresNotCheckedYet() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            await page.load('?pr=13575');
            const later = prRun('28', 'GridCommandHandlerSslTest.testLaterToo');
            const summaryOf = classRuns => {
                const card = { buildId: 9001, note: null, runsKnown: true,
                    classes: [Object.assign({}, ssl, { runs: classRuns })] };
                page.run('renderPrTests')(card, verdict({ live: true, liveBuildId: 9001 }));
                return /<div class="reason">(\\d+ failed \\([^)]*\\))/.exec(page.el('prTests').innerHTML)[1];
            };
            report({ two: summaryOf([runs.later, later]), mixed: summaryOf([runs.later, later, runs.muted]) });
            """);

        assertThat(out.get("two").asText()).isEqualTo("2 failed (2 not checked yet)");
        assertThat(out.get("mixed").asText()).isEqualTo("3 failed (2 not checked yet, 1 not checked)");
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
            row.closest = sel => sel === 'details' ? fold : null;
            page.el('filtered').querySelectorAll = sel => sel === '.why.ai' ? [ai] : [];
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

    /**
     * The reader looks at Blockers by root cause, where a test has no row per suite, and has folded the first cause:
     * the link brings Suites back and leaves the cause folded.
     */
    @Test
    void aBlockerLinkLeavesRootCausesForSuites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/causes', { body: { clusters: [], noMessage: [], unread: [], sampled: 1, total: 1 } });
            await page.load('?pr=13575');
            page.run('setBlockerView')('causes');
            const card = page.el('blockersCard'), link = page.el('link'), cause = page.el('cause');
            card.scrollIntoView = () => {};
            card.querySelector = sel => /^details\\b/.test(sel) ? cause : null;
            Object.assign(link.dataset, { place: 'blockers', name: 'org.apache.ignite.Gone.test', suite: 'Cache' });
            page.run('showVerdictRow')(link);
            report({ view: page.run('blockerView'), blockersHidden: page.el('blockers').classList.contains('hidden'),
                focused: page.run('document.activeElement').id, causeOpen: cause.open });
            """);

        assertThat(out.get("view").asText()).isEqualTo("suites");
        assertThat(out.get("blockersHidden").asBoolean()).isFalse();
        assertThat(out.get("focused").asText()).as("no row of that test: the card").isEqualTo("blockersCard");
        assertThat(out.get("causeOpen").asBoolean()).as("the first root cause").isFalse();
    }

    /**
     * Broken suites shows ci2's artifact glitch first, with PDS 5 under its fold, then two suites that timed out,
     * Snapshots 6 among them, under a fold of their own. "its suite broke" under a test of Snapshots 6 opens the fold
     * that holds Snapshots 6 and takes the reader to its row; the glitch's fold stays as it was.
     */
    @Test
    void aBrokenSuiteLinkOpensTheFoldOfItsGroup() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const suite = (name, id, fold) => {
                const ai = page.el('ai-' + name), row = page.el('row-' + name);
                ai.dataset.suite = id;
                ai.closest = sel => sel === 'li' ? row : null;
                row.closest = sel => sel === 'details' ? fold : null;
                row.scrollIntoView = () => {};
                return ai;
            };
            const glitch = page.el('glitch'), timeout = page.el('timeout');
            const ais = [suite('pds5', 'IgniteTests24Java8_Pds5', glitch),
                suite('snapshots6', 'IgniteTests24Java8_Snapshots6', timeout)];
            page.el('brokenSuites').querySelectorAll = sel => sel === '.ai-suite' ? ais : [];
            page.el('brokenCard').querySelector = sel => /^details\\b/.test(sel) ? glitch : null;
            page.el('brokenCard').scrollIntoView = () => {};
            const link = page.el('link');
            Object.assign(link.dataset, { place: 'broken', suite: 'IgniteTests24Java8_Snapshots6' });
            page.run('showVerdictRow')(link);
            report({ glitch: glitch.open, timeout: timeout.open, focused: page.run('document.activeElement').id });
            """);

        assertThat(out.get("timeout").asBoolean()).isTrue();
        assertThat(out.get("glitch").asBoolean()).isFalse();
        assertThat(out.get("focused").asText()).isEqualTo("row-snapshots6");
    }

    /**
     * The link took the reader to Broken suites, and the card got a ring. Once the reader moves on, a click on the
     * card's text does not ring it again: the card is focusable only while the link's jump is on it.
     */
    @Test
    void theRingGoesOnceTheReaderMovesOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const card = page.el('brokenCard'), link = page.el('link');
            card.scrollIntoView = () => {};
            card.removeAttribute = name => { delete card.attributes[name]; };
            Object.assign(link.dataset, { place: 'broken', suite: 'IgniteTests24Java8_Snapshots6' });
            page.run('showVerdictRow')(link);
            const on = card.getAttribute('tabindex');
            for (const fn of card.listeners.blur || []) fn({ target: card });
            report({ on, left: card.getAttribute('tabindex'), focused: page.run('document.activeElement').id });
            """);

        assertThat(out.get("focused").asText()).isEqualTo("brokenCard");
        assertThat(out.get("on").asText()).isEqualTo("-1");
        assertThat(out.get("left").isNull()).as("tabindex after the card lost the focus").isTrue();
    }

    /** The tours quote every line the card writes under a failed test, and its class line. */
    @Test
    void theToursQuoteTheLines() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/static/index.js"), UTF_8);
        List<String> lines = List.of("blocks this PR", "recently started failing", "could not be checked", "not blamed",
            "not checked: TeamCity muted it", "not checked yet", "not checked", "its suite broke",
            "none blamed on this PR");

        for (String line : lines)
            assertThat(script).contains("'" + line + "'");
        for (Path tour : List.of(Path.of("docs/features.md"), Path.of("docs/features.ru.md"))) {
            String text = Files.readString(tour, UTF_8);
            for (String line : List.of("`blocks this PR`", "`recently started failing: …`", "`could not be checked`",
                "`not blamed: …`", "`not checked: its suite broke`", "`not checked: TeamCity muted it`",
                "`not checked yet`", "`not checked`", "`3 failed (none blamed on this PR)`",
                "`2 failed (2 not checked yet)`"))
                assertThat(text).as(tour.toString()).contains(line);
        }
    }
}
