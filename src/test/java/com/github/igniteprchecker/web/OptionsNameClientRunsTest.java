package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Since #308 and #309 the options in ⚙ act on the runs a user starts in a PR of the Python thin client too, while ⚙
 * said that they act on "each RunAll you start". Each option that acts on runs names both.
 */
class OptionsNameClientRunsTest {
    @Test
    void theOptionsThatActOnRunsNameTheClientsRunsToo() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8).replaceAll("\\s+", " ");

        assertThat(page).doesNotContain("each RunAll you start.")
            .contains("re-runs the suites the verdict blames, in each RunAll and each Python client PR run you start.")
            .contains("posts the verdict of each RunAll and each Python client PR run you start as a PR comment.")
            .contains("posts the verdict of each RunAll and each Python client PR run you start to the IGNITE ticket "
                + "the PR title names.");
    }
}
