package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.jira.SettingsJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A user who chose Russian in ⚙ read the PR page, its cards and ⚙ in English. The page now takes the Russian catalog
 * when /api/me names Russian, and says the verdict, the cards, the server's reasons, the runs and ⚙ in Russian: no
 * English phrase is left but the names of suites, tests, services and TeamCity's own messages. The page of anyone else
 * stays English and asks for no catalog.
 */
class RussianPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two or more English words in a row: a phrase the catalog missed. A name joined by "-" or "/" is one word. */
    private static final Pattern ENGLISH_PHRASE =
        Pattern.compile("(?<![\\w/.-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    /** TeamCity's own messages about the broken suites of the scenario: TeamCity's words, not the checker's. */
    private static final Pattern TEAMCITY_TEXT = Pattern.compile("Failed to resolve artifacts from <.*?[\\d\\]]>"
        + "|Failed to create temporary custom script file .*? No space left on device");

    /** The names and texts no catalog translates: services, terms the docs keep, suites and tests of the scenarios. */
    private static final List<String> NAMES = List.of("Apache Ignite", "Open Census",
        "Machine Learning", "Thin client: Node.js", "Thin client: PHP", "Thin client: Python", "Platform .NET",
        "Platform C++", "Firefox on Linux", "Chrome on Windows", "Move MDC to JUnit", "Check marshalled kind",
        "Bad Gateway", "Contents: Read and write", "public_repo", "fine-grained", "Ignite PR Checker");

    private static String russian() throws IOException {
        return PageScript.SIGNED_IN + "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json"),
            UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            """;
    }

    @Test
    void theVerdictAndItsCardsSpeakRussian() throws Exception {
        String served = JSON.writeValueAsString(AnalyzeController.Served.of(BrokenRuns.artifactsGlitch()));
        JsonNode out = PageScript.run("index.html", russian() + "const SERVED = " + served + ";\n" + """
            const flaky = (name, reason, occ) => Object.assign(blocker(name, occ), { blocker: false, reason });
            const oneRun = Object.assign(blocker('CacheTest.testOnce', 'occ-9'), { suite: 'IgniteTests24Java8_Pds',
                suiteName: 'PDS', reason: 'not seen failing in 3 master runs; failed the only run on this branch',
                doubts: ['ONE_RUN'], branchRuns: 'F', codeRuns: 1 });
            page.route('/api/analyze', { body: Object.assign(SERVED, { prNumber: 13575, buildId: 9001,
                computedAt: page.now() - 30000, finishedAt: RUN_FINISHED, startedAt: RUN_FINISHED - 6000,
                queuedAt: RUN_FINISHED - 7200, suitesRan: 135, suitesReused: 12,
                blockers: [Object.assign(blocker('ClientReconnectTest.testReconnect', 'occ-1'),
                    { reason: 'not seen failing in 100 master runs; failed all 3 runs on this branch' }), oneRun],
                watch: [Object.assign(blocker('WatchTest.testWatch', 'occ-3'), { blocker: false, watch: true,
                    reason: 'failed the only run on commit 3fdf446; the 2 earlier runs were on other code' })],
                filtered: [flaky('A.b', 'pre-existing: fails 1/4 on master on JDK 17', 'occ-4'),
                    flaky('A.c', 'flaky on branch: failed only the latest of 2 runs on commit 3fdf446 (an earlier run '
                        + 'on the same code passed)', 'occ-5'),
                    flaky('A.d', 'not failing in the last finished run (passed on re-run)', 'occ-6')],
                unverified: [flaky('A.e', 'could not verify (TeamCity error: 502 Bad Gateway)', 'occ-7')],
                shrunkSuites: [{ suite: 'IgniteTests24Java8_Basic', suiteBuildId: 9100, suiteName: 'Basic 1',
                    tests: 30, baseline: 60, dropPct: 50 }] }) });
            page.route('/api/runs', { body: [
                { buildId: 9201, name: 'Cache', state: 'running', webUrl: 'https://ci2/9201', pct: 40, leftSec: 600,
                    elapsedSec: 1200, onHead: true, mine: true, runAll: false },
                { buildId: 9202, name: 'PDS', state: 'queued', webUrl: 'https://ci2/9202', pct: -1, startSec: 300,
                    waitedSec: 60, by: 'bob', mine: false, runAll: false }] });
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2,
                what: '3 suites (2 with blockers, 1 broken)', etaEpochSec: Math.floor(page.now() / 1000) + 1200 } });
            page.route('/api/delta', { body: { delta: { latestBuildId: 9001, prevBuildId: 8000,
                appeared: [blocker('X.y', 'o1')], fixed: [blocker('X.z', 'o2')], persisting: 3 }, history: [] } });
            page.route('/api/pending', { body: { pending: true, ahead: 2, builtSha: 'abc1234', headSha: 'def5678',
                builtRevision: 'abc1234abc1234' } });
            await page.load('?pr=13575');
            await page.tick(100);
            const text = id => page.el(id).textContent;
            const ids = ['verdictText', 'verdictLines', 'freshText', 'freshMore', 'runs', 'settling', 'pending',
                'visaLine', 'delta', 'brokenSuites', 'brokenAdvice', 'shrunkSuites', 'blockers', 'oneRunBlockers',
                'watch', 'unverified', 'filtered', 'filteredCount', 'watchCount', 'unverifiedCount', 'shrunkCount',
                'blockersHead', 'tabCauses', 'runAllLabel'];
            const html = ['freshText', 'freshMore', 'runs', 'brokenSuites', 'shrunkSuites', 'blockers', 'watch',
                'filtered', 'delta'].map(id => page.el(id).innerHTML).join('\\n');
            report({ texts: Object.fromEntries(ids.map(id => [id, text(id)])), html,
                lang: page.run('document.documentElement.getAttribute("lang")'), tab: page.run('document.title') });
            """);

        JsonNode texts = out.get("texts");
        assertThat(untranslated(texts, out.get("html").asText())).isEmpty();
        assertThat(texts.get("verdictText").asText()).isEqualTo("2 блокера в 2 сьютах, из них 1 на 1 прогоне");
        assertThat(texts.get("filteredCount").asText()).isEqualTo("(1 сьюта, 3 теста)");
        assertThat(texts.get("verdictLines").asText()).startsWith("Этот прогон покрывает PR не полностью: у 64 сьют нет "
            + "надёжного результата (60× сбой ci2: артефакты недоступны;");
        assertThat(texts.get("settling").asText()).isEqualTo("♻︎ Автоперезапуск №1 из не более чем 2: перезапускаю 3 "
            + "сьюты (2 с блокерами, 1 битая), закончится через ~20 мин. Вердикт ниже может измениться, когда они "
            + "закончатся.");
        assertThat(texts.get("filtered").asText()).contains("уже было: падает 1/4 на master на JDK 17",
            "флаки на ветке: упал только в последнем из 2 прогонов на коммите 3fdf446 (более ранний прогон на том же "
                + "коде прошёл)");
        assertThat(out.get("lang").asText()).isEqualTo("ru");
    }

    @Test
    void settingsSpeakRussianAndTheJournalComesInRussian() throws Exception {
        SettingsJournal journal = new SettingsJournal(JSON);
        journal.add("alice", SettingsJournal.Entry.language(SettingsJournal.By.of("here", "Firefox on Linux"),
            Lang.RU));
        String entries = JSON.writeValueAsString(journal.views("alice", "here", "Firefox on Linux", Lang.RU));
        JsonNode out = PageScript.run("index.html", russian() + "const ENTRIES = " + entries + ";\n" + """
            const DAY = 86400000;
            page.route('/api/auto-visa-all', { body: { visa: true, rerun: true, gh: false, style: false,
                commands: true, login: 'alice-gh', jiraStored: true, ghStored: true, jiraTokenRejected: false,
                ghTokenRejected: false, tcTokenRejected: false, suites: true, loginUnproven: false,
                githubAvailable: true, appLogin: 'ignite-pr-checker', githubLinkable: true, ghTokenKind: 'classic',
                ghTokenNote: 'Your saved token is a classic one: it can push to every public repository you can push '
                    + 'to, apache/ignite too if you are a committer. A fine-grained token for your fork can push '
                    + 'there only.', linkedAt: page.now() - DAY, linkedFrom: 'Firefox on Linux',
                tokensUnread: ['github'], tokensUnreadWhy: 'key-changed', tokensRemoved: [] } });
            page.route('/api/logins', { body: { logins: [
                { id: 'here', browser: 'Firefox on Linux', createdAt: page.now() - DAY, earlier: false,
                    lastSeen: page.now(), current: true },
                { id: 'there', browser: 'Chrome on Windows', createdAt: page.now() - 3 * DAY, earlier: false,
                    lastSeen: page.now() - 2 * 3600000, current: false }], ended: 0 } });
            page.route('/api/journal', { body: { entries: ENTRIES, full: false } });
            page.route('/api/token-expiry?service=teamcity', { body: { state: 'expires', expiresAt: page.now() + 3 * DAY } });
            page.route('/api/token-expiry?service=jira', { body: { state: 'never' } });
            page.route('/api/token-expiry?service=github', { body: { state: 'unknown', why: 'several', tokens: 2 } });
            await page.load('');
            await page.el('settingsBtn').onclick();
            await page.settle();
            const ids = ['tokensUnread', 'tcState', 'tcExpiry', 'jiraState', 'jiraExpiry', 'ghLoginState',
                'ghTokenState', 'ghTokenExpiry', 'ghTokenNote', 'ghCommentApp', 'loginsState', 'loginsList',
                'journalState', 'journalList', 'settingsMsg', 'settingsGhMsg'];
            report({ texts: Object.fromEntries(ids.map(id => [id, page.el(id).textContent])),
                titles: ['tcExpiry', 'jiraExpiry', 'ghTokenExpiry'].map(id => page.el(id).title).join('\\n'),
                language: page.el('langSelect').value });
            """);

        JsonNode texts = out.get("texts");
        assertThat(untranslated(texts, out.get("titles").asText())).isEmpty();
        assertThat(texts.get("tcExpiry").asText()).isEqualTo(", токен истекает через 3 дня: войди с новым до этого");
        assertThat(texts.get("ghTokenState").asText()).isEqualTo("сохранён, classic");
        assertThat(texts.get("loginsState").asText()).isEqualTo("2 браузера");
        assertThat(texts.get("journalList").asText()).contains("Язык страниц переключён на русский.",
            "из этого браузера");
        assertThat(texts.get("tokensUnread").asText()).startsWith("⚠ Ключ сервера сменили, поэтому чекер больше не может "
            + "прочитать сохранённые токены: токен GitHub для коммитов.");
        assertThat(out.get("language").asText()).isEqualTo("ru");
    }

    /** Log out answers in the page's language; the next opening of the page is English, as for anyone logged out. */
    @Test
    void logOutAnswersInRussianAndForgetsTheLanguage() throws Exception {
        String note = LoginController.LOGGED_OUT_HERE + " " + LoginController.othersGoOn(2) + " "
            + LoginController.OPTIONS_GO_ON;
        JsonNode out = PageScript.run("index.html", russian() + "const NOTE = " + JSON.writeValueAsString(note) + ";\n"
            + """
            page.route('/api/logout', { body: { note: NOTE } });
            await page.load('?pr=13575');
            const before = page.run('localStorage.getItem("lang")');
            await page.el('logout').onclick();
            await page.settle();
            report({ before, after: page.run('localStorage.getItem("lang")'), note: page.el('loginErr').textContent,
                head: page.el('loginPrHead').textContent });
            """);

        assertThat(out.get("before").asText()).isEqualTo("ru");
        assertThat(out.get("after").isNull()).isTrue();
        assertThat(out.get("note").asText()).isEqualTo("Ты вышел в этом браузере. В 2 других браузерах ты по-прежнему во "
            + "входе. Твои опции в ⚙ остаются включёнными и продолжают пользоваться твоим токеном TeamCity, который сервер "
            + "для них хранит. Чтобы это прекратить, войди и выключи их.");
        assertThat(out.get("head").asText()).startsWith("Войди, чтобы увидеть PR #13575");
    }

    /** Nobody chose Russian: the page asks for no catalog and writes what it always wrote. */
    @Test
    void anEnglishReaderGetsNoCatalog() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'en' } });
            await page.load('?pr=13575');
            report({ catalog: page.fetches('/ru.json').length, head: page.el('verdictText').textContent,
                stored: page.run('localStorage.getItem("lang")'),
                lang: page.run('document.documentElement.getAttribute("lang")') });
            """);

        assertThat(out.get("catalog").asInt()).isZero();
        assertThat(out.get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("stored").isNull()).isTrue();
        assertThat(out.get("lang").isNull()).isTrue();
    }

    /**
     * Russian chosen at the last opening makes the page ask for the catalog at once and wait hidden for its language;
     * a reader no longer logged in gets the page shown, in English, and the choice is forgotten.
     */
    @Test
    void aPageThatWaitedForRussianShowsEnglishToALoggedOutReader() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { status: 401, body: {} });
            page.run('localStorage.setItem("lang", "ru"); document.documentElement.setAttribute("data-lang-hint", "ru")');
            await page.load('');
            report({ catalog: page.fetches('/ru.json').length,
                hint: page.run('document.documentElement.getAttribute("data-lang-hint")'),
                stored: page.run('localStorage.getItem("lang")'), login: !page.el('loginView').classList.contains('hidden'),
                language: page.run('LANG') });
            """);

        assertThat(out.get("catalog").asInt()).as("asked as the page opened").isEqualTo(1);
        assertThat(out.get("hint").isNull()).isTrue();
        assertThat(out.get("stored").isNull()).isTrue();
        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("language").asText()).isEqualTo("en");
    }

    /** The choice in ⚙ goes to the server, which keeps it for the account, and the page opens again in it. */
    @Test
    void choosingALanguageInSettingsTellsTheServerAndReloads() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/language', { body: { lang: 'ru' } }, 'POST');
            await page.load('');
            page.run('location.reload = () => { window.reloaded = true; }');
            await page.el('settingsBtn').onclick();
            await page.settle();
            const shown = page.el('langSelect').value;
            page.el('langSelect').value = 'ru';
            await page.el('langSelect').onchange();
            await page.settle();
            report({ shown, sent: page.fetches('/api/language').map(f => f.body), reloaded: page.run('!!window.reloaded') });
            """);

        assertThat(out.get("shown").asText()).isEqualTo("en");
        assertThat(out.get("sent").toString()).isEqualTo("[\"{\\\"lang\\\":\\\"ru\\\"}\"]");
        assertThat(out.get("reloaded").asBoolean()).isTrue();
    }

    /**
     * The page's own HTML goes into Russian where it stands: each text and tooltip the catalog has, its white space kept
     * around it, a link into the docs to the Russian docs, and code left as it is.
     */
    @Test
    void thePagesOwnHtmlGoesIntoRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", russian() + """
            await page.load('');
            const text = v => ({ nodeType: 3, nodeValue: v });
            const el = (tagName, attrs, kids) => ({ nodeType: 1, tagName, attrs, childNodes: kids,
                getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
                setAttribute(k, v) { this.attrs[k] = v; } });
            const label = text('\\n                Re-run failed suites of my runs ');
            const code = text('/run-all');
            const help = el('A', { href: 'https://github.com/anton-vinogradov/ignite-pr-checker/blob/main/docs/'
                + 'features.md#cards-on-the-pr-page', title: 'What each verdict means and what to do next' }, []);
            const root = el('DIV', {}, [el('B', {}, [label]), el('CODE', {}, [code]), help,
                el('INPUT', { placeholder: 'Filter, or a PR number' }, []), text('   ')]);
            page.run('translateDom')(root);
            report({ label: label.nodeValue, code: code.nodeValue, href: help.attrs.href, title: help.attrs.title,
                placeholder: root.childNodes[3].attrs.placeholder });
            """);

        assertThat(out.get("label").asText()).isEqualTo("\n                Перезапускать упавшие сьюты моих прогонов ");
        assertThat(out.get("code").asText()).isEqualTo("/run-all");
        assertThat(out.get("href").asText()).endsWith("/docs/features.ru.md#карточки-на-странице-pr");
        assertThat(out.get("title").asText()).isEqualTo("Что значит каждый вердикт и что делать дальше");
        assertThat(out.get("placeholder").asText()).isEqualTo("Фильтр или номер PR");
    }

    /** Numbers take the Russian form of the word after them, and dates and times read as Russians write them. */
    @Test
    void numbersDatesAndTimesReadAsInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", russian() + """
            await page.load('');
            const forms = [1, 2, 5, 11, 12, 21, 22, 25, 111, 1.5].map(n => page.run('ruForm')(n, 'тест', 'теста', 'тестов'));
            const at = Date.parse('2026-09-15T21:05:00Z');
            report({ forms, day: page.run('fmtDay')(at), lastYear: page.run('fmtDay')(Date.parse('2025-03-02T08:07:00Z')),
                ago: page.run('ago')(page.now() - 3 * 86400000), spent: page.run('fmtSpent')(3900),
                left: page.run('fmtLeft')(90), test: page.run('fmtTestTime')(400),
                browsers: page.run('countOf')(21, 'browser') });
            """);

        assertThat(out.get("forms").toString()).isEqualTo("[\"тест\",\"теста\",\"тестов\",\"тестов\",\"тестов\",\"тест\","
            + "\"теста\",\"тестов\",\"тестов\",\"теста\"]");
        assertThat(out.get("day").asText()).isEqualTo("15 сентября в 21:05");
        assertThat(out.get("lastYear").asText()).isEqualTo("2 марта 2025 г. в 08:07");
        assertThat(out.get("ago").asText()).isEqualTo("3 дня назад");
        assertThat(out.get("spent").asText()).isEqualTo("1 ч 05 мин");
        assertThat(out.get("left").asText()).isEqualTo("~2 мин");
        assertThat(out.get("test").asText()).isEqualTo("0,4 с");
        assertThat(out.get("browsers").asText()).isEqualTo("21 браузер");
    }

    /** The English phrases left in the texts, the names of the scenarios aside. */
    private static List<String> untranslated(JsonNode texts, String html) {
        List<String> all = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = texts.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            all.add(e.getKey() + ": " + e.getValue().asText());
        }
        Matcher title = Pattern.compile("title=\"([^\"]*)\"").matcher(html);
        while (title.find())
            all.add("title: " + title.group(1));

        List<String> left = new ArrayList<>();
        for (String line : all) {
            String text = TEAMCITY_TEXT.matcher(line.substring(line.indexOf(": ") + 2)).replaceAll("")
                .replaceAll("https?://\\S+", "");
            for (String name : NAMES)
                text = text.replace(name, "");
            Matcher phrase = ENGLISH_PHRASE.matcher(text);
            while (phrase.find())
                left.add(line.substring(0, line.indexOf(':')) + ": «" + phrase.group() + "» in " + line);
        }

        return left;
    }
}
