package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Each client project keeps its repository in page navigation, API requests and actions on a run. */
class ClientProjectsPageTest {
    /** PR #69 has a finished client run and its GitHub title, while no replacement run is active. */
    private static final String CLIENT = """
        const key = '%s', language = '%s', repo = 'apache/ignite-' + key + '-thin-client';
        const suite = 'Thin client: ' + language;
        const result = { ...verdict({ prNumber: 69 }), repo: key, clientRepo: repo, igniteRepo: 'apache/ignite',
            suiteName: suite, clientSha: 'a'.repeat(40), igniteSha: 'b'.repeat(40), standing: 'BLOCKERS',
            pushed: { pending: false, checked: true } };
        page.route('/api/client/prs', { body: [{ number: 69, title: 'IGNITE-29117 Client change',
            url: 'https://github.com/' + repo + '/pull/69' }] });
        page.route('/api/client/pr-title', { body: { title: 'IGNITE-29117 Client change' } });
        page.route('/api/client/analyze', { body: result });
        page.route('/api/client/refresh', { body: result }, 'POST');
        page.route('/api/client/runs', { body: [] });
        page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 1 } });
        page.route('/api/client/visa', { body: { state: 'off', mine: true, starter: 'alice', jiraReady: true } }, 'GET');
        page.route('/api/client/pr-comment', { body: { bot: 'ready' } }, 'GET');
        const asked = [];
        page.run('window').confirm = question => { asked.push(question); return true; };
        """;

    /** A project's home lists that project's PRs and exposes direct links to all project homes. */
    @ParameterizedTest
    @CsvSource({"python,Python", "php,PHP", "nodejs,Node.js"})
    void projectHomesAndPrLinksKeepTheirIdentity(String key, String language) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            await page.load('/' + key);
            const list = page.el('prList').innerHTML;
            page.run('openPr')(69, true);
            await page.settle();
            report({ list, home: page.fetches('/api/client/prs').map(f => f.url),
                title: page.text('prTitle'), label: page.text('runAllLabel'), pushed: page.pushed,
                requests: page.fetches('/api/client/').map(f => f.url), ignite: page.fetches('/api/analyze').length,
                tabs: ['ignite', 'python', 'php', 'nodejs', 'extensions'].map(k => page.el('project' + k).getAttribute('href')),
                homeLinks: ['brand', 'navPrs'].map(id => page.el(id).href),
                selected: page.el('project' + key).getAttribute('aria-current') });
            """);

        assertThat(out.get("home").toString()).isEqualTo("[\"/api/client/prs?repo=" + key + "\"]");
        assertThat(out.get("list").asText()).contains("href=\"/" + key + "?pr=69\"");
        assertThat(out.get("pushed").toString()).isEqualTo("[\"/" + key + "?pr=69\"]");
        assertThat(out.get("title").asText()).startsWith(language + " client #69");
        assertThat(out.get("label").asText()).isEqualTo("“Thin client: " + language + "”:");
        assertThat(out.get("ignite").asInt()).isZero();
        assertThat(out.get("tabs").toString()).isEqualTo("[\"/ignite\",\"/python\",\"/php\",\"/nodejs\",\"/extensions\"]");
        assertThat(out.get("homeLinks").toString()).isEqualTo("[\"/" + key + "\",\"/" + key + "\"]");
        assertThat(out.get("selected").asText()).isEqualTo("page");
        assertThat(out.get("requests")).allSatisfy(url -> assertThat(url.asText()).contains("repo=" + key));
    }

    /** Given a signed-out client PR link, its context replaces the general introduction and keeps the token notice. */
    @ParameterizedTest
    @CsvSource({"python,Python", "php,PHP", "nodejs,Node.js"})
    void theLoginFormKeepsTheProject(String key, String language) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            page.route('/api/me', { status: 401, body: {} });
            await page.load('/' + key + '?pr=69');
            report({ head: page.text('loginPrHead'), link: page.el('loginPrNote').innerHTML,
                introHidden: page.el('loginIntro').classList.contains('hidden'),
                tokenNoteHidden: page.el('loginTokenNote').classList.contains('hidden'),
                read: page.fetches('/api/client/').length });
            """);

        assertThat(out.get("head").asText()).startsWith("Log in to see " + language + " client PR #69");
        assertThat(out.get("link").asText()).contains("https://github.com/apache/ignite-" + key + "-thin-client/pull/69");
        assertThat(out.get("introHidden").asBoolean()).isTrue();
        assertThat(out.get("tokenNoteHidden").asBoolean()).isFalse();
        assertThat(out.get("read").asInt()).isZero();
    }

    /** Given a client home without a PR, its login introduction names that client and its one suite. */
    @ParameterizedTest
    @CsvSource({"python,Python", "php,PHP", "nodejs,Node.js"})
    void theClientHomeLoginIntroNamesItsSuite(String key, String language) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            page.route('/api/me', { status: 401, body: {} });
            await page.load('/' + key);
            report({ intro: page.text('loginIntro'), hidden: page.el('loginIntro').classList.contains('hidden'),
                pr: page.el('loginPr').classList.contains('hidden') });
            """);

        assertThat(out.get("intro").asText()).contains(language + " client PR", "Start or re-run “Thin client: " + language + "”")
            .doesNotContain("Apache Ignite PR", "RunAll");
        assertThat(out.get("hidden").asBoolean()).isFalse();
        assertThat(out.get("pr").asBoolean()).isTrue();
    }

    /**
     * Given delayed project configuration, the client login waits instead of showing Ignite's introduction or
     * an unavailable error.
     */
    @Test
    void theClientLoginWaitsForItsProjectConfiguration() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            let release;
            const config = new Promise(done => { release = () => done({ body: {
                teamcityUrl: 'https://ci2.example/', clients: [{ key: 'php', label: 'PHP',
                    repo: 'apache/ignite-php-thin-client', suite: 'Thin client: PHP' }] } }); });
            page.route('/api/config', () => config);
            page.route('/api/me', { status: 401, body: {} });
            await page.load('/php');
            const waiting = page.el('loginView').classList.contains('hidden');
            release();
            await page.settle();
            report({ waiting, intro: page.text('loginIntro'), login: !page.el('loginView').classList.contains('hidden'),
                unavailable: page.text('loginPrHead') });
            """);

        assertThat(out.get("waiting").asBoolean()).isTrue();
        assertThat(out.get("intro").asText()).contains("PHP client PR", "Thin client: PHP").doesNotContain("RunAll");
        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("unavailable").asText()).doesNotContain("not available");
    }

    /** Given a Russian PHP page, logging out keeps the project and suite in the Russian introduction. */
    @Test
    void theClientHomeLoginIntroSpeaksRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted("php", "PHP")
            + "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', lang: 'ru' } });
            await page.load('/php');
            await page.el('logout').onclick();
            await page.settle();
            report({ intro: page.text('loginIntro'), login: !page.el('loginView').classList.contains('hidden') });
            """);

        assertThat(out.get("intro").asText()).contains("PR клиента PHP", "Запусти или перезапусти «Thin client: PHP»")
            .doesNotContain("RunAll", "Apache Ignite PR");
        assertThat(out.get("login").asBoolean()).isTrue();
    }

    /** Every run action and verdict post addresses the selected client, even when PR numbers overlap. */
    @ParameterizedTest
    @CsvSource({"python,Python", "php,PHP", "nodejs,Node.js"})
    void runRefreshCancelAndPostsAddressTheSelectedClient(String key, String language) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            page.route('/api/client/trigger', { body: { triggered: [{ buildId: 9002, webUrl: 'https://ci2.example/9002' }] } }, 'POST');
            page.route('/api/client/cancel-all', { body: { cancelled: 1 } }, 'POST');
            page.route('/api/client/visa', { body: { state: 'posted', issue: 'IGNITE-29117', url: 'https://jira.example/1' } }, 'POST');
            page.route('/api/client/pr-comment', { body: { bot: 'ready', url: 'https://github.com/' + repo + '/pull/69#c1' } }, 'POST');
            await page.load('/' + key + '?pr=69');
            await page.settle();
            await page.run('doTrigger')('trigger', false, page.el('run'));
            await page.run('refresh')();
            await page.settle();
            // Each post asks in its dialog first; the scenario says yes.
            for (const post of ['postVisa', 'postToPr']) {
                const pressed = page.run(post)();
                await page.settle();
                page.el('postDialogOk').onclick();
                await pressed;
            }
            page.route('/api/client/runs', { body: [{ buildId: 9002, name: suite, state: 'running', runAll: true,
                mine: true, by: 'alice', pinned: true, pct: 10 }] });
            await page.run('cancelMine')();
            report({ posted: page.fetches('/api/client/').filter(f => f.method === 'POST').map(f => f.url), asked });
            """);

        assertThat(out.get("posted")).hasSize(5).allSatisfy(url -> assertThat(url.asText()).contains("repo=" + key));
        assertThat(out.get("posted").toString()).contains("/api/client/trigger?pr=69&repo=" + key,
            "/api/client/refresh?pr=69&repo=" + key, "/api/client/visa?pr=69&repo=" + key,
            "/api/client/pr-comment?pr=69&repo=" + key, "/api/client/cancel-all?pr=69&repo=" + key);
        assertThat(out.get("asked").get(0).asText()).contains(language + " client PR #69");
    }

    /** Mine searches only the current project; the filter opens a missing PR in that project. */
    @ParameterizedTest
    @CsvSource({"python,Python", "php,PHP", "nodejs,Node.js"})
    void tabsAndFilterStayWithinTheProject(String key, String language) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            page.route('/api/me', { body: { username: 'alice', githubLinked: true } });
            page.route('/api/client/your-prs', { body: { login: 'alice-gh', mine: [{ number: 68, title: 'My change' }], involved: [] } });
            await page.load('/' + key);
            const mine = page.el('prList').innerHTML;
            page.el('prFilter').value = '70';
            page.run('applyPrFilter')();
            const open = page.el('prOpenAny').href;
            for (const fn of page.el('prFilter').listeners.keydown) fn({ key: 'Enter' });
            await page.settle();
            report({ mine, open, pushed: page.pushed, own: page.fetches('/api/client/your-prs').map(f => f.url),
                ignite: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("mine").asText()).contains("href=\"/" + key + "?pr=68\"").doesNotContain("pr=69");
        assertThat(out.get("open").asText()).isEqualTo("/" + key + "?pr=70");
        assertThat(out.get("pushed").toString()).isEqualTo("[\"/" + key + "?pr=70\"]");
        assertThat(out.get("own").toString()).isEqualTo("[\"/api/client/your-prs?repo=" + key + "\"]");
        assertThat(out.get("ignite").asInt()).isZero();
    }

    /** OAuth stays on Settings and preserves the return project and PR for another link attempt. */
    @ParameterizedTest
    @ValueSource(strings = {"/python", "/php?pr=69", "/nodejs?pr=69"})
    void githubLinkReturnsToTheSameProject(String address) throws Exception {
        String key = address.substring(1).split("\\?")[0];
        String language = key.equals("nodejs") ? "Node.js" : key.equals("php") ? "PHP" : "Python";
        String settingsAddress = "/settings?repo=" + key + (address.contains("?") ? "&pr=69" : "");
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted(key, language) + """
            const replaced = [];
            page.run('history').replaceState = (state, title, url) => replaced.push(url);
            page.route('/api/auto-visa-all', { body: { commands: false, login: null, githubLinkable: true } });
            page.route('/api/github/link', { body: { url: 'https://github.com/login/oauth/authorize?state=x' } }, 'POST');
            await page.load('%s&github=denied');
            await page.el('ghLink').onclick();
            report({ replaced, back: page.el('settingsBack').href,
                link: page.fetches('/api/github/link').map(f => f.url) });
            """.formatted(settingsAddress));

        assertThat(out.get("replaced").get(0).asText()).isEqualTo(settingsAddress);
        assertThat(out.get("back").asText()).isEqualTo(address);
        assertThat(out.get("link").get(0).asText()).contains("repo=" + key);
        if (address.contains("?"))
            assertThat(out.get("link").get(0).asText()).contains("pr=69");
    }

    /** Unknown page paths never select Ignite or Python implicitly, even when the path names an object property. */
    @ParameterizedTest
    @ValueSource(strings = {"/unknown", "/index.html", "/", "/php/", "/extensions/", "/constructor", "/toString"})
    void unknownPathsQueueAndReadNoPr(String path) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('%s?pr=69');
            await page.run('doTrigger')('trigger', false, page.el('run'));
            report({ read: page.fetches('/api/analyze').length + page.fetches('/api/client/').length,
                hidden: page.el('actions').classList.contains('hidden'), status: page.text('status') });
            """.formatted(path));

        assertThat(out.get("read").asInt()).isZero();
        assertThat(out.get("hidden").asBoolean()).isTrue();
        assertThat(out.get("status").asText()).contains("not available on this checker");
    }

    /** A project absent from the server catalog cannot read or launch another project's PR. */
    @Test
    void aProjectAbsentFromConfigDoesNotFallBack() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/config', { body: { clients: [] } });
            await page.load('/php?pr=69');
            await page.run('doTrigger')('trigger', false, page.el('run'));
            report({ reads: page.fetches('/api/client/').length + page.fetches('/api/analyze').length,
                status: page.text('status'), hidden: page.el('projectphp').classList.contains('hidden') });
            """);

        assertThat(out.get("reads").asInt()).isZero();
        assertThat(out.get("status").asText()).contains("not available on this checker");
        assertThat(out.get("hidden").asBoolean()).isTrue();
    }

    /** Given Russian selected, a PHP verdict names PHP in its title and run confirmation. */
    @Test
    void russianPhpPageNamesPhp() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT.formatted("php", "PHP")
            + "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', lang: 'ru' } });
            await page.load('/php?pr=69');
            report({ title: page.text('prTitle'), question: page.run('clientRunQuestion')(69, false, []) });
            """);

        assertThat(out.get("title").asText()).startsWith("Клиент PHP #69");
        assertThat(out.get("question").asText()).contains("клиента PHP").doesNotContain("Python");
    }
}
