package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

/**
 * The pages spoke English only. A user who chooses Russian in ⚙ reads every page in Russian, the PR page, the login
 * form, ⚙, the status page and the flaky board, from one catalog, {@code static/ru.json}, that the pages and the server
 * share. These checks keep it whole: each string a page shows has its Russian, the catalog keeps no string that nothing
 * shows, a Russian string puts in only what its English holds, and each link into the docs leads to a heading of the
 * docs in its language.
 */
class RussianCatalogTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Path MAIN = Path.of("src/main/java");

    private static final String LITERAL = "(?:'(?:[^'\\\\\\n]|\\\\.)*'|\"(?:[^\"\\\\\\n]|\\\\.)*\")";

    /** {@code T('…')}, {@code T('…' + '…', v)} and {@code phrase('…')}: the English is the literals, joined. */
    private static final Pattern CALL =
        Pattern.compile("\\b(?:T|phrase)\\(\\s*(" + LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*)\\s*[,)]");

    /** {@code T`Queued ${n} suites`}: the key names each value by its place, "Queued {0} suites". */
    private static final Pattern TAG = Pattern.compile("\\bT`((?:[^`\\\\$]|\\\\.|\\$(?!\\{)|\\$\\{[^`]*?})*)`");

    /** {@code countOf(n, 'browser')}: "{0} browser{1}". */
    private static final Pattern COUNT =
        Pattern.compile("\\bcountOf\\([^,()]*(?:\\([^()]*\\))?[^,()]*,\\s*'([A-Za-z]+)'\\)");

    /** {@code Texts.t(lang, "…" + "…", …)} in the server's code. */
    private static final Pattern SERVER_CALL = Pattern.compile("Texts\\.t\\(\\s*lang\\s*,\\s*(\"(?:[^\"\\\\\\n]|\\\\.)*\""
        + "(?:\\s*\\+\\s*\"(?:[^\"\\\\\\n]|\\\\.)*\")*)");

    private static final Pattern JAVA_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)(?::n)?}");

    private static final Pattern RU_VALUE = Pattern.compile("\\{(\\w+)(?::tr|\\|([^{}]*))?}");

    private static final Pattern HTML_TAG = Pattern.compile("</?[a-z][^>]*>");

    private static final Pattern DOCS_LINK = Pattern.compile("^https://github\\.com/[^#]+/blob/main/(docs/[\\w.]+|README"
        + "[\\w.]*)#(.+)$");

    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    /** A word in lower case, as a sentence has, not a name: "suites", not "PR" or "JetBrains". */
    private static final Pattern ENGLISH_WORD = Pattern.compile("(?<![A-Za-z])[a-z]{3,}");

    @Test
    void everyStringOfThePagesHasItsRussian() throws IOException {
        Map<String, String> ru = catalog();

        assertThat(pageKeys().stream().filter(k -> !ru.containsKey(k)).toList()).isEmpty();
        assertThat(serverKeys().stream().filter(k -> !ru.containsKey(k)).toList()).isEmpty();
    }

    /**
     * A key no page and no server code writes is left from a change of the words. A template of the server's texts
     * stands for a sentence its code puts together from parts, so it counts as written while the code's strings still
     * hold each of its words and three of them in a row.
     */
    @Test
    void theCatalogKeepsNoStringNothingShows() throws IOException {
        Set<String> shown = new LinkedHashSet<>(pageKeys());
        shown.addAll(serverKeys());
        String javaText = javaLiterals();
        List<String> unused = new ArrayList<>();
        sections().forEach((section, entries) -> entries.keySet().forEach(key -> {
            boolean used = section.startsWith("server") ? writtenBy(javaText, key) : shown.contains(key);
            if (!used)
                unused.add(section + ": " + key);
        }));

        assertThat(unused).isEmpty();
    }

    @Test
    void aRussianStringPutsInWhatItsEnglishHolds() throws IOException {
        List<String> wrong = new ArrayList<>();
        catalog().forEach((en, ru) -> {
            Set<String> ids = PLACEHOLDER.matcher(en).results().map(m -> m.group(1))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Matcher value = RU_VALUE.matcher(ru);
            Set<String> used = new LinkedHashSet<>();
            while (value.find()) {
                used.add(value.group(1));
                if (!ids.contains(value.group(1)))
                    wrong.add(en + " → " + ru + ": {" + value.group(1) + "} is not in the English");
                if (value.group(2) != null && value.group(2).split("\\|", -1).length != 3)
                    wrong.add(en + " → " + ru + ": a word with a number takes three forms");
            }
            for (String id : ids) {
                boolean suffix = Pattern.compile("[A-Za-z]\\{" + id + "}").matcher(en).find();
                if (!used.contains(id) && !suffix)
                    wrong.add(en + " → " + ru + ": {" + id + "} is lost");
            }
            if (ru.contains("\"") && !en.contains("\""))
                wrong.add(en + " → " + ru + ": a quote would end the attribute the page puts it in");
            if (!tags(en).equals(tags(ru)))
                wrong.add(en + " → " + ru + ": the markup differs");
            if (!CYRILLIC.matcher(ru).find() && ENGLISH_WORD.matcher(ru.replaceAll("\\{[^}]*}", "")).find()
                && !DOCS_LINK.matcher(en).matches())
                wrong.add(en + " → " + ru + ": no Russian in it");
        });

        assertThat(wrong).isEmpty();
    }

    /** The same English phrase reads the same in Russian wherever it is shown, whatever names its values. */
    @Test
    void aPhraseReadsTheSameEverywhere() throws IOException {
        Map<String, String> byPhrase = new HashMap<>();
        List<String> differ = new ArrayList<>();
        catalog().forEach((en, ru) -> {
            String phrase = PLACEHOLDER.matcher(en).replaceAll("{}");
            String said = RU_VALUE.matcher(ru).replaceAll(m -> m.group().replaceFirst("\\{\\w+", "{"));
            String before = byPhrase.putIfAbsent(phrase, said);
            if (before != null && !before.equals(said))
                differ.add(en + ": " + before + " | " + said);
        });

        assertThat(differ).isEmpty();
    }

    /** Each link of the page into the docs has its Russian, and both land on a heading of their own document. */
    @Test
    void everyDocsLinkLeadsToAHeadingInItsLanguage() throws IOException {
        Map<String, String> ru = catalog();
        List<String> links = pageKeys().stream().filter(k -> DOCS_LINK.matcher(k).matches()).toList();

        assertThat(links).as("the help links of the pages").hasSizeGreaterThanOrEqualTo(5);
        for (String link : links) {
            assertThat(ru).as(link).containsKey(link);
            for (String target : List.of(link, ru.get(link))) {
                Matcher m = DOCS_LINK.matcher(target);
                assertThat(m.matches()).as(target).isTrue();
                assertThat(GlossaryTest.anchors(Files.readString(Path.of(m.group(1)), UTF_8))).as(target)
                    .contains(m.group(2));
            }
            assertThat(ru.get(link)).as(link).contains(".ru.md#");
        }
    }

    /** The catalog's sections, each its English strings and their Russian. */
    static Map<String, Map<String, String>> sections() throws IOException {
        return Jackson2Defaults.mapper().readValue(STATIC.resolve("ru.json").toFile(), new TypeReference<>() {
        });
    }

    /** The catalog as one map: a phrase has one Russian wherever it stands. */
    static Map<String, String> catalog() throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        for (Map<String, String> entries : sections().values()) {
            entries.forEach((en, ru) -> assertThat(all.put(en, ru)).as("one entry for " + en).isNull());
        }

        return all;
    }

    /** The strings the pages write: those of their HTML, and those their scripts put into the page's language. */
    static Set<String> pageKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String page : List.of("index.html", "status.html", "flaky.html"))
            keys.addAll(htmlKeys(Files.readString(STATIC.resolve(page), UTF_8)));
        for (String script : List.of("common.js", "index.js", "status.js", "flaky.js"))
            keys.addAll(scriptKeys(Files.readString(STATIC.resolve(script), UTF_8)));

        return keys;
    }

    /** The strings the server puts into the language of the page's reader. */
    static Set<String> serverKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (Path file : javaFiles()) {
            Matcher call = SERVER_CALL.matcher(withoutComments(Files.readString(file, UTF_8)));
            while (call.find())
                keys.add(joined(call.group(1)));
        }

        return keys;
    }

    /** What a script puts into the page's language, its comments left out. */
    static Set<String> scriptKeys(String js) {
        String code = withoutComments(js);
        Set<String> keys = new LinkedHashSet<>();
        Matcher call = CALL.matcher(code);
        while (call.find())
            keys.add(joined(call.group(1)));
        Matcher tag = TAG.matcher(code);
        while (tag.find()) {
            int[] at = {0};
            String key = Pattern.compile("\\$\\{[^`]*?}").matcher(tag.group(1)).replaceAll(m -> "{" + at[0]++ + "}");
            keys.add(unescaped(key));
        }
        Matcher count = COUNT.matcher(code);
        while (count.find())
            keys.add("{0} " + count.group(1) + "{1}");

        return keys;
    }

    /**
     * What a reader sees of a page's HTML: its title, each text between tags with its runs of white space made one,
     * and the tooltips, labels, placeholders and links into the docs. Code stays English.
     */
    static Set<String> htmlKeys(String html) {
        String page = html.replaceAll("(?s)<!--.*?-->", "");
        Set<String> keys = new LinkedHashSet<>();
        Matcher title = Pattern.compile("(?s)<title>(.*?)</title>").matcher(page);
        if (title.find())
            keys.add(normalized(title.group(1)));
        String body = page.substring(page.indexOf("<body")).replaceAll("(?s)<(script|style|code|svg)\\b.*?</\\1>", "<x>");
        Matcher tag = Pattern.compile("<[a-zA-Z][^>]*>").matcher(body);
        while (tag.find()) {
            for (String attr : List.of("title", "aria-label", "placeholder", "href")) {
                Matcher value = Pattern.compile("\\s" + attr + "=\"([^\"]*)\"").matcher(tag.group());
                if (!value.find())
                    continue;
                String text = normalized(value.group(1));
                if (attr.equals("href") ? DOCS_LINK.matcher(text).matches() : text.matches("(?s).*[A-Za-z].*"))
                    keys.add(text);
            }
        }
        for (String text : body.split("<[^>]*>")) {
            String t = normalized(text);
            if (t.matches("(?s).*[A-Za-zА-Яа-я].*"))
                keys.add(t);
        }

        return keys;
    }

    private static String normalized(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&").strip().replaceAll("\\s+", " ");
    }

    private static String withoutComments(String code) {
        return Stream.of(code.split("\n")).filter(line -> !line.stripLeading().startsWith("//")
            && !line.stripLeading().startsWith("*") && !line.stripLeading().startsWith("/*"))
            .collect(Collectors.joining("\n"));
    }

    /** The text of literals joined with "+": each one's quotes and escapes taken away. */
    private static String joined(String literals) {
        Matcher lit = Pattern.compile(LITERAL).matcher(literals);
        StringBuilder out = new StringBuilder();
        while (lit.find())
            out.append(unescaped(lit.group().substring(1, lit.group().length() - 1)));

        return out.toString();
    }

    private static String unescaped(String s) {
        return s.replace("\\n", "\n").replaceAll("\\\\(.)", "$1");
    }

    /** The markup tags of a string, in order: a Russian string has the English one's. */
    private static List<String> tags(String s) {
        return HTML_TAG.matcher(s).results().map(m -> m.group().replaceAll("\"[^\"]*\"", "\"\"")).toList();
    }

    /** Whether the code's strings hold each word of the template and three of its words in a row, or a shorter run. */
    private static boolean writtenBy(String javaText, String key) {
        List<List<String>> runs = Stream.of(PLACEHOLDER.matcher(key).replaceAll("\u0000").split("[^A-Za-z ]+"))
            .map(run -> List.of(run.strip().split(" +"))).filter(run -> !run.get(0).isEmpty()).toList();
        boolean words = runs.stream().flatMap(List::stream).filter(w -> w.length() >= 4).allMatch(javaText::contains);
        List<List<String>> phrases = runs.stream().filter(run -> run.size() >= 2).toList();

        return words && (phrases.isEmpty() || phrases.stream().anyMatch(run -> inARow(javaText, run)));
    }

    private static boolean inARow(String javaText, List<String> run) {
        int width = Math.min(3, run.size());
        for (int i = 0; i + width <= run.size(); i++) {
            if (javaText.contains(String.join(" ", run.subList(i, i + width))))
                return true;
        }

        return false;
    }

    /** Every string literal of the server's code, one after another. */
    private static String javaLiterals() throws IOException {
        StringBuilder out = new StringBuilder();
        for (Path file : javaFiles()) {
            Matcher lit = JAVA_LITERAL.matcher(Files.readString(file, UTF_8));
            while (lit.find())
                out.append(unescaped(lit.group(1))).append('\u0000');
        }

        return out.toString().replace("\u0000 ", " ").replace(" \u0000", " ");
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
