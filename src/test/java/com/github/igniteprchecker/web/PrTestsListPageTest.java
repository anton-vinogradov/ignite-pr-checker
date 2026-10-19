package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The owner, on PR 13335: "This PR's tests" said that 20 of 67 tests were looked up, and showed only the failed and
 * the slow ones, with no way to see the rest; nothing led to TeamCity. Each class now opens into all its tests of the
 * RunAll, failed first, then slow, then by name, and every test and suite name opens its run on TeamCity.
 */
class PrTestsListPageTest {
    /** PR 13335's changed GridCommandHandlerSslTest: one failure, one slow test and three quick ones. */
    private static final String SSL = """
        function prRun(id, test, status, durationMs, over = {}) {
            return Object.assign({ testId: id, name: 'IgniteControlUtilityTestSuite: org.apache.ignite.util.' + test,
                suite: 'IgniteTests24Java8_ControlUtility', suiteBuildId: 9002, suiteName: 'Control Utility',
                occurrenceId: 'build:(id:9002),id:' + id, status, durationMs, masterRuns: null, muted: false }, over);
        }
        const ssl = { name: 'org.apache.ignite.util.GridCommandHandlerSslTest', path: 'GridCommandHandlerSslTest.java',
            added: false, notInSuite: false, atRun: null, runs: [
                prRun('11', 'GridCommandHandlerSslTest.testA', 'SUCCESS', 400),
                prRun('12', 'GridCommandHandlerSslTest.testReload', 'FAILURE', 3000, { masterRuns: 40 }),
                prRun('13', 'GridCommandHandlerSslTest.testC', 'SUCCESS', 70000),
                prRun('14', 'GridCommandHandlerSslTest.testB', 'UNKNOWN', 0),
                prRun('15', 'GridCommandHandlerSslTest.testD', 'SUCCESS', 125000)] };
        page.route('/api/pr-tests', { body: { buildId: 9001, note: null, runsKnown: true, classes: [ssl] } });
        // The button the card drew for the class, as the browser would hand it to its click handler.
        function toggleOf(html) {
            const btn = page.el('toggle');
            const attr = name => new RegExp(name + '="([^"]*)"').exec(html)[1];
            btn.dataset.cls = attr('data-cls');
            btn.setAttribute('aria-controls', attr('aria-controls'));
            btn.setAttribute('aria-expanded', attr('aria-expanded'));
            return btn;
        }
        const names = html => [...html.matchAll(/test's run in TeamCity\\.">([^<]+)</g)].map(m => m[1]);
        """;

    @Test
    void aClassOpensIntoAllItsTestsFailedThenSlowThenByName() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            await page.load('?pr=13575');
            const card = page.el('prTests').innerHTML;
            const btn = toggleOf(card);
            const list = page.el(btn.getAttribute('aria-controls'));
            const folded = names(list.innerHTML.length ? list.innerHTML : card);
            page.run('togglePrTestClass')(btn);
            const opened = { names: names(list.innerHTML), expanded: btn.getAttribute('aria-expanded'),
                label: btn.textContent, title: btn.title, text: list.textContent };
            page.run('togglePrTestClass')(btn);
            const closed = { names: names(list.innerHTML), expanded: btn.getAttribute('aria-expanded'),
                label: btn.textContent, title: btn.title };
            report({ card, folded, opened, closed });
            """);

        assertThat(out.get("card").asText()).contains("<button class=\"why prt-all\" type=\"button\" "
            + "data-cls=\"org.apache.ignite.util.GridCommandHandlerSslTest\" aria-controls=\"prtTests0\" "
            + "aria-expanded=\"false\" title=\"Shows every test of this class in this RunAll, with its status and "
            + "time.\">all 5 tests ▸</button>", "<ul id=\"prtTests0\" class=\"suite-tests\">");
        assertThat(out.get("folded")).extracting(JsonNode::asText).containsExactly(
            "GridCommandHandlerSslTest.testReload", "GridCommandHandlerSslTest.testD",
            "GridCommandHandlerSslTest.testC");
        assertThat(out.get("opened").get("names")).extracting(JsonNode::asText).containsExactly(
            "GridCommandHandlerSslTest.testReload", "GridCommandHandlerSslTest.testD",
            "GridCommandHandlerSslTest.testC", "GridCommandHandlerSslTest.testA", "GridCommandHandlerSslTest.testB");
        assertThat(out.get("opened").get("expanded").asText()).isEqualTo("true");
        assertThat(out.get("opened").get("label").asText()).isEqualTo("all 5 tests ▾");
        assertThat(out.get("opened").get("title").asText())
            .isEqualTo("Shows only the tests that failed or ran longer than 60 s.");
        assertThat(out.get("opened").get("text").asText()).contains("GridCommandHandlerSslTest.testApassed0.4 s",
            "GridCommandHandlerSslTest.testBignored", "GridCommandHandlerSslTest.testDpassed2 m 5 s");
        assertThat(out.get("closed").get("names")).isEqualTo(out.get("folded"));
        assertThat(out.get("closed").get("expanded").asText()).isEqualTo("false");
        assertThat(out.get("closed").get("label").asText()).isEqualTo("all 5 tests ▸");
    }

    /** A redraw of the same run, by a new verdict, keeps the class the reader opened open. */
    @Test
    void anOpenedClassStaysOpenOnARedraw() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            await page.load('?pr=13575');
            page.run('togglePrTestClass')(toggleOf(page.el('prTests').innerHTML));
            page.route('/api/analyze', { body: verdict({ suitesRan: 11 }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            const card = page.el('prTests').innerHTML;
            report({ card, names: names(card) });
            """);

        assertThat(out.get("card").asText()).contains("aria-expanded=\"true\"", "all 5 tests ▾");
        assertThat(out.get("names")).hasSize(5);
    }

    /** A class whose every test failed or was slow shows them all already: it has no button. */
    @Test
    void aClassWithEveryTestShownHasNoButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            ssl.runs = ssl.runs.filter(r => r.status === 'FAILURE' || r.durationMs > 60000);
            await page.load('?pr=13575');
            report({ card: page.el('prTests').innerHTML });
            """);

        assertThat(out.get("card").asText()).doesNotContain("prt-all", "all 3 tests");
    }

    /**
     * A test's name opens its run in the suite's build, at the test on the Tests tab, as a row's TC does in the other
     * cards; the suite's name opens the suite's run. Both open as the page's other links do, and TeamCity's names
     * go in escaped.
     */
    @Test
    void namesLeadToTheirRunsOnTeamCity() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            ssl.runs.forEach(r => { r.suiteName = 'Control <Utility> "1"'; });
            await page.load('?pr=13575');
            report({ card: page.el('prTests').innerHTML });
            """);

        String card = out.get("card").asText();
        assertThat(card).contains("<a href=\"https://ci2.example/buildConfiguration/"
            + "IgniteTests24Java8_ControlUtility/9002?buildTab=tests&amp;expandedTest=build%3A%28id%3A9002%29%2Cid%3A12"
            + "#testNameId12\" target=\"_self\" rel=\"noopener\" title=\"Opens this test's run in TeamCity.\">"
            + "GridCommandHandlerSslTest.testReload</a>");
        assertThat(card).contains(" in <a href=\"https://ci2.example/buildConfiguration/"
            + "IgniteTests24Java8_ControlUtility/9002\" target=\"_self\" rel=\"noopener\" "
            + "title=\"Opens this suite's run in TeamCity.\">Control &lt;Utility&gt; &quot;1&quot;</a>"
            + " · slowest test <span class=\"time-of\">2 m 5 s</span>");
        assertThat(card).doesNotContain("<Utility>");
    }

    /**
     * On a phone the rows wrap like the other cards' rows, and long names break instead of pushing the page wider: a
     * test's name anywhere, a class's or a suite's name between words where it can.
     */
    @Test
    void narrowScreensWrapTheRows() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"));
        String narrow = css.substring(css.indexOf("@media (max-width: 768px)"));

        assertThat(rule(css, ".tname")).contains("word-break: break-all", "flex-wrap: wrap");
        assertThat(rule(css, ".suite-name")).contains("min-width: 0", "overflow-wrap: anywhere");
        assertThat(rule(narrow, "ul.suite-tests > li.test-row")).contains("flex-wrap: wrap");
        assertThat(rule(css, ".test-time")).doesNotContain("width");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();

        return m.group(1);
    }
}
