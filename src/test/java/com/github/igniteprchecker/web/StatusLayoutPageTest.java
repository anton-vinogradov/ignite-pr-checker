package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The status page as the final self-review found it at 1280 and 375 px. The settings gave each name a column of
 * 120 px, and 19 of 37 names were longer: GITHUB_OAUTH_CLIENT_ID stood over its value. A failed save of users.json
 * showed "java.nio.file.AccessDeniedException: /opt/ignite-pr-checker/cache/users.json.tmp", which ran 56 px past its
 * card. The page drew its headings first and the cards when the status came, so every section jumped down: a layout
 * shift of 0.46 at 1280 px, where 0.1 is good.
 */
class StatusLayoutPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/status.html");

    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const status = { version: '1.42.0', signedIn: true, uptimeSeconds: 60, startedAt: page.now() - 60000,
            health: 'ok', logHealth: 'ok', healthProblems: [], teamcity: { lastHour, sinceStart: 0 },
            github: { lastHour, sinceStart: 0 }, jvm: { heapUsedMb: 120, heapMaxMb: 512, heapPeakMb: 28, threads: 40,
            cpus: 2 }, app: { openPrs: 3, pooledTokens: 1 }, log: { errors: 0, warnings: 0, clientMistakes: 0,
            recent: [] }, config: { GITHUB_OAUTH_CLIENT_ID: 'Ov23liAbCdEfGhIjKlMn' } };
        """;

    /** The names take one column as wide as the longest; on a phone a value goes under its name. */
    @Test
    void aLongSettingNameKeepsItsValueApart() throws Exception {
        String css = Files.readString(PAGE, UTF_8);
        String narrow = css.substring(css.indexOf("@media (max-width: 768px)"));

        assertThat(rule(css, "#configList")).contains("display: grid", "grid-template-columns: max-content 1fr");
        assertThat(rule(css, "#configList > *")).contains("grid-column: 1 / -1");
        assertThat(rule(css, "#configList .catrow")).contains("grid-template-columns: subgrid");
        assertThat(rule(narrow, "#configList")).contains("grid-template-columns: minmax(0, 1fr)");
    }

    @Test
    void aPathInACardBreaksInsideIt() throws Exception {
        assertThat(rule(Files.readString(PAGE, UTF_8), ".card .v small")).contains("overflow-wrap: anywhere");
    }

    /**
     * In Russian, "предупреждений: 3" in the log card is 124 px wide, and 132 px in Terminal. A card of 150 px had 119
     * px for it, so in a 500 px window, 700 px in Terminal, the 3 went to the next line. A card of 165 px has 134 px.
     */
    @Test
    void aCardHoldsACountWithItsNoun() throws Exception {
        assertThat(rule(Files.readString(PAGE, UTF_8), ".cards")).contains("repeat(auto-fill, minmax(165px, 1fr))");
    }

    /**
     * The sections stay hidden until the first status is drawn, and show as they are once it fails; the CSS shows them
     * after 2 s whatever happens.
     */
    @Test
    void theSectionsWaitForTheFirstStatus() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            let answer;
            page.route('/api/status', () => new Promise(done => { answer = done; }));
            await page.load('');
            const waiting = page.run('document.body').classList.contains('awaits-status');
            answer({ body: status });
            await page.settle();
            const drawn = page.run('document.body').classList.contains('awaits-status');
            report({ waiting, drawn, cards: page.el('systemCards').innerHTML.length > 0 });
            """);
        JsonNode failed = PageScript.run("status.html", STATUS + """
            page.route('/api/status', { status: 502, body: {} });
            await page.load('');
            report({ waiting: page.run('document.body').classList.contains('awaits-status'),
                error: page.el('err').textContent });
            """);

        assertThat(out.get("waiting").asBoolean()).isTrue();
        assertThat(out.get("drawn").asBoolean()).isFalse();
        assertThat(out.get("cards").asBoolean()).isTrue();
        assertThat(failed.get("waiting").asBoolean()).isFalse();
        assertThat(failed.get("error").asText()).isEqualTo("status unavailable: HTTP 502");
        assertThat(rule(Files.readString(PAGE, UTF_8), "body.awaits-status .wrap"))
            .contains("visibility: hidden", "animation: lang-wait 0s 2s forwards");
    }

    /**
     * Endpoint and category names, setting keys and user names took the colour of links, though a click on them does
     * nothing: "alice" on the Users tab read as a link to her profile.
     */
    @Test
    void namesAreTextNotLinks() throws Exception {
        assertThat(rule(Files.readString(PAGE, UTF_8), ".catname")).doesNotContain("--accent").doesNotContain("color");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
