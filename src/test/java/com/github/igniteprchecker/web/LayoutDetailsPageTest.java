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
 * white; "🔔 Will notify" looked like "🔔 Notify me", while Auto visa is filled when on; at 375 px two live chips of a
 * suite ran over its Rerun pair, and the log of /status left about 50 px to a message; the bars of "per RunAll" were
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

    /** A switch that is on is filled: Notify me as Auto visa, the Terminal theme included. */
    @Test
    void notifyMeIsFilledWhileOn() throws IOException {
        String css = read("index.html");

        assertThat(rule(css, "#autoVisaBtn[aria-pressed=\"true\"], #autoVisaBtn[aria-pressed=\"true\"]:disabled, "
            + "#notifyBtn[aria-pressed=\"true\"]")).contains("background: var(--accent)", "color: var(--on-accent)");
        assertThat(css).contains(".act.primary, .act.primary:not(:disabled):hover, #autoVisaBtn[aria-pressed=\"true\"],"
                + " #autoVisaBtn[aria-pressed=\"true\"]:disabled, #notifyBtn[aria-pressed=\"true\"] {"
                + " background: var(--accent);")
            .contains("#notifyBtn[aria-pressed=\"true\"]::before, #notifyBtn[aria-pressed=\"true\"]::after "
                + "{ color: var(--on-accent); }");
    }

    @Test
    void theLiveChipsOfASuiteWrapBesideItsName() throws IOException {
        assertThat(rule(read("index.html"), ".suite-live")).contains("flex-wrap: wrap");
    }

    /** On a phone the logger goes under the time and level, and the message takes the rest of the row. */
    @Test
    void aLogMessageTakesTheRowOnAPhone() throws IOException {
        String narrow = narrow(read("status.html"));

        assertThat(rule(narrow, ".logrow")).contains("grid-template-columns: auto auto 1fr");
        assertThat(rule(narrow, ".loglogger")).contains("grid-column: 1 / -1", "grid-row: 2");
    }

    /** A bar is a column 24 px wide with the 8 px bar in its middle; 30 of them wrap rather than run off a phone. */
    @Test
    void aPerRunAllBarIsATargetOf24Px() throws IOException {
        String css = read("index.html");

        assertThat(rule(css, ".trend")).contains("flex-wrap: wrap").doesNotContain("gap: 2px", "height: 16px");
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
