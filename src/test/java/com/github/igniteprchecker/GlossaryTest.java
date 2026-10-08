package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ChainCollector;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.VisaService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The PR page showed Blockers, broken, watch, filtered, ✓, ?, My?, rev @start and three kinds of visa with no word on
 * what they mean, and the reasons "could not verify" and "started failing in the last K of L runs", the 10% shrink
 * and its 20-test floor were written down nowhere. The verdict glossary at the top of the feature tour says it all;
 * these checks keep the page's links landing on it, its quotes in the code, and its numbers the code's.
 */
class GlossaryTest {
    private static final Path TOUR = Path.of("docs/features.md");

    private static final Path TOUR_RU = Path.of("docs/features.ru.md");

    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    private static final Path PAGE_SCRIPT = Path.of("src/main/resources/static/index.js");

    private static final Path COMMON_SCRIPT = Path.of("src/main/resources/static/common.js");

    private static final Path MAIN = Path.of("src/main/java/com/github/igniteprchecker");

    private static final String TOUR_URL = "https://github.com/" + GithubClient.SELF_REPO
        + "/blob/main/docs/features.md#";

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} (.+)$");

    private static final Pattern LOCAL_LINK = Pattern.compile("]\\(#([^)]+)\\)");

    private static final Pattern HELP_LINK =
        Pattern.compile("<a class=\"help\" href=\"([^\"]+)\"[^>]*title=\"([^\"]+)\"");

    @Test
    void everyCardOfThePrPageHasItsQuestionMark() throws IOException {
        String results = read(PAGE).substring(read(PAGE).indexOf("<div id=\"results\""));
        Matcher heads = Pattern.compile("<(h2|h3|summary)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL).matcher(results);

        int cards = 0;
        while (heads.find()) {
            cards++;
            Matcher help = HELP_LINK.matcher(heads.group(2));
            assertThat(help.find()).as(heads.group(2)).isTrue();
            assertThat(help.group(1)).startsWith(TOUR_URL);
        }
        assertThat(cards).as("cards of the PR page").isGreaterThanOrEqualTo(10);
    }

    @Test
    void everyLinkIntoTheTourLandsOnAHeading() throws IOException {
        List<String> links = new ArrayList<>();
        Matcher help = HELP_LINK.matcher(read(PAGE));
        while (help.find())
            links.add(help.group(1));
        links.add(VisaService.GLOSSARY);

        Set<String> anchors = anchors(read(TOUR));
        for (String link : links) {
            assertThat(link).startsWith(TOUR_URL);
            assertThat(anchors).as(link).contains(link.substring(TOUR_URL.length()));
        }
        assertThat(links).as("the cards, the PR list's legend, the runs row and the PR comment")
            .hasSizeGreaterThanOrEqualTo(13);

        for (Path tour : List.of(TOUR, TOUR_RU)) {
            Matcher local = LOCAL_LINK.matcher(read(tour));
            while (local.find())
                assertThat(anchors(read(tour))).as(tour + ": #" + local.group(1)).contains(local.group(1));
        }
    }

    /** The legend under the PR list's filter shows each badge the list can show, as the list draws it. */
    @Test
    void thePrListLegendShowsEveryBadge() throws IOException {
        String legend = between(read(PAGE), "<p id=\"prLegend\"", "</p>");
        Map<String, String> badges = new LinkedHashMap<>(Map.of("ok", "✓", "bad", "2"));
        Matcher standing = Pattern.compile("\\[\\s*'(\\w+)',\\s*'(.)',").matcher(between(read(PAGE_SCRIPT),
            "const STANDING_BADGES", "};"));
        while (standing.find())
            badges.put(standing.group(1), standing.group(2));

        assertThat(badges).containsKeys("watch", "unproven");
        badges.forEach((cls, mark) -> assertThat(legend)
            .containsPattern("<span class=\"pr-badge " + cls + "\"[^>]*>" + Pattern.quote(mark) + "</span>"));
        assertThat(legend).contains("<span class=\"my-tag\"").contains("My?</span>");
    }

    @Test
    void theGlossaryQuotesWhatTheCodeWrites() throws IOException {
        quoted(MAIN.resolve("analysis/BlockerAnalyzer.java"), "not seen failing in ", "only ", " master run(s)",
            "no master history", "(can't prove pre-existing)", "rare on master: fails ", ", passed the last ",
            " on master at ", " on other PR branches at ", " other PRs", "failed the only run", "failed all ",
            "failed the last ", " on this branch", " on revision ", "first failure", " — watch",
            "nothing has passed on this code", "started failing in the last ", "an earlier run on the same code passed",
            " — watch (too few failures on this code yet to outweigh other PRs)", "pre-existing: fails ",
            "flaky on other PR branches: ", "flaky on branch: failed only the latest of ",
            "not failing in the last finished run (passed on re-run)", "could not verify (TeamCity error: ",
            " ran on other code", " could not be placed on a revision",
            "passed just before, but TeamCity gave no revisions to prove that was the same code");
        quoted(MAIN.resolve("analysis/Caveats.java"), "the RunAll was interrupted — ", " suite(s) never ran",
            " suite(s) have no reliable result (", " suite(s) ran far fewer tests than the same suites on master",
            " failed test(s) could not be checked (TeamCity errors)",
            "a newer run is still going — its unfinished suites can still fail",
            " commit(s) pushed since this run — it tested older code");
        quoted(MAIN.resolve("analysis/ChainCollector.java"), "compilation error", "execution timeout",
            "non-zero exit code", "JVM crash / out of memory", "failed dependency", "failed without running tests");
        quoted(MAIN.resolve("analysis/model/BrokenGroup.java"), "ci2 glitch: artifacts unavailable (",
            " failed in this run and passed on a re-run since — ", " never ran; ", " to run them",
            "nothing else ran", " did not run; fix the build, then ");
        quoted(PAGE, "Suites that never ran", "Broken suites", "Fewer tests than master",
            "Crashed near the end of the run", "Not confirmed: 1 run", "Recently started failing",
            "Could not be checked", "This PR's tests", "Filtered out", "Run at top", "Run RunAll", "Cancel my runs",
            "JIRA visa", "Auto visa", "Notify me");
        quoted(PAGE_SCRIPT, "No blockers 🎉", "No test blockers", "1 run", "new test / no master history", "unverified",
            "flaky?", "rev ✓ head", "rev @start", "● includes an unfinished run", " from earlier runs",
            "pushed since this run", "♻️ Auto re-run #", "♻️ Deciding on auto re-runs", "Rerun at top", "Auto visa ✓",
            "armed: ", "Auto visa: on in ⚙");
        quoted(COMMON_SCRIPT, "⚖ assertion — likely a real logic failure", "♻ environment/timing — a re-run may pass",
            "⌛ hang — the test ran out of time");
    }

    @Test
    void theThresholdsAreTheCodes() throws Exception {
        Map<String, String> table = table(section(glossary(TOUR), "Thresholds"));
        AnalysisProperties analysis = new AnalysisProperties(null, null, null, null, null, null, null);
        long shrink = constant(ChainCollector.class, "SHRINK_PCT");

        assertThat(table.get("Master runs of a test looked at, per suite")).startsWith(analysis.historyDepth() + " ");
        assertThat(table.get("Failed branch runs in a row for a blocker"))
            .startsWith(analysis.blockerFailStreak() + ",");
        assertThat(table.get("Thin master history"))
            .contains("fewer than " + constant(BlockerAnalyzer.class, "FEW_MASTER_RUNS") + " master runs");
        assertThat(table.get("A rare master failure"))
            .contains("at most " + constant(BlockerAnalyzer.class, "RARE_ON_MASTER_PERCENT") + "% of master runs")
            .contains("the newest " + constant(BlockerAnalyzer.class, "RECENT_MASTER_GREEN"));
        assertThat(table.get("Too many failures for chance"))
            .contains(String.format(Locale.ROOT, "1 in %,d", constant(BlockerAnalyzer.class, "OUTWEIGHS_ONE_IN")));
        assertThat(table.get("Flaky in other PRs"))
            .contains("failing in " + constant(BlockerAnalyzer.class, "FLAKY_IN_PRS") + " or more other PRs");
        assertThat(table.get("Master's failures set aside as down to the scale factor"))
            .contains("at least " + constant(BlockerAnalyzer.class, "MIN_OTHER_PR_RUNS") + " runs");
        assertThat(table.get("Fewer tests than master")).contains("at least " + shrink + "% fewer")
            .contains(constant(ChainCollector.class, "SHRINK_MIN_BASELINE") + " or more tests on master");
        assertThat(table.get("A crashed suite whose results stand")).contains("over " + (100 - shrink) + "%");
        assertThat(table.get("An incomplete verdict held back"))
            .startsWith(constant(BlockerAnalyzer.class, "HOLD_INCOMPLETE_MS") / 60_000 + " minutes");
        assertThat(table.get("A slow test of the PR")).contains(pageConstant("SLOW_TEST_MS") / 1000 + " s");
        assertThat(table.get("A rerun that asks first")).startsWith(pageConstant("ASK_FROM_SUITES") + " suites");
    }

    /** Both tours carry the same tables: the same rows, and the same quoted labels in their first column. */
    @Test
    void theRussianGlossaryHasTheSameTables() throws IOException {
        List<List<String>> en = firstColumns(glossary(TOUR));
        List<List<String>> ru = firstColumns(glossary(TOUR_RU));

        assertThat(en).as("the glossary's tables").hasSizeGreaterThanOrEqualTo(8);
        assertThat(ru).hasSameSizeAs(en);
        for (int i = 0; i < en.size(); i++) {
            assertThat(ru.get(i)).as("table " + (i + 1)).hasSameSizeAs(en.get(i));
            for (int row = 0; row < en.get(i).size(); row++)
                assertThat(codeIn(ru.get(i).get(row))).as("table " + (i + 1) + ", row " + (row + 1))
                    .isEqualTo(codeIn(en.get(i).get(row)));
        }
    }

    /** Each phrase is in the source, so the glossary quotes what the checker really writes, and in the glossary. */
    private static void quoted(Path source, String... phrases) throws IOException {
        String code = read(source);
        String glossary = glossary(TOUR);
        for (String phrase : phrases) {
            assertThat(code).as(source.getFileName() + " writes it").contains(phrase);
            assertThat(glossary).as("the glossary quotes it").contains(phrase.strip());
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, UTF_8);
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as(from).isNotNegative();

        return text.substring(start, text.indexOf(to, start));
    }

    /** The anchors GitHub gives the headings of a markdown file. */
    private static Set<String> anchors(String markdown) {
        return HEADING.matcher(markdown).results()
            .map(m -> m.group(1).strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", ""))
            .map(heading -> heading.replace(' ', '-'))
            .collect(Collectors.toSet());
    }

    /** The glossary of a tour: its first second-level section, which must be the glossary. */
    private static String glossary(Path tour) throws IOException {
        String text = read(tour);
        int start = text.indexOf("\n## ");
        assertThat(text.substring(start + 4, text.indexOf('\n', start + 1)))
            .isIn("Verdict glossary", "Словарь вердикта");

        return text.substring(start, text.indexOf("\n## ", start + 1));
    }

    private static String section(String glossary, String title) {
        int start = glossary.indexOf("\n### " + title + "\n");
        assertThat(start).as(title).isNotNegative();
        int end = glossary.indexOf("\n### ", start + 1);

        return glossary.substring(start, end < 0 ? glossary.length() : end);
    }

    /** A table's rows, its first column to its second. */
    private static Map<String, String> table(String section) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (List<String> row : rows(section))
            rows.put(row.get(0), row.get(1));

        return rows;
    }

    /** The first column of every table of the glossary, table by table. */
    private static List<List<String>> firstColumns(String glossary) {
        return List.of(glossary.split("\n### ")).stream().skip(1)
            .map(s -> rows(s).stream().map(r -> r.get(0)).toList()).toList();
    }

    /** A section's table rows, header and separator left out, as cells. */
    private static List<List<String>> rows(String section) {
        return section.lines().filter(l -> l.startsWith("| ")).skip(1)
            .map(l -> List.of(l.substring(2, l.length() - 2).split(" \\| "))).toList();
    }

    private static List<String> codeIn(String cell) {
        return Pattern.compile("`([^`]+)`").matcher(cell).results().map(m -> m.group(1)).toList();
    }

    private static long constant(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);

        return ((Number)f.get(null)).longValue();
    }

    private static long pageConstant(String name) throws IOException {
        Matcher m = Pattern.compile("const " + name + " = (\\d+);").matcher(read(PAGE_SCRIPT));
        assertThat(m.find()).as(name).isTrue();

        return Long.parseLong(m.group(1));
    }
}
