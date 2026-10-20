package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The author of apache/ignite#13709 re-ran the suite that had a blocker on the PR's head, and the verdict became a
 * mixed run with no blockers. The page had no way to put that verdict into the ticket. Post visa stands in the visa's
 * row: it asks first, naming the run and the ticket, posts with the author's stored JIRA token, and the line then links
 * the visa. While the verdict can change, the PR title names no ticket or the reader has no JIRA token stored, it is
 * grey, and the ? next to it says why; for the token it opens ⚙.
 */
class PostVisaPageTest {
    private static final String TOKEN_MISSING = "Post visa uses the JIRA token you save in ⚙, and you have none"
        + " there. Saving one also switches on Visa my runs in JIRA.";

    private static final String WAIT = "Post visa waits for the final verdict: runs of this PR can still change it.";

    /** The row of alice's run, whose visa JIRA did not take, and a page that keeps the questions it asks. */
    private static final String ROW = """
        const URL = 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=7#comment-7';
        const row = over => ({ body: Object.assign({ state: 'retrying', starter: 'alice', mine: true, issue: null,
            url: null, jiraRefused: false, jiraReady: true, yoursIn: null }, over || {}) });
        page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
        // The row the page polls for, and the answer to Post visa; a scenario sets either, or a function for it.
        let shown = row();
        let answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049', url: URL,
            jiraRefused: false, jiraReady: true, yoursIn: 'IGNITE-29049', edited: false } };
        const visaRoute = (url, method) => {
            const it = method === 'POST' ? answer : shown;
            return typeof it === 'function' ? it() : it;
        };
        page.route('/api/visa', visaRoute);
        page.route('/api/client/visa', visaRoute);
        const asked = [];
        page.run('window').confirm = q => { asked.push(q); return true; };
        const posts = () => page.fetches('/api/').filter(f => f.method === 'POST' && f.url.includes('visa?'));
        const state = () => ({ shown: !page.el('visaPostGroup').classList.contains('hidden'),
            off: page.el('visaPost').disabled, why: page.el('visaPostWhy').classList.contains('hidden') ? null
                : page.el('visaPostWhy').title, title: page.el('visaPost').title });
        """;

    @Test
    void aFinalVerdictIsPostedAfterTheQuestionAndTheLineLinksTheVisa() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await page.el('visaPost').onclick();
            await page.settle();
            report({ before, asked, posts: posts().map(f => f.url), line: page.el('visaLine').innerHTML,
                status: page.el('status').textContent, after: state() });
            """);

        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/off").asBoolean()).isFalse();
        assertThat(out.at("/before/why").isNull()).isTrue();
        assertThat(out.at("/before/title").asText()).isEqualTo("Posts the verdict on this page to IGNITE-29049 as your"
            + " comment in JIRA, with the JIRA token you saved in ⚙. A second press for this run updates that"
            + " comment.");
        assertThat(out.get("asked").get(0).asText()).isEqualTo("Post the visa of RunAll 9001 to IGNITE-29049? It goes"
            + " in as your comment in JIRA, with the verdict on this page.");
        assertThat(out.get("posts").get(0).asText()).isEqualTo("/api/visa?pr=13575&build=9001&issue=IGNITE-29049");
        assertThat(out.get("line").asText()).isEqualTo("Visa: posted to <a href=\"https://issues.apache.org/jira/"
            + "browse/IGNITE-29049?focusedCommentId=7#comment-7\" target=\"_self\" rel=\"noopener\" title=\"Opens the"
            + " visa in JIRA.\">IGNITE-29049</a>");
        assertThat(out.get("status").asText()).isEqualTo("Visa posted to IGNITE-29049.");
        assertThat(out.at("/after/off").asBoolean()).as("a second press updates the visa").isFalse();
    }

    /** The reader's own visa of the run is in the ticket: the question says the press updates it, as the answer does. */
    @Test
    void aSecondPressAsksToUpdateTheReadersVisa() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'posted', issue: 'IGNITE-29049', url: URL, yoursIn: 'IGNITE-29049' });
            answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049', url: URL,
                jiraReady: true, yoursIn: 'IGNITE-29049', edited: true } };
            await page.load('?pr=13575');
            await page.settle();
            await page.el('visaPost').onclick();
            await page.settle();
            report({ asked, status: page.el('status').textContent });
            """);

        assertThat(out.get("asked").get(0).asText())
            .isEqualTo("Update your visa of RunAll 9001 in IGNITE-29049 with the verdict on this page?");
        assertThat(out.get("status").asText()).isEqualTo("Your visa in IGNITE-29049 now has the verdict on this page.");
    }

    /** bob's option posted the visa of his run: alice's goes in as a comment of its own, and the question says so. */
    @Test
    void someoneElsesVisaOfTheRunIsNamedInTheQuestion() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'posted', starter: 'bob', mine: false, issue: 'IGNITE-29049', url: URL });
            await page.load('?pr=13575');
            await page.settle();
            await page.el('visaPost').onclick();
            await page.settle();
            report({ asked });
            """);

        assertThat(out.get("asked").get(0).asText()).isEqualTo("Post the visa of RunAll 9001 to IGNITE-29049? It goes"
            + " in as your comment in JIRA, with the verdict on this page.\n\nbob's visa of this run is in"
            + " IGNITE-29049 already. Yours goes in as a comment of its own.");
    }

    /** No JIRA token stored: the button is grey, its ? says why in the status line too, and opens ⚙. */
    @Test
    void withoutAStoredJiraTokenTheQuestionMarkOpensSettings() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'off', jiraReady: false });
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await page.el('visaPost').onclick();
            page.el('visaPostWhy').onclick({ preventDefault() {} });
            await page.settle();
            report({ before, posts: posts().length, status: page.el('status').textContent,
                settings: !page.el('settingsPanel').classList.contains('hidden') });
            """);

        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/off").asBoolean()).isTrue();
        assertThat(out.at("/before/why").asText()).isEqualTo(TOKEN_MISSING + " A click opens ⚙.");
        assertThat(out.at("/before/title").asText()).isEmpty();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("status").asText()).isEqualTo(TOKEN_MISSING);
        assertThat(out.get("settings").asBoolean()).isTrue();
    }

    /** A suite re-run of the PR is going: the mixed verdict can still change, so the visa waits. */
    @Test
    void whileARunOfThePrGoesTheButtonWaits() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/runs', { body: [{ buildId: 9426316, buildTypeId: 'IgniteTests24Java8_ControlUtility5',
                name: 'Control Utility 5', state: 'running', webUrl: 'https://ci2.example/b', by: 'alice', mine: true,
                runAll: false }] });
            await page.load('?pr=13575');
            await page.settle();
            const going = state();
            page.route('/api/runs', { body: [] });
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2 } });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ going, settling: state() });
            """);

        assertThat(out.at("/going/off").asBoolean()).isTrue();
        assertThat(out.at("/going/why").asText()).isEqualTo(WAIT);
        assertThat(out.at("/settling/off").asBoolean()).isTrue();
        assertThat(out.at("/settling/why").asText()).isEqualTo(WAIT);
    }

    @Test
    void aTitleWithoutATicketGreysTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            page.run('setPrTitle')(13575, 'Move MDC to JUnit', null);
            await page.run('loadVisaLine')(13575);
            report({ line: page.el('visaLine').textContent, row: state() });
            """);

        assertThat(out.get("line").asText()).isEqualTo("Visa: the PR title names no IGNITE ticket");
        assertThat(out.at("/row/off").asBoolean()).isTrue();
        assertThat(out.at("/row/why").asText()).isEqualTo("The PR title names no IGNITE ticket, so there is no ticket"
            + " to post the visa to. Add the ticket key to the title.");
    }

    /** JIRA refused the stored token: the answer says so, and the button greys as the token is gone. */
    @Test
    void aRefusedTokenIsSaidAndGreysTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            let refused = false;
            shown = () => row({ jiraReady: !refused });
            answer = () => {
                refused = true;
                return { status: 412, body: { error: 'JIRA rejected your JIRA token, so the checker dropped it and'
                    + ' switched off Visa my runs in JIRA. Save a new token in ⚙.', need: 'jira' } };
            };
            await page.load('?pr=13575');
            await page.settle();
            await page.el('visaPost').onclick();
            await page.settle();
            report({ status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                row: state() });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Visa not posted: JIRA rejected your JIRA token, so the"
            + " checker dropped it and switched off Visa my runs in JIRA. Save a new token in ⚙.");
        assertThat(out.get("err").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isTrue();
        assertThat(out.at("/row/why").asText()).startsWith(TOKEN_MISSING);
    }

    /**
     * The client's PR #69: its run has no visa on record and its verdict is final, so the line says none came, and Post
     * visa posts to the client's endpoint, naming the suite's build.
     */
    @Test
    void aClientPrsRunIsPostedToTheClientsEndpoint() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
                starCount: 1, refreshAfterSeconds: 120, clientRepo: 'apache/ignite-python-thin-client',
                clientSuite: 'Thin client: Python' } });
            page.route('/api/client/analyze', { body: Object.assign(verdict({ prNumber: 69, buildId: 9424794,
                branchName: 'pull/69', blockers: [] }), { repo: 'python', rerunId: 0, suiteName: 'Thin client: Python',
                standing: 'CLEAN', caveats: [], pushed: { pending: false, checked: true } }) });
            page.route('/api/client/runs', { body: [] });
            page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 1 } });
            shown = { body: { state: null, starter: null, mine: false, jiraReady: true, yoursIn: null } };
            answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29117', url: URL,
                jiraReady: true, yoursIn: 'IGNITE-29117', edited: false } };
            page.route('/api/client/pr-title', { body: {
                title: 'IGNITE-29117 Fix test to forcefully use H2 engine' } });
            page.route('/api/client/prs', { body: [] });
            await page.load('?repo=python&pr=69');
            await page.tick(100);
            const line = page.el('visaLine').textContent;
            await page.el('visaPost').onclick();
            await page.settle();
            report({ line, asked, posts: posts().map(f => f.url), status: page.el('status').textContent });
            """);

        assertThat(out.get("line").asText()).isEqualTo("Visa: none for this run");
        assertThat(out.get("asked").get(0).asText()).isEqualTo("Post the visa of Thin client: Python build 9424794 to"
            + " IGNITE-29117? It goes in as your comment in JIRA, with the verdict on this page.");
        assertThat(out.get("posts").get(0).asText())
            .isEqualTo("/api/client/visa?pr=69&build=9424794&issue=IGNITE-29117");
        assertThat(out.get("status").asText()).isEqualTo("Visa posted to IGNITE-29117.");
    }

    /** The Russian page names the button and its question in Russian. */
    @Test
    void theRussianPageSaysItInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + ROW + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            shown = row({ jiraReady: false });
            await page.load('?pr=13575');
            await page.settle();
            report({ button: page.text('visaPost'), why: page.el('visaPostWhy').title,
                label: page.el('visaPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.get("button").asText()).isEqualTo("Поставить визу");
        assertThat(out.get("why").asText()).isEqualTo("Кнопка «Поставить визу» берёт токен JIRA, который ты сохраняешь"
            + " в ⚙, а там его нет. Когда ты сохранишь токен, заодно включится опция «Ставить визу моим прогонам в"
            + " JIRA». Щелчок открывает ⚙.");
        assertThat(out.get("label").asText()).isEqualTo("Почему кнопка «Поставить визу» недоступна");
    }
}
