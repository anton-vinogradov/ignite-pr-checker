package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three pages ran their script inline, so the CSP could not forbid inline script, and markup slipping past
 * {@code esc()} would still have run. Each page also kept its own copy of the shared helpers, and the copies drifted:
 * the flaky board's run chips left out what the PR page says from the same data. Script now comes only from
 * {@code static/*.js}, which lets the CSP forbid any other, and the shared helpers from {@code common.js} alone.
 */
class PageScriptsTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.DOTALL);

    private static final Pattern SCRIPT_SRC = Pattern.compile("<script src=\"([^\"]+)\"></script>");

    /** An {@code onclick=…} or the like inside a tag, in the markup or in markup a script builds. */
    private static final Pattern INLINE_HANDLER = Pattern.compile("<[a-zA-Z][^<>\\n]*\\son[a-z]+\\s*=");

    /** Code made from a string: {@code script-src 'self'} blocks it, so it would break the page. */
    private static final Pattern CODE_FROM_STRING =
        Pattern.compile("\\beval\\s*\\(|\\bnew Function\\s*\\(|\\bset(Timeout|Interval)\\s*\\(\\s*['\"`]|javascript:");

    private static final Pattern TOP_LEVEL_NAME =
        Pattern.compile("^(?:async function|function|const|let|var) ([A-Za-z0-9_$]+)", Pattern.MULTILINE);

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void aPageRunsNoInlineScript(String page) throws IOException {
        String html = Files.readString(STATIC.resolve(page));

        Matcher tags = SCRIPT_TAG.matcher(html);
        while (tags.find())
            assertThat(tags.group(1)).as(tags.group()).isEmpty();
        assertThat(found(INLINE_HANDLER, html)).as("inline event handlers in " + page).isEmpty();
        assertThat(found(CODE_FROM_STRING, html)).as("code from strings in " + page).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void aPageAppliesTheThemeInItsHeadAndLoadsTheSharedHelpersBeforeItsOwnScript(String page) throws IOException {
        String html = Files.readString(STATIC.resolve(page));

        assertThat(sources(html)).containsExactly("/theme.js", "/common.js", "/" + page.replace(".html", ".js"));
        assertThat(html.indexOf("<script src=\"/theme.js\">")).isLessThan(html.indexOf("</head>"));
    }

    @Test
    void noScriptBuildsHandlersOrCodeFromStrings() throws IOException {
        List<Path> scripts = scripts();

        assertThat(scripts).extracting(p -> p.getFileName().toString())
            .contains("theme.js", "common.js", "index.js", "flaky.js", "status.js");
        for (Path script : scripts) {
            String js = Files.readString(script);
            assertThat(found(INLINE_HANDLER, js)).as("inline event handlers in " + script).isEmpty();
            assertThat(found(CODE_FROM_STRING, js)).as("code from strings in " + script).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.js", "flaky.js", "status.js"})
    void aPageScriptKeepsNoCopyOfASharedHelper(String script) throws IOException {
        Set<String> shared = topLevelNames(STATIC.resolve("common.js"));
        Set<String> own = topLevelNames(STATIC.resolve(script));

        assertThat(shared).contains("esc", "chipEta", "fmtLeft", "shortTestName", "copyText");
        assertThat(own).doesNotContainAnyElementsOf(shared);
    }

    private static List<String> found(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(MatchResult::group).toList();
    }

    private static List<String> sources(String html) {
        return SCRIPT_SRC.matcher(html).results().map(m -> m.group(1)).toList();
    }

    private static List<Path> scripts() throws IOException {
        try (Stream<Path> files = Files.list(STATIC)) {
            return files.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
    }

    private static Set<String> topLevelNames(Path script) throws IOException {
        Set<String> names = new TreeSet<>();
        TOP_LEVEL_NAME.matcher(Files.readString(script)).results().forEach(m -> names.add(m.group(1)));
        return names;
    }
}
