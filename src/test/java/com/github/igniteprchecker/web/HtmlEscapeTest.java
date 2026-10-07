package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A PR title is written by any GitHub user and lands inside {@code title="…"} on every logged-in page.
 * The pages' {@code esc()} left quotes as they were, so a title with a quote added its own attribute
 * (an {@code onmouseover}) and ran in a committer's session. Runs each page's real {@code esc()}.
 */
class HtmlEscapeTest {
    private static final String PAYLOAD = "x\" onmouseover=\"alert(1)\" ' <b>&";

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void escapesEverythingThatBreaksOutOfAnAttribute(String page) throws Exception {
        String out = runEsc(escSource(page), PAYLOAD);

        assertThat(out).isEqualTo("x&quot; onmouseover=&quot;alert(1)&quot; &#39; &lt;b&gt;&amp;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "flaky.html", "status.html"})
    void rendersMissingValuesAsEmpty(String page) throws Exception {
        assertThat(runEsc(escSource(page), null)).isEmpty();
    }

    /** Build links and revisions from ci2 land in the same attributes on the PR page, next to ones already escaped. */
    @Test
    void ciValuesStayInsideTheirAttributesOnThePrPage() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const q = '" onmouseover="alert(1)';
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'running', name: 'RunAll', btId: 'RunAll',
                webUrl: 'https://ci2.example/build/9100' + q, pct: 40, leftSec: 3000, startSec: -1, waitedSec: 60,
                elapsedSec: 600, onHead: false, rev: 'abc123' + q }] });
            page.route('/api/rerun-suites', { body: { triggered: [{ buildId: 9200,
                webUrl: 'https://ci2.example/build/9200' + q }] } }, 'POST');
            page.route('/api/rerun-suite', { body: { triggered: [{ buildId: 9201,
                webUrl: 'https://ci2.example/build/9201' + q }] } }, 'POST');
            const chip = page.el('chip');
            chip.dataset.btid = 'RunAll';
            page.run('document').querySelectorAll = sel => sel === '.suite-live' ? [chip] : [];
            await page.load('?pr=13575');
            const runs = page.el('runs').innerHTML;
            await page.run('rerunSuites')(['IgniteTests24Java8_Cache'], false, page.el('rerunBtn'));
            const suites = page.el('status').innerHTML;
            await page.run('rerunSuite')('IgniteTests24Java8_Cache', false, page.el('rerunBtn'));
            report({ runs, chip: chip.innerHTML, suites, suite: page.el('status').innerHTML });
            """);

        for (String part : new String[] {"runs", "chip", "suites", "suite"}) {
            assertThat(out.get(part).asText()).as(part)
                .contains("&quot; onmouseover=&quot;alert(1)")
                .doesNotContain("\" onmouseover");
        }
        assertThat(out.get("runs").asText()).contains("Started on abc123&quot; onmouseover");
    }

    @Test
    void everyResponseCarriesTheHardeningHeaders() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), res, new MockFilterChain());

        assertThat(res.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    private static String escSource(String page) throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static", page));
        Matcher m = Pattern.compile("function esc\\(s\\) \\{.*?\\n\\s*}\\n", Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as("esc() in " + page).isTrue();
        return m.group();
    }

    private static String runEsc(String escFn, String arg) throws Exception {
        String js = escFn + "process.stdout.write(esc(" + (arg == null ? "null" : jsString(arg)) + "));";
        Process node;
        try {
            node = new ProcessBuilder("node", "-e", js).redirectErrorStream(true).start();
        }
        catch (IOException e) {
            assumeTrue(false, "node is not installed");
            throw e;
        }
        assertThat(node.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.exitValue()).as(out).isZero();
        return out;
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
