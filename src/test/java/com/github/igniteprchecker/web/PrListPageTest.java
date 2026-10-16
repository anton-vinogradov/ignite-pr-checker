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
 * The PR list as the audit found it: a legend of two or three lines stood open in the sticky head on every screen;
 * "#13335 ? My?" put two question marks in a row, both red, and six red "?" among the first fifteen PRs made the
 * list look broken when one PR had blockers; the ticket key took a third of each row and left two or three words of
 * the title. The legend is a fold, open until a PR is picked and folded then; "?" is amber; the tag reads "my run"
 * with the same tooltip in the row and the legend; the key stands by the number and the title starts with its own
 * words.
 */
class PrListPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    @Test
    void theLegendFoldsWhenAPrOpensAndShowsTheCheckingBadgeOnlyThen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const legend = () => ({ open: page.el('prLegend').open,
                checking: !page.el('legendChecking').classList.contains('hidden') });
            await page.load('');
            const home = legend();
            page.run('openPr')(13575, true);
            await page.settle();
            const opened = legend();
            page.run('goHome')(true);
            await page.settle();
            report({ home, opened, back: legend() });
            """);

        assertThat(out.get("home").toString()).isEqualTo("{\"open\":true,\"checking\":false}");
        assertThat(out.get("opened").toString()).isEqualTo("{\"open\":false,\"checking\":true}");
        assertThat(out.get("back").toString()).isEqualTo("{\"open\":true,\"checking\":false}");
        assertThat(Files.readString(PAGE, UTF_8)).contains("<details id=\"prLegend\" class=\"pr-legend\" open>\n"
            + "                        <summary>What the badges mean</summary>")
            .contains("<li><span></span><span>no badge — no verdict yet</span></li>");
    }

    /** The filter, the head and the visa read the title from data-title and the link's title, which keep the key. */
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
                    triggeredBy: null, blockers: 0, proven: true }] });
            await page.load('');
            report({ html: page.el('prList').innerHTML });
            """);

        String html = out.get("html").asText();
        assertThat(html)
            .contains("<span class=\"pr-num\">#13575</span><span class=\"pr-key\">IGNITE-29049</span><span class=\"pr-badge "
                + "bad\" title=\"1 blocker\">1</span><span class=\"my-tag\" title=\"You started its latest RunAll (the PR "
                + "may be someone else's)\">my run</span>")
            .contains("<span class=\"pr-ttl\">Move MDC to JUnit</span>")
            .contains("data-title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("<span class=\"pr-num\">#13600</span><span class=\"pr-badge ok\"")
            .contains("<span class=\"pr-ttl\">Fix a typo in the README</span>")
            .contains("<span class=\"pr-num\">#13601</span><span class=\"pr-key\">IGNITE-29100</span><span class=\"my-tag\"")
            .contains("<span class=\"pr-ttl\">IGNITE-29100</span>")
            .contains("<span class=\"pr-num\">#13602</span><span class=\"pr-key\">IGNITE-29101</span>")
            .contains("<span class=\"pr-ttl\">IGNITE-29102: Two tickets</span>")
            .doesNotContain("My?", "blocker(s)", "<span class=\"pr-ttl\">, IGNITE");
    }

    /**
     * The fold follows a PR opening and closing, and nothing else: the legend you opened by hand while a PR was open
     * stays open when the list pane hides and shows again, and the one you folded on the start screen stays folded
     * when the phone turns and the 768 px breakpoint fires.
     */
    @Test
    void theFoldYouChoseStaysWhileThePaneCollapsesOrTheScreenTurns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            const legend = page.el('prLegend');
            page.run('openPr')(13575, true);
            await page.settle();
            const folded = legend.open;
            legend.open = true;
            page.run('setPaneCollapsed')(true);
            page.run('setPaneCollapsed')(false);
            const keptOpen = legend.open;
            page.run('goHome')(true);
            await page.settle();
            const unfolded = legend.open;
            legend.open = false;
            page.run('syncPane')();
            report({ folded, keptOpen, unfolded, keptFolded: legend.open });
            """);

        assertThat(out.get("folded").asBoolean()).isFalse();
        assertThat(out.get("keptOpen").asBoolean()).isTrue();
        assertThat(out.get("unfolded").asBoolean()).isTrue();
        assertThat(out.get("keptFolded").asBoolean()).isFalse();
    }

    /** The legend's badges carry the rows' own tooltips, word for word, and a count reads "1 blocker" or "33 blockers". */
    @Test
    void theLegendRepeatsTheRowsTooltips() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            const badges = page.run('STANDING_BADGES');
            report({ watch: badges.WATCH[2], unproven: badges.UNPROVEN[2], checking: badges.CHECKING[2],
                clean: page.run('CLEAN_BADGE_TITLE'), mine: page.run('MY_RUN_TITLE'),
                one: page.run('blockerBadge')(1, false, null), many: page.run('blockerBadge')(33, false, null),
                old: page.run('blockerBadge')(0, false, 'OLD_CODE'), proven: page.run('blockerBadge')(0, true, null) });
            """);

        String legend = Files.readString(PAGE, UTF_8);
        int start = legend.indexOf("<details id=\"prLegend\"");
        legend = legend.substring(start, legend.indexOf("</details>", start));
        assertThat(legend).contains("<span class=\"pr-badge watch\" title=\"" + out.get("watch").asText() + "\">!</span>")
            .contains("<span class=\"pr-badge unproven\" title=\"" + out.get("unproven").asText() + "\">?</span>")
            .contains("<span class=\"pr-badge checking\" title=\"" + out.get("checking").asText() + "\">…</span>")
            .contains("<span class=\"pr-badge ok\" title=\"" + out.get("clean").asText() + "\">✓</span>")
            .contains("<span class=\"pr-badge bad\" title=\"2 blockers\">2</span>")
            .contains("<span class=\"my-tag\" title=\"" + out.get("mine").asText() + "\">my run</span>");
        assertThat(out.get("unproven").asText()).startsWith("No blockers found, but the run can't prove the PR clean: ");
        assertThat(out.get("one").asText()).isEqualTo("<span class=\"pr-badge bad\" title=\"1 blocker\">1</span>");
        assertThat(out.get("many").asText()).isEqualTo("<span class=\"pr-badge bad\" title=\"33 blockers\">33</span>");
        assertThat(out.get("old").asText())
            .isEqualTo("<span class=\"pr-badge unproven\" title=\"No blockers found, but commits were pushed after this "
                + "run.\">?</span>");
        assertThat(out.get("proven").asText())
            .isEqualTo("<span class=\"pr-badge ok\" title=\"A clean run of the PR&#39;s current code.\">✓</span>");
    }

    /** "?" is amber, as every "wait or rerun" mark; red is only for a count. The tag is a plate, as the tags next to a test. */
    @Test
    void theUnprovenBadgeIsAmberAndTheTagAPlate() throws IOException {
        String css = Files.readString(PAGE, UTF_8);

        assertThat(rule(css, ".pr-badge.unproven")).contains("color: var(--warn)", "border: 1px solid var(--warn)");
        assertThat(rule(css, ".pr-badge.bad")).contains("background: var(--bad)", "color: var(--on-bad)");
        assertThat(rule(css, ".my-tag")).contains("background: var(--fill)", "color: var(--muted)", "cursor: help")
            .doesNotContain("border:");
        assertThat(rule(css, ".pr-key")).contains("color: var(--muted)", "font-family: ui-monospace, monospace");
        assertThat(rule(css, ".pr-legend li")).contains("grid-template-columns: max-content 1fr");
        assertThat(css).doesNotContain(".my-tag::before");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
