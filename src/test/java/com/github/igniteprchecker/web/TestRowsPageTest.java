package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * PR 13692's page listed its 33 blockers as 33 rows that each repeated the grey reason, the "1 run" tag and a strip
 * of one bar under a heading that already said every one of them failed its only run; the Python test names carried
 * /opt/teamcity/work/425d7af4e9aa99d9/ in their parameters; "why?" and "ai" stood on every row; and a suite re-run
 * once stood as two groups with two Rerun pairs, since the rows were grouped by run while the buttons counted
 * suites. Now the rows are grouped by suite, a reason shared by the tests of a suite is written once above them,
 * what the heading says is not repeated on the rows, the name is plain text without the agent's path, and the
 * failure opens from "failure ▸" with the copy buttons inside its panel.
 */
class TestRowsPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** {@code test(name, occ, over)} is a blocker with fields of {@code over}; the Python names are PR 13692's. */
    private static final String TESTS = """
        const test = (name, occ, over) => Object.assign(blocker(name, occ), over);
        const PY_PATH = '/opt/teamcity/work/425d7af4e9aa99d9/ignite-python-thin-client/tests/__/examples/read_binary_py';
        const SHARED = 'not seen failing in 78 master runs on JDK 17; failed all 3 runs on this branch';
        """;

    @Test
    void twoRunsOfOneSuiteAreOneGroupLinkedToItsNewestRun() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TESTS + """
            const again = test('TxTest.testTx', 'occ-2', { suiteBuildId: 9010, blocker: false,
                reason: 'pre-existing: fails 12/100 on master' });
            const first = test('ClientReconnectTest.testReconnect', 'occ-1', { blocker: false,
                reason: 'pre-existing: fails 12/100 on master' });
            page.route('/api/analyze', { body: verdict({ blockers: [], filtered: [first, again] }) });
            await page.load('?pr=13575');
            report({ html: page.el('filtered').innerHTML, count: page.el('filteredCount').textContent });
            """);

        String html = out.get("html").asText();
        assertThat(html.split("class=\"suite-head\"")).as("one group for the suite").hasSize(2);
        assertThat(html)
            .contains("href=\"https://ci2.example/buildConfiguration/IgniteTests24Java8_Cache/9010\" target=\"_self\" "
                + "rel=\"noopener\" title=\"Open the newest run of this suite in TeamCity.\">TC</a>")
            .contains("/IgniteTests24Java8_Cache/9002?buildTab=tests")
            .contains("/IgniteTests24Java8_Cache/9010?buildTab=tests");
        assertThat(out.get("count").asText()).isEqualTo("(1 suite, 2 tests)");
    }

    @Test
    void aReasonSharedByTheTestsOfASuiteIsWrittenOnceAboveThem() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TESTS + """
            const other = 'not seen failing in only 4 master runs on JDK 17; failed all 3 runs on this branch';
            page.route('/api/analyze', { body: verdict({ blockers: [test('A.a', 'o1', { reason: other }),
                test('B.b', 'o2', { reason: SHARED }), test('C.c', 'o3', { reason: SHARED })] }) });
            await page.load('?pr=13575');
            const several = { html: page.el('blockers').innerHTML, text: page.el('blockers').textContent };
            page.route('/api/analyze', { body: verdict({ buildId: 9100, blockers: [test('A.a', 'o1', { reason: SHARED }),
                test('B.b', 'o2', { reason: SHARED })] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ several, one: { html: page.el('blockers').innerHTML, text: page.el('blockers').textContent } });
            """);

        JsonNode several = out.get("several");
        assertThat(several.get("html").asText()).as("the bigger group first, its rows one step in, the lone test's reason under it")
            .containsSubsequence("<li class=\"reason-head\">2 tests: not seen failing in 78 master runs on JDK 17; failed "
                + "all 3 runs on this branch</li>", "<li class=\"test-row grouped\" title=\"not seen failing in 78", ">B.b</span>",
                "<li class=\"test-row grouped\" title=\"not seen failing in 78", ">C.c</span>", "<li class=\"test-row\"><span",
                ">A.a</span>", "<div class=\"reason\">not seen failing in only 4 master runs on JDK 17; failed all 3 runs on "
                + "this branch</div>")
            .doesNotContain("1 tests:");
        assertThat(several.get("text").asText().split("not seen failing in 78 master")).as("said once").hasSize(2);
        JsonNode one = out.get("one");
        assertThat(one.get("html").asText()).as("the shared reason is the first line of the list, at the rows' step")
            .containsSubsequence("</div><ul class=\"suite-tests\"><li class=\"reason-head\">not seen failing in 78 master runs on "
                + "JDK 17; failed all 3 runs on this branch</li><li class=\"test-row\" title=", ">A.a</span>", ">B.b</span>")
            .doesNotContain("tests:", "grouped", "<div class=\"reason\">");
        assertThat(one.get("text").asText().split("not seen failing in 78 master")).as("said once").hasSize(2);
    }

    /** Only the lists whose heading says "1 run" leave it out: Recently started failing keeps the tag and the strip. */
    @Test
    void theNotConfirmedListSaysOneRunOnceForAllItsTests() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TESTS + """
            const once = (name, occ, over) => test(name, occ, Object.assign({ branchRuns: 'F', codeRuns: 1,
                doubts: ['ONE_RUN'], reason: 'no master history on JDK 17 (can\\'t prove pre-existing); failed the only '
                + 'run on this branch' }, over));
            page.route('/api/analyze', { body: verdict({ blockers: [test('A.a', 'o1', { reason: SHARED }),
                once('B.b', 'o2'), once('C.c', 'o3', { reason: 'not seen failing in 78 master runs on JDK 17; failed '
                + 'the only run on this branch' })],
                watch: [once('D.d', 'o4', { blocker: false, watch: true, branchRuns: 'PPF',
                    reason: 'failed the only run on commit 5be1c0d; the 2 earlier runs were on other code' })] }) });
            await page.load('?pr=13575');
            const mixed = { oneRun: page.el('oneRunBlockers').innerHTML, watch: page.el('watch').innerHTML };
            page.route('/api/analyze', { body: verdict({ buildId: 9100, blockers: [once('B.b', 'o2'), once('C.c', 'o3')] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ mixed, all: page.el('blockers').innerHTML, allText: page.el('blockers').textContent });
            """);

        String oneRun = out.get("mixed").get("oneRun").asText();
        assertThat(oneRun)
            .contains("<li class=\"test-row\" title=\"no master history on JDK 17 (can&#39;t prove pre-existing); failed the "
                + "only run on this branch\">")
            .contains("<div class=\"reason\">no master history on JDK 17 (can&#39;t prove pre-existing)</div>")
            .contains("<div class=\"reason\">not seen failing in 78 master runs on JDK 17</div>")
            .contains("data-verdict=\"BLOCKER — no master history on JDK 17 (can&#39;t prove pre-existing); failed the "
                + "only run on this branch\"")
            .doesNotContain(">1 run</span>", "class=\"strip\"", "failed the only run on this branch</div>");
        assertThat(out.get("mixed").get("watch").asText()).contains(">1 run</span>", "class=\"strip\"");
        assertThat(out.get("all").asText()).as("every blocker on one run: the line above the list says it")
            .contains("<ul class=\"suite-tests\"><li class=\"reason-head\">no master history on JDK 17 (can&#39;t prove "
                + "pre-existing)</li>")
            .doesNotContain(">1 run</span>", "class=\"strip\"");
        assertThat(out.get("allText").asText()).doesNotContain("failed the only run on this branch");
    }

    @Test
    void theRowOpensTheFailureFromFailureAndCopiesFromThePanel() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TESTS + """
            const silent = test('S.s', null, { occurrenceId: null, branchRuns: 'PF', codeRuns: 2,
                doubts: ['NO_MASTER_HISTORY'] });
            page.route('/api/analyze', { body: verdict({ blockers: [blocker('A.a', 'occ-1'), silent] }) });
            await page.load('?pr=13575');
            report({ html: page.el('blockers').innerHTML });
            """);

        String html = out.get("html").asText();
        Matcher row = Pattern.compile("<li class=\"test-row\"><span class=\"tname\" title=\"org.apache.ignite.A.a\">A.a</span>"
            + "<span class=\"tacts\">(.*?)</span><div class=\"details hidden\" (.*?)</li>").matcher(html);
        assertThat(row.find()).as(html).isTrue();
        assertThat(row.group(1)).as("strip, tags, TC, failure")
            .containsSubsequence("<span class=\"strip\"", "</span>", "<a class=\"ext\" href=", ">TC</a>",
                "<button class=\"why toggle\" data-occ=\"occ-1\" aria-expanded=\"false\" "
                    + "title=\"Show the failure message and stack trace.\">failure ▸</button>")
            .doesNotContain("why?", ">ai<", "copy AI prompt");
        assertThat(row.group(2))
            .startsWith("data-occ=\"occ-1\" data-samecode=\"FFF\"><div class=\"details-acts\">"
                + "<button class=\"why copy\" type=\"button\" title=\"Copy the failure message.\">copy failure</button>"
                + "<button class=\"why ai\" data-occ=\"occ-1\"")
            .endsWith("title=\"Copies a prompt that asks an AI coding assistant to fix this test.\">copy AI prompt</button>"
                + "</div><pre></pre></div>");
        Matcher noPanel = Pattern.compile("<li class=\"test-row\"><span class=\"tname\" title=\"org.apache.ignite.S.s\">S.s"
            + "</span><span class=\"tacts\">(.*?)</span></li>").matcher(html);
        assertThat(noPanel.find()).as(html).isTrue();
        assertThat(noPanel.group(1)).as("no failure message to open: the prompt button stands on the row")
            .containsSubsequence("<span class=\"strip\"", ">new test / no master history</span>", ">TC</a>",
                "<button class=\"why ai\" data-occ=\"\"", ">copy AI prompt</button>")
            .doesNotContain("toggle", "failure ▸", "class=\"details");
    }

    /** A redraw that reopens a panel goes through openDetails, which paints the button as the click does. */
    @Test
    void theFailureButtonSaysWhetherItsPanelIsOpen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const btn = page.el('toggle'), row = page.el('row'), box = page.el('box'), pre = page.el('pre');
            btn.closest = () => row;
            box.closest = () => row;
            row.querySelector = sel => sel === '.details' ? box : sel === '.why.toggle' ? btn : null;
            box.classList.add('hidden');
            box.dataset.occ = 'occ-1';
            box.querySelector = () => pre;
            const state = () => ({ expanded: btn.getAttribute('aria-expanded'), label: btn.textContent, title: btn.title,
                hidden: box.classList.contains('hidden') });
            page.run('toggleDetails')(btn);
            await page.settle();
            const opened = state();
            page.run('toggleDetails')(btn);
            await page.settle();
            const closed = state();
            await page.run('openDetails')(box);
            report({ opened, closed, restored: state(), shown: pre.textContent });
            """);

        assertThat(out.get("opened").toString()).isEqualTo("{\"expanded\":\"true\",\"label\":\"failure ▾\","
            + "\"title\":\"Hide the failure message.\",\"hidden\":false}");
        assertThat(out.get("closed").toString()).isEqualTo("{\"expanded\":\"false\",\"label\":\"failure ▸\","
            + "\"title\":\"Show the failure message and stack trace.\",\"hidden\":true}");
        assertThat(out.get("restored").toString()).isEqualTo(out.get("opened").toString());
        assertThat(out.get("shown").asText()).isEqualTo("java.lang.AssertionError: expected:<1> but was:<2>");
    }

    @Test
    void theNameIsPlainTextWithTheFullNameInItsTitleAndNoAgentPath() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TESTS + """
            const py = test('x', 'occ-py', { name: 'test_examples.test_examples(' + PY_PATH + ')', suite: 'Python',
                suiteName: 'Thin client: Python', suiteBuildId: 9003 });
            page.route('/api/analyze', { body: verdict({ blockers: [py] }) });
            await page.load('?pr=13575');
            report({ html: page.el('blockers').innerHTML });
            """);

        assertThat(out.get("html").asText())
            .contains("<span class=\"tname\" title=\"test_examples.test_examples(/opt/teamcity/work/425d7af4e9aa99d9/"
                + "ignite-python-thin-client/tests/__/examples/read_binary_py)\">test_examples.test_examples("
                + "ignite-python-thin-client/tests/__/examples/read_binary_py)</span>")
            .doesNotContain(">test_examples.test_examples(/opt");
    }

    /** The one rule cuts the work directory of any agent, a Windows one included, and leaves other paths alone. */
    @Test
    void theAgentsWorkDirectoryIsCutFromTheName() throws Exception {
        JsonNode out = PageFunctions.run("index.js", """
            out({
                linux: shortTestName('test_examples.test_examples(/opt/teamcity/work/425d7af4e9aa99d9/ignite-python-thin-client/tests/__/examples/sql_py)'),
                windows: shortTestName('IgniteOdbcTest: Suite: TestCase(C:\\\\TeamCity\\\\work\\\\1f2e3d4c5b6a7980\\\\modules\\\\x.cpp)'),
                two: shortTestName('test_a.test_b(/opt/teamcity/work/425d7af4e9aa99d9/a, /opt/teamcity/work/425d7af4e9aa99d9/b)'),
                plain: shortTestName('org.apache.ignite.SomeTest.testWork(/work/in/progress)'),
                java: shortTestName('org.apache.ignite.internal.SomeTest.testX[param=1]'),
            });
            """);

        assertThat(out.get("linux").asText()).isEqualTo("test_examples.test_examples(ignite-python-thin-client/tests/__/examples/sql_py)");
        assertThat(out.get("windows").asText()).isEqualTo("Suite: TestCase(modules\\x.cpp)");
        assertThat(out.get("two").asText()).isEqualTo("test_a.test_b(a, b)");
        assertThat(out.get("plain").asText()).isEqualTo("SomeTest.testWork(/work/in/progress)");
        assertThat(out.get("java").asText()).isEqualTo("SomeTest.testX[param=1]");
    }

    /**
     * The tests of a suite share one grid, so their strips, tags and buttons stand in one column that the names and
     * the buttons alone size: a reason line or an open failure panel, which span both columns, must not move it. The
     * rows under a "N tests" line stand one step in; a phone wraps.
     */
    @Test
    void theRowsOfASuiteAlignTheirActionsInOneColumn() throws Exception {
        String css = Files.readString(PAGE);

        assertThat(rule(css, "ul.suite-tests")).contains("display: grid", "grid-template-columns: minmax(0, max-content) auto");
        assertThat(rule(css, "ul.suite-tests > li.test-row")).contains("grid-template-columns: subgrid");
        assertThat(rule(css, "ul.suite-tests > li.reason-head, ul.suite-tests > li.test-row > .reason, "
            + "ul.suite-tests > li.test-row > .details")).contains("grid-column: 1 / -1", "contain: inline-size");
        assertThat(rule(css, "ul.suite-tests > li.test-row.grouped")).contains("padding-left: 2rem");
        assertThat(rule(css, "ul.suite-tests > li.test-row:hover")).contains("background: var(--fill)");
        String narrow = css.substring(css.indexOf("@media (max-width: 768px)"));
        assertThat(rule(narrow, "ul.suite-tests")).contains("display: block");
        assertThat(rule(narrow, "ul.suite-tests > li.test-row")).contains("display: flex", "flex-wrap: wrap");
    }

    /** The panel's copy button goes back to its own label after the flash, not to the flaky board's "copy". */
    @Test
    void theCopyFailureButtonReadsCopyFailureAgainAfterTheFlash() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const copy = page.el('copy'), pre = page.el('pre');
            pre.textContent = 'java.lang.AssertionError: expected:<1> but was:<2>';
            copy.closest = () => ({ querySelector: () => pre });
            page.el('blockers').querySelectorAll = sel => sel === '.details .copy' ? [copy] : [];
            await page.load('?pr=13575');
            copy.onclick();
            await page.settle();
            const flashed = copy.textContent;
            await page.tick(1600);
            report({ flashed, idle: copy.textContent });
            """);

        assertThat(out.get("flashed").asText()).isEqualTo("copied");
        assertThat(out.get("idle").asText()).isEqualTo("copy failure");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
