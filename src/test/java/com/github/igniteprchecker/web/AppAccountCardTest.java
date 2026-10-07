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
import org.junit.jupiter.api.Test;

/**
 * Nothing told the operator whose GitHub account the checker writes as; on prod it was the owner's personal one,
 * which can push to apache/ignite. The status page names the account and flags one with write access.
 */
class AppAccountCardTest {
    private static final String[] FUNCTIONS = {"card", "esc", "appAccountCard"};

    @Test
    void theAccountIsNamed() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker-bot', canPush: false }));")
            .asText();

        assertThat(card).contains("App account", "@ignite-pr-checker-bot", "class=\"v ok\"");
    }

    @Test
    void anAccountThatCanPushIsFlagged() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'anton-vinogradov', canPush: true }));").asText();

        assertThat(card).contains("@anton-vinogradov", "can push to the repo", "class=\"v warn\"");
    }

    @Test
    void noTokenAndARefusedOneAreSaid() throws Exception {
        JsonNode cards = run("out([appAccountCard({ state: 'none' }), appAccountCard({ state: 'refused' })]);");

        assertThat(cards.get(0).asText()).contains("no GITHUB_TOKEN");
        assertThat(cards.get(1).asText()).contains("GitHub refuses GITHUB_TOKEN", "class=\"v bad\"");
    }

    /** Runs {@code script} after the page's functions; whatever it passes to {@code out} comes back. */
    private static JsonNode run(String script) throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/status.html"));
        StringBuilder js = new StringBuilder();
        for (String name : FUNCTIONS) {
            Matcher m = Pattern.compile("\n        function " + name + "\\(.*?\n        }\n", Pattern.DOTALL).matcher(html);
            assertThat(m.find()).as(name + "() in status.html").isTrue();
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
        assertThat(node.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.exitValue()).as(out).isZero();

        return new ObjectMapper().readTree(out);
    }
}
