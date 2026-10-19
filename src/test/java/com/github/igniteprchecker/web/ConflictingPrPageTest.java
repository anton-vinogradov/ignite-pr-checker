package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Run, Run at top and Run RunAll queued a new RunAll on a PR with merge conflicts, whose code has to change before it
 * can be merged. Now the page says why nothing was queued and offers Run anyway, which asks first and queues the same
 * RunAll with {@code force}. Here GitHub reports conflicts between PR 13575 and master.
 */
class ConflictingPrPageTest {
    private static final String CONFLICTS = "GitHub reports merge conflicts between this PR and its base branch."
        + " Resolve them and run RunAll again, or press Run anyway to queue RunAll on the PR as it is";

    private static final String SCENE = """
        const asked = [];
        page.run('window').confirm = q => { asked.push(q); return true; };
        page.route('/api/trigger', url => url.includes('force=true')
            ? { body: { triggered: [{ buildId: 501, state: 'queued', name: 'RunAll', btId: 'RunAll',
                webUrl: 'https://ci2.example/build/501', pct: -1, leftSec: -1, startSec: -1, waitedSec: -1,
                elapsedSec: -1 }], replaced: 0 } }
            : { status: 409, body: { error: '%s', conflicts: true } }, 'POST');
        const shown = () => !page.el('statusForce').classList.contains('hidden');
        const posted = () => page.fetches('/api/trigger').map(f => f.url);
        await page.load('?pr=13575');
        """.formatted(CONFLICTS);

    @Test
    void runSaysWhyNothingWasQueuedAndOffersRunAnyway() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SCENE + """
            await page.run('doTrigger')('trigger', 'false', page.el('run'));
            report({ status: page.el('status').textContent, shown: shown(), posted: posted() });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Not queued: " + CONFLICTS);
        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("posted").toString()).isEqualTo("[\"/api/trigger?pr=13575&top=false\"]");
    }

    @Test
    void runAnywayAsksAndQueuesTheSameRunAllWithForce() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SCENE + """
            await page.run('doTrigger')('trigger', 'true', page.el('runTop'));
            page.el('statusForce').onclick();
            await page.settle();
            report({ question: asked[1], status: page.el('status').textContent, shown: shown(), posted: posted() });
            """);

        assertThat(out.get("question").asText()).startsWith("GitHub reports merge conflicts in this PR. Queue RunAll"
            + " for PR #13575 at the top of the ci2 queue, ahead of everyone else's builds?");
        assertThat(out.get("posted").get(1).asText()).isEqualTo("/api/trigger?pr=13575&top=true&force=true");
        assertThat(out.get("status").asText()).isEqualTo("Queued 1 build: #501.");
        assertThat(out.get("shown").asBoolean()).isFalse();
    }

    @Test
    void runAnywayCancelledAtTheQuestionQueuesNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SCENE + """
            await page.run('doTrigger')('trigger', 'false', page.el('pendingRerun'));
            page.run('window').confirm = () => false;
            page.el('statusForce').onclick();
            await page.settle();
            report({ posted: posted() });
            """);

        assertThat(out.get("posted").size()).isEqualTo(1);
    }

    @Test
    void anotherErrorOffersNoRunAnyway() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SCENE + """
            page.route('/api/trigger',
                { status: 502, body: { error: 'TeamCity answered 500 — try again in a moment' } }, 'POST');
            await page.run('doTrigger')('trigger', 'false', page.el('run'));
            report({ shown: shown() });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }

    @Test
    void anotherPrHasNoRunAnyway() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SCENE + """
            await page.run('doTrigger')('trigger', 'false', page.el('run'));
            page.run('openPr')(13461, true);
            await page.settle();
            const hidden = !shown();
            page.el('statusForce').onclick();
            await page.settle();
            report({ hidden, posted: posted() });
            """);

        assertThat(out.get("hidden").asBoolean()).isTrue();
        assertThat(out.get("posted").size()).isEqualTo(1);
    }

    @Test
    void theDocsNameTheButtonAsThePageDoes() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8))
            .contains(">Run anyway</button>");
        assertThat(Files.readString(Path.of("docs/features.md"), UTF_8)).contains("**Run anyway**");
        assertThat(Files.readString(Path.of("docs/features.ru.md"), UTF_8)).contains("**Run anyway**");
    }
}
