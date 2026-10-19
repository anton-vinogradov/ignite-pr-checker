package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The pages had no responsive layout at all: the top bar never wrapped, the freshness line was
 * nowrap, so at 1024 px the ↻ button and "includes an unfinished run" ran off the edge, and on a phone
 * the 320 px PR list left the result a sliver unless it was collapsed by hand.
 */
class NarrowScreenTest {
    @Test
    void listFoldsAwayWheneverAPrOpensOnAPhone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.narrow = true;
            await page.load('?pr=13575');
            const split = page.el('analyzeView');
            const opened = split.classList.contains('collapsed');
            page.el('paneExpand').listeners.click[0]();
            const expanded = split.classList.contains('collapsed');
            page.el('prFilter').value = '13461';
            page.el('prFilter').listeners.keydown[0]({ key: 'Enter' });
            await page.settle();
            report({ opened, expanded, picked: split.classList.contains('collapsed') });
            """);

        assertThat(out.get("opened").asBoolean()).isTrue();
        assertThat(out.get("expanded").asBoolean()).isFalse();
        assertThat(out.get("picked").asBoolean()).isTrue();
    }

    @Test
    void startPageGivesTheListTheWholeScreen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.narrow = true;
            await page.load('');
            report({ home: page.el('analyzeView').classList.contains('home'),
                collapsed: page.el('analyzeView').classList.contains('collapsed') });
            """);

        assertThat(out.get("home").asBoolean()).isTrue();
        assertThat(out.get("collapsed").asBoolean()).isFalse();
    }

    @Test
    void wideScreenKeepsTheReadersChoice() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            report({ collapsed: page.el('analyzeView').classList.contains('collapsed') });
            """);

        assertThat(out.get("collapsed").asBoolean()).isFalse();
    }

    @Test
    void freshnessLineWrapsInsteadOfRunningOffTheEdge() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(rule(css, "#freshness")).doesNotContain("nowrap");
        assertThat(css).contains("@media (max-width: 768px)");
    }

    @Test
    void runsRowWrapsInsteadOfSlidingUnderCancelAll() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(rule(css, "#runsRow")).contains("flex-wrap: wrap");
        assertThat(rule(css, ".runs .run-item")).doesNotContain("nowrap").contains("flex-wrap: wrap");
    }

    /**
     * The Russian section pair is wider than the English one. On a 360 px phone it pushed the cards sideways, and
     * Cancel my runs covered a run's tag. On a narrow Russian page the section pair wraps, and the runs take the whole
     * row.
     */
    @Test
    void aRussianPageWrapsItsLongerWordsOnAPhone() throws Exception {
        String narrow = narrow("index.html");

        assertThat(rule(narrow, "html[lang=\"ru\"] .sect-act")).contains("flex-wrap: wrap");
        assertThat(rule(narrow, "html[lang=\"ru\"] #runsRow .runs")).contains("flex-basis: 100%");
    }

    /**
     * At 375 px in English, the Rerun pair of a broken group stood beside "non-zero exit code" and left the name 89 px,
     * 62 px in Terminal: it broke as "non-zero exi | t code", and a live chip of 160 px ran under the Rerun button. In
     * Russian, the pair wrapped under the name, and each button stood at the left like the grey reason line under it.
     * On a phone the pair goes under a name it does not fit beside, at the right, as one group, in both languages; a
     * name breaks between its words.
     */
    @Test
    void aSuitesRerunPairGoesUnderItsNameAtTheRight() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"));
        String narrow = narrow("index.html");

        assertThat(rule(narrow, ".suite-head, .suite-runs")).contains("flex-wrap: wrap");
        assertThat(rule(narrow, ".suite-runs")).contains("flex-shrink: 1", "margin-left: auto",
            "justify-content: flex-end");
        assertThat(rule(css, ".suite-name")).contains("overflow-wrap: anywhere").doesNotContain("break-all");
    }

    /**
     * In Russian, "Перезапустить в начало очереди" made the suite row's pair 295 px wide, more than the 284 px a 375 px
     * phone has for it. The second button says "В начало очереди", and its tooltip still says what it does.
     */
    @Test
    void theRussianSuitePairIsShort() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            await page.load('?pr=13575');
            const pair = page.run('suiteRowActs')('IgniteTests24Java8_Cache', null);
            const top = /<button[^>]*data-top="true" title="([^"]*)">([^<]*)<\\/button>/.exec(pair);
            report({ title: top[1], text: top[2] });
            """);

        assertThat(out.get("text").asText()).isEqualTo("В начало очереди");
        assertThat(out.get("title").asText()).startsWith("Ставит эту сьюту в очередь ci2, в её начало.");
    }

    /**
     * In Terminal at 375 px, the English "135 suites ran, 12 reused from earlier runs" did not wrap: the result pane
     * scrolled sideways by 17 px. The Russian line already wrapped there; the English one now wraps too.
     */
    @Test
    void theFreshnessLineWrapsOnAPhoneInEveryLanguage() throws Exception {
        String narrow = narrow("index.html");

        assertThat(rule(narrow, "#freshness .hint")).contains("white-space: normal");
        assertThat(narrow).doesNotContain("html[lang=\"ru\"] #freshness");
    }

    /**
     * At 375 px, the class line of NewTableFunctionIntegrationTest ended "самый медленный тест 1", and "мин 15 с" began
     * the next line; in Terminal "slowest test 1 m" and "15 s". The time stays on one line.
     */
    @Test
    void theSlowestTestsTimeStaysOnOneLine() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const run = { testId: '1', name: 'org.apache.ignite.NewTableFunctionIntegrationTest.testSlow',
                suite: 'IgniteTests24Java8_Calcite', suiteName: 'Calcite', suiteBuildId: 9002, occurrenceId: 'o',
                status: 'SUCCESS', durationMs: 75000, masterRuns: 0, muted: false };
            page.run('renderPrTests')({ buildId: 9001, note: null, runsKnown: true,
                classes: [{ name: 'org.apache.ignite.NewTableFunctionIntegrationTest', path: 'T.java', added: true,
                    notInSuite: false, atRun: null, runs: [run] }] }, page.run('lastResult'));
            report({ html: page.el('prTests').innerHTML });
            """);

        assertThat(out.get("html").asText()).contains(" · slowest test <span class=\"time-of\">1 m 15 s</span>");
        assertThat(rule(Files.readString(Path.of("src/main/resources/static/index.html")), ".time-of"))
            .contains("white-space: nowrap");
    }

    /**
     * In Russian at 375 px, "Группировать: [По сьютам][По причинам (≥12)]" in the Blockers heading did not wrap and
     * crossed the card's border, in Terminal the screen's edge too. On a phone the tabs go under "Group by:".
     */
    @Test
    void theGroupByTabsWrapOnAPhone() throws Exception {
        assertThat(rule(narrow("index.html"), ".group-by")).contains("flex-wrap: wrap");
    }

    /**
     * "No blockers found — not proven clean" takes two lines on a phone, and its "?" stood alone on a third, with 240
     * px free after the last word. The text and the "?" are one span in the heading's row, so the "?" follows the word.
     */
    @Test
    void theVerdictsHelpMarkFollowsItsLastWord() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(html).containsPattern("<h2 id=\"verdictHead\"><span class=\"verdict-title\">"
            + "<span id=\"verdictText\"></span>\\s*<a class=\"help\"[^>]*>\\?</a></span>");
    }

    /**
     * On the Users tab of the status page at 375 px, "last seen 2d ago · logged in 21 times · first seen 40d ago" did
     * not wrap in English and ran 140 px past the screen; the Russian line already wrapped. Among the settings, the
     * Russian "2 имени, их видят только операторы" ran 27 px past it, and in Terminal APP_PUBLIC_URL ran 64 px past it.
     * On a phone every value wraps, and an address or a path breaks anywhere.
     */
    @Test
    void aStatusValueWrapsOnAPhoneInEveryLanguage() throws Exception {
        String narrow = narrow("status.html");

        assertThat(rule(narrow, ".catval")).contains("white-space: normal", "overflow-wrap: anywhere");
        assertThat(narrow).doesNotContain("html[lang=\"ru\"] #usersView");
    }

    /**
     * On the flaky board, the Russian chip of a queued re-run did not wrap: "#13655 · в очереди 1 ч 05 мин · старт
     * через ~1 ч 18 мин" ran about 50 px past a 360 px phone. On a narrow Russian page the chip wraps; the English one
     * fits. The chip sits in the test's name, which breaks anywhere, and broke as "осталось ~1 ч 18 м | ин": it wraps
     * between its words.
     */
    @Test
    void aRussianRunChipWrapsOnAPhone() throws Exception {
        assertThat(rule(narrow("flaky.html"), "html[lang=\"ru\"] .live")).contains("white-space: normal",
            "word-break: normal");
    }

    /** One top bar for the three pages, in the stylesheet they share. */
    @Test
    void topBarWraps() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/common.css"));

        assertThat(rule(css, ".topbar")).contains("flex-wrap: wrap");
        assertThat(rule(css, ".topbar-right")).contains("flex-wrap: wrap");
    }

    /** The rules of a page's narrow-screen block. */
    private static String narrow(String page) throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static", page));
        int start = css.indexOf("@media (max-width: 768px)");
        assertThat(start).as("the narrow-screen block of " + page).isNotNegative();
        return css.substring(start);
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
