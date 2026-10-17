package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The dark themes put white text on their light-blue accent: 2.3:1 on the selected tab and the update button, 2.8:1
 * on the red blocker count; the light theme's amber was 4.2:1 on white and 3.9:1 on its own tint; and a hover rule
 * of higher specificity took the selected tab's fill away under its white label, 1.1:1. The badges of the PR list
 * stood at 4.3:1 on the selected and the hovered row, and the Terminal brackets of the selected status tab at 1.2:1
 * on its fill, pairs no rule names. {@code page/contrast.js} cascades every page's own CSS as a browser would, in
 * each theme, pairs each fill with what it holds, and reports text under 4.5:1 on its background.
 */
class PageContrastTest {
    private static final Path SCRIPT = Path.of("src/test/resources/page/contrast.js");

    private static final Path STATIC = Path.of("src/main/resources/static");

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void everyThemeKeepsTextReadableOnItsFill(String page) throws Exception {
        JsonNode out = run(STATIC.resolve(page));

        assertThat(out.get("themes").toString()).isEqualTo("[\"light\",\"dark\",\"jetbrains\",\"terminal\"]");
        assertThat(out.get("pairs").asInt()).as("pairs of text and fill checked").isGreaterThan(300);
        assertThat(out.get("failures")).as(out.get("failures").toPrettyString()).isEmpty();
        assertThat(out.get("rings").asInt()).as("focus rings checked").isGreaterThan(300);
        assertThat(out.get("badRings")).as(out.get("badRings").toPrettyString()).isEmpty();
    }

    /**
     * The keyboard's focus was Chrome's own ring, #005fcc: 1.5:1 on the selected tab's accent fill and 2.2:1 on Auto
     * visa when on; .tabs clipped the ring's outer part. The check finds a ring of the fill's own colour, a ring cut
     * off by a parent that clips, and an element with no ring.
     */
    @Test
    void theCheckFindsARingLostOnItsFillCutByItsParentOrMissing() throws Exception {
        Path page = Files.createTempFile("page", ".html");
        try {
            Files.writeString(page, """
                <html><head><style>
                :root { --accent: #1565c0; --on-accent: #fff; --fg: #1a1a1a; }
                body { color: var(--fg); background: #fff; }
                .tabs { overflow: hidden; }
                .tabs .act.on { background: var(--accent); color: var(--on-accent); }
                .update-btn { background: var(--accent); color: var(--on-accent); }
                :focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
                .update-btn:focus-visible { outline-offset: -3px; }
                .quiet:focus-visible { outline: none; }
                </style></head><body></body></html>
                """);
            JsonNode out = run(page);

            List<String> rings = new ArrayList<>();
            for (JsonNode r : out.get("badRings")) {
                if (!r.get("element").asText().contains(":hover"))
                    rings.add(r.get("theme").asText() + " " + r.get("element").asText() + " "
                        + (r.get("cut").asBoolean() ? "cut" : r.get("ring").asText() + " " + r.get("ratio").asText()));
            }
            assertThat(rings).containsExactlyInAnyOrder("light .tabs button.act.on:focus-visible cut",
                "light a.update-btn:focus-visible #1565c0 1", "light button.quiet:focus-visible none 0");
        }
        finally {
            Files.deleteIfExists(page);
        }
    }

    /** The check sees through variables, nesting, specificity and a hover rule that outranks the selected tab's. */
    @Test
    void theCheckFindsWhiteOnLightBlueAndTheHoverThatLosesTheFill() throws Exception {
        Path page = Files.createTempFile("page", ".html");
        try {
            Files.writeString(page, """
                <html><head><style>
                :root { --accent: #1565c0; --fg: #1a1a1a; --btn-hover: #f0f4fb; }
                body { color: var(--fg); background: #fff; }
                .act { color: var(--fg); background: #fafafa; }
                .tabs .act.on { background: var(--accent); color: #fff; }
                .act:not(:disabled):hover { background: var(--btn-hover); }
                html[data-theme="dark"] {
                    --accent: #6ab0ff; --fg: #e6e6e6; --btn-hover: #2b333d;
                    body { background: #17191c; }
                    .act { background: #24282d; }
                }
                </style></head><body></body></html>
                """);
            JsonNode out = run(page);

            List<String> failures = new ArrayList<>();
            for (JsonNode f : out.get("failures"))
                failures.add(f.get("theme").asText() + " " + f.get("element").asText() + " " + f.get("ratio").asText());
            assertThat(failures).containsExactlyInAnyOrder("light .tabs button.act.on:hover 1.1",
                "dark .tabs button.act.on 2.27");
        }
        finally {
            Files.deleteIfExists(page);
        }
    }

    /**
     * A badge's rule names no row and a bracket's rule names no tab: the check pairs a fill with what it holds, and
     * reads a {@code ::before} with text as a child of its element; one with empty content has nothing to check.
     */
    @Test
    void theCheckSeesABadgeOnTheHoveredRowAndTheBracketOnTheFilledTab() throws Exception {
        Path page = Files.createTempFile("page", ".html");
        try {
            Files.writeString(page, """
                <html><head><style>
                :root { --fg: #1a1a1a; --muted: #666; }
                body { color: var(--fg); background: #fff; }
                .pr-list li:hover { background: #ededed; }
                .pr-badge.watch { color: #a35f00; }
                .mini-btn.tab.on { background: #58a6ff; color: #0d1117; }
                .mini-btn::before { content: "["; color: var(--muted); }
                .mini-btn::after { content: ''; color: var(--muted); }
                </style></head><body></body></html>
                """);
            JsonNode out = run(page);

            List<String> failures = new ArrayList<>();
            for (JsonNode f : out.get("failures"))
                failures.add(f.get("theme").asText() + " " + f.get("element").asText() + " " + f.get("ratio").asText());
            assertThat(failures).containsExactlyInAnyOrder("light .pr-list li:hover .pr-badge.watch 4.28",
                "light .pr-list li:hover .pr-badge.watch:hover 4.28", "light button.mini-btn.tab.on::before 2.27",
                "light button.mini-btn.tab.on:hover::before 2.27");
        }
        finally {
            Files.deleteIfExists(page);
        }
    }

    /**
     * The check passed what it skipped: the client rows of the /status log, faded to an opacity of 0.55; the busy
     * pulse, faded to 0.55 in its @keyframes; Chrome's grey placeholder on the dark fields. They stood at 2.3:1 to
     * 3.9:1. The check blends a faded element into what lies beneath it, checks each frame of an animation, and reads a
     * placeholder as Chrome and as Firefox draw it; a disabled button is left out, as WCAG leaves it.
     */
    @Test
    void theCheckSeesFadedTextAPulseAndAPlaceholder() throws Exception {
        Path page = Files.createTempFile("page", ".html");
        try {
            Files.writeString(page, """
                <html><head><style>
                :root { --bg: #fff; --fg: #1a1a1a; --muted: #666; --input-bg: #fff; }
                body { color: var(--fg); background: var(--bg); }
                .logrow.client { opacity: 0.55; }
                .busy { color: var(--muted); animation: busy 1.4s ease-in-out infinite; }
                @keyframes busy { 50% { opacity: 0.55; } }
                input { color: var(--fg); background: var(--input-bg); }
                button:disabled { opacity: 0.5; }
                html[data-theme="dark"] { --bg: #17191c; --fg: #e6e6e6; --muted: #9aa0a6; --input-bg: #1f2328; }
                </style></head><body></body></html>
                """);
            JsonNode out = run(page);

            List<String> failures = new ArrayList<>();
            for (JsonNode f : out.get("failures")) {
                if (!f.get("element").asText().contains(":hover"))
                    failures.add(f.get("theme").asText() + " " + f.get("element").asText() + " " + f.get("ratio").asText());
            }
            assertThat(failures).containsExactlyInAnyOrder("light .logrow.client 3.89",
                "light .busy @keyframes busy 50% 2.3", "light input::placeholder (Firefox) 3.77",
                "dark .busy @keyframes busy 50% 2.92", "dark input::placeholder (Chrome) 3.43");
        }
        finally {
            Files.deleteIfExists(page);
        }
    }

    private static JsonNode run(Path page) throws Exception {
        Process node;
        try {
            node = new ProcessBuilder("node", SCRIPT.toString(), page.toString()).redirectErrorStream(true).start();
        }
        catch (IOException e) {
            assumeTrue(false, "node is not installed");
            throw e;
        }
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(node.exitValue()).as(out).isZero();

        return new ObjectMapper().readTree(out);
    }
}
