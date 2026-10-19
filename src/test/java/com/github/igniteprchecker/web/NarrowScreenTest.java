package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

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
     * The Russian Rerun pair is nearly three times as wide as the English one. On a 360 px phone it pushed the cards
     * sideways and squeezed each suite's name into a column of letters, and Cancel my runs covered a run's tag. On a
     * narrow Russian page the pair wraps under the suite's name, and the runs take the whole row.
     */
    @Test
    void aRussianPageWrapsItsLongerWordsOnAPhone() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"));
        String narrow = css.substring(css.indexOf("@media (max-width: 768px)"));

        assertThat(rule(narrow, "html[lang=\"ru\"] .suite-head, html[lang=\"ru\"] .suite-runs, html[lang=\"ru\"] "
            + ".sect-act")).contains("flex-wrap: wrap");
        assertThat(rule(narrow, "html[lang=\"ru\"] .suite-runs")).contains("flex-shrink: 1");
        assertThat(rule(narrow, "html[lang=\"ru\"] #freshness .hint")).contains("white-space: normal");
        assertThat(rule(narrow, "html[lang=\"ru\"] #runsRow .runs")).contains("flex-basis: 100%");
    }

    /** One top bar for the three pages, in the stylesheet they share. */
    @Test
    void topBarWraps() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/common.css"));

        assertThat(rule(css, ".topbar")).contains("flex-wrap: wrap");
        assertThat(rule(css, ".topbar-right")).contains("flex-wrap: wrap");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
