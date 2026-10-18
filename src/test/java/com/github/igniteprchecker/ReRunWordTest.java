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
 * PR 13692's page had a button "Rerun" with the tooltip "Re-run the 2 suites of this section", the PR list's legend
 * said "a rerun shows whether they are blockers", and the comment's legend of the 1 run tag "a rerun of its suite",
 * while ⚙, /status and the comment's own lines wrote "re-run". "Rerun" is now only the name of the button: the pages'
 * texts, the tooltips and the docs write "re-run", and a button's tooltip says what a click does.
 */
class ReRunWordTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final List<Path> DOCS = List.of(Path.of("README.md"), Path.of("README.ru.md"),
        Path.of("docs/features.md"), Path.of("docs/features.ru.md"));

    /** The word in any spelling but "re-run", and its plural. */
    private static final Pattern RERUN = Pattern.compile("(?i)(?<![\\w-])reruns?(?![\\w-])");

    /** A text that is a button's whole name: "Rerun", "Rerun at top", "Rerun 2 suites", "Rerun # suite#". */
    private static final Pattern BUTTON = Pattern.compile("Rerun(?: at top| [#\\d]+ suites?#?)?");

    /** A button named inside a sentence: in quotes on the page, in bold in the docs. */
    private static final Pattern NAMED = Pattern.compile("“Rerun[^”]*”|\\*\\*Rerun[^*]*\\*\\*");

    private static final Pattern ATTRIBUTE =
        Pattern.compile("\\b(?:title|aria-label|content|placeholder)=\"([^\"]*)\"");

    private static final Pattern CODE_LIKE = Pattern.compile("[\\w./?&=#:-]*");

    @Test
    void theDocsSayReRunAndBoldTheButton() throws IOException {
        for (Path doc : DOCS) {
            String text = Files.readString(doc, UTF_8).replaceAll("`[^`\n]*`", "");
            assertThat(misused(List.of(text.split("\n")))).as(doc.toString()).isEmpty();
        }
    }

    @Test
    void thePagesSayReRunAndNameOnlyTheButtonRerun() throws IOException {
        for (String page : List.of("index", "flaky", "status")) {
            String html = Files.readString(STATIC.resolve(page + ".html"), UTF_8)
                .replaceAll("(?s)<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->", "");
            assertThat(misused(texts(html))).as(page + ".html").isEmpty();

            List<String> literals = new ArrayList<>();
            for (String script : List.of(page + ".js", "common.js"))
                literals.addAll(literals(Files.readString(STATIC.resolve(script), UTF_8)));
            List<String> texts = new ArrayList<>();
            for (String literal : literals)
                texts.addAll(texts(literal));
            assertThat(misused(texts)).as(page + ".js").isEmpty();
        }
    }

    /** The scanner finds what the PR page's script writes, a tooltip in a template inside a template among them. */
    @Test
    void theScriptsTextsAreAllRead() throws IOException {
        List<String> literals = literals(Files.readString(STATIC.resolve("index.js"), UTF_8));

        assertThat(literals).contains("Rerun at top", "the # suites of this group",
            " Its result joins the verdict when it finishes.",
            "♻︎ Deciding whether to re-run the failed suites. The verdict below may still change.");
        assertThat(misused(List.of("Rerun their suites", "a rerun shows", "“Rerun” and “Rerun at top”",
            "Rerun 2 suites", "the reruns"))).containsExactly("Rerun their suites", "a rerun shows", "the reruns");
    }

    /** The texts that say the word otherwise than "re-run" or as a button's name. */
    private static List<String> misused(List<String> texts) {
        return texts.stream().map(String::strip)
            .filter(t -> !BUTTON.matcher(t).matches() && !CODE_LIKE.matcher(t).matches())
            .filter(t -> RERUN.matcher(NAMED.matcher(t).replaceAll("")).find())
            .toList();
    }

    /** What people read in a piece of markup: its text between tags, and its tooltips and labels. */
    private static List<String> texts(String html) {
        List<String> texts = new ArrayList<>();
        Matcher attr = ATTRIBUTE.matcher(html);
        while (attr.find())
            texts.add(attr.group(1));
        texts.addAll(List.of(html.replaceAll("<[^>]*>", "\n").split("\n")));

        return texts;
    }

    /** The strings of a script, its comments left out; a template has "#" for each of its expressions. */
    static List<String> literals(String js) {
        List<String> out = new ArrayList<>();
        new Scanner(js, out).code(0, false);

        return out;
    }

    /** Walks a script as its parser does, as far as strings, templates, comments and regular expressions go. */
    private record Scanner(String js, List<String> out) {
        /** The code from {@code at}; in a template's expression, up to its closing brace, returning past it. */
        int code(int at, boolean expression) {
            int depth = 0;
            char last = 0;
            int i = at;
            while (i < js.length()) {
                char c = js.charAt(i);
                if (js.startsWith("//", i))
                    i = endOf(js.indexOf('\n', i));
                else if (js.startsWith("/*", i))
                    i = endOf(js.indexOf("*/", i)) + 2;
                else if (c == '\'' || c == '"') {
                    int end = closing(i, c);
                    out.add(js.substring(i + 1, end).replace("\\" + c, String.valueOf(c)));
                    i = end + 1;
                    last = 'x';
                }
                else if (c == '`') {
                    i = template(i);
                    last = 'x';
                }
                else if (c == '/' && regexMayStart(last, i)) {
                    i = regex(i);
                    last = 'x';
                }
                else {
                    if (expression && c == '{')
                        depth++;
                    else if (expression && c == '}' && depth-- == 0)
                        return i + 1;
                    if (!Character.isWhitespace(c))
                        last = c;
                    i++;
                }
            }

            return i;
        }

        private int endOf(int at) {
            return at < 0 ? js.length() : at;
        }

        private int closing(int open, char quote) {
            int i = open + 1;
            while (js.charAt(i) != quote)
                i += js.charAt(i) == '\\' ? 2 : 1;

            return i;
        }

        private int template(int open) {
            StringBuilder text = new StringBuilder();
            int i = open + 1;
            while (js.charAt(i) != '`') {
                if (js.charAt(i) == '\\') {
                    text.append(js.charAt(i + 1));
                    i += 2;
                }
                else if (js.startsWith("${", i)) {
                    text.append('#');
                    i = code(i + 2, true);
                }
                else
                    text.append(js.charAt(i++));
            }
            out.add(text.toString());

            return i + 1;
        }

        /** After an operator, a bracket or a keyword a slash starts a regular expression; after a value it divides. */
        private boolean regexMayStart(char last, int at) {
            if (last == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(last) >= 0)
                return true;
            Matcher word = Pattern.compile("(\\w+)\\s*$").matcher(js.substring(Math.max(0, at - 12), at));

            return word.find() && List.of("return", "typeof", "case", "in", "of").contains(word.group(1));
        }

        private int regex(int open) {
            boolean inClass = false;
            int i = open + 1;
            while (inClass || js.charAt(i) != '/') {
                if (js.charAt(i) == '\\')
                    i++;
                else if (js.charAt(i) == '[')
                    inClass = true;
                else if (js.charAt(i) == ']')
                    inClass = false;
                i++;
            }
            i++;
            while (i < js.length() && Character.isLetter(js.charAt(i)))
                i++;

            return i;
        }
    }
}
