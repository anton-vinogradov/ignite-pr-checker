package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * apache/ignite#13709 was merged on 20 Oct at 10:12 UTC at its commit 9b02561. Its page told so in a note over the
 * verdict, along with how the checker keeps a merged PR's verdict. The owner asked for a tag instead: Merged beside the
 * PR's number, whose title says when and at which commit, and Closed for a PR closed without a merge. How the verdict
 * is kept is in the glossary behind the ? next to Merged. The same for a PR of the Python thin client.
 */
class MergedTagPageTest {
    private static final String TAG = """
        const HEAD = '9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a';
        const MERGED_AT = 1792491159;
        const MERGED = { title: 'IGNITE-29120 Fix control.sh', state: 'merged', closedAt: MERGED_AT, head: HEAD };
        const CU5 = { suite: 'IgniteTests24Java8_ControlUtility5', suiteName: 'Control Utility 5', buildId: 9426316,
            revision: HEAD };
        const head = () => ({ text: page.text('prTitle'), html: page.el('prTitle').innerHTML,
            title: (/<span class="tag" title="([^"]*)">/.exec(page.el('prTitle').innerHTML) || [])[1] || null });
        """;

    private static final String HELP = "<a class=\"help\" href=\"https://github.com/anton-vinogradov/ignite-pr-checker"
        + "/blob/main/docs/features.md#runs-buttons-and-visas\" target=\"_self\" rel=\"noopener\" title=\"Opens the "
        + "verdict glossary on GitHub. It says how the checker keeps the verdict of a merged PR.\" aria-label=\"What "
        + "Merged means\">?</a>";

    @Test
    void aMergedPrIsTaggedMergedBesideItsNumber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + """
            page.route('/api/pr-title', { body: MERGED });
            page.route('/api/analyze', { body: verdict({ prNumber: 13709, mergedAt: MERGED_AT }) });
            await page.load('?pr=13709');
            report(head());
            """);

        assertThat(out.get("text").asText()).isEqualTo("#13709 Merged ? IGNITE-29120 Fix control.sh");
        assertThat(out.get("title").asText()).isEqualTo("The PR was merged at its commit 9b02561 on 20 Oct at 10:12.");
        assertThat(out.get("html").asText()).contains(">#13709</a> <span><span class=\"tag\" title=\"").contains(HELP);
    }

    @Test
    void aPrClosedWithoutAMergeIsTaggedClosed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + """
            page.route('/api/pr-title', { body: { title: 'IGNITE-28800 Old work', state: 'closed', closedAt: MERGED_AT,
                head: 'def4567890abcdef' } });
            page.route('/api/analyze', { body: verdict({ prNumber: 13100 }) });
            await page.load('?pr=13100');
            report(head());
            """);

        assertThat(out.get("text").asText()).isEqualTo("#13100 Closed IGNITE-28800 Old work");
        assertThat(out.get("title").asText())
            .isEqualTo("The PR was closed without a merge at its commit def4567 on 20 Oct at 10:12.");
        assertThat(out.get("html").asText()).as("a closed PR's verdict is computed as an open one's")
            .doesNotContain("class=\"help\"");
    }

    /** The title and the verdict come in either order: the tag names the runs at the merge after both. */
    @Test
    void theTagNamesTheRunsAtTheMergeWhicheverAnswerComesFirst() throws Exception {
        String scenario = TAG + """
            let release;
            const held = new Promise(r => { release = r; });
            page.route('HELD_PATH', () => held.then(() => ({ body: HELD_BODY })));
            page.route('NOW_PATH', { body: NOW_BODY });
            await page.load('?pr=13709');
            const before = head().title;
            release();
            await page.settle();
            report(Object.assign(head(), { before }));
            """;
        String verdict = "verdict({ prNumber: 13709, mergedAt: MERGED_AT, runsAtMerge: [CU5] })";
        String titleLate = scenario.replace("HELD_PATH", "/api/pr-title").replace("HELD_BODY", "MERGED")
            .replace("NOW_PATH", "/api/analyze").replace("NOW_BODY", verdict);
        String verdictLate = scenario.replace("HELD_PATH", "/api/analyze").replace("HELD_BODY", verdict)
            .replace("NOW_PATH", "/api/pr-title").replace("NOW_BODY", "MERGED");

        String expected = "The PR was merged at its commit 9b02561 on 20 Oct at 10:12. After the merge, the checker "
            + "computed the verdict once more, adding runs queued before the merge: Control Utility 5 on 9b02561.";
        JsonNode late = PageScript.run("index.html", PageScript.SIGNED_IN + titleLate);
        assertThat(late.get("before").asText()).isEqualTo("The PR was merged on 20 Oct at 10:12. After the merge, the "
            + "checker computed the verdict once more, adding runs queued before the merge: Control Utility 5 on "
            + "9b02561.");
        assertThat(late.get("title").asText()).isEqualTo(expected);
        JsonNode first = PageScript.run("index.html", PageScript.SIGNED_IN + verdictLate);
        assertThat(first.get("before").asText())
            .isEqualTo("The PR was merged at its commit 9b02561 on 20 Oct at 10:12.");
        assertThat(first.get("title").asText()).isEqualTo(expected);
    }

    /** /api/config names the hosts after the title came: the head is drawn again, with its tag. */
    @Test
    void theTagStaysWhenTheHeadIsDrawnAgain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + """
            page.route('/api/pr-title', { body: MERGED });
            await page.load('?pr=13709');
            page.run('setPrTitle')(...page.run('shownTitle'));
            report(head());
            """);

        assertThat(out.get("title").asText()).isEqualTo("The PR was merged at its commit 9b02561 on 20 Oct at 10:12.");
    }

    @Test
    void anotherPrOpenedAfterAMergedOneHasNoTag() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + """
            page.route('/api/pr-title', url => ({ body: url.endsWith('=13709') ? MERGED
                : { title: 'IGNITE-28972 Check marshalled kind' } }));
            page.route('/api/analyze', { body: verdict({ prNumber: 13709, mergedAt: MERGED_AT }) });
            await page.load('?pr=13709');
            page.route('/api/analyze', { body: verdict({ prNumber: 13461 }) });
            page.run('openPr')(13461, true);
            const opened = head();
            await page.settle();
            report(Object.assign(head(), { opened: opened.text }));
            """);

        assertThat(out.get("opened").asText()).as("before 13461's answers come").isEqualTo("#13461");
        assertThat(out.get("text").asText()).isEqualTo("#13461 IGNITE-28972 Check marshalled kind");
        assertThat(out.get("title").isNull()).isTrue();
    }

    @Test
    void aMergedPythonClientPrIsTaggedToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + """
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
                starCount: 1, refreshAfterSeconds: 120, clientRepo: 'apache/ignite-python-thin-client',
                clientSuite: 'Thin client: Python' } });
            page.route('/api/client/prs', { body: [] });
            page.route('/api/client/runs', { body: [] });
            page.route('/api/client/pr-title', { body: { title: 'IGNITE-29001 Support Python 3.13', state: 'merged',
                closedAt: MERGED_AT, head: 'a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4' } });
            await page.load('?repo=python&pr=68');
            report(Object.assign(head(), { asked: page.fetches('/api/pr-title').length }));
            """);

        assertThat(out.get("text").asText()).isEqualTo("Python client #68 Merged ? IGNITE-29001 Support Python 3.13");
        assertThat(out.get("title").asText()).isEqualTo("The PR was merged at its commit a1b2c3d on 20 Oct at 10:12.");
        assertThat(out.get("asked").asInt()).as("Ignite's PR #68 is not asked about").isZero();
    }

    @Test
    void theRussianPageSaysItInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + TAG + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            page.route('/api/pr-title', { body: MERGED });
            page.route('/api/analyze', { body: verdict({ prNumber: 13709, mergedAt: MERGED_AT,
                runsAtMergeDue: true }) });
            await page.load('?pr=13709');
            report(head());
            """);

        assertThat(out.get("text").asText()).isEqualTo("#13709 Смержен ? IGNITE-29120 Fix control.sh");
        assertThat(out.get("title").asText()).isEqualTo("PR смержили 20 октября в 10:12 на его коммите 9b02561. "
            + "Вердикт ещё может измениться один раз: чекер ищет прогоны, поставленные до мержа, которые вердикт не "
            + "учёл.");
        assertThat(out.get("html").asText()).contains("href=\"https://github.com/anton-vinogradov/ignite-pr-checker/"
            + "blob/main/docs/features.ru.md#прогоны-кнопки-и-визы\"", "aria-label=\"Что значит метка «Смержен»\"");
    }
}
