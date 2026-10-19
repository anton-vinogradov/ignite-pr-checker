package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The bot's verdict on apache/ignite-python-thin-client#69 linked only TeamCity: the checker's page had no PRs of the
 * Python client. Now {@code ?repo=python&pr=69} opens the verdict of the PR's newest run of Thin client: Python, and
 * {@code ?pr=69} stays Ignite's PR #69. The page asks the client's endpoints, shows the commits the run tested, leaves
 * out the Java-only cards, and Run queues the suite as {@code /run-all} does. The client's open PRs stand in a group
 * under Ignite's, and the filter takes {@code python#69}.
 */
class ClientPrPageTest {
    private static final String CONFLICTS = "GitHub reports merge conflicts between this PR and its base branch."
        + " Resolve them and run the suite again, or press Run anyway to queue it on the PR as it is";

    /** A verdict of the client's PR #69 with one blocker, and the client's list of one PR that alice ran. */
    private static final String CLIENT = """
        const CLIENT_SHA = 'a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4';
        const IGNITE_SHA = '1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c';
        function clientVerdict(over) {
            return Object.assign(verdict({ prNumber: 69, buildId: 9424794, branchName: 'pull/69', suitesRan: 1,
                suitesReused: 0, blockers: [Object.assign(blocker('x', 'o2'), { name: 'test_sql.test_sql_fields_query',
                    suite: 'IgniteTests24Java8_WiPThinClientPython', suiteBuildId: 9424794,
                    suiteName: 'Thin client: Python',
                    reason: 'not seen failing in 100 master runs; failed the only run on this branch' })],
                filtered: [Object.assign(blocker('y', 'o1'), { name: 'test_cache_config.test_get', blocker: false,
                    suite: 'IgniteTests24Java8_WiPThinClientPython', suiteBuildId: 9424794,
                    suiteName: 'Thin client: Python', reason: 'pre-existing: fails 3/100 on master' })] }),
                { repo: 'python', rerunId: 0, suiteName: 'Thin client: Python', clientSha: CLIENT_SHA,
                    igniteSha: IGNITE_SHA, clientRepo: 'apache/ignite-python-thin-client', igniteRepo: 'apache/ignite',
                    standing: 'BLOCKERS', caveats: [], pushed: { pending: false, checked: true } }, over);
        }
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120, clientRepo: 'apache/ignite-python-thin-client',
            clientSuite: 'Thin client: Python' } });
        page.route('/api/client/analyze', { body: clientVerdict() });
        page.route('/api/client/runs', { body: [] });
        page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 1 } });
        page.route('/api/client/visa', { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29117',
            url: 'https://jira.example/c/1' } });
        page.route('/api/client/pr-title', { body: { title: 'IGNITE-29117 Fix test to forcefully use H2 engine' } });
        page.route('/api/client/prs', { body: [{ number: 69, title: 'IGNITE-29117 Fix test to forcefully use H2 engine',
            url: 'https://github.com/apache/ignite-python-thin-client/pull/69', triggeredBy: 'alice', blockers: 1,
            proven: false, headSha: CLIENT_SHA, standing: 'BLOCKERS' }] });
        const asked = [];
        page.run('window').confirm = q => { asked.push(q); return true; };
        """;

    @Test
    void theClientsPrOpensOnItsOwnAddressAndAsksTheClientsEndpoints() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            await page.load('?repo=python&pr=69');
            await page.tick(100);
            const hidden = id => page.el(id).classList.contains('hidden');
            report({ asked: page.fetches('/api/client/').map(f => f.url.replace(/&build=\\d+/, '')),
                ignite: ['/api/analyze', '/api/runs', '/api/pr-tests', '/api/delta', '/api/pending', '/api/reruns',
                    '/api/settling', '/api/visa', '/api/causes', '/api/progress']
                    .filter(p => page.fetches(p).length),
                title: page.text('prTitle'), fresh: page.text('freshText'), freshHtml: page.el('freshText').innerHTML,
                visa: page.text('visaLine'), client: page.el('results').classList.contains('client'),
                prTests: hidden('prTestsCard'), filtered: !hidden('filteredCard'), label: page.text('runAllLabel'),
                again: page.text('pendingRerun'), force: page.el('statusForce').title, tab: page.run('document.title'),
                tabs: hidden('blockerTabs') });
            """);

        assertThat(out.get("asked").toString()).contains("/api/client/analyze?pr=69", "/api/client/runs?pr=69",
            "/api/client/pr-title?pr=69", "/api/client/settling?pr=69", "/api/client/visa?pr=69");
        assertThat(out.get("ignite")).isEmpty();
        assertThat(out.get("title").asText()).isEqualTo("Python client #69 IGNITE-29117 Fix test to forcefully use H2 engine");
        assertThat(out.get("fresh").asText()).startsWith("Thin client: Python build 9424794 · tested the PR at a1b2c3d"
            + " against Ignite master at 1f2e3d4 · finished ");
        assertThat(out.get("freshHtml").asText())
            .contains("href=\"https://ci2.example/build/9424794\"")
            .contains("href=\"https://github.com/apache/ignite-python-thin-client/pull/69/commits/a1b2c3d4e5f60718")
            .contains("href=\"https://github.com/apache/ignite/commit/1f2e3d4c5b6a7988");
        assertThat(out.get("visa").asText()).isEqualTo("Visa: posted to IGNITE-29117");
        assertThat(out.get("client").asBoolean()).isTrue();
        assertThat(out.get("prTests").asBoolean()).isTrue();
        assertThat(out.get("filtered").asBoolean()).isTrue();
        assertThat(out.get("label").asText()).isEqualTo("Thin client: Python:");
        assertThat(out.get("again").asText()).isEqualTo("Run the suite");
        assertThat(out.get("force").asText()).isEqualTo("Queues the suite although GitHub reports merge conflicts in"
            + " this PR. Asks first.");
        assertThat(out.get("tab").asText()).isEqualTo("❌ 1 blocker · python#69 — Ignite PR Checker");
        assertThat(out.get("tabs").asBoolean()).isTrue();
    }

    /** The cards hide their own Rerun buttons and the AI prompts for a client's PR: Run re-runs its one suite. */
    @Test
    void theCardsOfAClientsPrHaveNoRerunOfTheirOwn() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);

        assertThat(css).contains("#results.client .rerun-advice, #results.client .sect-act, #results.client .suite-runs,"
            + "\n        #results.client .group-rerun, #results.client .why.ai, #results.client .ai-suite,"
            + " #results.client .ai-cause { display: none; }");
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            await page.load('?repo=python&pr=69');
            await page.run('rerunSuite')('IgniteTests24Java8_WiPThinClientPython', false, page.el('x'));
            await page.run('rerunSuites')(['IgniteTests24Java8_WiPThinClientPython'], false, page.el('x'), null);
            report({ posted: page.fetches('/api/rerun-suite').length });
            """);

        assertThat(out.get("posted").asInt()).isZero();
    }

    @Test
    void ignitesPrSixtyNineStaysOnItsAddress() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            await page.load('?pr=69');
            report({ ignite: page.fetches('/api/analyze').map(f => f.url),
                client: page.fetches('/api/client/analyze').length,
                isClient: page.el('results').classList.contains('client'), label: page.text('runAllLabel') });
            """);

        assertThat(out.get("ignite").toString()).isEqualTo("[\"/api/analyze?pr=69\"]");
        assertThat(out.get("client").asInt()).isZero();
        assertThat(out.get("isClient").asBoolean()).isFalse();
        assertThat(out.get("label").asText()).isEqualTo("RunAll:");
    }

    @Test
    void runQueuesTheSuiteAndRunAnywayForcesItOnAPrWithConflicts() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            page.route('/api/client/trigger', url => url.includes('force=true')
                ? { body: { triggered: [{ buildId: 9424800, state: 'queued', name: 'Thin client: Python',
                    btId: 'IgniteTests24Java8_WiPThinClientPython', webUrl: 'https://ci2.example/build/9424800',
                    pct: -1, leftSec: -1, startSec: -1, waitedSec: -1, elapsedSec: -1 }], replaced: 0 } }
                : { status: 409, body: { error: '%s', conflicts: true } }, 'POST');
            await page.load('?repo=python&pr=69');
            await page.run('doTrigger')('trigger', 'false', page.el('run'));
            const refused = page.text('status');
            const offered = !page.el('statusForce').classList.contains('hidden');
            page.el('statusForce').onclick();
            await page.settle();
            report({ asked, refused, offered, status: page.text('status'),
                posted: page.fetches('/api/client/trigger').map(f => f.url), ignite: page.fetches('/api/trigger').length });
            """.formatted(CONFLICTS));

        assertThat(out.get("asked").get(0).asText()).isEqualTo("Queue “Thin client: Python” for Python client PR #69?"
            + " It tests the PR's head against Ignite master as it is now.");
        assertThat(out.get("asked").get(1).asText()).startsWith("GitHub reports merge conflicts in this PR. Queue “Thin"
            + " client: Python” for Python client PR #69?");
        assertThat(out.get("refused").asText()).isEqualTo("⚠ Not queued: " + CONFLICTS);
        assertThat(out.get("offered").asBoolean()).isTrue();
        assertThat(out.get("posted").toString()).isEqualTo("[\"/api/client/trigger?pr=69&top=false\","
            + "\"/api/client/trigger?pr=69&top=false&force=true\"]");
        assertThat(out.get("status").asText()).isEqualTo("Queued 1 build: #9424800.");
        assertThat(out.get("ignite").asInt()).isZero();
    }

    @Test
    void cancelMyRunsCancelsYourRunsOfTheSuiteOnThisPr() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            page.route('/api/client/runs', { body: [{ buildId: 9424800, name: 'Thin client: Python', state: 'running',
                webUrl: 'https://ci2.example/build/9424800', pct: 10, leftSec: 900, by: 'alice', mine: true,
                runAll: true, pinned: true }] });
            page.route('/api/client/cancel-all', { body: { cancelled: 1 } }, 'POST');
            await page.load('?repo=python&pr=69');
            const runs = page.text('runs');
            await page.run('cancelMine')();
            report({ asked, runs, posted: page.fetches('/api/client/cancel-all').map(f => f.url),
                status: page.text('status') });
            """);

        assertThat(out.get("runs").asText()).isEqualTo("On ci2 now: Thin client: Pythonrunning · ~15m left");
        assertThat(out.get("asked").get(0).asText()).startsWith("Cancel your 1 run of Python client PR #69?");
        assertThat(out.get("posted").toString()).isEqualTo("[\"/api/client/cancel-all?pr=69&ids=9424800\"]");
        assertThat(out.get("status").asText()).isEqualTo("Cancelled 1 run.");
    }

    @Test
    void aPrWithoutARunOfItsCodeSaysHowToStartOne() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            page.route('/api/client/analyze', { status: 404, body: { error: 'no run of the suite tested this PR\\'s own'
                + ' commit', noRun: true } });
            await page.load('?repo=python&pr=69');
            report({ status: page.text('status') });
            """);

        assertThat(out.get("status").asText()).isEqualTo("No run of the suite tested this PR's code yet. Start one with"
            + " Run above, or post /run-all in the PR.");
    }

    /** Ignite's PR #69 opened while the client's PR #69 was still loading: the client's late answer is not drawn. */
    @Test
    void aLateAnswerForTheClientsPrIsNotDrawnOnIgnites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            let release;
            page.route('/api/client/analyze', () => new Promise(done => { release = () => done({ body: clientVerdict() }); }));
            await page.load('?repo=python&pr=69');
            page.run('openPr')(69, true, null);
            await page.settle();
            release();
            await page.settle();
            report({ fresh: page.text('freshText'), client: page.el('results').classList.contains('client'),
                pushed: page.pushed });
            """);

        assertThat(out.get("fresh").asText()).startsWith("RunAll build 9001");
        assertThat(out.get("client").asBoolean()).isFalse();
        assertThat(out.get("pushed").toString()).isEqualTo("[\"?pr=69\"]");
    }

    @Test
    void theClientsPrsStandInAGroupUnderIgnites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            await page.load('');
            report({ html: page.el('clientList').innerHTML, count: page.text('clientCount'),
                shown: !page.el('clientGroup').classList.contains('hidden') });
            """);

        assertThat(out.get("html").asText())
            .contains("<li data-num=\"69\" data-repo=\"python\" data-url=\"https://github.com/apache/ignite-python-thin-client/pull/69\"")
            .contains("<a class=\"pr-link\" href=\"?repo=python&pr=69\" data-num=\"69\" data-repo=\"python\"")
            .contains("<span class=\"pr-num\">#69</span> <span class=\"pr-key\">IGNITE-29117</span>"
                + "<span class=\"pr-badge bad\" title=\"1 blocker\"")
            .contains("data-triggeredby=\"alice\"")
            .contains("<span class=\"my-tag\" title=\"You started its latest run of the suite. The PR may be someone"
                + " else's.\">my run</span>");
        assertThat(out.get("count").asText()).isEqualTo("(1)");
        assertThat(out.get("shown").asBoolean()).isTrue();
    }

    @Test
    void theFilterTakesPythonAndANumber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            await page.load('');
            const filter = page.el('prFilter');
            const type = async text => {
                filter.value = text;
                page.run('applyPrFilter')();
            };
            const any = page.el('prOpenAny');
            await type('python#70');
            const other = { text: any.textContent, href: any.href, repo: any.dataset.repo,
                hidden: any.classList.contains('hidden') };
            await type('69');
            const ignite = { text: any.textContent, href: any.href, repo: any.dataset.repo || null };
            await type('python#69');
            const listed = { hidden: any.classList.contains('hidden'), none: !page.el('prNone').classList.contains('hidden'),
                group: !page.el('clientGroup').classList.contains('hidden') };
            for (const fn of filter.listeners.keydown) fn({ key: 'Enter' });
            await page.settle();
            report({ other, ignite, listed, pushed: page.pushed,
                asked: page.fetches('/api/client/analyze').map(f => f.url) });
            """);

        assertThat(out.get("other").toString()).isEqualTo("{\"text\":\"Open Python client PR #70 →\","
            + "\"href\":\"?repo=python&pr=70\",\"repo\":\"python\",\"hidden\":false}");
        assertThat(out.get("ignite").toString()).isEqualTo("{\"text\":\"Open PR #69 →\",\"href\":\"?pr=69\","
            + "\"repo\":null}");
        assertThat(out.get("listed").toString()).isEqualTo("{\"hidden\":true,\"none\":false,\"group\":true}");
        assertThat(out.get("pushed").toString()).isEqualTo("[\"?repo=python&pr=69\"]");
        assertThat(out.get("asked").toString()).isEqualTo("[\"/api/client/analyze?pr=69\"]");
    }

    /** Mine lists the PRs of the user's linked GitHub account in both repos: here the client's one only. */
    @Test
    void mineListsYourPrsOfTheClientToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            page.route('/api/me', { body: { username: 'alice', githubLinked: true } });
            page.route('/api/your-prs', { body: { login: 'alice-gh', mine: [], involved: [] } });
            page.route('/api/client/your-prs', { body: { login: 'alice-gh', mine: [{ number: 68,
                title: 'Support Python 3.13', url: 'https://github.com/apache/ignite-python-thin-client/pull/68',
                triggeredBy: null, blockers: null }], involved: [] } });
            await page.load('');
            report({ html: page.el('clientList').innerHTML, note: page.text('prTabNote'),
                noteShown: !page.el('prTabNote').classList.contains('hidden'),
                asked: page.fetches('/api/client/your-prs').length });
            """);

        assertThat(out.get("html").asText()).contains("href=\"?repo=python&pr=68\"").doesNotContain("pr=69");
        assertThat(out.get("noteShown").asBoolean()).isFalse();
        assertThat(out.get("asked").asInt()).isEqualTo(1);
    }

    /** A click on Mine after All asks for the client's PRs of the tab too, as for Ignite's. */
    @Test
    void aClickOnMineAsksForYourPrsOfTheClient() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + """
            page.route('/api/me', { body: { username: 'alice', githubLinked: true } });
            page.route('/api/your-prs', { body: { login: 'alice-gh', mine: [], involved: [] } });
            page.route('/api/client/your-prs', { body: { login: 'alice-gh', mine: [{ number: 68,
                title: 'Support Python 3.13', url: 'https://github.com/apache/ignite-python-thin-client/pull/68',
                triggeredBy: null, blockers: null }], involved: [] } });
            page.run('localStorage').setItem('prTab', 'all');
            await page.load('');
            const before = page.fetches('/api/client/your-prs').length;
            page.run('pickPrTab')('mine');
            await page.settle();
            report({ before, after: page.fetches('/api/client/your-prs').length,
                html: page.el('clientList').innerHTML });
            """);

        assertThat(out.get("before").asInt()).isZero();
        assertThat(out.get("after").asInt()).isEqualTo(1);
        assertThat(out.get("html").asText()).contains("href=\"?repo=python&pr=68\"");
    }

    @Test
    void theLoginFormNamesTheClientsPr() throws Exception {
        JsonNode out = PageScript.run("index.html", """
            page.route('/api/me', { status: 401, body: {} });
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
                clientRepo: 'apache/ignite-python-thin-client' } });
            await page.load('?repo=python&pr=69');
            report({ head: page.text('loginPrHead'), note: page.el('loginPrNote').innerHTML });
            """);

        assertThat(out.get("head").asText()).isEqualTo("Log in to see Python client PR #69: which test failures are its"
            + " own, and how its runs are going.");
        assertThat(out.get("note").asText()).contains("https://github.com/apache/ignite-python-thin-client/pull/69");
    }

    @Test
    void theClientsPageSpeaksRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + CLIENT + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            page.route('/api/client/analyze', { body: clientVerdict({ blockers: [], standing: 'WATCH', watch: [
                Object.assign(blocker('w', 'o3'), { name: 'test_sql.test_sql', blocker: false, watch: true,
                    suite: 'IgniteTests24Java8_WiPThinClientPython', suiteName: 'Thin client: Python',
                    reason: 'failed the only run on commits 1f2e3d4 and a1b2c3d' })],
                pushed: { pending: true, checked: true, ahead: 0, builtSha: 'a1b2c3d', headSha: 'b2c3d4e',
                    caveat: 'new commits pushed since this run — it tested older code', rewritten: false } }) });
            page.route('/api/client/runs', { body: [{ buildId: 9424800, name: 'Thin client: Python', state: 'queued',
                webUrl: 'https://ci2.example/build/9424800', pct: -1, startSec: 300, by: 'bob', mine: false,
                runAll: true, pinned: true }] });
            page.route('/api/client/settling', { body: { phase: 'settling', wave: 1, of: 1,
                what: 'Thin client: Python' } });
            await page.load('?repo=python&pr=69');
            await page.tick(100);
            const ids = ['prTitle', 'verdictText', 'verdictLines', 'freshText', 'runs', 'settling', 'pending',
                'pendingRerun', 'runAllLabel', 'visaLine'];
            const html = ['freshText', 'runs', 'watch', 'filtered'].map(id => page.el(id).innerHTML).join('\\n');
            report({ texts: Object.fromEntries(ids.map(id => [id, page.text(id)])), html,
                question: page.run('clientRunQuestion')(69, true, []) });
            """);

        JsonNode texts = out.get("texts");
        assertThat(EnglishLeft.in(texts, out.get("html").asText(), List.of("Thin client: Python", "Fix test to"
            + " forcefully use H2 engine"))).isEmpty();
        assertThat(texts.get("freshText").asText()).startsWith("Thin client: Python, сборка 9424794 · проверен PR на"
            + " коммите a1b2c3d с master проекта Ignite на коммите 1f2e3d4");
        assertThat(texts.get("settling").asText()).isEqualTo("♻︎ Автоперезапуск сьюты на тех же коммитах: вердикт ниже"
            + " может измениться, когда он закончится.");
        assertThat(texts.get("pendingRerun").asText()).isEqualTo("Запустить сьюту");
        assertThat(out.get("question").asText()).startsWith("Поставить «Thin client: Python» для PR #69 клиента Python"
            + " в начало очереди ci2");
    }
}
