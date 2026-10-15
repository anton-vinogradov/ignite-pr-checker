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
 * of higher specificity took the selected tab's fill away under its white label, 1.1:1. {@code page/contrast.js}
 * cascades every page's own CSS as a browser would, in each theme, and reports text under 4.5:1 on its background.
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
