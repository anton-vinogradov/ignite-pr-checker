package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The flaky board as the audit found it: CacheNearReaderUpdateTest, which fails by design with fail("IGNITE-627"),
 * sat among flaky tests with a prompt to "stabilise" it; a row counted "252 open PRs" when 40 were open; next to
 * master's fail rate stood the pass/fail strip of one PR's branch; "64/100 on master 64%" said one number twice and
 * "on master" forty times; every row carried its failed master runs as a line of chips; and the failure message
 * needed a login the page did not mention. Flaky tests come first, tests broken on master follow under their own
 * heading and prompt; the rate and the PR count stand in two columns under their headings; the master runs and the
 * failure message are in the row's failure panel, which opens without a login and says what needs one.
 */
class FlakyBoardPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/flaky.html");

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
            masterFails: 14, failStreak: 12, prCount: 0, prs: [],
            masterFailures: [{ btId: 'IgniteTests24Java8_Cache', buildId: 9380300, occ: 'occ-m' },
                { btId: 'IgniteTests24Java8_Cache', buildId: 9380100, occ: 'occ-m2' }] });
        page.route('/api/top-flaky', { body: { tracked: 5, muted: 2, prDays: 14,
            tests: [rebalance, nearReader, recent] } });
        await page.load('');
        """;

    /** The slot of the run chip in the rows of the Cache suite, as the board finds it. */
    private static final String SLOT = """
        const slot = page.el('slot');
        slot.dataset.suite = 'IgniteTests24Java8_Cache';
        page.el('flaky').querySelectorAll = sel => sel === '.lb-live' ? [slot] : [];
        """;

    /** A row's toggle, panel, message box and buttons, wired as the page wires them; the copy button starts disabled. */
    private static final String PANEL = """
        const btn = page.el('why'), row = page.el('row'), box = page.el('box'), pre = page.el('pre');
        const copyBtn = page.el('copy'), retryBtn = page.el('retry');
        copyBtn.disabled = true;
        btn.closest = () => row;
        row.querySelector = () => box;
        box.classList.add('hidden');
        box.querySelector = sel => sel === 'pre' ? pre : copyBtn;
        pre.querySelector = () => retryBtn;
        """;

    @Test
    void flakyTestsComeFirstAndBrokenOnesFollowUnderTheirOwnHeading() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            report({ board: page.el('flaky').textContent, html: page.el('flaky').innerHTML,
                intro: page.el('intro').textContent });
            """);

        String board = out.get("board").asText();
        assertThat(board).containsSubsequence("Flaky on master (1)", "Master fail rate", "PRs, last 14 days",
            "They fail some master runs and pass others. Make them stable.",
            "RebalanceTest.testRebalance", "3%3 of 100 runs", "1",
            "Broken on master (2)", "They failed their last 10 master runs in a row, or every run if there were "
                + "fewer. Find the commit that broke them.",
            "CacheNearReaderUpdateTest.testUpdate", "100 in a rowof 100 runs", "3",
            "TxTest.testCommit", "12 in a rowof 100 runs", "—");
        assertThat(board).doesNotContain("open PR", "/100 on master", "PR in 14 days", "recent master runs");
        assertThat(out.get("html").asText()).doesNotContain("class=\"strip\"", "class=\"lbprs\"");
        assertThat(out.get("intro").asText()).isEqualTo("This board shows tests that fail on master, so they also fail "
            + "in PRs that did not break them. 2 tests muted on TeamCity are not shown.");
    }

    /**
     * The columns say how often a test fails and in how many PRs; the details are in their tooltips. The PRs cell
     * carries its own label for the phone, where the column headings are gone.
     */
    @Test
    void theColumnsCarryTheDetailsInTheirTooltips() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            report({ html: page.el('flaky').innerHTML });
            """);

        assertThat(out.get("html").asText())
            .contains("<span class=\"lbcol\" title=\"This test failed 3 of the last 100 master runs.\"><span "
                + "class=\"flake \">3%</span><small>3 of 100 runs</small></span>")
            .contains("<span class=\"lbcol\" title=\"This test failed the last 12 of 100 master runs.\"><span "
                + "class=\"flake\">12 in a row</span><small>of 100 runs</small></span>")
            .contains("<span class=\"lbcol lbcol-prs\" title=\"This test failed in these PRs in the last 14 days: "
                + "#13583, #13655, #13660.\">3<small>PRs, last 14 days</small></span>")
            .contains("<span class=\"lbcol lbcol-prs\" title=\"This test did not fail in any PR in the last 14 days.\">"
                + "<span class=\"prs-none\">—</span><small>no PRs, last 14 days</small></span>");
    }

    /**
     * The row's TC opens the newest failed master run; the older failed master runs are in the panel, so a board of
     * 40 rows no longer shows 200 chips. A row with one failed master run has no such line.
     */
    @Test
    void theOlderFailedMasterRunsAreInTheFailurePanel() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            report({ html: page.el('flaky').innerHTML });
            """);

        String html = out.get("html").asText();
        assertThat(html).containsOnlyOnce("Older failed master runs: ")
            .contains("<p>Older failed master runs: <a href=\"https://ci2.example/buildConfiguration/IgniteTests24Java8_Cache"
                + "/9380100?buildTab=tests&amp;expandedTest=occ-m2#testNameId104\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Open this failed master run in TeamCity.\">#9380100</a></p>")
            .doesNotContain(">#9380300</a>")
            .contains("/9380300?buildTab=tests&amp;expandedTest=occ-m#testNameId104\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Open the latest master failure in TeamCity.\">TC</a>");
        assertThat(html.split("<div class=\"details hidden\">")).as("a panel per row with a message").hasSize(4);
        assertThat(html.split("<p>Failure message, from its latest failure in a PR:</p><pre></pre>")).hasSize(4);
    }

    /**
     * The board's toggle said "Show the failure message and stack trace.", as on the PR page, but its panel also holds
     * the older failed master runs, and a test with no failure in a PR has only those. Both titles name the runs.
     */
    @Test
    void theToggleNamesTheMasterRunsToo() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + PANEL + """
            await page.run('toggleDetails')(btn, recent);
            const opened = btn.title;
            await page.run('toggleDetails')(btn, recent);
            report({ opened, closed: btn.title, label: btn.textContent });
            """);

        assertThat(out.get("opened").asText()).isEqualTo("Hide the older failed master runs and the failure message.");
        assertThat(out.get("closed").asText()).isEqualTo("Show this test's older failed master runs and the failure "
            + "message from its latest failure in a PR, if there are any.");
        assertThat(out.get("label").asText()).isEqualTo("failure ▸");
    }

    /** A test with no failure message and one master run has nothing to open: the prompt button is on its row. */
    @Test
    void aRowWithNothingToOpenCarriesThePromptItself() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const bare = Object.assign({}, rebalance, { testId: '105', name: 'org.apache.ignite.BareTest.testIt',
                occurrenceId: '', masterFailures: [] });
            page.route('/api/top-flaky', { body: { tracked: 5, muted: 0, prDays: 14, tests: [rebalance, bare] } });
            await page.run('tick')();
            report({ html: page.el('flaky').innerHTML, intro: page.el('intro').textContent });
            page.run('renderIntro')(1);
            report({ one: page.el('intro').textContent });
            """);

        String html = out.get("html").asText();
        String bare = html.substring(html.indexOf("BareTest.testIt"));
        assertThat(bare).contains("<button class=\"why ai\" type=\"button\" data-i=\"1\" title=\"Copies a prompt that asks an "
            + "AI coding assistant to make this flaky test stable.\">copy AI prompt</button>")
            .doesNotContain("why toggle", "class=\"details");
        assertThat(html).containsOnlyOnce("<button class=\"why toggle\" type=\"button\" data-i=\"0\" aria-expanded=\"false\" "
            + "title=\"Show this test's older failed master runs and the failure message from its latest failure in a "
            + "PR, if there are any.\">failure ▸</button>");
        assertThat(out.get("intro").asText()).endsWith(" Tests muted on TeamCity are not shown.");
        assertThat(out.get("one").asText()).endsWith(" 1 test muted on TeamCity is not shown.");
    }

    /**
     * The intro before the first load was a fragment written as a sentence, and the "?" after it said what it explains
     * but not that it leaves the board for the docs on GitHub.
     */
    @Test
    void theIntroInTheMarkupIsASentenceAndTheHelpLinkSaysWhereItLeads() throws IOException {
        String html = Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ");

        assertThat(html).contains("<span id=\"intro\">This board shows tests that fail on master, so they also fail in "
                + "PRs that did not break them. Tests muted on TeamCity are not shown.</span>")
            .contains("features.md#the-flaky-board-flakyhtml\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Opens the docs on GitHub that explain how this board works.\"");
    }

    /** A board of 40 rows a group says how many tests the group has when it holds more. */
    @Test
    void aGroupCutShortSaysHowManyItHas() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            page.route('/api/top-flaky', { body: { tracked: 45, muted: 2, prDays: 14, flakyCount: 41, brokenCount: 2,
                tests: [rebalance, nearReader, recent] } });
            await page.run('tick')();
            report({ board: page.el('flaky').textContent });
            """);

        assertThat(out.get("board").asText()).containsSubsequence("Flaky on master (1 of 41)", "Broken on master (2)");
    }

    @Test
    void anEmptyBoardSaysWhatFillsIt() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            page.route('/api/top-flaky', { body: { tracked: 0, muted: 0, prDays: 14, tests: [] } });
            await page.run('tick')();
            report({ board: page.el('flaky').textContent });
            """);

        assertThat(out.get("board").asText()).isEqualTo("No tests failing on master recorded yet. The list fills as "
            + "the checker analyses PRs, and that needs an active login. Check back shortly.");
    }

    @Test
    void aTestBrokenOnMasterGetsAPromptToFindTheCommitThatBrokeIt() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            await page.run('aiFlakyPrompt')(page.el('ai'), nearReader);
            await page.run('aiFlakyPrompt')(page.el('ai'), rebalance);
            const flashed = page.el('ai').textContent;
            await page.tick(2000);
            report({ broken: copied[0], flaky: copied[1], flashed, label: page.el('ai').textContent });
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
        assertThat(out.get("flashed").asText()).isEqualTo("copied");
        assertThat(out.get("label").asText()).isEqualTo("copy AI prompt");
    }

    /**
     * The board kept its own copy of the run chip and left out what the PR page shows from the same data: how long the
     * re-run has gone and that the estimate is what is left. The time follows the state, as on the PR page: right
     * after the PR number, "#13655 12m" read as part of the number.
     */
    @Test
    void aReRunChipSaysHowLongTheRunHasGoneAndWhatIsLeft() throws Exception {
        assertThat(boardWithReRun("state: 'running', pct: 40, leftSec: 1080, startSec: -1, elapsedSec: 2700, "
            + "waitedSec: 60")).contains("#13655 · running 45m · ~18m left");
        assertThat(boardWithReRun("state: 'queued', pct: -1, leftSec: -1, startSec: 300, elapsedSec: -1, "
            + "waitedSec: 720")).contains("#13655 · queued 12m · starts ~5m");
    }

    /**
     * The board compared each poll with the last one as a whole, the re-runs of every PR included. Their times change
     * on every poll while any run goes on ci2, so every 15 s the board was drawn again: the open "failure ▾" panel
     * closed and the focus fell to the page. A re-run's chip is drawn in its slot; the board is drawn again only when
     * its list changes.
     */
    @Test
    void aRunningReRunUpdatesItsChipAndLeavesTheBoardAlone() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD.replace("page.route('/api/reruns', { body: [] });", """
            let polls = 0;
            page.route('/api/reruns', () => ({ body: [{ buildTypeId: 'IgniteTests24Java8_Cache', pr: 13999,
                webUrl: 'https://ci2.example/build/9500', state: 'running', pct: 40, leftSec: -1, startSec: -1,
                waitedSec: -1, elapsedSec: 120 + 20 * polls++ }] }));
            """).replace("await page.load('');", SLOT + "await page.load('');") + """
            const drawn = page.el('flaky').htmlWrites, first = slot.textContent;
            await page.tick(15000);
            const second = slot.textContent;
            await page.tick(15000);
            report({ drawn, after: page.el('flaky').htmlWrites, first, second, third: slot.textContent,
                writes: slot.htmlWrites });
            """);

        assertThat(out.get("after").asInt()).as("the board is not drawn again").isEqualTo(out.get("drawn").asInt());
        assertThat(out.get("first").asText()).isEqualTo("#13999 · running 2m");
        assertThat(out.get("second").asText()).isEqualTo("#13999 · running 2m");
        assertThat(out.get("third").asText()).isEqualTo("#13999 · running 3m");
        assertThat(out.get("writes").asInt()).as("a chip that did not change is not written again").isEqualTo(2);
    }

    /**
     * When the list itself changes and the board is drawn again, the panel that was open opens again from the message
     * already loaded, with no new request, and its toggle keeps the focus.
     */
    @Test
    void aNewListKeepsTheOpenPanelAndTheFocus() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            const doc = page.run('document'), board = page.el('flaky');
            function row(n) {
                const r = page.el('row' + n), box = page.el('box' + n), toggle = page.el('toggle' + n);
                const pre = page.el('pre' + n), copy = page.el('copy' + n);
                r.dataset.key = '102@IgniteTests24Java8_Cache';
                toggle.dataset.i = '0';
                toggle.classList.add('why', 'toggle');
                toggle.closest = () => r;
                box.classList.add('details', 'hidden');
                box.querySelector = sel => sel === 'pre' ? pre : copy;
                r.querySelector = sel => sel === '.details' ? box : sel === '.why.toggle' ? toggle : null;
                return { r, box, toggle, pre };
            }
            const old = row(1), fresh = row(2);
            board.querySelectorAll = sel => sel === '.lbrow' ? [old.r] : [];
            board.contains = el => el === old.toggle;
            await page.run('toggleDetails')(old.toggle, rebalance);
            old.toggle.focus();
            const asked = page.fetches('/api/test-details').length;
            const write = Object.getOwnPropertyDescriptor(board, 'innerHTML').set;
            Object.defineProperty(board, 'innerHTML', { get() { return this._html; }, set(v) {
                write.call(this, v);
                this.querySelectorAll = sel => sel === '.lbrow' ? [fresh.r] : [];
            } });
            page.route('/api/top-flaky', { body: { tracked: 5, muted: 2, prDays: 14,
                tests: [Object.assign({}, rebalance, { masterFails: 4 }), nearReader, recent] } });
            await page.tick(15000);
            report({ open: !fresh.box.classList.contains('hidden'), expanded: fresh.toggle.getAttribute('aria-expanded'),
                shown: fresh.pre.textContent, focused: doc.activeElement === fresh.toggle,
                asked: page.fetches('/api/test-details').length - asked });
            """);

        assertThat(out.get("open").asBoolean()).isTrue();
        assertThat(out.get("expanded").asText()).isEqualTo("true");
        assertThat(out.get("shown").asText()).contains("java.lang.AssertionError: IGNITE-627");
        assertThat(out.get("focused").asBoolean()).isTrue();
        assertThat(out.get("asked").asInt()).as("no new request for the message").isZero();
    }

    @Test
    void aTestThatBrokeRecentlyIsNotToldARerunMayPass() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + PANEL + """
            page.route('/api/test-details', { body: { kind: 'environment', details: 'Node has not joined' } });
            await page.run('toggleDetails')(btn, recent);
            await page.settle();
            report({ shown: pre.textContent, label: btn.textContent, expanded: btn.getAttribute('aria-expanded'),
                hidden: box.classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asText())
            .startsWith("♻ environment/timing — but it failed the last 12 master runs, so a re-run alone is unlikely "
                + "to pass");
        assertThat(out.get("label").asText()).isEqualTo("failure ▾");
        assertThat(out.get("expanded").asText()).isEqualTo("true");
        assertThat(out.get("hidden").asBoolean()).isFalse();
    }

    /** Without a login the panel says what needs one, and the prompt still copies, without the failure output. */
    @Test
    void withoutALoginThePanelSaysSoAndThePromptStillCopies() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + PANEL + """
            page.route('/api/test-details', { status: 401, body: { error: 'Unauthorized' } });
            await page.run('toggleDetails')(btn, nearReader);
            await page.run('aiFlakyPrompt')(page.el('ai'), nearReader);
            report({ shown: pre.textContent, loaded: pre.dataset.loaded || null, prompt: copied[0] });
            """);

        assertThat(out.get("shown").asText()).isEqualTo("Log in on the main page to see the failure message. Without a "
            + "login, the AI prompt still works, but it has no failure message.");
        assertThat(out.get("loaded").isNull()).as("loads again after a login").isTrue();
        assertThat(out.get("prompt").asText()).startsWith("Find the commit that broke a test on Apache Ignite master")
            .doesNotContain("Failure output");
    }

    /** A load that failed leaves a Retry and loads again on it; "(no failure message available)" was a lie then. */
    @Test
    void aFailedLoadLeavesARetry() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + PANEL + """
            page.route('/api/test-details', { status: 502, body: {} });
            await page.run('toggleDetails')(btn, recent);
            const failed = pre.innerHTML;
            page.route('/api/test-details', { body: { kind: 'assertion', details: 'java.lang.AssertionError: boom' } });
            await retryBtn.onclick();
            report({ failed, shown: pre.textContent });
            """);

        assertThat(out.get("failed").asText()).contains("<span class=\"err\">Could not load the failure message.</span>")
            .contains("<button class=\"why retry\" type=\"button\"").doesNotContain("no failure message available");
        assertThat(out.get("shown").asText()).contains("java.lang.AssertionError: boom");
    }

    /**
     * "copy failure" copied the hint about logging in, or "loading…", and said "copied". It works once the message is
     * there: disabled when the panel is drawn and without a login, enabled when the message arrives.
     */
    @Test
    void theCopyButtonWaitsForTheMessage() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + PANEL + """
            page.route('/api/test-details', { status: 401, body: { error: 'Unauthorized' } });
            await page.run('toggleDetails')(btn, nearReader);
            const withoutLogin = copyBtn.disabled;
            page.route('/api/test-details', { body: { kind: 'assertion', details: 'java.lang.AssertionError: IGNITE-627' } });
            await page.run('loadFailure')(box, nearReader);
            report({ html: page.el('flaky').innerHTML, withoutLogin, loaded: copyBtn.disabled });
            """);

        assertThat(out.get("html").asText()).contains("<div class=\"details-acts\"><button class=\"why copy\" type=\"button\" "
            + "disabled title=\"Copy the failure message.\">copy failure</button>");
        assertThat(out.get("withoutLogin").asBoolean()).isTrue();
        assertThat(out.get("loaded").asBoolean()).isFalse();
    }

    /**
     * The columns are as wide in every row as in their headings (the same width both, so "PRS, LAST 14 DAYS" holds one
     * line in the Terminal theme's monospace), and wrap under the name on a phone, where the headings are gone and the
     * PRs cell shows its own label instead of a bare number or dash.
     */
    @Test
    void theColumnsLineUpUnderTheirHeadingsAndWrapOnAPhone() throws IOException {
        String css = Files.readString(PAGE, UTF_8);

        assertThat(rule(css, ".lbcol")).contains("flex: none", "width: 8.5rem");
        assertThat(rule(css, ".lbcol-prs")).contains("justify-content: center").doesNotContain("width");
        assertThat(rule(css, ".lbcol-prs small")).contains("display: none");
        assertThat(css.substring(css.indexOf("@media (max-width: 768px)")))
            .contains(".lbhead, .group-row { flex-wrap: wrap; }").contains(".lbcol { width: auto;")
            .contains(".lbcol-head { display: none; }").contains(".lbcol-prs small { display: inline; }")
            .contains(".lbcol-prs .prs-none { display: none; }");
        assertThat(css).contains("<span id=\"intro\">").contains("features.md#the-flaky-board-flakyhtml")
            .doesNotContain("fix-master");
    }

    /** The board's text while PR 13655 re-runs the suite all its rows are in; {@code run} gives state and timings. */
    private static String boardWithReRun(String run) throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD.replace("page.route('/api/reruns', { body: [] });",
            "page.route('/api/reruns', { body: [{ buildTypeId: 'IgniteTests24Java8_Cache', pr: 13655, "
                + "webUrl: 'https://ci2.example/build/9400', " + run + " }] });").replace("await page.load('');", SLOT
                + "await page.load('');") + """
            report({ chip: slot.textContent });
            """);

        return out.get("chip").asText();
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
