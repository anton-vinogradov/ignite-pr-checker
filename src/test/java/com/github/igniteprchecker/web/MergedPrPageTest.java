package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A merged PR's page was recomputed against a master history that by then held the PR's own failures, and called
 * them "pre-existing". It now shows the verdict the PR had at the merge, and says so.
 */
class MergedPrPageTest {
    @Test
    void aMergedPrSaysItsVerdictIsTheOneAtTheMerge() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13575');
            report({ note: page.el('mergedNote').textContent,
                shown: !page.el('mergedNote').classList.contains('hidden'),
                refresh: !page.el('refreshBtn').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("note").asText()).isEqualTo("Merged 6 Oct 12:00. This is the verdict as it stood at the "
            + "merge. It is not recomputed: master's history now holds this PR's own runs.");
        assertThat(out.get("refresh").asBoolean()).isFalse();
    }

    /** The page redraws the run's age every half minute; that brought ↻ back on a merged PR. */
    @Test
    void aMergedPrOffersNoRefreshLaterEither() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13575');
            await page.tick(31000);
            report({ refresh: !page.el('refreshBtn').classList.contains('hidden') });
            """);

        assertThat(out.get("refresh").asBoolean()).isFalse();
    }

    @Test
    void anOpenPrHasNoSuchNote() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            report({ shown: !page.el('mergedNote').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }
}
