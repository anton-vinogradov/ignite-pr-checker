package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A user who chose Russian in ⚙ read the flaky board in English. The board now asks the server which language its
 * signed-in reader chose and reads in Russian: the summary, the sections, the columns, the rows and their tooltips,
 * the run chips and the panel of a row, with the Russian forms of the words after numbers. The AI prompt stays
 * English: it is written for a coding assistant. A reader without a login reads the board in English, as before.
 */
class RussianFlakyBoardTest {
    /** The name of the project, which the scenario's tooltips and prompt name. */
    private static final List<String> NAMES = List.of("Apache Ignite");

    /** A failure message from TeamCity, which the panel shows as the test wrote it. */
    private static final String TIMEOUT = "java.net.SocketTimeoutException: Read timed out";

    /** The board of {@link PageScript#MASTER_BOARD} with a re-run of its suite going and no login for the messages. */
    private static final String BOARD = PageScript.MASTER_BOARD + """
        page.run('document.title = "Ignite PR Checker — Master"');
        page.route('/api/reruns', { body: [{ buildTypeId: 'IgniteTests24Java8_Cache', pr: 13655,
            webUrl: 'https://ci2.example/build/9500', state: 'running', pct: 40, leftSec: 1080, startSec: -1,
            waitedSec: -1, elapsedSec: 2700 }] });
        page.route('/api/test-details', { status: 401, body: {} });
        const seldom = broke(105, 'SnapshotTest.testRestore', 'P'.repeat(40) + 'FF' + 'P'.repeat(46) + 'FFF', null);
        const young = always(109, 'YoungTest.testIt', 'F'.repeat(21), { history: history('F'.repeat(21), true) });
        const rising = test(130, 'RisingTest.testIt', 'F'.repeat(6) + 'P'.repeat(69) + 'F'.repeat(16) + 'P'.repeat(9));
        board([tx, seldom, rebalance, rising, nearReader, young], { flakyCount: 41 });
        const slot = page.el('slot');
        slot.dataset.suite = 'IgniteTests24Java8_Cache';
        page.el('flaky').querySelectorAll = sel => sel === '.lb-live' ? [slot] : [];
        """;

    private static String russian() throws Exception {
        return "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            """;
    }

    @Test
    void theBoardReadsInRussian() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            await page.load('');
            await press({ fold: 'always' });
            for (const t of [tx, rebalance, nearReader, young]) await press({ row: key(t) });
            const strip = control({ id: key(rebalance) });
            strip.classList.add('strip');
            strip.getBoundingClientRect = () => ({ left: 100, top: 300, width: 299 });
            strip.children = Array.from({ length: 100 }, (_, i) => page.el('bar' + i));
            await page.mouse('mousemove', strip, { clientX: 100 + 298 });
            const attrs = ['title', 'aria-label', 'placeholder'];
            const said = page.staticElements(e => attrs.some(a => e.getAttribute(a) !== null))
                .flatMap(e => attrs.map(a => e.getAttribute(a)).filter(v => v !== null));
            report({ texts: Object.assign({ page: page.text(page.run('document.body')), chip: slot.textContent,
                    tab: page.run('document.title'), tip: page.el('tip').textContent },
                Object.fromEntries(said.map((v, i) => ['attribute ' + i, v]))),
                html: page.el('flaky').innerHTML + slot.innerHTML, board: page.el('flaky').textContent,
                what: page.el('what').textContent, chips: page.el('chips').textContent,
                intro: page.el('intro').textContent,
                docs: page.staticElements(e => /\\/docs\\//.test(e.getAttribute('href') || ''))
                    .map(e => e.getAttribute('href')) });
            """);

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(), NAMES)).isEmpty();
        assertThat(out.get("what").asText())
            .isEqualTo("До 100 прогонов master на тест или сьюту · последний завершился 44\u00a0мин назад");
        assertThat(out.get("chips").asText()).isEqualTo("2 недавно сломаны0 проблемных сьют41 флаки2 падают всегда");
        assertThat(out.get("board").asText()).containsSubsequence("▾ Недавно сломаны 2 теста · 2 причины",
            "Раньше проходили, теперь падают в каждом прогоне. Найди коммит.", "Прогоны master, от старых к новым",
            "До поломки", "PR, 14\u00a0дн.", "С 7 октября в 00:16", "12 прогонов · 1 тест", "TxTest.testCommit",
            "Последний успешный запуск в истории: 6 октября в 23:16; первое падение после него: 7 октября в 00:16; "
                + "12 падений подряд. До этого тест упал в 0 из 88 прогонов.",
            "Упал в 2 PR за последние 14\u00a0дней: #13583 #13655",
            "С 7 октября в 09:16", "3 прогона · 1 тест", "SnapshotTest.testRestore", "упал в 2 из 88",
            "▾ Флаки 2 из 41", "Доля падений ▾", "Последнее падение",
            "RebalanceTest.testRebalance", "25%", "44\u00a0мин назад",
            "Этот тест упал в 25 из 100 последних прогонов master. Последнее падение:\u00a07 октября в 11:16. "
                + "Последние 25 прогонов:\u00a028%. До них:\u00a024%.",
            "RisingTest.testIt", "22%", "↑64%",
            "▾ Падают всегда 2", "Ни одного настоящего успеха за последние 100 прогонов. Почини или замьють.",
            "Последний успех", "CacheNearReaderUpdateTest.testUpdate", "ни одного успеха за 100 прогонов",
            "Ни одного успеха за последние 100 прогонов master: когда он сломался, неизвестно. Почини его или "
                + "замьють с тикетом.",
            "YoungTest.testIt", "ни разу не прошёл · новый тест, 21 прогон",
            "Master запускал его 21\u00a0раз, и он ни разу не прошёл.");
        assertThat(out.get("html").asText())
            .contains("title=\"Этот тест упал в 25 из 100 последних прогонов master.\"",
                "title=\"За последние 14 дней этот тест падал в этих PR: #13583, #13655.\"",
                "title=\"Последний успешный прогон:\u00a06 октября в 23:16.\"",
                "title=\"Последние 25 прогонов:\u00a064%. До них:\u00a08%.\"",
                "<span class=\"num\" data-label=\"PR, 14\u00a0дн.\">",
                ">последний упавший прогон master</a>")
            .contains(">#13655 · идёт 45\u00a0мин · осталось ~18\u00a0мин</a>");
        assertThat(out.get("intro").asText()).isEqualTo("2 теста, замьюченных в TeamCity, не показаны.");
        JsonNode texts = out.get("texts");
        assertThat(SplitUnits.in(out.get("html").asText())).isEmpty();
        assertThat(texts.get("tip").asText()).isEqualTo("7 октября в 11:16 · упал · клик открывает сборку №9000");
        assertThat(out.get("board").asText()).contains("Войди на главной странице, чтобы увидеть сообщение");
        assertThat(texts.get("page").asText()).contains("прошёл", "упал", "Мастер").doesNotContain("Flaky tests");
        assertThat(texts.get("tab").asText()).isEqualTo("Ignite PR Checker — Мастер");
        assertThat(out.get("docs")).hasSize(1);
        assertThat(out.get("docs").get(0).asText()).endsWith("/docs/features.ru.md#доска-master-masterhtml");
    }

    /**
     * The line of a cause about its commits reads in Russian, with the Russian form of the word after each number. The
     * titles of the commits stay as their authors wrote them.
     */
    @Test
    void theCommitsOfACauseReadInRussian() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + PageScript.MASTER_CAUSES + """
            await page.load('');
            await press({ commits: 'commit:f93b6d2' });
            report({ texts: { board: page.el('flaky').textContent }, html: page.el('flaky').innerHTML });
            """);

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(), NAMES,
            Pattern.compile("IGNITE-\\d+ [^()]+ \\(#\\d+\\)"))).isEmpty();
        assertThat(out.get("texts").get("board").asText()).containsSubsequence(
            "1 коммит в диапазоне: c27a40eIGNITE-29314 Enforce the lock wait timeout (#13853)",
            "3 коммита в диапазоне: сравнить на GitHub ↗4f1c9aeIGNITE-29303 Change 3 (#13803)",
            "4 коммита в диапазоне: сравнить на GitHub ↗ показать",
            "25 коммитов в диапазоне: сравнить на GitHub ↗f93b6d2IGNITE-29320 Change 20 (#13820)", "и ещё 5",
            "В диапазоне нет коммитов: последний успешный прогон и первое падение шли на одном коммите.",
            "Коммиты в диапазоне: сравнить на GitHub ↗");
        assertThat(out.get("html").asText())
            .contains("title=\"Коммиты после последнего успешного прогона до первого упавшего включительно.\"");
    }

    /**
     * The problem suites read in Russian: the chip, the section, the tags and what a row and its panel say. A kind of
     * problem is named as the Russian PR page names it for a broken suite, in a tag's tooltip, in the panel and over a
     * bar.
     */
    @Test
    void theProblemSuitesReadInRussian() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            const cpp = suite('PlatformCppWin', 'Platform C++ CMake', 'P'.repeat(96) + 'XXXX',
                { prCount: 2, prs: [13583, 13655] });
            const snapshots = suite('Snapshots2', 'Snapshots 2', 'PPO');
            const queries = suite('Queries3', 'Queries 3', 'PPPTPPXPPP' + 'P'.repeat(30) + 'T' + 'P'.repeat(9));
            board([rebalance], { suites: [queries, snapshots, cpp] });
            await page.load('');
            for (const s of [cpp, snapshots, queries]) await press({ row: key(s) });
            const strip = control({ id: key(queries) });
            strip.classList.add('strip');
            strip.getBoundingClientRect = () => ({ left: 100, top: 300, width: 299 });
            strip.children = Array.from({ length: 100 }, (_, i) => page.el('bar' + i));
            await page.mouse('mousemove', strip, { clientX: 371 });
            const problem = page.el('tip').textContent;
            await page.mouse('mousemove', strip, { clientX: 398 });
            report({ texts: { board: page.el('flaky').textContent, chips: page.el('chips').textContent, problem,
                reported: page.el('tip').textContent }, html: page.el('flaky').innerHTML });
            """);

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(),
            List.of("Apache Ignite", "Platform C++ CMake", "Queries 3", "Snapshots 2"))).isEmpty();
        JsonNode texts = out.get("texts");
        assertThat(texts.get("chips").asText())
            .isEqualTo("0 недавно сломано3 проблемные сьюты, 2 падают сейчас1 флаки0 падают всегда");
        assertThat(texts.get("board").asText()).containsSubsequence("▾ Проблемные сьюты 3",
            "Завершаются без вердикта по тестам: таймаут, падение, нехватка памяти, код выхода, ошибка компиляции. Их "
                + "тесты остаются без результата.", "Проблемные прогоны", "Последняя проблема", "PR, 14\u00a0дн.",
            "Platform C++ CMake", "код выхода", "4%", "сейчас", "4 подряд",
            "Падает сейчас: последние 4 прогона master подряд, с 7 октября в 08:16. Последний хороший "
                + "прогон:\u00a07 октября в 07:16.",
            "Была битой сьютой в 2 PR за последние 14\u00a0дней: #13583 #13655",
            "Прогон этой сьюты в PR может завершиться так же: тогда она показана как битая сьюта, а не как блокер.",
            "последний проблемный прогон",
            "Snapshots 2", "падение / нехватка памяти", "1 подряд",
            "Падает сейчас: её последний прогон master, 7 октября в 11:16.",
            "Queries 3", "таймаут ×2", "код выхода ×1", "10\u00a0ч назад",
            "Последняя проблема:\u00a07 октября в 02:16. 3 из 50 прогонов master завершились без вердикта по тестам.",
            "Таймаут выполнения: 2 · Ненулевой код выхода: 1", "▾ Флаки 1");
        assertThat(out.get("html").asText()).contains("title=\"Ненулевой код выхода: 4 из 100 прогонов master.\"",
            "title=\"Падение JVM / нехватка памяти: 1 из 3 прогонов master.\"",
            "title=\"Эта сьюта завершилась без вердикта по тестам в 3 из 50 последних прогонов master.\"",
            "title=\"В скольких PR сьюта была битой за последние 14\u00a0дней.\"",
            "title=\"За последние 14 дней эта сьюта была битой в этих PR: #13583, #13655.\"",
            "title=\"За последние 14 дней эта сьюта не была битой ни в одном PR.\"",
            "title=\"Открыть последний проблемный прогон в TeamCity.\"");
        assertThat(texts.get("problem").asText())
            .isEqualTo("7 октября в 02:16 · таймаут выполнения · клик открывает сборку №8991");
        assertThat(texts.get("reported").asText())
            .isEqualTo("7 октября в 11:16 · отчиталась о тестах · клик открывает сборку №9000");
    }

    /** A board with nothing newly broken, nothing found by the filter and nothing at all says so in Russian. */
    @Test
    void theEmptySectionsReadInRussian() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            board([rebalance]);
            await page.load('');
            await press({ fold: 'always' });
            const none = page.el('flaky').textContent;
            page.el('q').value = 'zzz';
            page.el('q').listeners.input[0]({ target: page.el('q') });
            await page.settle();
            report({ texts: { none, filtered: page.el('flaky').textContent }, html: page.el('flaky').innerHTML });
            """);

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(), NAMES)).isEmpty();
        assertThat(out.get("texts").get("none").asText()).contains("▾ Недавно сломаны 0",
            "Среди уже проверенных тестов таких нет.",
            "Каждая сьюта отчиталась о тестах в последних 100 прогонах master.",
            "Нет тестов, которые падают в каждом прогоне.");
        assertThat(out.get("texts").get("filtered").asText()).contains("Фильтру ничего не соответствует.");
    }

    /**
     * A test broken on master failed with a timeout. The panel said in Russian that a re-run may not help, but named
     * the runs it failed in English: "упал all 100 recent master runs". It names them in Russian now, and the failure
     * message, which stays as the test wrote it, is marked as English for a screen reader.
     */
    @Test
    void anEnvironmentFailureNamesTheFailedRunsInRussian() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            page.route('/api/test-details', { body: { details: '%s', kind: 'environment' } });
            const streak = always(104, 'TxTest.testRollback', 'PP' + 'FP'.repeat(43) + 'F'.repeat(12),
                { masterFails: 40 });
            board([nearReader, streak]);
            await page.load('');
            await press({ fold: 'always' });
            await press({ row: key(nearReader) });
            await press({ row: key(streak) });
            const shown = [...page.el('flaky').innerHTML.matchAll(/<pre>([^<]*)(<span[^>]*>[^<]*<\\/span>)/g)];
            report({ texts: { all: shown[0][1], last: shown[1][1] }, html: shown[0][2] + shown[1][2] });
            """.formatted(TIMEOUT));

        assertThat(EnglishLeft.in(out.get("texts"), out.get("html").asText(), NAMES, Pattern.compile(TIMEOUT)))
            .isEmpty();
        assertThat(out.get("texts").get("all").asText()).isEqualTo("окружение/время — но тест упал во всех 100 "
            + "последних прогонах master, так что один перезапуск вряд ли пройдёт\n\n");
        assertThat(out.get("texts").get("last").asText()).startsWith("окружение/время — но тест упал в 12 последних "
            + "прогонах master, так что");
        assertThat(out.get("html").asText()).contains("<span lang=\"en\">" + TIMEOUT + "</span>");
    }

    /** The list may come before the catalog: the board waits for the language, or its English would stay. */
    @Test
    void theBoardWaitsForTheLanguage() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            const ticks = async n => { for (let i = 0; i < n; i++) await new Promise(r => setImmediate(r)); };
            page.route('/ru.json', async () => { await ticks(20); return { body: CATALOG }; });
            const el = page.el('flaky'), drawn = [];
            const write = Object.getOwnPropertyDescriptor(el, 'innerHTML');
            Object.defineProperty(el, 'innerHTML', { get: write.get,
                set(v) { drawn.push(String(v)); write.set.call(this, v); } });
            await page.load('');
            await page.tick(15000);
            report({ drawn });
            """);

        assertThat(out.get("drawn")).isNotEmpty().allSatisfy(html -> assertThat(html.asText())
            .contains("Недавно сломаны").doesNotContain("Newly broken", "Always failing"));
    }

    /** The prompt goes to a coding assistant in English; the button then reads its Russian name again. */
    @Test
    void theAiPromptStaysEnglish() throws Exception {
        JsonNode out = PageScript.run("master.html", russian() + BOARD + """
            await page.load('');
            const ai = control({ ai: key(nearReader) });
            await page.mouse('click', ai);
            const flashed = ai.textContent;
            await page.tick(2000);
            report({ prompt: copied[0], flashed, label: ai.textContent });
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
        JsonNode out = PageScript.run("master.html", BOARD + """
            page.route('/api/page-language', { status: 404, body: {} });
            await page.load('');
            report({ board: page.el('flaky').textContent, catalog: page.fetches('/ru.json').length,
                tab: page.run('document.title') });
            """);

        assertThat(out.get("board").asText()).containsSubsequence("Newly broken 2 tests · 2 causes", "Flaky 2 of 41",
            "Fail rate");
        assertThat(out.get("catalog").asInt()).isZero();
        assertThat(out.get("tab").asText()).isEqualTo("Ignite PR Checker — Master");
    }
}
