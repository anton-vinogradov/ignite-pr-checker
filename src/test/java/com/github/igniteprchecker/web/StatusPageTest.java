package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The status page put its "service started" line at the browser's clock minus a whole-second uptime, so the error
 * about a snapshot that failed to load at startup landed under it, among the previous run's problems. The health
 * dot's tooltip listed the problems alone, so a dot red from a fresh error in the log explained itself with a yellow
 * warning. The Status and Users tabs were drawn as the Flush caches button, a card's label broke in the middle of
 * "451 / max", a legend's square stayed at the end of a line while its name went to the next, twelve colours served
 * up to nineteen categories in an order that changed every hour, and the watcher's cards were named "Standing visas"
 * and "Suite fixes" for things the glossary calls standing options and tests added to suites. Runs the page's own
 * functions.
 */
class StatusPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/status.html");

    private static final Path SCRIPT = Path.of("src/main/resources/static/status.js");

    private static final Path TC_CLIENT = Path.of("src/main/java/com/github/igniteprchecker/tc/TcClient.java");

    private static final Path COMMON_CSS = Path.of("src/main/resources/static/common.css");

    private static final Pattern HSL = Pattern.compile("hsl\\((\\d+), (\\d+)%, (\\d+)%\\)");

    private static final String[] FUNCTIONS = {"card", "agoShort", "logTime", "renderLog", "healthProblems",
        "healthTitle", "persistenceCard", "versionCard", "configRows"};

    /** A signed-in viewer's status, which the scenario may add to before the page loads. */
    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const status = { version: '1.21.0-3-g5f2c9e1-dirty', commit: '5f2c9e1d8a', dirty: true,
            signedIn: true, uptimeSeconds: 60, startedAt: page.now() - 60000, health: 'ok', logHealth: 'ok',
            healthProblems: [], teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
            jvm: { heapUsedMb: 120, heapMaxMb: 512, heapPeakMb: 28, threads: 40, cpus: 2 },
            app: { openPrs: 3, pooledTokens: 1 }, log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] },
            config: { APP_PUBLIC_URL: 'https://prc.example.org', SESSION_SECRET: 'set' } };
        page.route('/api/status', { body: status });
        """;

    @Test
    void anErrorLoggedWhileStartingIsThisRunsNotThePreviousOnes() throws Exception {
        String html = run("""
            const start = Date.now() - 3700;
            const el = {};
            renderLog(el, { signedIn: true, startedAt: start, uptimeSeconds: 3, log: { recent: [
                { t: start + 251, level: 'ERROR', logger: 'CacheStore', message: 'could not read standing-visas.json' },
                { t: start - 3600000, level: 'WARN', logger: 'TcClient', message: 'TeamCity did not answer' },
            ] } });
            out(el.innerHTML);
            """).asText();

        assertThat(html.indexOf("could not read standing-visas.json")).isNotNegative()
            .isLessThan(html.indexOf("service started"));
        assertThat(html.indexOf("service started")).isLessThan(html.indexOf("TeamCity did not answer"));
    }

    @Test
    void theDotNamesTheErrorInTheLogNextToAWarning() throws Exception {
        String title = run("""
            const d = { health: 'error', logHealth: 'error', log: { lastErrorAt: Date.now() - 5 * 60000 },
                healthProblems: [{ level: 'warn', text: 'no TeamCity token in the pool' }] };
            out(healthTitle(d, healthProblems(d)));
            """).asText();

        assertThat(title).isEqualTo("no TeamCity token in the pool; error logged 5m ago");
    }

    @Test
    void anonymousViewersLearnThatSomethingIsWrongButNotWhat() throws Exception {
        JsonNode page = run("""
            const d = { health: 'error', logHealth: 'ok', log: {},
                healthProblems: [{ level: 'error', text: null }, { level: 'warn', text: null }] };
            const problems = healthProblems(d);
            out({ problems, title: healthTitle(d, problems),
                card: persistenceCard({ enabled: true, active: true, problems: 1, lastBackup: null }) });
            """);

        assertThat(page.get("problems").toString())
            .isEqualTo("[{\"level\":\"error\",\"text\":\"2 problems — log in on the main page to see them\"}]");
        assertThat(page.get("title").asText()).isEqualTo("2 problems — log in on the main page to see them");
        assertThat(page.get("card").asText()).contains("1 problem", "log in on the main page to see it");
    }

    /** Prod ran "1.20.10-dev", a local build whose code no tag and no release matched. */
    @Test
    void theVersionNamesItsCommit() throws Exception {
        JsonNode page = run("""
            out({ dev: versionCard({ version: '1.21.0-3-g5f2c9e1-dirty', commit: '5f2c9e1d8a', dirty: true }),
                release: versionCard({ version: '1.21.1', commit: 'a7b3c4d9e0', dirty: false }),
                old: versionCard({ version: '1.20.10-dev' }) });
            """);

        assertThat(page.get("dev").asText()).contains("1.21.0-3-g5f2c9e1-dirty", "commit 5f2c9e1 + uncommitted changes",
            "v warn");
        assertThat(page.get("release").asText()).contains("1.21.1", "commit a7b3c4d<").doesNotContain("uncommitted");
        assertThat(page.get("old").asText()).contains("1.20.10-dev").doesNotContain("commit");
    }

    /** What the service ran with was nowhere to be seen; the settings name server paths and the operators. */
    @Test
    void signedInViewersSeeTheSettings() throws Exception {
        JsonNode page = run("""
            out({ signedIn: configRows({ config: { APP_PUBLIC_URL: 'https://prc.example.org', SESSION_SECRET: 'set' } }),
                anonymous: configRows({}) });
            """);

        assertThat(page.get("signedIn").asText()).contains("APP_PUBLIC_URL", "https://prc.example.org",
            "SESSION_SECRET");
        assertThat(page.get("anonymous").asText()).contains("log in on the main page to see the settings");
    }

    /** The page as a signed-in viewer sees it after its first poll: the commit beside the version, and the settings. */
    @Test
    void thePageShowsTheCommitAndTheSettings() throws Exception {
        JsonNode page = PageScript.run("status.html", STATUS + """
            await page.load('');
            report({ error: page.el('err').textContent, system: page.el('systemCards').textContent,
                config: page.el('configList').textContent });
            """);

        assertThat(page.get("error").asText()).isEmpty();
        assertThat(page.get("system").asText())
            .contains("1.21.0-3-g5f2c9e1-dirty commit 5f2c9e1 + uncommitted changes");
        assertThat(page.get("config").asText()).contains("APP_PUBLIC_URL", "https://prc.example.org",
            "SESSION_SECRET");
    }

    /** Status | Users is the segmented switch of the PR page's Suites | Root causes; Flush caches and Restart stay buttons. */
    @Test
    void theViewsAreASegmentedSwitchAndTheActionsButtons() throws Exception {
        String html = Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ");
        assertThat(html).contains("<div class=\"tabs-row\"><span class=\"tabs\"> <button id=\"tabStatus\" class=\"act on\" "
                + "type=\"button\" aria-pressed=\"true\"")
            .contains("<button id=\"tabUsers\" class=\"act\" type=\"button\" aria-pressed=\"false\"")
            .contains("<button id=\"restartBtn\" class=\"mini-btn danger\"")
            .contains("<button id=\"flushBtn\" class=\"mini-btn\"")
            .doesNotContain("mini-btn tab");

        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            page.el('tabUsers').listeners.click[0]();
            await page.settle();
            report({ users: page.el('tabUsers').getAttribute('aria-pressed'),
                status: page.el('tabStatus').getAttribute('aria-pressed'),
                on: page.el('tabUsers').classList.contains('on'), off: page.el('tabStatus').classList.contains('on') });
            """);

        assertThat(out.get("users").asText()).isEqualTo("true");
        assertThat(out.get("status").asText()).isEqualTo("false");
        assertThat(out.get("on").asBoolean()).isTrue();
        assertThat(out.get("off").asBoolean()).isFalse();
    }

    /**
     * A category's colour follows its name: the same whatever else is on the chart, none shared among the categories
     * TcClient records, and the list of those is TcClient's own.
     */
    @Test
    void aCategoryKeepsItsColourWhateverElseIsOnTheChart() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            const cats = page.run('TC_CATEGORIES');
            const draw = byCat => { page.run('drawStacked')(page.el('tcStack'), page.el('stackLegend'), byCat);
                return page.el('stackLegend').innerHTML; };
            report({ cats, colours: cats.map(c => page.run('catColor')(c)), other: page.run('catColor')('somethingNew'),
                first: draw({ history: [1], baseline: [2] }), second: draw({ baseline: [2], history: [1] }) });
            """);

        List<String> cats = new ArrayList<>();
        out.get("cats").forEach(c -> cats.add(c.asText()));
        List<String> colours = new ArrayList<>();
        out.get("colours").forEach(c -> colours.add(c.asText()));
        assertThat(colours).hasSameSizeAs(cats).doesNotHaveDuplicates()
            .allMatch(c -> c.matches("hsl\\(\\d+, 60%, \\d+%\\)"));
        assertThat(out.get("other").asText()).matches("hsl\\(\\d+, 60%, \\d+%\\)");
        String history = "<span><span class=\"dot\" style=\"background:" + colours.get(cats.indexOf("history"))
            + "\"></span>history</span>";
        assertThat(out.get("first").asText()).contains(history).startsWith(history);
        assertThat(out.get("second").asText()).contains(history).doesNotStartWith(history);

        Set<String> recorded = new HashSet<>();
        Matcher m = Pattern.compile("(?:get|recorded|occurrencesWithConditions)\\(\"([A-Za-z]+)\"")
            .matcher(Files.readString(TC_CLIENT, UTF_8));
        while (m.find())
            recorded.add(m.group(1));
        assertThat(recorded).as("the categories TcClient records").hasSizeGreaterThan(20);
        assertThat(new HashSet<>(cats)).isEqualTo(recorded);
        assertThat(Files.readString(SCRIPT, UTF_8)).doesNotContain("CAT_COLORS");
    }

    /**
     * One lightness for every theme left the yellow-green bars at 1.8:1 on the white card and the blue ones at 1.9:1 on
     * the dark cards. Every category's bar holds 3:1 (WCAG 1.4.11, graphics) against the card of each theme, and the
     * chart is drawn again when the theme changes.
     */
    @Test
    void everyCategorysBarStandsOutFromTheCardInEveryTheme() throws Exception {
        Map<String, String> cardBg = cardBackgrounds();
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.teamcity.perMinuteByCategory = { history: [1], baseline: [2] };
            await page.load('');
            const cats = page.run('TC_CATEGORIES');
            const byTheme = {};
            for (const t of %s) {
                page.run('document').documentElement.setAttribute('data-theme', t);
                byTheme[t] = cats.map(c => page.run('catColor')(c));
            }
            const toggle = page.el('theme-toggle');
            toggle.value = 'terminal';
            for (const fn of toggle.listeners.change) fn();
            report({ cats, byTheme, legend: page.el('stackLegend').innerHTML });
            """.formatted(cardBg.keySet().stream().map(t -> "'" + t + "'").collect(Collectors.joining(", ", "[", "]"))));

        List<String> cats = new ArrayList<>();
        out.get("cats").forEach(c -> cats.add(c.asText()));
        for (Map.Entry<String, String> theme : cardBg.entrySet()) {
            JsonNode colours = out.get("byTheme").get(theme.getKey());
            assertThat(colours).hasSize(cats.size());
            for (int i = 0; i < cats.size(); i++) {
                assertThat(contrast(colours.get(i).asText(), theme.getValue()))
                    .as(cats.get(i) + " " + colours.get(i).asText() + " on the " + theme.getKey() + " card")
                    .isGreaterThanOrEqualTo(3.0);
            }
        }
        assertThat(out.get("legend").asText()).contains("<span><span class=\"dot\" style=\"background:"
            + out.get("byTheme").get("terminal").get(cats.indexOf("history")).asText() + "\"></span>history</span>");
    }

    /**
     * The labels of the chart's axes were drawn in #999, 2.85:1 on the light card. They take the theme's muted text
     * colour, which the page check holds at 4.5:1 or better.
     */
    @Test
    void theAxisLabelsAreInTheThemesMutedColour() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            page.run('document').documentElement.style['--muted'] = ' #666';
            const texts = [];
            page.el('tcStack').getContext = () => new Proxy({}, {
                get: (ctx, k) => k === 'fillText' ? (text => texts.push(String(text) + ' ' + ctx.fillStyle))
                    : k in ctx ? ctx[k] : () => {} });
            status.teamcity.perMinuteByCategory = { history: [4] };
            await page.load('');
            report({ texts });
            """);

        assertThat(out.get("texts").toString()).isEqualTo("[\"0 #666\",\"1 #666\",\"2 #666\",\"3 #666\",\"4 #666\","
            + "\"-60m #666\",\"now #666\"]");
    }

    /**
     * A label breaks only between its " · " chunks, so a number keeps its unit; a legend item keeps its square. The
     * dot stood between the chunks, and the browser broke the line before it: "· sweep never" began a line of the
     * Tests added to suites card. The dot ends the chunk before it, after a no-break space.
     */
    @Test
    void aCardLabelBreaksOnlyBetweenItsChunks() throws Exception {
        String card = run("out(card('Heap', 45, '/ 494 MB · old-gen peak 28 MB', 'ok', 'Whole heap in use now.'));")
            .asText();
        String css = Files.readString(PAGE, UTF_8);

        assertThat(card).isEqualTo("<div class=\"card\" title=\"Whole heap in use now.\"><div class=\"k\">Heap</div>"
            + "<div class=\"v ok\">45 <small><span class=\"seg\">/ 494 MB\u00a0·</span> <span class=\"seg\">old-gen peak "
            + "28 MB</span></small></div></div>");
        String three = run("out(card('Tests added to suites', 0, 'commits · 0 users with the option on · sweep never'));")
            .asText();
        assertThat(three).contains("<span class=\"seg\">commits\u00a0·</span> <span class=\"seg\">0 users with the "
            + "option on\u00a0·</span> <span class=\"seg\">sweep never</span>");
        assertThat(three.replaceAll("<span class=\"seg\">[^<]*</span>", "")).doesNotContain("·");
        assertThat(rule(css, ".card .v small .seg")).contains("display: inline-block", "max-width: 100%");
        assertThat(rule(css, ".legend > span")).contains("white-space: nowrap");
        assertThat(css).doesNotContain(".legend span {");
    }

    /** The watcher's heading is a sentence, and its cards are named as the glossary names what they count. */
    @Test
    void theWatcherSpeaksTheGlossarysWords() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            const now = page.now();
            status.watcher = { tracked: 2, chains: 1, suites: 1, lastRefreshAt: now - 1000, lastRefreshMs: 300,
                chainFinishes: 4, autoVisaArmed: 1, autoVisaPosted: 2, autoVisaLastPostedAt: now - 180000,
                standingEnrolled: 7, standingPosted: 5, standingLastSweepAt: now - 120000, standingLastSweepMs: 9200,
                prCommands: 3, prCommandsLastPollAt: now - 30000,
                suiteFix: { users: 1, pushed: 2, lastSweepAt: now - 120000 } };
            await page.load('');
            report({ cards: page.el('watcherCards').textContent, system: page.el('systemCards').innerHTML });
            """);

        assertThat(out.get("cards").asText().replace('\u00a0', ' '))
            .contains("Auto visas armed1 2 posted · last 3m ago")
            .contains("Standing options7 users with an option on · 5 JIRA visas posted · sweep 2m ago (9.2s)")
            .contains("Tests added to suites2 commits · 1 user with the option on · sweep 2m ago")
            .doesNotContain("Standing visas", "Auto-visa armed", "Suite fixes");
        assertThat(out.get("system").asText()).contains("<div class=\"card\" title=\"The first number is the whole "
            + "heap in use now. Old-gen peak is the most the old generation has held since the server started. Set "
            + "-Xmx large enough to hold this peak.\"><div class=\"k\">Heap</div>"
            + "<div class=\"v ok\">120 <small><span class=\"seg\">/ 512 MB\u00a0·</span> <span class=\"seg\">old-gen peak "
            + "28 MB</span></small></div></div>");
        assertThat(Files.readString(PAGE, UTF_8).replaceAll("\\s+", " "))
            .contains("<h2>TC watcher <span class=\"muted\" style=\"font-weight:400;font-size:0.8rem\">· checks the RunAlls "
                + "and suite re-runs of PRs every 20 s. When one ends, it recomputes the verdict at once and starts what "
                + "waits for it: re-runs, visas, PR comments.</span></h2>")
            .doesNotContain("finish = eager re-analysis");
    }

    /** Runs {@code script} after the page's functions; whatever it passes to {@code out} comes back. */
    private static JsonNode run(String script) throws Exception {
        return PageFunctions.run("status.js", script, FUNCTIONS);
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }

    /** The card's background of each theme, from the shared stylesheet: "light" for {@code :root}. */
    private static Map<String, String> cardBackgrounds() throws IOException {
        Map<String, String> bg = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?:(:root)|html\\[data-theme=\"(\\w+)\"]) \\{[^}]*?--card-bg: (#[0-9a-fA-F]{3,6})")
            .matcher(Files.readString(COMMON_CSS, UTF_8));
        while (m.find())
            bg.put(m.group(1) != null ? "light" : m.group(2), m.group(3));
        assertThat(bg).containsKeys("light", "dark", "jetbrains", "terminal");
        return bg;
    }

    /** The WCAG contrast ratio of an {@code hsl(h, s%, l%)} colour on a {@code #rgb} or {@code #rrggbb} one. */
    private static double contrast(String hsl, String hex) {
        Matcher m = HSL.matcher(hsl);
        assertThat(m.matches()).as(hsl).isTrue();
        double a = luminance(hslToRgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) / 100.0,
            Integer.parseInt(m.group(3)) / 100.0));
        String six = hex.length() == 4 ? hex.replaceAll("([0-9a-fA-F])", "$1$1") : hex;
        double b = luminance(new double[] {Integer.parseInt(six.substring(1, 3), 16) / 255.0,
            Integer.parseInt(six.substring(3, 5), 16) / 255.0, Integer.parseInt(six.substring(5, 7), 16) / 255.0});
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double[] hslToRgb(int h, double s, double l) {
        double a = s * Math.min(l, 1 - l);
        double[] rgb = new double[3];
        int[] n = {0, 8, 4};
        for (int c = 0; c < 3; c++) {
            double k = (n[c] + h / 30.0) % 12;
            rgb[c] = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
        }
        return rgb;
    }

    private static double luminance(double[] rgb) {
        double[] w = {0.2126, 0.7152, 0.0722};
        double sum = 0;
        for (int c = 0; c < 3; c++) {
            double v = rgb[c];
            sum += w[c] * (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        return sum;
    }
}
