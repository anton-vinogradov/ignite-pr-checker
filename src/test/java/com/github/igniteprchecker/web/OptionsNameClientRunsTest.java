package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The standing options explain which of the viewer's runs they act on across projects. */
class OptionsNameClientRunsTest {
    @Test
    void theOptionsCoverTheViewersRunsInEveryProject() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8).replaceAll("\\s+", " ");

        assertThat(page).doesNotContain("each RunAll you start.")
            .contains("re-runs failures in your Ignite, Python, PHP, Node.js or Extensions runs at the top; ci2 may refuse priority.")
            .contains("posts the verdicts of runs you start in any project as PR comments.")
            .contains("posts the verdicts of runs you start in any project to the IGNITE ticket named in the PR title.");
    }
}
