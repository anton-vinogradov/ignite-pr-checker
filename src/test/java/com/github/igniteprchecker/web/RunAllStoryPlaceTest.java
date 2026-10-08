package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The PR commands caption in ⚙ and both READMEs said the /run-all comment itself then narrates the run. PrCommands
 * edits the command comment only while it holds the user's GitHub token; otherwise the app account tells the story in
 * a comment of its own. A user without a token looked for the story in the wrong place.
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
            .contains("a comment in the PR then narrates the run.")
            .contains("While the server holds my GitHub token (for the PR comment or the checkstyle autofix), the "
                + "reactions come from my own account and the story is written into my command comment; otherwise "
                + "the checker's own account reacts and tells the story in a comment of its own.");
    }

    @Test
    void theReadmesSayWhereTheStoryGoes() throws IOException {
        assertThat(collapsed("README.md")).contains("and a comment in the PR then tells how the run goes: your own "
            + "when the checker holds your GitHub token, else one of the app account.");
        assertThat(collapsed("README.ru.md")).contains("а потом комментарий в PR рассказывает, как идёт прогон: твой "
            + "собственный, если у чекера есть твой токен GitHub, иначе комментарий аккаунта приложения.");
    }

    private static String collapsed(String file) throws IOException {
        return Files.readString(Path.of(file), UTF_8).replaceAll("\\s+", " ");
    }
}
