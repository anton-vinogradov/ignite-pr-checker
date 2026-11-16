package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Help buttons have short accessible names; PR rows separate the number, ticket key and spoken badge meaning. */
class ScreenReaderNamesPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    private static final Pattern HELP = Pattern.compile("<button(?: id=\"[^\"]+\")? class=\"help meaning-help\" "
        + "type=\"button\" title=\"[^\"]+\" aria-label=\"([^\"]+)\">\\?</button>");

    @Test
    void everyQuestionMarkHasAShortNameAndKeepsItsDefinitionInItsTitle() throws IOException {
        Matcher help = HELP.matcher(Files.readString(PAGE, UTF_8));
        int links = 0;
        while (help.find()) {
            links++;
            assertThat(help.group(1)).isIn("What this means", "What the run chips and commit tags mean");
            assertThat(help.group())
                .containsPattern("title=\"[^\"]{20,}\"");
        }
        assertThat(links).isEqualTo(12);
    }

    @Test
    void aListRowReadsTheNumberTheKeyAndTheBadgeInWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/prs', { body: [
                { number: 13575, title: 'IGNITE-29049 Move MDC to JUnit', url: 'u', triggeredBy: 'alice', blockers: 5,
                    proven: true },
                { number: 13600, title: 'Fix a typo in the README', url: 'u', blockers: 1, proven: true },
                { number: 13583, title: 'IGNITE-29001 a', url: 'u', blockers: 0, proven: false, standing: 'WATCH' },
                { number: 13636, title: 'IGNITE-29002 b', url: 'u', blockers: 0, proven: false, standing: 'OLD_CODE' },
                { number: 13461, title: 'IGNITE-29003 c', url: 'u', blockers: 0, proven: true, standing: 'CLEAN' }] });
            await page.load('');
            const badge = page.run('blockerBadge');
            report({ list: page.el('prList').innerHTML, checking: badge(0, false, 'CHECKING'),
                unknown: badge(0, false, 'UNKNOWN_CODE'), unproven: badge(0, false, null) });
            """);

        String list = out.get("list").asText();
        assertThat(list).contains("<span class=\"pr-num\">#13575</span> <span class=\"pr-key\">IGNITE-29049</span>"
                + "<span class=\"pr-badge bad\" title=\"5 blockers\" role=\"img\" aria-label=\"5 blockers\">5</span>")
            .contains("<span class=\"pr-num\">#13600</span><span class=\"pr-badge bad\" title=\"1 blocker\" role=\"img\" "
                + "aria-label=\"1 blocker\">1</span>")
            .contains("role=\"img\" aria-label=\"started failing\">!</span>")
            .contains("role=\"img\" aria-label=\"not proven clean\">?</span>")
            .contains("role=\"img\" aria-label=\"clean\">✓</span>");
        assertThat(out.get("checking").asText()).endsWith("role=\"img\" aria-label=\"checking\">…</span>");
        assertThat(out.get("unknown").asText()).endsWith("role=\"img\" aria-label=\"not proven clean\">?</span>");
        assertThat(out.get("unproven").asText()).endsWith("role=\"img\" aria-label=\"not proven clean\">?</span>");

        String page = Files.readString(PAGE, UTF_8);
        Matcher key = Pattern.compile("(?m)^\\s*\\.pr-key \\{([^}]*)}").matcher(page);
        assertThat(key.find()).isTrue();
        assertThat(key.group(1)).as("the space parts the number and the key").doesNotContain("margin-left");
    }
}
