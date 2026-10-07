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
 * The status page put its "service started" line at the browser's clock minus a whole-second uptime, so the error
 * about a snapshot that failed to load at startup landed under it, among the previous run's problems. The health
 * dot's tooltip listed the problems alone, so a dot red from a fresh error in the log explained itself with a yellow
 * warning. Runs the page's own functions.
 */
class StatusPageTest {
    private static final String[] FUNCTIONS = {"card", "esc", "agoShort", "logTime", "renderLog", "healthProblems",
        "healthTitle", "persistenceCard"};

    @Test
    void anErrorLoggedWhileStartingIsThisRunsNotThePreviousOnes() throws Exception {
        String html = run("""
            const start = Date.now() - 3700;
            const el = {};
            renderLog(el, { signedIn: true, startedAt: start, uptimeSeconds: 3, log: { recent: [
                { t: start + 251, level: 'ERROR', logger: 'CacheStore', message: 'could not read standing-visas.json' },
                { t: start - 3600000, level: 'WARN', logger: 'TcClient', message: 'TeamCity did not answer' },
            ] } });
            out(el.innerHTML);
            """).asText();

        assertThat(html.indexOf("could not read standing-visas.json")).isNotNegative()
            .isLessThan(html.indexOf("service started"));
        assertThat(html.indexOf("service started")).isLessThan(html.indexOf("TeamCity did not answer"));
    }

    @Test
    void theDotNamesTheErrorInTheLogNextToAWarning() throws Exception {
        String title = run("""
            const d = { health: 'error', logHealth: 'error', log: { lastErrorAt: Date.now() - 5 * 60000 },
                healthProblems: [{ level: 'warn', text: 'no TeamCity token in the pool' }] };
            out(healthTitle(d, healthProblems(d)));
            """).asText();

        assertThat(title).isEqualTo("no TeamCity token in the pool; error logged 5m ago");
    }

    @Test
    void anonymousViewersLearnThatSomethingIsWrongButNotWhat() throws Exception {
        JsonNode page = run("""
            const d = { health: 'error', logHealth: 'ok', log: {},
                healthProblems: [{ level: 'error', text: null }, { level: 'warn', text: null }] };
            const problems = healthProblems(d);
            out({ problems, title: healthTitle(d, problems),
                card: persistenceCard({ enabled: true, active: true, problems: 1, lastBackup: null }) });
            """);

        assertThat(page.get("problems").toString())
            .isEqualTo("[{\"level\":\"error\",\"text\":\"2 problems — log in on the main page to see them\"}]");
        assertThat(page.get("title").asText()).isEqualTo("2 problems — log in on the main page to see them");
        assertThat(page.get("card").asText()).contains("1 problem", "log in on the main page to see it");
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
