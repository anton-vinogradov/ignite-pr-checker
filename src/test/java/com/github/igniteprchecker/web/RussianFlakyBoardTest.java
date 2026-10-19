package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A user who chose Russian in ⚙ read the flaky board in English. The board now asks the server which language its
 * signed-in reader chose and reads in Russian: the groups, the columns, the rows and their tooltips, the run chips and
 * the failure panel, with the Russian forms of the words after numbers. The AI prompt stays English: it is written for
 * a coding assistant. A reader without a login reads the board in English, as before.
 */
class RussianFlakyBoardTest {
    /** The name of the project, which the scenario's tooltips and prompt name. */
    private static final List<String> NAMES = List.of("Apache Ignite");

    private static final String BOARD = """
        const copied = [];
        page.run('navigator').clipboard.writeText = async text => { copied.push(text); };
        page.run('document.title = "Ignite PR Checker — Flaky tests"');
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite' } });
        page.route('/api/reruns', { body: [{ buildTypeId: 'IgniteTests24Java8_Cache', pr: 13655,
            webUrl: 'https://ci2.example/build/9500', state: 'running', pct: 40, leftSec: 1080, startSec: -1,
            waitedSec: -1, elapsedSec: 2700 }] });
        page.route('/api/test-details', { status: 401, body: {} });
        const nearReader = { testId: '103', name: 'org.apache.ignite.CacheNearReaderUpdateTest.testUpdate',
            suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache', suiteBuildId: 9388970, occurrenceId: 'occ-1',
            masterFails: 100, masterRuns: 100, failStreak: 100, broken: true, prCount: 3, prs: [13583, 13655, 13660],
            masterFailures: [{ btId: 'IgniteTests24Java8_Cache', buildId: 9380300, occ: 'occ-m' },
                { btId: 'IgniteTests24Java8_Cache', buildId: 9380100, occ: 'occ-m2' }] };
        const rebalance = { testId: '102', name: 'org.apache.ignite.RebalanceTest.testRebalance',
            suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache', suiteBuildId: 9388970, occurrenceId: 'occ-2',
            masterFails: 3, masterRuns: 100, failStreak: 0, broken: false, prCount: 1, prs: [13655],
            masterFailures: [] };
        const bare = Object.assign({}, rebalance, { testId: '105', name: 'org.apache.ignite.BareTest.testIt',
            occurrenceId: '', prCount: 0, prs: [], masterFails: 21, masterRuns: 21 });
        page.route('/api/top-flaky', { body: { tracked: 5, muted: 2, prDays: 14, flakyCount: 41, brokenCount: 1,
            tests: [rebalance, bare, nearReader] } });
        const slot = page.el('slot');
        slot.dataset.suite = 'IgniteTests24Java8_Cache';
        page.el('flaky').querySelectorAll = sel => sel === '.lb-live' ? [slot] : [];
        const btn = page.el('why'), row = page.el('row'), box = page.el('box'), pre = page.el('pre');
        const copyBtn = page.el('copy');
        btn.closest = () => row;
        row.querySelector = () => box;
        box.classList.add('hidden');
        box.querySelector = sel => sel === 'pre' ? pre : copyBtn;
        """;

    private static String russian() throws Exception {
        return "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            """;
    }

    @Test
    void theBoardReadsInRussian() throws Exception {
        JsonNode out = PageScript.run("flaky.html", russian() + BOARD + """
            await page.load('');
            await page.run('toggleDetails')(btn, nearReader);
            const panel = { label: btn.textContent, title: btn.title, message: pre.textContent };
            const attrs = ['title', 'aria-label', 'placeholder'];
            const said = page.staticElements(e => attrs.some(a => e.getAttribute(a) !== null))
                .flatMap(e => attrs.map(a => e.getAttribute(a)).filter(v => v !== null));
            report({ texts: Object.assign({ page: page.text(page.run('document.body')), chip: slot.textContent,
                    tab: page.run('document.title') }, panel,
                Object.fromEntries(said.map((v, i) => ['attribute ' + i, v]))),
                html: page.el('flaky').innerHTML + slot.innerHTML, board: page.el('flaky').textContent,
                intro: page.el('intro').textContent,
                docs: page.staticElements(e => /\\/docs\\//.test(e.getAttribute('href') || ''))
                    .map(e => e.getAttribute('href')) });
            """);

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(), NAMES)).isEmpty();
        assertThat(out.get("board").asText()).containsSubsequence("Флаки на master (2 из 41)", "Падения на master",
            "PR за 14 дней", "RebalanceTest.testRebalance", "3%3 из 100 прогонов", "1",
            "BareTest.testIt", "100%21 из 21 прогона", "—нет PR за 14 дней",
            "Сломаны на master (1)", "CacheNearReaderUpdateTest.testUpdate", "100 подрядиз 100 прогонов", "3");
        assertThat(out.get("html").asText())
            .contains("title=\"Этот тест упал в 3 из 100 последних прогонов master.\"",
                "title=\"За последние 14 дней этот тест падал в этих PR: #13583, #13655, #13660.\"",
                "<p>Более ранние упавшие прогоны master: <a ")
            .contains(">#13655 · идёт 45 мин · осталось ~18 мин</a>");
        assertThat(out.get("intro").asText()).isEqualTo("На этой доске — тесты, которые падают на master, поэтому "
            + "падают и в PR, которые их не ломали. 2 теста, замьюченных в TeamCity, не показаны.");
        JsonNode texts = out.get("texts");
        assertThat(texts.get("label").asText()).isEqualTo("падение ▾");
        assertThat(texts.get("message").asText()).startsWith("Войди на главной странице, чтобы увидеть сообщение");
        assertThat(texts.get("tab").asText()).isEqualTo("Ignite PR Checker — Флаки-тесты");
        assertThat(out.get("docs")).hasSize(1);
        assertThat(out.get("docs").get(0).asText()).endsWith("/docs/features.ru.md#доска-флаки-flakyhtml");
    }

    /** The prompt goes to a coding assistant in English; the button then reads its Russian name again. */
    @Test
    void theAiPromptStaysEnglish() throws Exception {
        JsonNode out = PageScript.run("flaky.html", russian() + BOARD + """
            await page.load('');
            await page.run('aiFlakyPrompt')(page.el('ai'), nearReader);
            const flashed = page.el('ai').textContent;
            await page.tick(2000);
            report({ prompt: copied[0], flashed, label: page.el('ai').textContent });
            """);

        assertThat(out.get("prompt").asText())
            .startsWith("Find the commit that broke a test on Apache Ignite master (Java) — it failed all 100 recent "
                + "master runs.")
            .contains("- Failed in 3 pull requests in the last 14 days")
            .doesNotContainPattern("\\p{IsCyrillic}");
        assertThat(out.get("flashed").asText()).isEqualTo("скопировано");
        assertThat(out.get("label").asText()).isEqualTo("копировать промпт для ИИ");
    }

    @Test
    void aReaderWithoutALoginReadsTheBoardInEnglish() throws Exception {
        JsonNode out = PageScript.run("flaky.html", BOARD + """
            page.route('/api/page-language', { status: 404, body: {} });
            await page.load('');
            report({ board: page.el('flaky').textContent, catalog: page.fetches('/ru.json').length,
                tab: page.run('document.title') });
            """);

        assertThat(out.get("board").asText()).containsSubsequence("Flaky on master (2 of 41)", "Master fail rate");
        assertThat(out.get("catalog").asInt()).isZero();
        assertThat(out.get("tab").asText()).isEqualTo("Ignite PR Checker — Flaky tests");
    }
}
