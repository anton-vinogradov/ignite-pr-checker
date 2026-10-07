package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void topBarWraps(String page) throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static", page));

        assertThat(rule(css, ".topbar")).contains("flex-wrap: wrap");
        assertThat(rule(css, ".topbar-right")).contains("flex-wrap: wrap");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
