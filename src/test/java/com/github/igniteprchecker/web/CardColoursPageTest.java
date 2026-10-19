package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.igniteprchecker.Jackson2Defaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Suites that never ran stood red, the colour of what the author has to fix, though a cancelled suite only needs a
 * rerun; and the one line of the visa group with no ticket in the PR title stood at the heading's full contrast
 * though marked muted: a later rule of the same specificity gave it the text colour. {@code page/contrast.js}
 * cascades the page's CSS as a browser would; these checks read the colour each element lands on, in every theme.
 */
class CardColoursPageTest {
    private static final Path SCRIPT = Path.of("src/test/resources/page/contrast.js");

    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    private static final List<String> THEMES = List.of("light", "dark", "jetbrains", "terminal");

    @Test
    void suitesThatNeverRanAreAmberLikeFewerTestsNotRedLikeBrokenSuites() throws Exception {
        Map<String, Map<String, String>> colours = colours();

        for (String theme : THEMES) {
            Map<String, String> of = colours.get(theme);
            assertThat(of.get(".card.interrupted h2")).as(theme).isEqualTo(of.get(".card.watch h2"))
                .isNotEqualTo(of.get(".blockers h2"));
        }
    }

    @Test
    void theNoTicketLineOfTheVisaGroupIsMuted() throws Exception {
        Map<String, Map<String, String>> colours = colours();

        for (String theme : THEMES) {
            Map<String, String> of = colours.get(theme);
            assertThat(of.get(".visa-group.muted")).as(theme + ": a rule gives the no-ticket line its own colour").isNotNull()
                .isEqualTo(of.get(".muted")).isNotEqualTo(of.get(".visa-group"));
        }
    }

    /** The text colour of every element the page's rules name, by theme and element. */
    private static Map<String, Map<String, String>> colours() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("node", SCRIPT.toString(), PAGE.toString()).redirectErrorStream(true);
        pb.environment().put("CONTRAST_ALL", "1");
        Process node;
        try {
            node = pb.start();
        }
        catch (IOException e) {
            assumeTrue(false, "node is not installed");
            throw e;
        }
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(node.exitValue()).as(out).isZero();

        Map<String, Map<String, String>> colours = new HashMap<>();
        for (JsonNode pair : Jackson2Defaults.mapper().readTree(out).get("failures"))
            colours.computeIfAbsent(pair.get("theme").asText(), t -> new HashMap<>())
                .put(pair.get("element").asText(), pair.get("color").asText());

        return colours;
    }
}
