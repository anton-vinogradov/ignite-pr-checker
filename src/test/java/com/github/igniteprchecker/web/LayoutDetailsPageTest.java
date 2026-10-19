package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Small layout faults the self-review found on the pages: "verdict computed 46s ago↻" glued the ↻ to the text; the
 * theme list of the dark themes had a transparent fill, which Chromium on Windows and Linux draws as light text on
 * white; "🔔 Will notify" looked like "🔔 Notify me", while a switch that is on is filled; at 375 px two live chips of
 * a suite ran over its Rerun pair, and the log of /status left about 50 px to a message; the bars of "per RunAll" were
 * links 8 px wide and 2 px apart.
 */
class LayoutDetailsPageTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void theRefreshMarkStandsApartFromTheTextBeforeIt() throws IOException {
        assertThat(rule(read("index.html"), ".refresh")).contains("margin-left: 0.3em").doesNotContain("margin-right");
    }

    @Test
    void theThemeListHasTheFillOfItsTheme() throws IOException {
        assertThat(rule(read("common.css"), ".theme-sel")).contains("background: var(--bg)")
            .doesNotContain("transparent");
    }

    /** A switch that is on is filled: Notify me as the primary buttons, the Terminal theme included. */
    @Test
    void notifyMeIsFilledWhileOn() throws IOException {
        String css = read("index.html");

        assertThat(rule(css, "#notifyBtn[aria-pressed=\"true\"]"))
            .contains("background: var(--accent)", "color: var(--on-accent)");
        assertThat(css).contains(".act.primary, .act.primary:not(:disabled):hover, #notifyBtn[aria-pressed=\"true\"] {"
                + " background: var(--accent);")
            .contains("#notifyBtn[aria-pressed=\"true\"]::before, #notifyBtn[aria-pressed=\"true\"]::after "
                + "{ color: var(--on-accent); }");
    }

    @Test
    void theLiveChipsOfASuiteWrapBesideItsName() throws IOException {
        assertThat(rule(read("index.html"), ".suite-live")).contains("flex-wrap: wrap");
    }

    /**
     * On a phone the logger goes on a line of its own under the message, and the message takes the logger's column.
     * The logger stood 8 px under its message and 4.8 px over the next entry, so it read as that entry's heading. The
     * lines of one entry stand closer together than two entries do. With columns as wide as their content, a WARN
     * message started 8 px left of an ERROR one; the time and level columns have fixed widths.
     */
    @Test
    void aLogMessageTakesTheRowOnAPhone() throws IOException {
        String narrow = narrow(read("status.html"));

        assertThat(rule(narrow, ".logrow")).contains("grid-template-columns: 62px 46px 1fr", "row-gap: 0.1rem");
        assertThat(rule(narrow, ".loglist")).contains("gap: 0.6rem");
        assertThat(rule(narrow, ".loglogger")).contains("grid-column: 1 / -1", "grid-row: 2");
    }

    /**
     * A bar is a column 24 px wide with the 8 px bar in its middle; 30 of them wrap rather than run off a phone. The
     * wrapped rows stood with no gap, so a full bar touched the bar above it and the two read as one taller bar.
     */
    @Test
    void aPerRunAllBarIsATargetOf24Px() throws IOException {
        String css = read("index.html");

        assertThat(rule(css, ".trend")).contains("flex-wrap: wrap", "row-gap: 4px")
            .doesNotContain("gap: 2px", "height: 16px");
        assertThat(rule(css, ".trend a")).contains("width: 24px", "padding: 0 8px", "background-clip: content-box");
        assertThat(rule(css, ".trend a.clean")).contains("background-image:").doesNotContain("background:");
    }

    private static String read(String file) throws IOException {
        return Files.readString(STATIC.resolve(file), UTF_8);
    }

    /** The rules of the page's narrow-screen block. */
    private static String narrow(String css) {
        int start = css.indexOf("@media (max-width: 768px) {");
        assertThat(start).as("the narrow-screen block").isNotNegative();
        return css.substring(start);
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
