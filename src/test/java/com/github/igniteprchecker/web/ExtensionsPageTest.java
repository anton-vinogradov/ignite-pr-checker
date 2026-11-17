package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Extensions uses the separate-project API while presenting a composite chain and its leaf-suite results. */
class ExtensionsPageTest {
    /** A finished Extensions chain has failures in two suites and no replacement run in progress. */
    private static final String EXTENSIONS = """
        const result = { ...verdict({ prNumber: 42, branchName: 'pull/42/' }), repo: 'extensions', composite: true,
            clientRepo: 'apache/ignite-extensions', igniteRepo: 'apache/ignite', suiteName: 'Extensions: All Tests',
            clientSha: 'a'.repeat(40), igniteSha: 'b'.repeat(40), standing: 'BLOCKERS',
            pushed: { pending: false, checked: true } };
        result.blockers = [
            { ...blocker('KafkaTest.testCache', 'ext-1'), testId: '101', suite: 'Ext_Kafka',
                suiteName: 'Kafka extension', suiteBuildId: 8901 },
            { ...blocker('SpringTest.testCache', 'ext-2'), testId: '102', suite: 'Ext_Spring',
                suiteName: 'Spring extension', suiteBuildId: 8902 }
        ];
        page.route('/api/client/prs', { body: [{ number: 42, title: 'IGNITE-29117 Extension change',
            url: 'https://github.com/apache/ignite-extensions/pull/42' }] });
        page.route('/api/client/pr-title', { body: { title: 'IGNITE-29117 Extension change' } });
        page.route('/api/client/analyze', { body: result });
        page.route('/api/client/refresh', { body: result }, 'POST');
        page.route('/api/client/runs', { body: [] });
        page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 2 } });
        page.route('/api/client/visa', { body: { state: 'off', mine: true, starter: 'alice', jiraReady: true } }, 'GET');
        page.route('/api/client/pr-comment', { body: { bot: 'ready' } }, 'GET');
        const asked = [];
        page.run('window').confirm = question => { asked.push(question); return true; };
        """;

    /** Given an Extensions PR, opening it keeps navigation, filtering and API requests in that repository. */
    @Test
    void theProjectPageShowsAChainAndKeepsItsRepository() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + EXTENSIONS + """
            await page.load('/extensions?pr=42');
            await page.settle();
            const matching = page.run('prMatches')([], 'extensions#43');
            const other = page.run('prMatches')([], 'python#43');
            page.el('prFilter').value = 'extensions#43';
            page.run('applyPrFilter')();
            report({ title: page.text('prTitle'), open: page.el('prOpenAny').href,
                matching, other, tab: page.el('projectextensions').getAttribute('aria-current'),
                hidden: page.el('projectextensions').classList.contains('hidden'),
                home: page.el('brand').href, requests: page.fetches('/api/client/').map(f => f.url),
                main: page.fetches('/api/analyze').length });
            """);

        assertThat(out.get("title").asText()).startsWith("Extensions #42").doesNotContain("client");
        assertThat(out.get("matching").get("open").asInt()).isEqualTo(43);
        assertThat(out.get("other").get("open").isNull()).isTrue();
        assertThat(out.get("open").asText()).isEqualTo("/extensions?pr=43");
        assertThat(out.get("tab").asText()).isEqualTo("page");
        assertThat(out.get("hidden").asBoolean()).isFalse();
        assertThat(out.get("home").asText()).isEqualTo("/extensions");
        assertThat(out.get("requests")).allSatisfy(url -> assertThat(url.asText()).contains("repo=extensions"));
        assertThat(out.get("main").asInt()).isZero();
    }

    /** Given failures from two leaf suites, the composite verdict names the chain and shows both suites' results. */
    @Test
    void theCompositeVerdictShowsEveryLeafSuite() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + EXTENSIONS + """
            await page.load('/extensions?pr=42');
            await page.settle();
            report({ label: page.text('runAllLabel'), fresh: page.text('freshText'),
                failures: page.el('blockers').innerHTML });
            """);

        assertThat(out.get("label").asText()).isEqualTo("“Extensions: All Tests” build chain:");
        assertThat(out.get("fresh").asText()).contains("Extensions: All Tests chain");
        assertThat(out.get("failures").asText()).contains("Kafka extension", "Spring extension", "8901", "8902");
    }

    /** Given a signed-out Extensions PR, its context names the chain and replaces the general introduction. */
    @Test
    void theLoginFormNamesTheCompositeProject() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + EXTENSIONS + """
            page.route('/api/me', { status: 401, body: {} });
            await page.load('/extensions?pr=42');
            report({ introHidden: page.el('loginIntro').classList.contains('hidden'), head: page.text('loginPrHead'),
                tokenNoteHidden: page.el('loginTokenNote').classList.contains('hidden'),
                link: page.el('loginPrNote').innerHTML, reads: page.fetches('/api/client/').length });
            """);

        assertThat(out.get("introHidden").asBoolean()).isTrue();
        assertThat(out.get("tokenNoteHidden").asBoolean()).isFalse();
        assertThat(out.get("head").asText()).contains("Extensions PR #42", "build chain");
        assertThat(out.get("link").asText()).contains("https://github.com/apache/ignite-extensions/pull/42");
        assertThat(out.get("reads").asInt()).isZero();
    }

    /** Given a finished chain, Run, refresh, cancellation and both verdict posts stay on the Extensions API. */
    @Test
    void theWholeChainActionsKeepTheirProject() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + EXTENSIONS + """
            page.route('/api/client/trigger', { body: { triggered: [{ buildId: 9002 }] } }, 'POST');
            page.route('/api/client/cancel-all', { body: { cancelled: 1 } }, 'POST');
            page.route('/api/client/visa', { body: { state: 'posted', issue: 'IGNITE-29117' } }, 'POST');
            page.route('/api/client/pr-comment', { body: { bot: 'ready', url: 'https://github.com/apache/ignite-extensions/pull/42#c1' } }, 'POST');
            await page.load('/extensions?pr=42');
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
            page.route('/api/client/runs', { body: [{ buildId: 9002, name: 'Extensions: All Tests',
                state: 'running', runAll: true, mine: true, by: 'alice' }] });
            await page.run('cancelMine')();
            report({ asked, posts: page.fetches('/api/client/').filter(f => f.method === 'POST').map(f => f.url),
                mainPosts: page.fetches('/api/trigger').length });
            """);

        assertThat(out.get("posts")).hasSize(5).allSatisfy(url -> assertThat(url.asText()).contains("repo=extensions"));
        assertThat(out.get("asked").get(0).asText()).contains("build chain", "Extensions PR #42")
            .doesNotContain("client PR");
        assertThat(out.get("mainPosts").asInt()).isZero();
    }

    /** Given Russian in the user's settings, chain labels, confirmation and automatic re-run status use Russian. */
    @Test
    void russianCallsTheCompositeAChain() throws Exception {
        String catalog = Files.readString(Path.of("src/main/resources/static/ru.json"));
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + EXTENSIONS + "const CATALOG = " + catalog + ";\n" + """
            page.route('/api/me', { body: { username: 'alice', lang: 'ru' } });
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/client/runs', { body: [{ buildId: 9002, name: 'Extensions: All Tests', state: 'queued',
                webUrl: 'https://ci2.example/build/9002', pct: -1, runAll: true }] });
            page.route('/api/client/settling', { body: { phase: 'settling', wave: 1, of: 1,
                rerunBuildIds: [9002] } });
            await page.load('/extensions?pr=42');
            await page.settle();
            report({ title: page.text('prTitle'), label: page.text('runAllLabel'),
                question: page.run('clientRunQuestion')(42, false, []),
                settling: page.text('settling') });
            """);

        assertThat(out.get("title").asText()).startsWith("Расширения #42");
        assertThat(out.get("label").asText()).contains("Цепочка сборок");
        assertThat(out.get("question").asText()).contains("цепочку сборок", "проекта Расширения");
        assertThat(out.get("settling").asText()).isEqualTo("Предварительный вердикт — дождись обновлённого результата. ♻︎ Автоповтор 1/1: выполняется — 0, в очереди — 1.");
    }
}
