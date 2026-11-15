package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The options that act on a user's runs name RunAll and all supported client projects. */
class OptionsNameClientRunsTest {
    @Test
    void theOptionsThatActOnRunsNameTheClientsRunsToo() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8).replaceAll("\\s+", " ");

        assertThat(page).doesNotContain("each RunAll you start.")
            .contains("re-runs the suites the verdict blames, in each RunAll and each Python, PHP and Node.js client PR run you start.")
            .contains("posts the verdict of each RunAll and each Python, PHP and Node.js client PR run you start as a PR comment.")
            .contains("posts the verdict of each RunAll and each Python, PHP and Node.js client PR run you start to the IGNITE ticket "
                + "the PR title names.");
    }
}
