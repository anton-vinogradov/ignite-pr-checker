package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A merged PR's page was recomputed against a master history that by then held the PR's own failures, and called
 * them "pre-existing". It now shows the verdict the PR had at the merge, tags the PR Merged, and offers nothing that
 * would change that verdict.
 */
class MergedPrPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /**
     * The owner, 20 Oct: the note over a merged PR's verdict ("Merged 6 Oct 12:00. This is the verdict as it stood at
     * the merge. It is not recomputed: master's history now holds this PR's own runs…") told more about the merge than
     * a reader needs. The tag Merged beside the number says when; the glossary behind its ? says how the verdict is
     * kept.
     */
    @Test
    void aMergedPrIsTaggedInItsHeadWithNoNoteOverTheVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            page.route('/api/analyze', { body: verdict({ mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13575');
            report({ head: page.text('prTitle'), card: page.text('verdictCard'),
                refresh: !page.el('refreshBtn').classList.contains('hidden') });
            """);

        assertThat(out.get("head").asText()).isEqualTo("#13575 Merged ? IGNITE-29049 Move MDC to JUnit");
        assertThat(out.get("card").asText()).doesNotContain("Merged", "at the merge", "recomputed");
        assertThat(out.get("refresh").asBoolean()).isFalse();
        assertThat(Files.readString(PAGE, UTF_8)).doesNotContain("mergedNote", "merged-note");
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
     * PR 13692 merged at a commit 14 later than its last RunAll: the page said "⚠ The verdict is for older code" and
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
                merged: page.el('results').classList.contains('merged') });
            """);

        assertThat(out.get("row").asBoolean()).isTrue();
        assertThat(out.get("pending").asText())
            .isEqualTo("This run tested ac9f829. The PR was merged at its commit 5ae5733, 2 commits later.");
        assertThat(out.get("button").asBoolean()).as("Run RunAll").isFalse();
        assertThat(out.get("fresh").asText()).contains("title=\"When the checker last computed this verdict from the run "
            + "and master's history.\">verdict computed").doesNotContain("↻");
        assertThat(out.get("merged").asBoolean()).isTrue();
        assertThat(Files.readString(PAGE, UTF_8)).contains("#results.merged .rerun-advice { display: none; }")
            .contains("each of these failed its only run on this branch. <span class=\"rerun-advice\">Re-run");
    }

    /**
     * Re-runs do not change a verdict kept at the merge. So on a merged PR no line says that a re-run shows whether a
     * test is a blocker, or that it takes a suite off the list of suites that never ran.
     */
    @Test
    void aMergedPrsLinesDoNotPromiseThatARerunChangesTheVerdict() throws Exception {
        String scenario = """
            const watch = Object.assign(blocker('W.w', 'o9'), { blocker: false, watch: true });
            page.route('/api/analyze', { body: verdict({ MERGED blockers: [], watch: [watch], standing: 'WATCH',
                caveats: [], interrupted: true, canceledSuites: 2 }) });
            await page.load('?pr=13575');
            report({ lines: page.el('verdictLines').textContent, banner: page.el('interruptedText').textContent,
                merged: page.el('results').classList.contains('merged') });
            """;

        JsonNode merged = PageScript.run("index.html", PageScript.SIGNED_IN
            + scenario.replace("MERGED", "mergedAt: Math.floor(page.now() / 1000) - 86400,"));
        JsonNode open = PageScript.run("index.html", PageScript.SIGNED_IN + scenario.replace("MERGED", ""));

        assertThat(merged.get("lines").asText()).isEqualTo("1 test started failing on the code this run tested.");
        assertThat(merged.get("banner").asText()).contains("the verdict above says nothing about them.")
            .doesNotContain("re-run");
        assertThat(merged.get("merged").asBoolean()).isTrue();
        assertThat(open.get("lines").asText()).contains("Re-run their suites under “Recently started failing”");
        assertThat(open.get("banner").asText()).endsWith("A finished re-run takes a suite off this list.");
        assertThat(open.get("merged").asBoolean()).isFalse();
        assertThat(Files.readString(PAGE, UTF_8).replaceAll("\\s+", " "))
            .contains("to tell a break from a flake. <span class=\"rerun-advice\">Re-run their suites: if they keep "
                + "failing, they become blockers; if they pass, they drop out.</span>")
            .contains("<p id=\"brokenAdvice\" class=\"muted rerun-advice\">");
    }

    @Test
    void anOpenPrHasNoTag() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            report({ head: page.el('prTitle').innerHTML });
            """);

        assertThat(out.get("head").asText()).doesNotContain("class=\"tag\"", "Merged", "Closed");
    }
}
