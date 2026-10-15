package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The PR commands caption in ⚙ and both READMEs said the /run-all comment itself then narrates the run, and later
 * that it did so only while the checker held the user's GitHub token. The checker's account tells every run in a
 * comment of its own now, and mentions the commander when the verdict is ready.
 */
class RunAllStoryPlaceTest {
    @Test
    void theCaptionSaysWhereTheStoryGoes() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);
        int start = html.indexOf("id=\"commandsToggle\"");
        assertThat(start).isNotNegative();
        String caption = html.substring(start, html.indexOf("</label>", start)).replaceAll("<[^>]*>", " ")
            .replaceAll("\\s+", " ");

        assertThat(caption).doesNotContain("the command comment then narrates the run")
            .doesNotContain("written into my command comment");
        assertThat(collapsed("docs/features.md")).contains("🚀 means accepted, 😕 refused. Then a comment of the app"
            + " account, which mentions you, tells the story of the run")
            .contains("When the verdict is in, a new comment mentions you");
    }

    @Test
    void theReadmesSayWhereTheStoryGoes() throws IOException {
        assertThat(collapsed("README.md")).contains("The checker's GitHub account answers: a comment of its own tells"
            + " how the run goes, and a new one mentions you when the verdict is ready.");
        assertThat(collapsed("README.ru.md")).contains("Отвечает аккаунт GitHub самого чекера: его комментарий"
            + " рассказывает, как идёт прогон, а новый упоминает тебя, когда вердикт готов.");
    }

    private static String collapsed(String file) throws IOException {
        return Files.readString(Path.of(file), UTF_8).replaceAll("\\s+", " ");
    }
}
