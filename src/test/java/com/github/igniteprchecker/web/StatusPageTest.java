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

    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");

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
            renderLog(el, { signedIn: true, operator: true, startedAt: start, uptimeSeconds: 3, log: { recent: [
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

    /** The card of a heap dump on disk is among the System cards, as the service reports the dump. */
    @Test
    void theSystemCardsShowAHeapDumpOnDisk() throws Exception {
        JsonNode page = PageScript.run("status.html", STATUS + """
            status.jvm.heapDump = { writtenAt: page.now() - 5 * 3600e3, deletedAt: page.now() + 43 * 3600e3 };
            await page.load('');
            report({ system: page.el('systemCards').textContent });
            """);

        assertThat(page.get("system").asText()).contains("Out of memory", "heap dump deleted in 1d 19h 0m");
    }

    /** Status | Users is the segmented switch of the PR page's Suites | Root causes; Flush caches and Restart stay buttons. */
    @Test
    void theViewsAreASegmentedSwitchAndTheActionsButtons() throws Exception {
        String html = Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ");
        assertThat(html).contains("<div id=\"viewTabs\" class=\"tabs-row\" hidden><span class=\"tabs\"> "
                + "<button id=\"tabStatus\" class=\"act on\" type=\"button\" aria-pressed=\"true\"")
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
        assertThat(colours).hasSameSizeAs(cats).allMatch(c -> HEX.matcher(c).matches());
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
     * chart is drawn again when the theme changes. Hues a golden angle apart gave a category and the eighth after it
     * hues 20° apart at one lightness: on one chart prRuns, userBuilds and branchMoves were one purple, failedTests and
     * branchFailures one mint, a CIEDE2000 distance down to 2.9. Any two categories now differ by 15 or more in each
     * theme.
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
                for (int j = i + 1; j < cats.size(); j++) {
                    assertThat(deltaE2000(lab(rgb(colours.get(i).asText())), lab(rgb(colours.get(j).asText()))))
                        .as(cats.get(i) + " and " + cats.get(j) + " in " + theme.getKey()).isGreaterThanOrEqualTo(15.0);
                }
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

    /**
     * The watcher's heading is a sentence, and its cards are named as the glossary names what they count. Its heading
     * says "RunAlls", and two cards said "chains" of the same builds.
     */
    @Test
    void theWatcherSpeaksTheGlossarysWords() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            const now = page.now();
            status.watcher = { tracked: 2, chains: 1, suites: 1, lastRefreshAt: now - 1000, lastRefreshMs: 300,
                chainFinishes: 4,
                standingEnrolled: 7, standingPosted: 5, standingLastSweepAt: now - 120000, standingLastSweepMs: 9200,
                prCommands: 3, prCommandsLastPollAt: now - 30000,
                suiteFix: { users: 1, pushed: 2, lastSweepAt: now - 120000 } };
            await page.load('');
            report({ cards: page.el('watcherCards').textContent, system: page.el('systemCards').innerHTML });
            """);

        assertThat(out.get("cards").asText().replace('\u00a0', ' '))
            .contains("Tracked builds2 1 RunAll · 1 suite")
            .contains("RunAlls finished4 since start")
            .doesNotContain("chain", "Chain")
            .contains("Standing options7 users with an option on · 5 JIRA visas posted · sweep 2m ago (9.2s)")
            .contains("Tests added to suites2 commits · 1 user with the option on · sweep 2m ago")
            .doesNotContain("Standing visas", "Auto visa", "Auto-visa armed", "Suite fixes");
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

    /** The WCAG contrast ratio of a colour on another: {@code hsl(h, s%, l%)}, {@code #rgb} or {@code #rrggbb}. */
    private static double contrast(String colour, String on) {
        double a = luminance(rgb(colour));
        double b = luminance(rgb(on));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** The channels of a colour, 0 to 1. */
    private static double[] rgb(String colour) {
        Matcher m = HSL.matcher(colour);
        if (m.matches()) {
            return hslToRgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) / 100.0,
                Integer.parseInt(m.group(3)) / 100.0);
        }
        String six = colour.length() == 4 ? colour.replaceAll("([0-9a-fA-F])", "$1$1") : colour;
        assertThat(six).as(colour).matches("#[0-9a-fA-F]{6}");
        return new double[] {Integer.parseInt(six.substring(1, 3), 16) / 255.0,
            Integer.parseInt(six.substring(3, 5), 16) / 255.0, Integer.parseInt(six.substring(5, 7), 16) / 255.0};
    }

    /** CIE L*a*b* under D65. */
    private static double[] lab(double[] rgb) {
        double r = linear(rgb[0]);
        double g = linear(rgb[1]);
        double b = linear(rgb[2]);
        double x = labF((r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047);
        double y = labF(r * 0.2126 + g * 0.7152 + b * 0.0722);
        double z = labF((r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883);
        return new double[] {116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double labF(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** The CIEDE2000 colour difference: about 2 is just noticeable, and 15 tells two colours apart at a glance. */
    private static double deltaE2000(double[] lab1, double[] lab2) {
        double c1 = Math.hypot(lab1[1], lab1[2]);
        double c2 = Math.hypot(lab2[1], lab2[2]);
        double c7 = Math.pow((c1 + c2) / 2, 7);
        double g = 0.5 * (1 - Math.sqrt(c7 / (c7 + Math.pow(25, 7))));
        double a1 = (1 + g) * lab1[1];
        double a2 = (1 + g) * lab2[1];
        double c1p = Math.hypot(a1, lab1[2]);
        double c2p = Math.hypot(a2, lab2[2]);
        double h1 = hue(lab1[2], a1);
        double h2 = hue(lab2[2], a2);
        double dh = 0;
        if (c1p * c2p != 0) {
            dh = h2 - h1;
            if (dh > 180)
                dh -= 360;
            else if (dh < -180)
                dh += 360;
        }
        double dl = lab2[0] - lab1[0];
        double dc = c2p - c1p;
        double dbigH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
        double lMean = (lab1[0] + lab2[0]) / 2;
        double cMean = (c1p + c2p) / 2;
        double hMean = h1 + h2;
        if (c1p * c2p != 0) {
            if (Math.abs(h1 - h2) > 180)
                hMean += h1 + h2 < 360 ? 360 : -360;
            hMean /= 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hMean - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hMean))
            + 0.32 * Math.cos(Math.toRadians(3 * hMean + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hMean - 63));
        double rot = 30 * Math.exp(-Math.pow((hMean - 275) / 25, 2));
        double cMean7 = Math.pow(cMean, 7);
        double rc = 2 * Math.sqrt(cMean7 / (cMean7 + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lMean - 50, 2) / Math.sqrt(20 + Math.pow(lMean - 50, 2));
        double sc = 1 + 0.045 * cMean;
        double sh = 1 + 0.015 * cMean * t;
        double rt = -Math.sin(Math.toRadians(2 * rot)) * rc;
        return Math.sqrt(Math.pow(dl / sl, 2) + Math.pow(dc / sc, 2) + Math.pow(dbigH / sh, 2)
            + rt * (dc / sc) * (dbigH / sh));
    }

    private static double hue(double b, double a) {
        if (a == 0 && b == 0)
            return 0;
        double h = Math.toDegrees(Math.atan2(b, a));
        return h < 0 ? h + 360 : h;
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
        for (int c = 0; c < 3; c++)
            sum += w[c] * linear(rgb[c]);
        return sum;
    }

    private static double linear(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
