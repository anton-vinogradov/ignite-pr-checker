package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A PR title is written by any GitHub user and lands inside {@code title="…"} on every logged-in page.
 * The pages' {@code esc()} left quotes as they were, so a title with a quote added its own attribute
 * (an {@code onmouseover}) and ran in a committer's session. Runs the {@code esc()} all three pages share.
 */
class HtmlEscapeTest {
    private static final String PAYLOAD = "x\" onmouseover=\"alert(1)\" ' <b>&";

    @Test
    void escapesEverythingThatBreaksOutOfAnAttribute() throws Exception {
        String out = PageFunctions.run("common.js", "out(esc(" + jsString(PAYLOAD) + "));").asText();

        assertThat(out).isEqualTo("x&quot; onmouseover=&quot;alert(1)&quot; &#39; &lt;b&gt;&amp;");
    }

    @Test
    void rendersMissingValuesAsEmpty() throws Exception {
        assertThat(PageFunctions.run("common.js", "out(esc(null));").asText()).isEmpty();
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

        assertThat(res.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'")
            .contains("script-src 'self'").doesNotContain("unsafe");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
