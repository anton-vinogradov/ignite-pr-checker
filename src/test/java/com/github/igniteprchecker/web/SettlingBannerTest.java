package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * PR 13335's page showed its blockers for 8 minutes after the chain finished, and 3 minutes between two waves of
 * re-runs, with nothing running, while the PR comment said the re-runs were in progress: the page knew nothing of the
 * waves. It now says when the verdict on screen is still being settled, and stops saying it once it is final.
 */
class SettlingBannerTest {
    @Test
    void aVerdictReRunsStillSettleSaysItIsInterim() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 2, of: 2, what: '3 blocker suite(s)',
                etaEpochSec: Math.floor(page.now() / 1000) + 25 * 60 } });
            await page.load('?pr=13575');
            const settling = { shown: !page.el('settlingRow').classList.contains('hidden'),
                text: page.el('settling').textContent, asked: page.fetches('/api/settling?pr=13575&build=9001').length };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(90000);
            report({ settling, finalShown: !page.el('settlingRow').classList.contains('hidden') });
            """);

        assertThat(out.get("settling").get("shown").asBoolean()).isTrue();
        assertThat(out.get("settling").get("text").asText()).isEqualTo("♻️ Auto re-run #2 of up to 2 in progress — 3"
            + " blocker suite(s) re-queued, settled in ~25m. The verdict below is interim: it is redone when they finish.");
        assertThat(out.get("settling").get("asked").asInt()).isPositive();
        assertThat(out.get("finalShown").asBoolean()).isFalse();
    }

    @Test
    void theDecisionOnReRunsIsSaidToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent });
            """);

        assertThat(out.get("text").asText())
            .isEqualTo("♻️ Deciding on auto re-runs of this run's failed suites — the verdict below may still change.");
    }

    @Test
    void aFinalVerdictShowsNoBanner() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ shown: !page.el('settlingRow').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }
}
