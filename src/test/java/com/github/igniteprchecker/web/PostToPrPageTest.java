package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The owner looked at the verdict of #13721 and asked for two buttons: one to JIRA, one to the PR. Post to PR stands
 * next to Post to JIRA in the visa's row. It asks first, naming the run and the PR, and the checker's account puts the
 * verdict into the PR; when the PR has the checker's verdict comment of the run, the question and the answer say the
 * press updates it. It needs no ticket in the PR title. While the verdict can change, or the checker cannot write to
 * GitHub, it is grey, and the ? next to it says why.
 */
class PostToPrPageTest {
    private static final String COMMENT = "https://github.com/apache/ignite/pull/13575#issuecomment-4100";

    private static final String WAIT = "Post to PR waits for the final verdict: runs of this PR can still change it.";

    private static final String NO_BOT = "This checker has no GitHub account to write with (GITHUB_TOKEN), so it cannot"
        + " post to the PR. Its operator can add one.";

    /** The client's PR #69, whose newest run of the suite has a final verdict. */
    private static final String CLIENT_PR = """
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120, clientRepo: 'apache/ignite-python-thin-client',
            clientSuite: 'Thin client: Python' } });
        page.route('/api/client/analyze', { body: Object.assign(verdict({ prNumber: 69, buildId: 9424794,
            branchName: 'pull/69', blockers: [] }), { repo: 'python', rerunId: 0, suiteName: 'Thin client: Python',
            standing: 'CLEAN', caveats: [], pushed: { pending: false, checked: true } }) });
        page.route('/api/client/runs', { body: [] });
        page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 1 } });
        page.route('/api/client/pr-title', { body: { title: 'IGNITE-29117 Fix test to forcefully use H2 engine' } });
        page.route('/api/client/prs', { body: [] });
        """;

    /** The row of the run, the answer to a press, and a page that keeps the questions it asks. */
    private static final String ROW = """
        const COMMENT = 'https://github.com/apache/ignite/pull/13575#issuecomment-4100';
        page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
        // The row the page polls for, and the answer to Post to PR; a scenario sets either, or a function for it.
        let shown = { body: { bot: 'ready', url: null } };
        let answer = { body: { bot: 'ready', url: COMMENT, edited: false } };
        const prRoute = (url, method) => {
            const it = method === 'POST' ? answer : shown;
            return typeof it === 'function' ? it() : it;
        };
        page.route('/api/pr-comment', prRoute);
        page.route('/api/client/pr-comment', prRoute);
        const asked = [];
        page.run('window').confirm = q => { asked.push(q); return true; };
        const posts = () => page.fetches('/api/').filter(f => f.method === 'POST' && f.url.includes('pr-comment?'));
        const state = () => ({ shown: !page.el('prPostGroup').classList.contains('hidden'),
            off: page.el('prPost').disabled, why: page.el('prPostWhy').classList.contains('hidden') ? null
                : page.el('prPostWhy').title, title: page.el('prPost').title });
        """;

    @Test
    void aFinalVerdictIsPostedAfterTheQuestionAndTheStatusLinksTheComment() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await page.el('prPost').onclick();
            await page.settle();
            const after = state();
            await page.el('prPost').onclick();
            report({ before, after, asked, posts: posts().map(f => f.url), status: page.el('status').innerHTML,
                button: page.text('prPost'), visaButton: page.text('visaPost') });
            """);

        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/off").asBoolean()).isFalse();
        assertThat(out.at("/before/why").isNull()).isTrue();
        assertThat(out.at("/before/title").asText()).isEqualTo("Posts the verdict on this page to the PR as a comment"
            + " of the checker's GitHub account. A second press for this run updates that comment.");
        assertThat(out.get("asked").get(0).asText()).isEqualTo("Post the verdict of RunAll 9001 to PR #13575? It goes"
            + " in as a comment of the checker's GitHub account, and its last line names you.");
        assertThat(out.get("posts").get(0).asText()).isEqualTo("/api/pr-comment?pr=13575&build=9001");
        assertThat(out.get("status").asText()).isEqualTo("Verdict posted to <a href=\"" + COMMENT + "\""
            + " target=\"_self\" rel=\"noopener\" title=\"Opens the comment on GitHub.\">PR #13575</a>.");
        assertThat(out.at("/after/title").asText()).as("the comment is in, so a press now updates it")
            .isEqualTo("Updates the verdict comment of this run in the PR with the verdict on this page.");
        assertThat(out.get("asked").get(1).asText())
            .isEqualTo("Update the verdict comment of RunAll 9001 in PR #13575 with the verdict on this page?");
        assertThat(out.get("button").asText()).isEqualTo("Post to PR");
        assertThat(out.get("visaButton").asText()).isEqualTo("Post to JIRA");
    }

    /** The option of the run's starter posted its verdict comment: the press updates it, and the answer says so. */
    @Test
    void theVerdictCommentOfTheRunIsUpdated() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'ready', url: COMMENT } };
            answer = { body: { bot: 'ready', url: COMMENT, edited: true } };
            await page.load('?pr=13575');
            await page.settle();
            const title = page.el('prPost').title;
            await page.el('prPost').onclick();
            await page.settle();
            report({ title, asked, status: page.el('status').textContent });
            """);

        assertThat(out.get("title").asText())
            .isEqualTo("Updates the verdict comment of this run in the PR with the verdict on this page.");
        assertThat(out.get("asked").get(0).asText())
            .isEqualTo("Update the verdict comment of RunAll 9001 in PR #13575 with the verdict on this page?");
        assertThat(out.get("status").asText())
            .isEqualTo("The verdict comment in PR #13575 now has the verdict on this page.");
    }

    /** A checker without GITHUB_TOKEN cannot write to the PR: the button is grey, and its ? says so in the status line. */
    @Test
    void withoutAnAccountToWriteWithTheButtonIsGrey() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'none', url: null } };
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await page.el('prPost').onclick();
            page.el('prPostWhy').onclick({ preventDefault() {} });
            await page.settle();
            report({ before, posts: posts().length, status: page.el('status').textContent,
                tag: page.el('prPostWhy').tagName, label: page.el('prPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/off").asBoolean()).isTrue();
        assertThat(out.at("/before/why").asText()).isEqualTo(NO_BOT);
        assertThat(out.at("/before/title").asText()).isEmpty();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("status").asText()).isEqualTo(NO_BOT);
        assertThat(out.get("tag").asText()).isEqualTo("BUTTON");
        assertThat(out.get("label").asText()).isEqualTo("Why Post to PR is off");
    }

    /** GitHub refuses the checker's token, or asked it to wait: each greys the button with its own reason. */
    @Test
    void aRefusedOrPausedAccountGreysTheButtonWithItsReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'refused', url: null } };
            await page.load('?pr=13575');
            await page.settle();
            const refused = state();
            shown = { body: { bot: 'paused', url: null } };
            await page.run('loadVisaLine')(13575);
            await page.settle();
            report({ refused, paused: state() });
            """);

        assertThat(out.at("/refused/off").asBoolean()).isTrue();
        assertThat(out.at("/refused/why").asText()).isEqualTo("GitHub refuses the token the checker writes with"
            + " (GITHUB_TOKEN), so the checker cannot post to the PR now.");
        assertThat(out.at("/paused/off").asBoolean()).isTrue();
        assertThat(out.at("/paused/why").asText())
            .isEqualTo("GitHub asked the checker to wait before it writes again. Try again later.");
    }

    /** A suite re-run of the PR is going, then an auto re-run settles the run: the verdict can still change. */
    @Test
    void whileRunsCanChangeTheVerdictTheButtonWaits() throws Exception {
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

    /**
     * The page asks where the run stands and what Post to PR can do at once, and learns that auto re-runs settle the
     * run only after the row came: the button greys then too.
     */
    @Test
    void aLateWordThatReRunsSettleTheRunGreysTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            let answerSettling;
            page.route('/api/settling', () => new Promise(r => {
                answerSettling = () => r({ body: { phase: 'settling', wave: 1, of: 2 } });
            }));
            page.run('loadRuns')(13575);
            await page.settle();
            const before = state();
            answerSettling();
            await page.settle();
            report({ before, after: state() });
            """);

        assertThat(out.at("/before/off").asBoolean()).isFalse();
        assertThat(out.at("/after/off").asBoolean()).isTrue();
        assertThat(out.at("/after/why").asText()).isEqualTo(WAIT);
    }

    /** TeamCity errors left tests of the run unchecked a minute ago: the server takes no post for 20 minutes. */
    @Test
    void aVerdictTeamCityErrorsLeftUncheckedWaitsUpToTwentyMinutes() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/analyze', { body: verdict({ blockers: [], incompleteSince: page.now() - 60000 }) });
            await page.load('?pr=13575');
            await page.settle();
            const checking = state();
            await page.tick(19 * 60000 + 1000);
            await page.settle();
            report({ checking, after: state() });
            """);

        assertThat(out.at("/checking/off").asBoolean()).isTrue();
        assertThat(out.at("/checking/why").asText()).isEqualTo("TeamCity errors left part of this verdict unchecked."
            + " The checker checks that part again, and Post to PR waits up to 20 minutes for that.");
        assertThat(out.at("/after/off").asBoolean()).isFalse();
    }

    /** The PR title names no IGNITE ticket: Post to JIRA has nowhere to go, Post to PR still puts the verdict in. */
    @Test
    void aTitleWithoutATicketLeavesPostToPrOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/pr-title', { body: { title: 'Move MDC to JUnit' } });
            await page.load('?pr=13575');
            await page.settle();
            report({ row: state(), visaOff: page.el('visaPost').disabled, button: page.text('prPost') });
            """);

        assertThat(out.get("button").asText()).isEqualTo("Post to PR");
        assertThat(out.at("/row/shown").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isFalse();
        assertThat(out.get("visaOff").asBoolean()).isTrue();
    }

    /** GitHub asked the checker to wait while the press went: the answer says so, and the button greys. */
    @Test
    void aPauseInTheAnswerIsSaidAndGreysTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            let paused = false;
            shown = () => ({ body: { bot: paused ? 'paused' : 'ready', url: null } });
            answer = () => {
                paused = true;
                return { status: 409, body: { error: 'GitHub asked the checker to wait before it writes again. Try'
                    + ' again later.', bot: 'paused' } };
            };
            await page.load('?pr=13575');
            await page.settle();
            await page.el('prPost').onclick();
            await page.settle();
            report({ status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                row: state() });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Verdict not posted to the PR: GitHub asked the checker to"
            + " wait before it writes again. Try again later.");
        assertThat(out.get("err").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isTrue();
    }

    /** The client's PR #69: the press goes to the client's endpoint, and the question names the suite's build. */
    @Test
    void aClientPrsRunIsPostedToTheClientsEndpoint() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + CLIENT_PR + """
            answer = { body: { bot: 'ready', url: 'https://github.com/apache/ignite-python-thin-client/pull/69'
                + '#issuecomment-7', edited: false } };
            await page.load('?repo=python&pr=69');
            await page.tick(100);
            await page.el('prPost').onclick();
            await page.settle();
            report({ asked, posts: posts().map(f => f.url), status: page.el('status').textContent });
            """);

        assertThat(out.get("asked").get(0).asText()).isEqualTo("Post the verdict of Thin client: Python build 9424794"
            + " to PR #69? It goes in as a comment of the checker's GitHub account, and its last line names you.");
        assertThat(out.get("posts").get(0).asText()).isEqualTo("/api/client/pr-comment?pr=69&build=9424794");
        assertThat(out.get("status").asText()).isEqualTo("Verdict posted to PR #69.");
    }

    /** The Russian page names both buttons, the question and the status in Russian. */
    @Test
    void theRussianPageSaysItInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + ROW + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            await page.load('?pr=13575');
            await page.settle();
            const title = page.el('prPost').title;
            await page.el('prPost').onclick();
            await page.settle();
            report({ button: page.text('prPost'), visa: page.text('visaPost'), title, asked,
                status: page.el('status').textContent, label: page.el('prPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.get("button").asText()).isEqualTo("В PR");
        assertThat(out.get("visa").asText()).isEqualTo("В JIRA");
        assertThat(out.get("title").asText()).isEqualTo("Отправляет вердикт с этой страницы в PR комментарием аккаунта"
            + " GitHub чекера. Повторное нажатие для этого прогона правит тот же комментарий.");
        assertThat(out.get("asked").get(0).asText()).isEqualTo("Отправить в PR #13575 вердикт RunAll 9001? Он встанет"
            + " комментарием аккаунта GitHub чекера, а его последняя строка назовёт тебя.");
        assertThat(out.get("status").asText()).isEqualTo("Вердикт отправлен в PR #13575.");
        assertThat(out.get("label").asText()).isEqualTo("Почему кнопка «В PR» недоступна");
    }
}
