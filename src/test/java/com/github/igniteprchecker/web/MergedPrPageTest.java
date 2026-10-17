package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A merged PR's page was recomputed against a master history that by then held the PR's own failures, and called
 * them "pre-existing". It now shows the verdict the PR had at the merge, says so, and says that new runs do not change
 * it.
 */
class MergedPrPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

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
            + "merge. It is not recomputed: master's history now holds this PR's own runs. New runs do not change this "
            + "verdict.");
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

    /**
     * PR 13692 merged at a commit two later than its last RunAll: the page said "⚠ The verdict is for older code" and
     * offered Run RunAll, the hint over "verdict computed" promised a ↻ that a merged PR does not have, and the blockers
     * on one run asked for re-runs, which change nothing in a verdict kept at the merge.
     */
    @Test
    void aMergedPrOffersNothingThatWouldNotChangeItsVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/pending', { body: { pending: true, ahead: 2, builtSha: 'ac9f829', headSha: '5ae5733',
                rewritten: false } });
            page.route('/api/analyze', { body: verdict({ mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13575');
            report({ pending: page.el('pending').textContent, row: !page.el('pendingRow').classList.contains('hidden'),
                button: !page.el('pendingRerun').classList.contains('hidden'), fresh: page.el('freshText').innerHTML,
                merged: page.el('blockersCard').classList.contains('merged') });
            """);

        assertThat(out.get("row").asBoolean()).isTrue();
        assertThat(out.get("pending").asText())
            .isEqualTo("This run tested ac9f829. The PR was merged at its commit 5ae5733, 2 commits later.");
        assertThat(out.get("button").asBoolean()).as("Run RunAll").isFalse();
        assertThat(out.get("fresh").asText()).contains("title=\"When the checker last computed this verdict from the run "
            + "and master's history.\">verdict computed").doesNotContain("↻");
        assertThat(out.get("merged").asBoolean()).isTrue();
        assertThat(Files.readString(PAGE, UTF_8)).contains("#blockersCard.merged .rerun-advice { display: none; }")
            .contains("each of these failed its only run on this branch. <span class=\"rerun-advice\">Rerun");
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
