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
import java.lang.reflect.Method;
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

    private static final Path README = Path.of("README.md");

    private static final Path README_RU = Path.of("README.ru.md");

    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    private static final Path FLAKY_PAGE = Path.of("src/main/resources/static/flaky.html");

    private static final Path PAGE_SCRIPT = Path.of("src/main/resources/static/index.js");

    private static final Path COMMON_SCRIPT = Path.of("src/main/resources/static/common.js");

    private static final Path MAIN = Path.of("src/main/java/com/github/igniteprchecker");

    private static final String TOUR_URL = "https://github.com/" + GithubClient.SELF_REPO
        + "/blob/main/docs/features.md#";

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} (.+)$");

    private static final Pattern LOCAL_LINK = Pattern.compile("]\\(#([^)]+)\\)");

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

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
        for (Path page : List.of(PAGE, FLAKY_PAGE)) {
            Matcher help = HELP_LINK.matcher(read(page));
            while (help.find())
                links.add(help.group(1));
        }
        links.add(VisaService.GLOSSARY);

        Set<String> anchors = anchors(read(TOUR));
        for (String link : links) {
            assertThat(link).startsWith(TOUR_URL);
            assertThat(anchors).as(link).contains(link.substring(TOUR_URL.length()));
        }
        assertThat(links).as("the cards, the PR list, the runs row, the flaky board and the PR comment")
            .contains(TOUR_URL + "the-flaky-board-flakyhtml").hasSizeGreaterThanOrEqualTo(15);

        for (Path tour : List.of(TOUR, TOUR_RU)) {
            Matcher local = LOCAL_LINK.matcher(read(tour));
            while (local.find())
                assertThat(anchors(read(tour))).as(tour + ": #" + local.group(1)).contains(local.group(1));
        }
    }

    /** The legend under the PR list's filter shows each badge the list can show, as the list draws it. */
    @Test
    void thePrListLegendShowsEveryBadge() throws IOException {
        String legend = between(read(PAGE), "<details id=\"prLegend\"", "</details>");
        Map<String, String> badges = new LinkedHashMap<>(Map.of("ok", "✓", "bad", "2"));
        Matcher standing = Pattern.compile("\\[\\s*'(\\w+)',\\s*'(.)',").matcher(between(read(PAGE_SCRIPT),
            "const STANDING_BADGES", "};"));
        while (standing.find())
            badges.put(standing.group(1), standing.group(2));

        assertThat(badges).containsKeys("watch", "unproven");
        badges.forEach((cls, mark) -> assertThat(legend)
            .containsPattern("<span class=\"pr-badge " + cls + "\"[^>]*>" + Pattern.quote(mark) + "</span>"));
        assertThat(legend).contains("<span class=\"my-tag\"").contains("my run</span>");
    }

    @Test
    void theGlossaryQuotesWhatTheCodeWrites() throws IOException {
        quoted(MAIN.resolve("analysis/BlockerAnalyzer.java"), "not seen failing in ", "only ", " master run",
            "no master history", "(can't prove pre-existing)", "rare on master: fails ", ", passed the last ",
            " on master at ", " on other PR branches at ", " other PRs", "failed the only run", "failed the latest run",
            "failed all ", "failed the last ", " on this branch", " on commit ", ", after a pass on the same commit",
            "after a pass; TeamCity gave no commits to prove that was the same code",
            "an earlier run on the same code passed", "; too few failures on this code yet to outweigh other PRs",
            "pre-existing: fails ", "flaky on other PR branches: ", "flaky on branch: failed only the latest of ",
            "not failing in the last finished run (passed on re-run)", "could not verify (TeamCity error: ",
            " on other code", "TeamCity gave no commit for it, so ", " not count",
            "passed just before, but TeamCity gave no commits to prove that was the same code");
        quoted(MAIN.resolve("analysis/Caveats.java"), "the RunAll was interrupted — ", " never ran",
            " no reliable result (", " ran fewer tests than the same ", " could not be checked (TeamCity errors)",
            "a newer run is still going — its unfinished suites can still fail",
            " pushed since this run — it tested older code", "new commits pushed since this run — it tested older code",
            "mixed run — ", " re-ran on ", ", the rest of the RunAll ran on ",
            "; only a RunAll of the PR's head can prove it clean");
        quoted(MAIN.resolve("analysis/ChainCollector.java"), "compilation error", "execution timeout",
            "non-zero exit code", "JVM crash / out of memory", "failed dependency", "failed without running tests");
        quoted(MAIN.resolve("analysis/model/BrokenGroup.java"), "ci2 glitch: artifacts unavailable (",
            " failed in this run and passed on a re-run since — ", " never ran; ", " to run them",
            "nothing else ran", " did not run; fix the build, then ", "ran fewer tests than on master");
        quoted(PAGE, "Suites that never ran", "Broken suites", "Fewer tests than master",
            "Crashed near the end of the run", "Not confirmed: 1 run", "Not confirmed yet", "Recently started failing",
            "Could not be checked", "This PR's tests", "Not blamed on this PR", "Run at top", "Run RunAll",
            "Cancel my runs", "Notify me");
        quoted(PAGE_SCRIPT, "On ci2 now", "✓ No blockers", "No proven blockers yet", "No blockers found — not proven clean",
            "No blockers found", "1 run", "new test / no master history", "unverified", "not in any suite", "flaky?",
            "on latest commit", "⚠ old commit", "takes latest commit at start", "● includes an unfinished run",
            " reused from earlier runs", "The verdict is for older code", "♻︎ Auto re-run #",
            "♻︎ Deciding whether to re-run", "Rerun at top", "Visa: posted to ", "when the run settles",
            "JIRA did not take it, the checker tries again every 10 minutes", "is off in ⚙",
            "because JIRA rejected your token",
            "Visa: waits until you log in again", "Visa: waits until you save your JIRA token in ⚙ again",
            "Visa: waits for a new JIRA token in ⚙",
            "Visa: none for this run, a newer RunAll replaces it",
            "Visa: none, the PR is closed", "Visa: unknown, TeamCity does not say who started this run",
            "Visa: the PR title names no IGNITE ticket", "who started this run, has ",
            "Visa: none, the PR title named no IGNITE ticket when the run settled", "Visa: none, this run was cancelled",
            "could not check whether the PR changed since this run", "it tested older code (see the note at the top)",
            "⚠ Mixed run", "it is a mixed run (see the note at the top)",
            "Blockers since the ", " dropped out", " still blocking");
        quoted(COMMON_SCRIPT, "⚖ assertion — likely a real logic failure", "environment/timing — a re-run may pass",
            "⌛ hang — the test ran out of time");
    }

    /** A cause's button on the Root causes tab reads "copy AI prompt"; the tours name it so, apart from the rows' "ai". */
    @Test
    void theToursNameTheCausesButtonAsThePageWritesIt() throws IOException {
        assertThat(PageEnglish.of(read(PAGE_SCRIPT))).contains(">copy AI prompt</button>");
        for (Path tour : List.of(TOUR, TOUR_RU))
            assertThat(read(tour)).as(tour.getFileName().toString()).contains("**copy AI prompt**");
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
        assertThat(table.get("Too many failures for chance")).contains(
            String.format(Locale.ROOT, "at most 1 in %,d", constant(BlockerAnalyzer.class, "OUTWEIGHS_ONE_IN")));
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
        assertThat(table.get("A re-run that asks first")).startsWith(pageConstant("ASK_FROM_SUITES") + " suites");
    }

    /**
     * Master failing 1 run in 100 and the PR failing 2 in a row is a chance of exactly 1 in 10,000, and the code makes
     * it a blocker; the glossary said "less than 1 in 10,000". Each example streak is the shortest that outweighs.
     */
    @Test
    void theChanceExamplesAreTheShortestStreaksThatOutweigh() throws Exception {
        String row = rowOf(glossary(TOUR), "Too many failures for chance");
        Matcher example = Pattern.compile("(\\d+)(?: in a row)? against (?:a )?([\\d.]+)%").matcher(row);
        Method outweighs = BlockerAnalyzer.class.getDeclaredMethod("outweighs", int.class, int.class, int.class);
        outweighs.setAccessible(true);

        int examples = 0;
        while (example.find()) {
            examples++;
            int streak = Integer.parseInt(example.group(1));
            int failsIn1000 = (int)(Double.parseDouble(example.group(2)) * 10);
            assertThat(outweighs.invoke(null, failsIn1000, 1000, streak)).as(example.group()).isEqualTo(true);
            assertThat(outweighs.invoke(null, failsIn1000, 1000, streak - 1)).as(example.group()).isEqualTo(false);
        }
        assertThat(examples).isEqualTo(3);
        assertThat(outweighs.invoke(null, 1, 100, 2)).as("exactly 1 in 10,000").isEqualTo(true);
        assertThat(row).contains("at most 1 in 10,000");
        assertThat(rowOf(glossary(TOUR_RU), "Слишком много падений для случайности")).contains("не больше 1 к 10 000");
    }

    /**
     * "No proven blockers yet" heads a run with nothing blamed and tests under Recently started failing: the row says
     * where they are and that a rerun decides, without sending the reader to look for a caveat.
     */
    @Test
    void noProvenBlockersYetSaysWhereToLook() throws IOException {
        for (Path tour : List.of(TOUR, TOUR_RU))
            assertThat(rowOf(glossary(tour), "**No proven blockers yet**")).as(tour.toString())
                .contains("**Recently started failing**");
    }

    /**
     * Visa my runs in JIRA posts nothing for a PR whose title names no ticket, waits while a newer RunAll of the PR
     * goes, and skips a visa that repeats the last one of the same revision; the row said every RunAll gets its visa.
     * ⚙ keeps to one line per option, and its link leads to the tour, which says it.
     */
    @Test
    void theAutoVisaRowSaysWhenNoVisaGoesOut() throws IOException {
        String[] exceptions = {"no key, no visa", "No visa while a newer RunAll of the PR is going",
            "none that repeats the last one"};

        assertThat(read(TOUR).replaceAll("\\s+", " ")).as("the tour's section on the option")
            .contains("no key, no visa", "No visa goes out while a newer RunAll of the PR is going",
                "none that repeats the last visa");
        assertThat(read(PAGE)).contains(TOUR_URL + "standing-options");
        assertThat(rowOf(glossary(TOUR), "**Visa my runs in JIRA** (⚙)")).contains(exceptions);
        assertThat(rowOf(glossary(TOUR_RU), "**Visa my runs in JIRA** (⚙)"))
            .contains("нет ключа — нет визы", "Пока идёт более новый RunAll этого PR, визы нет",
                "не повторяет прошлую визу того же коммита");
    }

    /**
     * The page names its cards Recently started failing and Not blamed on this PR, and the comment and the visa say
     * "commit". The glossary's reasons ended in "To watch." and "Filtered out.", and the tours and the READMEs said
     * "filtered-out noise", "tests to watch" and "revision". Only the row on a merged PR's old words quotes "revision".
     * The flaky board's tour said the checker "filters out" its tests; the READMEs' first lines keep the verb for what
     * the checker does with the failures that master and other PRs explain.
     */
    @Test
    void theDocsUseThePagesNames() throws IOException {
        for (Path doc : List.of(TOUR, README)) {
            String text = withoutMergedRow(read(doc));
            assertThat(text).as(doc.toString()).doesNotContain("Filtered out", "filtered out", "filtered-out",
                "To watch.", "tests to watch", "revision");
        }
        for (Path doc : List.of(TOUR_RU, README_RU)) {
            String text = withoutMergedRow(read(doc));
            assertThat(text).as(doc.toString())
                .doesNotContain("В отсеве", "Под наблюдением.", "под наблюдением", "отфильтрован", "ревизи");
        }
        assertThat(read(TOUR)).doesNotContain("filters out");
        assertThat(read(TOUR_RU)).doesNotContain("отфильтровыва");
        assertThat(rowOf(glossary(TOUR), "`pre-existing: fails F/R on master`"))
            .contains("Listed under **Not blamed on this PR**.");
        assertThat(rowOf(glossary(TOUR), "`failed the latest run; TeamCity gave no commit for it, so the N earlier "
            + "runs do not count`")).contains("Listed under **Recently started failing**.");
    }

    /** The text without the glossary's row on a merged PR's verdict, which quotes the old words on purpose. */
    private static String withoutMergedRow(String text) {
        return text.lines()
            .filter(line -> !line.contains("merged PR's verdict") && !line.contains("вердикте смерженного PR"))
            .collect(Collectors.joining("\n"));
    }

    /** The tours quote the runs' composition as the page draws it: the tour still had the old "6 ran · 141 reused". */
    @Test
    void theToursQuoteTheRunsCompositionAsThePageDrawsIt() throws IOException {
        assertThat(read(PAGE_SCRIPT)).contains("${res.suitesRan} suite${res.suitesRan === 1 ? '' : 's'} ran",
            ", ${res.suitesReused} reused from earlier runs");
        Pattern label = Pattern.compile("\\d+ suites ran, \\d+ reused from earlier runs");

        for (Path tour : List.of(TOUR, TOUR_RU)) {
            List<String> quotes = all(CODE, read(tour)).stream()
                .filter(code -> code.contains("reused") || code.endsWith("from earlier runs")).toList();
            assertThat(quotes).as(tour + ": the glossary and the tour").hasSizeGreaterThanOrEqualTo(2)
                .allMatch(code -> label.matcher(code).matches());
        }
    }

    /**
     * Both tours carry the same tables: the same rows, the same quoted labels in their first column, and in every row
     * the same numbers and the same bold labels. A threshold or a button renamed in the English table alone fails.
     */
    @Test
    void theRussianGlossaryHasTheSameTables() throws IOException {
        List<List<String>> en = tables(glossary(TOUR));
        List<List<String>> ru = tables(glossary(TOUR_RU));

        assertThat(en).as("the glossary's tables").hasSizeGreaterThanOrEqualTo(8);
        assertThat(ru).hasSameSizeAs(en);
        for (int i = 0; i < en.size(); i++) {
            assertThat(ru.get(i)).as("table " + (i + 1)).hasSameSizeAs(en.get(i));
            for (int row = 0; row < en.get(i).size(); row++) {
                String enRow = en.get(i).get(row);
                String ruRow = ru.get(i).get(row);
                String where = "table " + (i + 1) + ", row " + firstCell(enRow);
                assertThat(all(CODE, firstCell(ruRow))).as(where).isEqualTo(all(CODE, firstCell(enRow)));
                assertThat(all(NUMBER, ruRow)).as("numbers, " + where).isEqualTo(all(NUMBER, enRow));
                assertThat(all(BOLD, ruRow)).as("bold labels, " + where).isEqualTo(all(BOLD, enRow));
            }
        }
    }

    /**
     * The PR comment and the visa write their caveats in English. The Russian glossary gives each caveat it quotes
     * its Russian words in «», so a reader learns what the comment's line says.
     */
    @Test
    void eachCaveatInTheRussianGlossaryHasItsRussianWords() throws IOException {
        List<String> caveats = rows(section(glossary(TOUR_RU), "Оговорки")).stream().map(row -> row.get(0)).toList();

        assertThat(caveats).hasSizeGreaterThanOrEqualTo(9).allSatisfy(cell -> assertThat(cell).contains("«"));
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
    static Set<String> anchors(String markdown) {
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

    /** The rows of every table of the glossary, table by table, each with its cells joined by " | ". */
    private static List<List<String>> tables(String glossary) {
        return List.of(glossary.split("\n### ")).stream().skip(1)
            .map(s -> rows(s).stream().map(r -> String.join(" | ", r)).toList()).toList();
    }

    /** The glossary's row whose first cell is this, its cells joined by " | ". */
    private static String rowOf(String glossary, String firstCell) {
        List<String> rows = tables(glossary).stream().flatMap(List::stream)
            .filter(row -> firstCell(row).equals(firstCell)).toList();
        assertThat(rows).as(firstCell).hasSize(1);

        return rows.get(0);
    }

    private static String firstCell(String row) {
        return row.substring(0, row.indexOf(" | "));
    }

    /** A section's table rows, header and separator left out, as cells. */
    private static List<List<String>> rows(String section) {
        return section.lines().filter(l -> l.startsWith("| ")).skip(1)
            .map(l -> List.of(l.substring(2, l.length() - 2).split(" \\| "))).toList();
    }

    /** What each match of the pattern captures: its last group, or the whole match when it has none. */
    private static List<String> all(Pattern p, String text) {
        return p.matcher(text).results().map(m -> m.group(m.groupCount())).toList();
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
