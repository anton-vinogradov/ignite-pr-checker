package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * README.ru.md had no word of the /run-all and /top commands the English README described, and the two feature tours
 * told different facts. Each pair now has the same sections in the same order, and each section the same number of
 * paragraphs, list items, table rows, code blocks and pictures, the same quoted code and the same numbers; a bold name
 * the Russian keeps in English, a button or a card, is in the English section too. A difference fails here, naming
 * the section. What the sentences say is not compared: a fact written in one language only is caught when it brings
 * a paragraph, an item, a number, a quote or such a name.
 */
class DocParityTest {
    private static final Path README = Path.of("README.md");

    private static final Path README_RU = Path.of("README.ru.md");

    private static final Path TOUR = Path.of("docs/features.md");

    private static final Path TOUR_RU = Path.of("docs/features.ru.md");

    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) (.+)$");

    private static final Pattern FENCE = Pattern.compile("^\\s*```");

    private static final Pattern ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.) ");

    private static final Pattern TABLE_RULE = Pattern.compile("^\\|[-:| ]+\\|$");

    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\(([^)\\s]+)");

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    @Test
    void theReadmesSayTheSame() throws IOException {
        assertThat(differences(README, read(README), README_RU, read(README_RU))).isEmpty();
    }

    @Test
    void theToursSayTheSame() throws IOException {
        assertThat(differences(TOUR, read(TOUR), TOUR_RU, read(TOUR_RU))).isEmpty();
    }

    @Test
    void aSectionLeftOutOfOneLanguageIsCaught() throws IOException {
        String ru = read(README_RU);
        String withoutLast = ru.substring(0, ru.lastIndexOf("\n## "));

        assertThat(differences(README, read(README), README_RU, withoutLast))
            .singleElement().asString().contains("sections");
    }

    @Test
    void aSectionMovedInOneLanguageIsCaught() throws IOException {
        String ru = read(README_RU);
        int last = ru.lastIndexOf("\n## ");
        int beforeLast = ru.lastIndexOf("\n## ", last - 1);
        String swapped = ru.substring(0, beforeLast) + ru.substring(last) + ru.substring(beforeLast, last);

        assertThat(differences(README, read(README), README_RU, swapped)).isNotEmpty();
    }

    @Test
    void aBulletLeftOutOfOneLanguageIsCaught() throws IOException {
        String ru = read(TOUR_RU);
        Matcher bullet = Pattern.compile("(?m)^- .*\\n(?:  .*\\n)*").matcher(ru.substring(ru.indexOf("\n## ", 1)));
        assertThat(bullet.find()).isTrue();
        String withoutBullet = ru.replace(bullet.group(), "");

        assertThat(differences(TOUR, read(TOUR), TOUR_RU, withoutBullet))
            .singleElement().asString().contains("list items");
    }

    @Test
    void aNumberChangedInOneLanguageIsCaught() throws IOException {
        String ru = read(README_RU).replaceFirst("\\b8080\\b", "8081");

        assertThat(differences(README, read(README), README_RU, ru)).singleElement().asString().contains("8081");
    }

    @Test
    void aQuoteChangedInOneLanguageIsCaught() throws IOException {
        String ru = read(README_RU).replaceFirst("`PRC_ADMINS`", "`PRC_ADMIN`");

        assertThat(differences(README, read(README), README_RU, ru)).singleElement().asString().contains("PRC_ADMIN");
    }

    /** The Russian tour kept the old button name after the English one was renamed, with the counts all equal. */
    @Test
    void aButtonNameLeftOldInOneLanguageIsCaught() throws IOException {
        String ru = read(TOUR_RU).replaceFirst("\\*\\*Cancel my runs\\*\\*", "**Cancel all**");

        assertThat(differences(TOUR, read(TOUR), TOUR_RU, ru)).singleElement().asString()
            .contains("bold names only in Russian [Cancel all]");
    }

    @Test
    void aBoldNameIsComparedOnlyWhereTheRussianKeepsItInEnglish() {
        String en = "# T\n\n**Standing options**: switch on **PR\ncommands** in ⚙.\n";
        String ru = "# Т\n\n**Постоянные опции**: включи **PR commands** в ⚙.\n";

        assertThat(differences(Path.of("en.md"), en, Path.of("ru.md"), ru)).isEmpty();
        assertThat(differences(Path.of("en.md"), en, Path.of("ru.md"), ru.replace("PR commands", "PR command")))
            .singleElement().asString().contains("bold names only in Russian [PR command]");
    }

    @Test
    void aHeadingInsideACodeBlockIsNotASection() {
        String en = "# T\n\nText.\n\n```bash\n# then open http://localhost:8080\n```\n";
        String ru = "# Т\n\nТекст.\n\n```bash\n# затем открой http://localhost:8080\n```\n";

        assertThat(differences(Path.of("en.md"), en, Path.of("ru.md"), ru)).isEmpty();
        assertThat(outline(en)).hasSize(1);
    }

    @Test
    void aWrappedParagraphIsOneParagraph() {
        Section s = outline("# T\n\nOne line,\nwrapped.\n\n- an item\n  wrapped too\n- another\n\n"
            + "| a | b |\n|---|---|\n| 1 | 2 |\n").get(0);

        assertThat(s.paragraphs()).isEqualTo(1);
        assertThat(s.items()).isEqualTo(2);
        assertThat(s.rows()).isEqualTo(2);
    }

    /** What one language's file says that the other's does not, section by section; empty when they match. */
    static List<String> differences(Path en, String enText, Path ru, String ruText) {
        List<Section> a = outline(enText);
        List<Section> b = outline(ruText);
        if (a.size() != b.size())
            return List.of(ru + " has " + b.size() + " sections, " + en + " has " + a.size() + ": " + titles(b)
                + " vs " + titles(a));

        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            Section x = a.get(i);
            Section y = b.get(i);
            String where = ru + " «" + y.title() + "» vs " + en + " «" + x.title() + "»: ";
            List<String> diff = new ArrayList<>();
            differ(diff, "heading level", x.level(), y.level());
            differ(diff, "paragraphs", x.paragraphs(), y.paragraphs());
            differ(diff, "list items", x.items(), y.items());
            differ(diff, "table rows", x.rows(), y.rows());
            differ(diff, "code blocks", x.codeBlocks(), y.codeBlocks());
            differ(diff, "pictures", x.images(), y.images());
            differ(diff, "quoted code", x.quotes(), y.quotes());
            differ(diff, "numbers", x.numbers(), y.numbers());
            List<String> keptInEnglish = y.bold().stream()
                .filter(name -> !CYRILLIC.matcher(name).find() && !x.bold().contains(name)).toList();
            if (!keptInEnglish.isEmpty())
                diff.add("bold names only in Russian " + keptInEnglish);
            if (!diff.isEmpty())
                out.add(where + String.join("; ", diff));
        }

        return out;
    }

    /**
     * The sections of a markdown text, split at its headings (a "#" line in a code block is not one), each with what
     * it holds. The text before the first heading is a section of its own.
     */
    static List<Section> outline(String markdown) {
        List<Section> sections = new ArrayList<>();
        Builder cur = new Builder(0, "");
        boolean fenced = false;
        boolean inBlock = false;
        for (String line : markdown.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                if (!fenced)
                    cur.codeBlocks++;
                fenced = !fenced;
                inBlock = false;
                cur.numbers(line);

                continue;
            }
            if (fenced) {
                cur.numbers(line);

                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (!cur.empty())
                    sections.add(cur.build());
                cur = new Builder(heading.group(1).length(), heading.group(2).strip());
                cur.facts(line);
                inBlock = false;

                continue;
            }
            if (line.isBlank()) {
                inBlock = false;

                continue;
            }

            cur.facts(line);
            if (line.startsWith("|")) {
                if (!TABLE_RULE.matcher(line.strip()).matches())
                    cur.rows++;
                inBlock = false;
            }
            else if (ITEM.matcher(line).find()) {
                cur.items++;
                inBlock = true;
            }
            else if (!inBlock) {
                cur.paragraphs++;
                inBlock = true;
            }
        }
        if (!cur.empty())
            sections.add(cur.build());

        return sections;
    }

    private static void differ(List<String> diff, String what, int en, int ru) {
        if (en != ru)
            diff.add(what + " " + ru + " ≠ " + en);
    }

    private static void differ(List<String> diff, String what, List<String> en, List<String> ru) {
        if (en.equals(ru))
            return;

        List<String> onlyRu = new ArrayList<>(ru);
        en.forEach(onlyRu::remove);
        List<String> onlyEn = new ArrayList<>(en);
        ru.forEach(onlyEn::remove);
        diff.add(what + ": only in Russian " + onlyRu + ", only in English " + onlyEn);
    }

    private static List<String> titles(List<Section> sections) {
        return sections.stream().map(Section::title).toList();
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, UTF_8);
    }

    /**
     * A section of a markdown file: its heading and what it holds, the quotes and numbers sorted, the bold names
     * outside code blocks as they read, a name wrapped over two lines included.
     */
    record Section(int level, String title, int paragraphs, int items, int rows, int codeBlocks, List<String> images,
        List<String> quotes, List<String> numbers, List<String> bold) {
    }

    private static final class Builder {
        private final int level;

        private final String title;

        private int paragraphs;

        private int items;

        private int rows;

        private int codeBlocks;

        private final List<String> images = new ArrayList<>();

        private final List<String> quotes = new ArrayList<>();

        private final List<String> numbers = new ArrayList<>();

        private final StringBuilder text = new StringBuilder();

        Builder(int level, String title) {
            this.level = level;
            this.title = title;
        }

        void facts(String line) {
            IMAGE.matcher(line).results().forEach(m -> images.add(m.group(1)));
            CODE.matcher(line).results().forEach(m -> quotes.add(m.group(1)));
            numbers(line);
            text.append(line).append('\n');
        }

        void numbers(String line) {
            NUMBER.matcher(line).results().forEach(m -> numbers.add(m.group()));
        }

        boolean empty() {
            return level == 0 && paragraphs + items + rows + codeBlocks == 0;
        }

        Section build() {
            return new Section(level, title, paragraphs, items, rows, codeBlocks, List.copyOf(images),
                quotes.stream().sorted().toList(), numbers.stream().sorted().toList(),
                BOLD.matcher(text).results().map(m -> m.group(1).replaceAll("\\s+", " ").strip()).distinct().sorted()
                    .toList());
        }
    }
}
