package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * One screen of the PR page said "ran 1 of master's 3 tests" under a broken suite and "ran 467 tests · master runs 861
 * — −46%" under Fewer tests than master. Next to "85 master runs" in the reasons, "master runs 861" reads as 861 runs.
 * Both cards use the broken suite's words.
 */
class ShortfallWordsPageTest {
    @Test
    void bothCardsSayTheShortfallInOneForm() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({
                brokenSuites: [{ suite: 'Cache1', suiteName: 'Cache 1', suiteBuildId: 9003,
                    problems: ['execution timeout'], tests: 1, baseline: 3 }],
                shrunkSuites: [{ suite: 'Pds2', suiteName: 'PDS 2', suiteBuildId: 9004, tests: 467, baseline: 861,
                    dropPct: 46 }] }) });
            await page.load('?pr=13575');
            report({ broken: page.el('brokenSuites').innerHTML, shrunk: page.el('shrunkSuites').innerHTML });
            """);

        assertThat(out.get("broken").asText()).contains("execution timeout — ran 1 of master&#39;s 3 tests");
        assertThat(out.get("shrunk").asText())
            .contains("<div class=\"reason\">ran <b>467</b> of master's <b>861</b> tests (<b>−46%</b>)</div>")
            .doesNotContain("master runs");
    }
}
