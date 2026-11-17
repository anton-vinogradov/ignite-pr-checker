package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The owner looked at the verdict of #13721 and asked for two buttons: one to JIRA, one to the PR. Post to PR is the
 * button of the group "to PR:" next to "Verdict to JIRA:" in the verdict's heading. It asks first in a dialog that
 * names the run and the PR and shows the comment as it will go, and the checker's account puts the verdict into the
 * PR; when the PR has the checker's verdict comment of the run, the group links it, and the button, the dialog and
 * the answer say the press updates it. It needs no ticket in the PR title. While the option of whoever started the run
 * still posts the comment, the group says so and has no button. While the verdict can change, or the checker cannot
 * write to GitHub, the button is grey, and a click on it, or the ? of the group, says why.
 */
class PostToPrPageTest {
    private static final String COMMENT = "https://github.com/apache/ignite/pull/13575#issuecomment-4100";

    private static final String WAIT = "Post to PR waits for the final verdict: runs of this PR can still change it.";

    private static final String NO_BOT = "This checker has no GitHub account to write with (GITHUB_TOKEN), so it cannot"
        + " post to the PR. Its operator can add one.";

    private static final String POSTS = "Posts the verdict on this page to the PR as a comment of the checker's GitHub"
        + " account. A second press for this run updates that comment.";

    private static final String UPDATES = "Updates the verdict comment of this run in the PR with the verdict on this"
        + " page.";

    private static final String TEXT = "**Ignite PR Checker** verdict\n\n✅ **No blockers**\n\n_Posted by ci2 user alice"
        + " from the checker's page._";

    /** The client's PR #69, whose newest run of the suite has a final verdict. */
    private static final String CLIENT_PR = """
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120, clients: [{ key: 'python', label: 'Python', repo: 'apache/ignite-python-thin-client', suite: 'Thin client: Python' }] } });
        page.route('/api/client/analyze', { body: Object.assign(verdict({ prNumber: 69, buildId: 9424794,
            branchName: 'pull/69', blockers: [] }), { repo: 'python', rerunId: 0, suiteName: 'Thin client: Python',
            standing: 'CLEAN', caveats: [], pushed: { pending: false, checked: true } }) });
        page.route('/api/client/runs', { body: [] });
        page.route('/api/client/settling', { body: { phase: 'final', wave: 0, of: 1 } });
        page.route('/api/client/pr-title', { body: { title: 'IGNITE-29117 Fix test to forcefully use H2 engine' } });
        page.route('/api/client/prs', { body: [] });
        """;

    /**
     * The row of the run, the comment as the server would post it, the answer to a press, and a page whose dialog is
     * answered and kept: {@code press()} presses the button and answers the dialog it opens, if it opens one.
     */
    private static final String ROW = """
        const COMMENT = 'https://github.com/apache/ignite/pull/13575#issuecomment-4100';
        const TEXT = '**Ignite PR Checker** verdict\\n\\n✅ **No blockers**\\n\\n_Posted by ci2 user alice from the'
            + " checker's page._";
        page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
        // The row the page polls for, the preview, and the answer to Post to PR; a scenario sets any of them, or a
        // function for it.
        let shown = { body: { bot: 'ready', url: null } };
        let preview = { body: { text: TEXT, key: 'c0ffee' } };
        let answer = { body: { bot: 'ready', url: COMMENT, edited: false } };
        const prRoute = (url, method) => {
            const it = method === 'POST' ? answer : url.includes('/preview?') ? preview : shown;
            return typeof it === 'function' ? it() : it;
        };
        page.route('/api/pr-comment', prRoute);
        page.route('/api/client/pr-comment', prRoute);
        const asked = [];
        async function press() {
            const pressed = page.el('prPost').onclick();
            await page.settle();
            if (!page.el('postDialog').classList.contains('hidden')) {
                asked.push({ title: page.text('postDialogTitle'), by: page.text('postDialogBy'),
                    text: page.text('postDialogText'), ok: page.text('postDialogOk') });
                page.el('postDialogOk').onclick();
            }
            await pressed;
            await page.settle();
        }
        const posts = () => page.fetches('/api/').filter(f => f.method === 'POST' && f.url.includes('pr-comment?'));
        const previews = () => page.fetches('/api/').filter(f => f.url.includes('pr-comment/preview?')).map(f => f.url);
        const shownText = id => page.el(id).classList.contains('hidden') ? null : page.text(id);
        const hint = () => page.el('postHint').classList.contains('hidden') ? null : page.el('postHint').innerHTML;
        const state = () => ({ shown: !page.el('prPostGroup').classList.contains('hidden'),
            line: shownText('prLine'), button: shownText('prPost'),
            off: page.el('prPost').getAttribute('aria-disabled') === 'true',
            why: page.el('prPostWhy').classList.contains('hidden') ? null : page.el('prPostWhy').title,
            title: page.el('prPost').title });
        """;

    @Test
    void aFinalVerdictIsPostedAfterTheQuestionAndTheGroupLinksTheComment() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            const after = state();
            const status = page.el('status').innerHTML;
            await press();
            report({ before, after, asked, previews: previews(), posts: posts().map(f => f.url), status,
                line: page.el('prLine').innerHTML, lead: page.text('prLead') });
            """);

        assertThat(out.get("lead").asText()).isEqualTo("to PR:");
        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/line").isNull()).isTrue();
        assertThat(out.at("/before/button").asText()).isEqualTo("Post");
        assertThat(out.at("/before/off").asBoolean()).isFalse();
        assertThat(out.at("/before/why").asText()).isEqualTo(POSTS);
        assertThat(out.at("/before/title").asText()).isEqualTo(POSTS);
        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Post the verdict of RunAll 9001 to PR #13575?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("It goes in as a comment of the checker's GitHub account,"
            + " and its last line names you.");
        assertThat(out.at("/asked/0/text").asText()).as("the comment as the server would post it").isEqualTo(TEXT);
        assertThat(out.at("/asked/0/ok").asText()).isEqualTo("Post");
        assertThat(out.get("previews").get(0).asText()).isEqualTo("/api/pr-comment/preview?pr=13575&build=9001");
        assertThat(out.get("posts").get(0).asText()).as("the press carries the key of the text shown")
            .isEqualTo("/api/pr-comment?pr=13575&build=9001&key=c0ffee");
        assertThat(out.get("status").asText()).isEqualTo("Verdict posted to <a href=\"" + COMMENT + "\""
            + " target=\"_self\" rel=\"noopener\" title=\"Opens the comment on GitHub.\">PR #13575</a>.");
        assertThat(out.get("line").asText()).isEqualTo("<a href=\"" + COMMENT + "\" target=\"_self\" rel=\"noopener\""
            + " title=\"Opens the comment on GitHub.\">✓ posted</a>");
        assertThat(out.at("/after/button").asText()).as("the comment is in, so a press now updates it")
            .isEqualTo("Update");
        assertThat(out.at("/after/title").asText()).isEqualTo(UPDATES);
        assertThat(out.at("/after/why").asText())
            .isEqualTo("The verdict comment of this run is in the PR. " + UPDATES);
        assertThat(out.at("/asked/1/title").asText())
            .isEqualTo("Update the verdict comment of RunAll 9001 in PR #13575 with the verdict on this page?");
        assertThat(out.at("/asked/1/by").asText()).isEqualTo("It is a comment of the checker's GitHub account, and its"
            + " last line will say that you updated it.");
        assertThat(out.at("/asked/1/ok").asText()).isEqualTo("Update");
    }

    /** The option of the run's starter posted its verdict comment: the press updates it, and the answer says so. */
    @Test
    void theVerdictCommentOfTheRunIsUpdated() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'ready', url: COMMENT } };
            answer = { body: { bot: 'ready', url: COMMENT, edited: true } };
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            report({ before, asked, status: page.el('status').textContent });
            """);

        assertThat(out.at("/before/line").asText()).isEqualTo("✓ posted");
        assertThat(out.at("/before/button").asText()).isEqualTo("Update");
        assertThat(out.at("/before/title").asText()).isEqualTo(UPDATES);
        assertThat(out.at("/asked/0/title").asText())
            .isEqualTo("Update the verdict comment of RunAll 9001 in PR #13575 with the verdict on this page?");
        assertThat(out.get("status").asText())
            .isEqualTo("The verdict comment in PR #13575 now has the verdict on this page.");
    }

    /**
     * alice started the run with Post my runs' verdicts in the PR on, and auto re-runs still settle it: the group
     * says the comment comes by itself, and has no button before that. Of bob's run it names bob. Once the verdict is
     * final and no comment came, the button is there.
     */
    @Test
    void whileTheStartersOptionStillPostsTheCommentTheGroupSaysAutoWhenFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'ready', url: null, starter: 'alice', mine: true, auto: true } };
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2 } });
            await page.load('?pr=13575');
            await page.settle();
            const mine = state();
            page.el('prPostWhy').onclick();
            const said = hint();
            shown = { body: { bot: 'ready', url: null, starter: 'bob', mine: false, auto: true } };
            await page.run('loadVisaLine')(13575);
            await page.settle();
            const theirs = state();
            shown = { body: { bot: 'ready', url: null, starter: 'bob', mine: false, auto: false } };
            await page.run('loadVisaLine')(13575);
            await page.settle();
            const off = state();
            shown = { body: { bot: 'ready', url: null, starter: 'alice', mine: true, auto: true } };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ mine, said, theirs, off, final: state() });
            """);

        String auto = "The verdict will be posted to the PR when the run settles: Post my runs' verdicts in the PR is"
            + " on in ";
        assertThat(out.at("/mine/line").asText()).isEqualTo("auto when final");
        assertThat(out.at("/mine/button").isNull()).isTrue();
        assertThat(out.at("/mine/why").asText()).isEqualTo(auto + "Settings");
        assertThat(out.get("said").asText())
            .isEqualTo(auto + "<a href=\"/settings?repo=ignite&amp;pr=13575&amp;section=teamcity\">Settings</a>");
        assertThat(out.at("/theirs/line").asText()).isEqualTo("auto when final");
        assertThat(out.at("/theirs/button").isNull()).isTrue();
        assertThat(out.at("/theirs/why").asText())
            .isEqualTo("bob, who started this run, has Post my runs' verdicts in the PR on");
        assertThat(out.at("/off/line").isNull()).as("no option posts it: the button, which waits").isTrue();
        assertThat(out.at("/off/button").asText()).isEqualTo("Post");
        assertThat(out.at("/off/off").asBoolean()).isTrue();
        assertThat(out.at("/off/why").asText()).isEqualTo(WAIT);
        assertThat(out.at("/final/line").isNull()).isTrue();
        assertThat(out.at("/final/button").asText()).isEqualTo("Post");
        assertThat(out.at("/final/off").asBoolean()).isFalse();
    }

    /**
     * A checker without GITHUB_TOKEN cannot write to the PR: the button is grey, and a click on it says so in the line
     * under the row.
     */
    @Test
    void withoutAnAccountToWriteWithTheButtonIsGrey() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = { body: { bot: 'none', url: null } };
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            report({ before, said: hint(), dialogs: asked.length, posts: posts().length, previews: previews().length,
                disabled: page.el('prPost').disabled, tag: page.el('prPostWhy').tagName,
                label: page.el('prPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/off").asBoolean()).isTrue();
        assertThat(out.at("/before/why").asText()).isEqualTo(NO_BOT);
        assertThat(out.at("/before/title").asText()).isEqualTo(NO_BOT);
        assertThat(out.get("said").asText()).isEqualTo(NO_BOT);
        assertThat(out.get("dialogs").asInt()).isZero();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("previews").asInt()).isZero();
        assertThat(out.get("disabled").asBoolean()).as("grey by aria-disabled: the keyboard reaches it").isFalse();
        assertThat(out.get("tag").asText()).isEqualTo("BUTTON");
        assertThat(out.get("label").asText()).isEqualTo("About the verdict in the PR");
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
            report({ row: state(), visaOff: page.el('visaPost').getAttribute('aria-disabled') });
            """);

        assertThat(out.at("/row/button").asText()).isEqualTo("Post");
        assertThat(out.at("/row/shown").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isFalse();
        assertThat(out.get("visaOff").asText()).isEqualTo("true");
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
            await press();
            report({ status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                row: state() });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Verdict not posted to the PR: GitHub asked the checker to"
            + " wait before it writes again. Try again later.");
        assertThat(out.get("err").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isTrue();
    }

    /**
     * The checker's account lost its write between the page's poll and the press: the server refuses to show the
     * comment, as it would refuse to post it. The page says why, opens no dialog, posts nothing, and greys the button.
     */
    @Test
    void aCommentTheServerHoldsBackOpensNoDialog() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            let refused = false;
            shown = () => ({ body: { bot: refused ? 'refused' : 'ready', url: null } });
            preview = () => {
                refused = true;
                return { status: 409, body: { error: 'GitHub refuses the token the checker writes with (GITHUB_TOKEN),'
                    + ' so the checker cannot post to the PR now.', bot: 'refused' } };
            };
            await page.load('?pr=13575');
            await page.settle();
            await press();
            report({ status: page.el('status').textContent, dialogs: asked.length, posts: posts().length,
                row: state(), disabled: page.el('prPost').disabled });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Verdict not posted to the PR: GitHub refuses the token the"
            + " checker writes with (GITHUB_TOKEN), so the checker cannot post to the PR now.");
        assertThat(out.get("dialogs").asInt()).isZero();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.at("/row/off").asBoolean()).isTrue();
        assertThat(out.get("disabled").asBoolean()).isFalse();
    }

    /**
     * The run has its verdict comment in the PR. While it says what the verdict says, the group is "✓ posted" alone:
     * there is nothing to update. Once the verdict changed, the group says so and has Update. Where the server cannot
     * tell, as for a comment it kept without its verdict, "✓ posted" has its Update as before. After a press the
     * comment is current, and the button goes.
     */
    @Test
    void updateStandsOnlyByACommentTheVerdictLeftBehind() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            const row = over => ({ body: Object.assign({ bot: 'ready', url: COMMENT }, over) });
            const seen = async now => {
                shown = now;
                await page.tick(90000);
                return Object.assign(state(), { html: page.el('prLine').innerHTML });
            };
            await page.load('?pr=13575');
            await page.settle();
            const current = await seen(row({ current: true }));
            const changed = await seen(row({ current: false }));
            const untold = await seen(row({}));
            shown = row({ current: false });
            answer = row({ current: true, edited: true });
            await page.tick(90000);
            await press();
            report({ current, changed, untold, asked: asked.length, pressed: state() });
            """);

        String link = "<a href=\"https://github.com/apache/ignite/pull/13575#issuecomment-4100\" target=\"_self\""
            + " rel=\"noopener\" title=\"Opens the comment on GitHub.\">";
        assertThat(out.at("/current/html").asText()).isEqualTo(link + "✓ posted</a>");
        assertThat(out.at("/current/button").isNull()).as("nothing to update").isTrue();
        assertThat(out.at("/current/why").asText()).isEqualTo("The verdict comment of this run is in the PR. It says"
            + " what the verdict on this page says");
        assertThat(out.at("/changed/html").asText()).isEqualTo(link + "posted</a>, verdict changed");
        assertThat(out.at("/changed/button").asText()).isEqualTo("Update");
        assertThat(out.at("/changed/why").asText()).isEqualTo("The verdict comment of this run is in the PR. The verdict"
            + " changed after it was posted. Updates the verdict comment of this run in the PR with the verdict on this"
            + " page.");
        assertThat(out.at("/untold/html").asText()).isEqualTo(link + "✓ posted</a>");
        assertThat(out.at("/untold/button").asText()).isEqualTo("Update");
        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.at("/pressed/line").asText()).isEqualTo("✓ posted");
        assertThat(out.at("/pressed/button").isNull()).isTrue();
    }

    /**
     * The verdict changed while alice read the comment in the dialog: the server posts only the text of the key the
     * press carries, and refuses. The page says so and takes the next press, which shows the comment as it is now and
     * posts it with the new key.
     */
    @Test
    void aCommentThatChangedWhileTheDialogWasOpenIsNotPostedAndTheNextPressShowsTheNewOne() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            const posted = answer;
            answer = { status: 409, body: { error: 'The text changed after the page showed it to you. Press the button'
                + ' again to read it as it is now.', changed: true } };
            await page.load('?pr=13575');
            await page.settle();
            await press();
            const refused = { status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                dialog: !page.el('postDialog').classList.contains('hidden'), row: state(),
                disabled: page.el('prPost').disabled };
            preview = { body: { text: 'The verdict of the mixed run', key: 'decaf' } };
            answer = posted;
            await press();
            report({ refused, asked, posts: posts().map(f => f.url), status: page.el('status').textContent });
            """);

        assertThat(out.at("/refused/status").asText()).isEqualTo("⚠ Verdict not posted to the PR: The text changed"
            + " after the page showed it to you. Press the button again to read it as it is now.");
        assertThat(out.at("/refused/err").asBoolean()).isTrue();
        assertThat(out.at("/refused/dialog").asBoolean()).isFalse();
        assertThat(out.at("/refused/row/button").asText()).isEqualTo("Post");
        assertThat(out.at("/refused/disabled").asBoolean()).as("the button takes the next press").isFalse();
        assertThat(out.at("/asked/0/text").asText()).isEqualTo(TEXT);
        assertThat(out.at("/asked/1/text").asText()).isEqualTo("The verdict of the mixed run");
        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/pr-comment?pr=13575&build=9001&key=c0ffee\","
            + "\"/api/pr-comment?pr=13575&build=9001&key=decaf\"]");
        assertThat(out.get("status").asText()).isEqualTo("Verdict posted to PR #13575.");
    }

    /** The client's PR #69: the press asks the client's endpoints, and the dialog names the suite's build. */
    @Test
    void aClientPrsRunIsPostedToTheClientsEndpoint() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + CLIENT_PR + """
            answer = { body: { bot: 'ready', url: 'https://github.com/apache/ignite-python-thin-client/pull/69'
                + '#issuecomment-7', edited: false } };
            await page.load('/python?pr=69');
            await page.tick(100);
            await press();
            report({ asked, previews: previews(), posts: posts().map(f => f.url),
                status: page.el('status').textContent });
            """);

        assertThat(out.at("/asked/0/title").asText())
            .isEqualTo("Post the verdict of Thin client: Python build 9424794 to PR #69?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("It goes in as a comment of the checker's GitHub account,"
            + " and its last line names you.");
        assertThat(out.get("previews").get(0).asText())
            .isEqualTo("/api/client/pr-comment/preview?pr=69&repo=python&build=9424794");
        assertThat(out.get("posts").get(0).asText())
            .isEqualTo("/api/client/pr-comment?pr=69&repo=python&build=9424794&key=c0ffee");
        assertThat(out.get("status").asText()).isEqualTo("Verdict posted to PR #69.");
    }

    /** The Russian page names the group, its button, the question and the status in Russian. */
    @Test
    void theRussianPageSaysItInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + ROW + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            await page.load('?pr=13575');
            await page.settle();
            const title = page.el('prPost').title;
            const button = page.text('prPost');
            await press();
            report({ lead: page.text('prLead'), button, after: page.text('prPost'), line: page.text('prLine'), title,
                asked, status: page.el('status').textContent, label: page.el('prPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.get("lead").asText()).isEqualTo("в PR:");
        assertThat(out.get("button").asText()).isEqualTo("Отправить");
        assertThat(out.get("after").asText()).isEqualTo("Обновить");
        assertThat(out.get("line").asText()).isEqualTo("✓ отправлен");
        assertThat(out.get("title").asText()).isEqualTo("Отправляет вердикт с этой страницы в PR комментарием аккаунта"
            + " GitHub чекера. Повторное нажатие для этого прогона правит тот же комментарий.");
        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Отправить в PR #13575 вердикт RunAll 9001?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("Он встанет комментарием аккаунта GitHub чекера, а его"
            + " последняя строка назовёт тебя.");
        assertThat(out.at("/asked/0/ok").asText()).isEqualTo("Отправить");
        assertThat(out.get("status").asText()).isEqualTo("Вердикт отправлен в PR #13575.");
        assertThat(out.get("label").asText()).isEqualTo("О вердикте в PR");
    }
}
