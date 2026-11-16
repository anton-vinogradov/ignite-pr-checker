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

/** The PR list explains each badge and distinguishes run ownership from PR authorship. */
class PrListPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /**
     * The filter, the head and the visa read the title from data-title and the link's title, which keep the key. The
     * title of PR 13640 starts with a space, and its key stood on the second line.
     */
    @Test
    void theTicketKeyStandsByTheNumberAndTheTitleStartsWithItsOwnWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/prs', { body: [
                { number: 13575, title: 'IGNITE-29049: Move MDC to JUnit', url: 'https://github.com/apache/ignite/pull/13575',
                    triggeredBy: 'alice', blockers: 1, proven: true },
                { number: 13600, title: 'Fix a typo in the README', url: 'https://github.com/apache/ignite/pull/13600',
                    triggeredBy: null, blockers: 0, proven: true, standing: 'CLEAN' },
                { number: 13601, title: 'IGNITE-29100', url: 'https://github.com/apache/ignite/pull/13601',
                    triggeredBy: null, blockers: null },
                { number: 13602, title: 'IGNITE-29101, IGNITE-29102: Two tickets', url: 'https://github.com/apache/ignite/pull/13602',
                    triggeredBy: null, blockers: 0, proven: true },
                { number: 13640, title: ' IGNITE-29079 Implemented relaying', url: 'https://github.com/apache/ignite/pull/13640',
                    triggeredBy: null, blockers: null }] });
            await page.load('');
            report({ html: page.el('prList').innerHTML });
            """);

        String html = out.get("html").asText();
        assertThat(html)
            .contains("<span class=\"pr-num\">#13575</span> <span class=\"pr-key\">IGNITE-29049</span>"
                + "<span class=\"pr-badge bad\" title=\"1 blocker\" role=\"img\" aria-label=\"1 blocker\">1</span>"
                + "<span class=\"my-tag\" "
                + "title=\"You started its latest RunAll. The PR may be someone else's.\">my run</span>")
            .contains("<span class=\"pr-ttl\">Move MDC to JUnit</span>")
            .contains("data-title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("<span class=\"pr-num\">#13600</span><span class=\"pr-badge ok\"")
            .contains("<span class=\"pr-ttl\">Fix a typo in the README</span>")
            .contains("<span class=\"pr-num\">#13601</span> <span class=\"pr-key\">IGNITE-29100</span>"
                + "<span class=\"my-tag\"")
            .contains("<span class=\"pr-ttl\">IGNITE-29100</span>")
            .contains("<span class=\"pr-num\">#13602</span> <span class=\"pr-key\">IGNITE-29101</span>")
            .contains("<span class=\"pr-ttl\">IGNITE-29102: Two tickets</span>")
            .contains("<span class=\"pr-num\">#13640</span> <span class=\"pr-key\">IGNITE-29079</span>")
            .contains("<span class=\"pr-ttl\">Implemented relaying</span>")
            .contains("data-title=\" IGNITE-29079 Implemented relaying\"")
            .doesNotContain("My?", "blocker(s)", "<span class=\"pr-ttl\">, IGNITE");
    }

    /** Given each verdict standing, when a badge is drawn, its tooltip explains that standing and the blocker count. */
    @Test
    void theRowsExplainEveryBadge() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            const badges = page.run('STANDING_BADGES');
            report({ watch: badges.WATCH[2], unproven: badges.UNPROVEN[2], checking: badges.CHECKING[2],
                clean: page.run('CLEAN_BADGE_TITLE'), mine: page.run('MY_RUN_TITLE'),
                one: page.run('blockerBadge')(1, false, null), many: page.run('blockerBadge')(33, false, null),
                old: page.run('blockerBadge')(0, false, 'OLD_CODE'), proven: page.run('blockerBadge')(0, true, null) });
            """);

        assertThat(out.get("watch").asText()).isEqualTo("No blockers yet, but some tests started failing on the code "
            + "the run tested. A re-run shows whether they are blockers.");
        assertThat(out.get("unproven").asText()).isEqualTo("No blockers found, but the run can't prove the PR clean: "
            + "it was interrupted, suites broke or ran fewer tests than on master, failed tests could not be "
            + "checked, suites re-ran on other commits, or a newer run is still going.");
        assertThat(out.get("one").asText()).startsWith("<span class=\"pr-badge bad\" title=\"1 blocker\"")
            .endsWith(">1</span>");
        assertThat(out.get("many").asText()).startsWith("<span class=\"pr-badge bad\" title=\"33 blockers\"")
            .endsWith(">33</span>");
        assertThat(out.get("old").asText())
            .startsWith("<span class=\"pr-badge unproven\" title=\"No blockers found, but commits were pushed after this "
                + "run.\"").endsWith(">?</span>");
        assertThat(out.get("proven").asText())
            .startsWith("<span class=\"pr-badge ok\" title=\"A clean run of the PR&#39;s current code.\"")
            .endsWith(">✓</span>");
    }

    /** Given the list styles, unproven badges stay amber and ownership tags remain readable plates. */
    @Test
    void theUnprovenBadgeIsAmberAndTheTagAPlate() throws IOException {
        String css = Files.readString(PAGE, UTF_8);

        assertThat(rule(css, ".pr-badge.unproven")).contains("color: var(--warn)", "border: 1px solid var(--warn)");
        assertThat(rule(css, ".pr-badge.bad")).contains("background: var(--bad)", "color: var(--on-bad)");
        assertThat(rule(css, ".my-tag")).contains("background: var(--fill)", "color: var(--muted)", "cursor: help")
            .doesNotContain("border:");
        assertThat(rule(css, ".pr-key")).contains("color: var(--muted)", "font-family: ui-monospace, monospace");
        assertThat(css).doesNotContain(".my-tag::before");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
