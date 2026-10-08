package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The PR page named one pair of actions three ways: "Rerun" and "Rerun top" started a RunAll the PR may never have
 * had, "Re-run RunAll" sat next to them, and the suites had "Rerun" and "Rerun top" again. Now "Run" starts a new
 * RunAll chain, "Rerun" repeats suites that ran, and the twin that jumps the queue says "at top".
 */
class ButtonNamesTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern QUEUE_BUTTON =
        Pattern.compile("<button\\b([^>]*)\\bdata-top=\"(true|false)\"[^>]*>([^<]*)</button>");

    @Test
    void everyQueueButtonIsRunOrRerunAndItsTwinSaysAtTop() throws IOException {
        for (String file : List.of("index.html", "index.js")) {
            Matcher b = QUEUE_BUTTON.matcher(Files.readString(STATIC.resolve(file), UTF_8));
            int buttons = 0;
            while (b.find()) {
                buttons++;
                String label = b.group(3).replaceFirst(" \\(.*\\)$", "");
                boolean top = Boolean.parseBoolean(b.group(2));
                assertThat(label).as(file + ": " + b.group())
                    .isIn(top ? List.of("Run at top", "Rerun at top") : List.of("Run", "Rerun"));
                assertThat(label.startsWith("Run")).as(file + ": only a RunAll is run anew: " + b.group())
                    .isEqualTo(b.group(1).contains("data-act=\"trigger\""));
            }
            assertThat(buttons).as(file).isPositive();
        }
    }

    /** A 403 from ci2 answers Run, Rerun and Cancel my runs alike; it told whoever pressed Run "if Rerun or Cancel". */
    @Test
    void ci2RefusingSaysTheButtonsByTheirNames() throws IOException {
        String page = Files.readString(STATIC.resolve("index.html"), UTF_8);

        assertThat(ApiExceptionHandler.FORBIDDEN).contains("if Run, Rerun or Cancel my runs keeps failing");
        assertThat(page).contains(">Run</button>", ">Rerun</button>", ">Cancel my runs</button>");
    }

    @Test
    void theStaleVerdictOffersANewRunAll() throws IOException {
        String page = Files.readString(STATIC.resolve("index.html"), UTF_8);

        assertThat(page).contains("<button id=\"pendingRerun\" class=\"act\" type=\"button\">Run RunAll</button>")
            .doesNotContain("Re-run RunAll", "Rerun top");
    }
}
