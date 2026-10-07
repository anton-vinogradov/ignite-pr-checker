package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs page functions in plain node, with no DOM and no page around them: the whole of {@code static/common.js},
 * then the named top-level functions of one page's script. Skips the test where node is not installed.
 */
final class PageFunctions {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private PageFunctions() {
    }

    /** Runs {@code script} after the functions; whatever it passes to {@code out} comes back. */
    static JsonNode run(String pageScript, String script, String... functions) throws Exception {
        StringBuilder js = new StringBuilder(Files.readString(STATIC.resolve("common.js")));
        String page = functions.length == 0 ? "" : Files.readString(STATIC.resolve(pageScript));
        for (String name : functions) {
            Matcher m = Pattern.compile("\nfunction " + name + "\\(.*?\n}\n", Pattern.DOTALL).matcher(page);
            assertThat(m.find()).as(name + "() in " + pageScript).isTrue();
            js.append(m.group());
        }
        js.append("const out = v => process.stdout.write(JSON.stringify(v));\n").append(script);

        Process node;
        try {
            node = new ProcessBuilder("node", "-e", js.toString()).redirectErrorStream(true).start();
        }
        catch (IOException e) {
            assumeTrue(false, "node is not installed");
            throw e;
        }
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(node.exitValue()).as(out).isZero();

        return new ObjectMapper().readTree(out);
    }
}
