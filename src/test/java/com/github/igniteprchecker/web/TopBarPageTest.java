package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each page had its own top bar: the PR page offered Flaky tests and Status as pills, the flaky board Status and
 * "← App", the status page "← App" alone, each with a subtitle of its own; navigation, Log out, the theme selector
 * and the update buttons all looked like the same pill. Now every page carries the same menu, PRs · Flaky tests ·
 * Status, with the current page marked; Log out stands as a link beside the name; and only the update buttons are
 * accented.
 */
class TopBarPageTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern MENU = Pattern.compile("<nav class=\"menu\" aria-label=\"Pages\">(.*?)</nav>",
        Pattern.DOTALL);

    private static final Pattern MENU_LINK = Pattern.compile("<a ([^>]*)>([^<]*)(?:<span[^>]*></span>)?</a>");

    private static final Pattern HREF = Pattern.compile("\\bhref=\"([^\"]+)\"");

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void everyPageHasTheSameMenuWithItsOwnItemMarked(String page) throws IOException {
        String html = read(page);
        Matcher menu = MENU.matcher(html);
        assertThat(menu.find()).as("the menu of " + page).isTrue();

        Matcher link = MENU_LINK.matcher(menu.group(1));
        List<String> items = new ArrayList<>();
        String current = null;
        while (link.find()) {
            Matcher href = HREF.matcher(link.group(1));
            assertThat(href.find()).as(link.group()).isTrue();
            items.add(href.group(1) + " " + link.group(2));
            assertThat(link.group(1)).as(link.group()).contains("title=\"");
            if (link.group(1).contains("aria-current=\"page\"")) {
                assertThat(current).as("one current item").isNull();
                current = href.group(1);
            }
        }
        assertThat(items).containsExactly("/ PRs", "/flaky.html Flaky tests", "/status.html Status");
        assertThat(current).isEqualTo(page.equals("index.html") ? "/" : "/" + page);
        assertThat(html).doesNotContain("← App", "status-link", "class=\"pill");
        assertThat(html).contains("<button id=\"reloadPill\" class=\"update-btn hidden\"", "class=\"star-btn\"",
            "<select id=\"theme-toggle\" class=\"theme-sel\"");
        assertThat(html.indexOf("<link rel=\"stylesheet\" href=\"/common.css\">")).isPositive()
            .isLessThan(html.indexOf("<style>"));
    }

    @Test
    void theSloganStaysOnTheStartPageAndTheSubtitlesGo() throws IOException {
        assertThat(read("index.html")).contains("Ignite PR Checker <small>which tests your PR actually broke</small>");
        assertThat(read("flaky.html")).contains("Ignite PR Checker</a>").doesNotContain("fix-master queue</small>");
        assertThat(read("status.html")).contains("Ignite PR Checker</a>").doesNotContain("service status</small>");
    }

    @Test
    void theHealthDotStandsByTheStatusItem() throws IOException {
        assertThat(read("status.html")).contains("aria-current=\"page\" title=\"Service status and metrics\">Status"
            + "<span id=\"healthDot\" class=\"hdot\" title=\"health\"></span></a>");
        assertThat(read("index.html")).doesNotContain("healthDot");
        assertThat(read("flaky.html")).doesNotContain("healthDot");
    }

    @Test
    void logOutIsALinkBesideTheNameAndTheGearIsLast() throws IOException {
        String bar = read("index.html");
        bar = bar.substring(bar.indexOf("<div class=\"topbar-right\">"), bar.indexOf("</header>"));

        assertThat(bar.replaceAll("\\s+", " ")).contains("<span id=\"session\" class=\"session muted hidden\">"
            + "<span id=\"username\"></span> · <button id=\"logout\" class=\"linklike\" type=\"button\" "
            + "title=\"End your login here and in every other browser\">Log out</button></span>");
        assertThat(bar.substring(bar.lastIndexOf("<button"))).startsWith("<button id=\"settingsBtn\"");
        assertThat(read("flaky.html")).doesNotContain("id=\"session\"", "settingsBtn");
        assertThat(read("status.html")).doesNotContain("id=\"session\"", "settingsBtn");
    }

    /** Signed in, PRs in the menu goes back to the start page as the brand does: no reload. */
    @Test
    void prsLeadsBackToTheStartPageWithoutAReload() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            let prevented = false;
            page.el('navPrs').listeners.click[0]({ button: 0, preventDefault() { prevented = true; } });
            await page.settle();
            report({ selected: page.run('selectedPr'), pushed: page.pushed, prevented,
                title: page.el('prTitle').textContent, username: page.el('username').textContent });
            """);

        assertThat(out.get("selected").isNull()).isTrue();
        assertThat(out.get("pushed").toString()).isEqualTo("[\"/\"]");
        assertThat(out.get("prevented").asBoolean()).isTrue();
        assertThat(out.get("title").asText()).startsWith("Pick a PR");
        assertThat(out.get("username").asText()).isEqualTo("alice");
    }

    /** The three kinds of element, as the shared stylesheet draws them in the base rules of every theme. */
    @Test
    void aLinkIsTextAButtonHasABorderAndATagHasNeither() throws IOException {
        String css = Files.readString(STATIC.resolve("common.css"), UTF_8);

        assertThat(rule(css, "a.ext")).contains("color: var(--accent)").doesNotContain("border", "background");
        assertThat(rule(css, ".act, .why, .suite-rerun")).contains("border: 1px solid var(--line)",
            "background: var(--btn-bg)", "color: var(--fg)");
        assertThat(rule(css, ".tag, .tag-doubt, .tag-flaky")).contains("background: var(--fill)")
            .doesNotContain("border:");
        assertThat(rule(css, ".tabs .act.on, .tabs .act.on:hover")).contains("background: var(--accent)",
            "color: var(--on-accent)");
        assertThat(rule(css, ".update-btn")).contains("background: var(--accent)", "color: var(--on-accent)");
        for (String theme : List.of(":root", "html[data-theme=\"dark\"]", "html[data-theme=\"jetbrains\"]",
            "html[data-theme=\"terminal\"]"))
            assertThat(rule(css, theme)).as(theme).contains("--on-accent:", "--on-bad:", "--ok:", "--warn:",
                "--row-hover:");
    }

    private static String read(String page) throws IOException {
        return Files.readString(STATIC.resolve(page), UTF_8);
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
