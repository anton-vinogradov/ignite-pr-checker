package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A user who chose Russian in ⚙ read the status page in English: its cards, its problems, its log notes and the server's
 * texts on it. The page now asks the server which language its signed-in reader chose, and an operator and another
 * signed-in reader both read it in Russian: no English phrase is left but the log, which stays as the service wrote it,
 * and the names. A reader without a login reads it in English, as before.
 */
class RussianStatusPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The log lines of the scenario: the service's log stays English. */
    private static final List<String> LOG = List.of("PR 13655 skipped: TeamCity rejected the token of nsamelchev",
        "TeamCity answered 502 for the history of a test", "Bad request: missing suite");

    /** The names of GitHub scopes the checks of GITHUB_TOKEN list. */
    private static final List<String> SCOPES = List.of("repo, workflow");

    /** The parts of the page a script writes, and the tooltips in them. */
    private static final String WRITTEN = """
        const written = ['problems', 'systemCards', 'appCards', 'watcherCards', 'warmerCards', 'tcCards', 'ghCards',
            'jiraCards', 'logCards', 'tcByStatus', 'logList', 'configList', 'httpCats', 'tcCats', 'ghCatsApp',
            'ghCatsPat', 'jiraCats', 'usersActive', 'usersSeen', 'restartLast', 'flushLast', 'updated'];
        function seen() {
            const attrs = ['title', 'aria-label', 'placeholder'];
            const said = page.staticElements(e => attrs.some(a => e.getAttribute(a) !== null))
                .flatMap(e => attrs.map(a => e.getAttribute(a)).filter(v => v !== null));
            return { texts: Object.assign({ page: page.text(page.run('document.body')),
                    health: page.el('healthDot').title, tab: page.run('document.title') },
                Object.fromEntries(said.map((v, i) => ['attribute ' + i, v]))),
                html: written.map(id => page.el(id).innerHTML).join('\\n'),
                cards: Object.fromEntries(written.map(id => [id, page.el(id).textContent.replace(/\\u00a0/g, ' ')])) };
        }
        """;

    /** The status an operator gets of a service with something wrong in each part, as /api/status sends it. */
    private static String operatorsStatus() throws Exception {
        return "const PROBLEMS = " + JSON.writeValueAsString(StatusTexts.problems()) + ";\n"
            + "const CHECKS = " + JSON.writeValueAsString(StatusTexts.checks()) + ";\n"
            + "const SETTINGS = " + JSON.writeValueAsString(StatusTexts.SETTINGS) + ";\n"
            + "const LOG = " + JSON.writeValueAsString(LOG) + ";\n" + """
            page.el('tcStack').getContext = () => new Proxy({}, {
                get: (target, name) => name === 'measureText' ? () => ({ width: 30 }) : () => {} });
            page.run('document.title = "Ignite PR Checker — Status"');
            const MIN = 60000, DAY = 86400000;
            const hour = (total, fail) => ({ total, ok: total - fail, fail, avgLatencyMs: 420, maxLatencyMs: 2900 });
            const calls = (total, fail) => ({ total, fail, avgLatencyMs: 120 });
            const status = { version: '1.38.0', commit: 'abcdef1234567', dirty: true, signedIn: true, operator: true,
                operatorsNamed: true, uptimeSeconds: 200000, startedAt: page.now() - 60 * MIN, health: 'error',
                logHealth: 'error', healthProblems: PROBLEMS,
                teamcity: { lastHour: hour(1234, 34), sinceStart: 54321, byStatusHour: { '0': 2, '502': 3 },
                    byStatus: { '502': 7 }, byCategory: { history: calls(300, 2) },
                    perMinuteByCategory: { history: [1, 2, 3] } },
                github: { lastHour: hour(12, 1), sinceStart: 999,
                    byCategory: { prs: calls(10, 1), 'pat:tokenCheck': calls(2, 0) } },
                jira: { lastHour: hour(3, 0), sinceStart: 12, byCategory: { visa: calls(3, 0) } },
                http: { byCategory: { '/api/analyze': { total: 40, fail: 0, avgLatencyMs: 700, maxLatencyMs: 2100 } } },
                jvm: { heapUsedMb: 300, heapMaxMb: 512, heapPeakMb: 280, rssMb: 700, rssPeakMb: 800, hostMemMb: 4096,
                    threads: 40, cpus: 2, systemCpuPct: 55, processCpuPct: 20, loadAverage: 1.25,
                    heapDump: { writtenAt: page.now() - 60 * MIN, deletedAt: page.now() + 2 * DAY } },
                app: { openPrs: 21, stars: 12, githubRate: { limit: 5000, remaining: 4800 },
                    githubAccount: { state: 'ok', login: 'ignite-pr-checker', checks: CHECKS },
                    githubWrites: { pausedUntil: page.now() + 10 * MIN, refused: 'GitHub answered 403 to a write: '
                        + 'Forbidden', refusedAt: page.now() - MIN, capped: ['PR 13575', 'user alice'] },
                    pooledTokens: 2, resultsCached: 31, historyCached: 1, lastWarmed: 12, lastCached: 30,
                    lastFailed: 2, lastWarmCycleAt: page.now() - 2 * MIN, lastCycleMs: 4500, firstCycleMs: 90000,
                    cyclesCompleted: 5, warmerRunning: false },
                persistence: { enabled: true, active: true, unreadable: [{ file: 'pr-commands.json',
                    keptAs: 'pr-commands.json.unreadable-20261007-115900' }],
                    failingSaves: { 'users.json': 'java.nio.file.AccessDeniedException: /var/cache/users.json' } },
                watcher: { tracked: 3, chains: 1, suites: 2, lastRefreshAt: page.now() - 30000, lastRefreshMs: 850,
                    chainFinishes: 4, standingEnrolled: 3, standingPosted: 1, standingLastSweepAt: page.now() - 2 * MIN,
                    standingLastSweepMs: 3200, prCommands: 2, prCommandsLastPollAt: 0,
                    prCommandsFirstPollAt: page.now() - 5 * MIN,
                    suiteFix: { users: 3, acting: 1, pushed: 1, lastSweepAt: page.now() - 2 * MIN },
                    waitingAfterKeyChange: 2 },
                log: { errors: 1, warnings: 1, clientMistakes: 1, lastErrorAt: page.now() - 10 * MIN,
                    lastWarningAt: page.now() - 20 * MIN, recent: [
                        { t: page.now() - 10 * MIN, level: 'ERROR', logger: 'TcClient', message: LOG[1] },
                        { t: page.now() - 20 * MIN, level: 'WARN', logger: 'StandingVisas', message: LOG[0] },
                        { t: page.now() - 2 * DAY, level: 'WARN', logger: 'ApiExceptionHandler', message: LOG[2],
                            client: true }] },
                admin: { last: { restart: { by: 'avinogradov', at: page.now() - 120 * MIN },
                    flush: { by: 'alice', at: page.now() - 2 * MIN } } },
                config: SETTINGS };
            page.route('/api/status', { body: status });
            page.route('/api/users', { body: [
                { name: 'alice', lastSeen: page.now() - MIN, logins: 3, firstSeen: page.now() - 5 * DAY },
                { name: 'bob', lastSeen: page.now() - 2 * DAY, logins: 1, firstSeen: page.now() - 40 * DAY }] });
            """;
    }

    private static String russian() throws Exception {
        return "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            """;
    }

    @Test
    void anOperatorReadsTheWholePageInRussian() throws Exception {
        JsonNode out = PageScript.run("status.html", operatorsStatus() + russian() + WRITTEN + """
            await page.load('');
            page.el('tabUsers').listeners.click[0]();
            await page.settle();
            report(Object.assign(seen(), { lang: page.run('document.documentElement.getAttribute("lang")'),
                me: page.fetches('/api/me').length }));
            """);

        assertThat(untranslated(out)).isEmpty();
        JsonNode cards = out.get("cards");
        assertThat(cards.get("watcherCards").asText()).contains(
            "Постоянные опции3 пользователя с включённой опцией · отправлено виз в JIRA: 1 · обход 2 мин назад (3,2 с)",
            "Команды в PR2 команды /run-all обработаны · опрос — ни разу",
            "Тесты, добавленные в сьюты1 коммит · 3 пользователя с включённой опцией · 2 на паузе · обход 2 мин назад",
            "Нужны новые токены2 пользователя, чьи опции ждут");
        assertThat(cards.get("systemCards").asText()).contains("Аптайм2 д 7 ч 33 мин", "Средняя нагрузка1,25 на 2 CPU",
            "Не хватило памяти1 ч назад", "Снапшоты2 проблемы");
        assertThat(cards.get("problems").asText()).contains("постоянный обход последний раз начинался 30 мин назад",
            "в пуле нет токена TeamCity, хотя у 3 пользователей есть постоянные опции",
            "pr-commands.json не прочитался при запуске");
        assertThat(cards.get("logList").asText()).contains(LOG.get(0), "— сервис запущен 7 окт. 11:00 —");
        assertThat(cards.get("usersActive").asText())
            .isEqualTo("aliceпоследняя активность 60 с назад · вход выполнен 3 раза · первый визит 5 дней назад");
        assertThat(cards.get("restartLast").asText()).isEqualTo("последний перезапуск: avinogradov · 2 ч назад");
        assertThat(out.get("texts").get("tab").asText()).isEqualTo("Ignite PR Checker — Статус");
        assertThat(out.get("lang").asText()).isEqualTo("ru");
        assertThat(out.get("me").asInt()).as("asking for the language enters no login").isZero();
    }

    /** A signed-in reader who is not an operator: the counts of the log, the problems and settings without dates. */
    @Test
    void anotherSignedInReaderReadsItInRussianToo() throws Exception {
        String withoutDates = "PROBLEMS.splice(0, PROBLEMS.length, ..." + JSON.writeValueAsString(
            StatusTexts.problemsWithoutDates()) + ");\nCHECKS.splice(0, CHECKS.length, ..."
            + JSON.writeValueAsString(StatusTexts.checksWithoutDates()) + ");\n";
        JsonNode out = PageScript.run("status.html", operatorsStatus() + withoutDates + russian() + WRITTEN + """
            Object.assign(status, { operator: false });
            delete status.admin;
            delete status.watcher.waitingAfterKeyChange;
            status.log.recent = [];
            status.config = Object.assign({}, SETTINGS, { PRC_ADMINS: '2 names, shown to operators only' });
            await page.load('');
            report(seen());
            """);

        assertThat(untranslated(out)).isEmpty();
        assertThat(out.get("cards").get("logList").asText()).isEqualTo("Сообщения видят только операторы.");
        assertThat(out.get("cards").get("configList").asText()).contains("PRC_ADMINS2 имени, их видят только операторы",
            "SERVER_ADDRESSне задан (все интерфейсы)");
        assertThat(out.get("cards").get("problems").asText())
            .contains("GITHUB_TOKEN истекает через 14 дней или раньше: оператору надо его заменить");
    }

    /** Russian was chosen at the last opening, but the reader is not logged in now: the page is English. */
    @Test
    void aReaderWithoutALoginReadsItInEnglish() throws Exception {
        JsonNode out = PageScript.run("status.html", operatorsStatus() + WRITTEN + """
            Object.assign(status, { signedIn: false, operator: false });
            page.route('/api/page-language', { body: { lang: 'en' } });
            page.route('/ru.json', { body: {} });
            page.run('localStorage.setItem("lang", "ru"); document.documentElement.setAttribute("data-lang-hint", "ru")');
            await page.load('');
            report(Object.assign(seen(), { hint: page.run('document.documentElement.getAttribute("data-lang-hint")'),
                stored: page.run('localStorage.getItem("lang")') }));
            """);

        assertThat(out.get("cards").get("watcherCards").asText()).contains("Standing options3 users with an option on");
        assertThat(out.get("texts").get("tab").asText()).isEqualTo("Ignite PR Checker — Status");
        assertThat(out.get("hint").isNull()).isTrue();
        assertThat(out.get("stored").isNull()).isTrue();
    }

    /** The status may come before the catalog: the cards wait for the language, or they would stay English. */
    @Test
    void theCardsWaitForTheLanguage() throws Exception {
        JsonNode out = PageScript.run("status.html", operatorsStatus() + russian() + """
            const ticks = async n => { for (let i = 0; i < n; i++) await new Promise(r => setImmediate(r)); };
            page.route('/ru.json', async () => { await ticks(20); return { body: CATALOG }; });
            const cards = page.el('systemCards'), drawn = [];
            const write = Object.getOwnPropertyDescriptor(cards, 'innerHTML');
            Object.defineProperty(cards, 'innerHTML', { get: write.get,
                set(v) { drawn.push(String(v)); write.set.call(this, v); } });
            await page.load('');
            await page.settle();
            report({ drawn });
            """);

        assertThat(out.get("drawn")).isNotEmpty().allSatisfy(html -> assertThat(html.asText()).contains("Аптайм")
            .doesNotContain("Uptime"));
    }

    private static List<String> untranslated(JsonNode out) {
        return EnglishLeft.in(out.get("texts"), out.get("html").asText(), SCOPES,
            Pattern.compile(String.join("|", LOG.stream().map(Pattern::quote).toList())));
    }
}
