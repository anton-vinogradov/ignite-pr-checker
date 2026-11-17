package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The author of apache/ignite#13709 re-ran the suite that had a blocker on the PR's head, and the verdict became a
 * mixed run with no blockers. The page had no way to put that verdict into the ticket. Post to JIRA is the button of
 * the group "Verdict to JIRA:" in the verdict's heading: it asks first in a dialog that names the run and the ticket
 * and shows the visa as it will go, posts with the author's stored JIRA token, and the group then links the visa.
 * While the verdict can change, the PR title names no ticket or the reader has no JIRA token stored, the button is
 * grey, and a click on it, or the ? of the group, says why.
 */
class PostVisaPageTest {
    private static final String TOKEN_MISSING = "Post to JIRA uses the JIRA token you save in Settings, and you have"
        + " none there. Saving one also switches on Visa my runs in JIRA.";

    private static final String WAIT = "Post to JIRA waits for the final verdict: runs of this PR can still change it.";

    private static final String RETRIED = "TeamCity errors left part of this verdict unchecked. The checker checks"
        + " that part again, and Post to JIRA waits up to 20 minutes for that.";

    private static final String NOT_TAKEN = "Visa: JIRA did not take it, the checker tries again every 10 minutes. ";

    private static final String VISA = "Ignite PR Checker verdict for PR 13575\n\nNo blockers\n\nPosted by ci2 user"
        + " alice from the checker's page.";

    private static final String CHANGED = "The text changed after the page showed it to you. Press the button again to"
        + " read it as it is now.";

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
     * The row of alice's run, whose visa JIRA did not take, and a page whose dialog is answered and kept: {@code
     * press()} presses the button and answers the dialog it opens, if it opens one.
     */
    static final String ROW = """
        const URL = 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=7#comment-7';
        const VISA = 'Ignite PR Checker verdict for PR 13575\\n\\nNo blockers\\n\\nPosted by ci2 user alice from the'
            + " checker's page.";
        const row = over => ({ body: Object.assign({ state: 'retrying', starter: 'alice', mine: true, issue: null,
            url: null, jiraRefused: false, jiraReady: true, yoursIn: null }, over || {}) });
        page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
        // The row the page polls for, the visa as the server would post it, and the answer to Post to JIRA; a
        // scenario sets any of them, or a function for it.
        let shown = row();
        let preview = { body: { text: VISA, key: 'c0ffee' } };
        let answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049', url: URL,
            jiraRefused: false, jiraReady: true, yoursIn: 'IGNITE-29049', edited: false } };
        const visaRoute = (url, method) => {
            const it = method === 'POST' ? answer : url.includes('/preview?') ? preview : shown;
            return typeof it === 'function' ? it() : it;
        };
        page.route('/api/visa', visaRoute);
        page.route('/api/client/visa', visaRoute);
        const asked = [];
        let yes = true;
        async function press(id = 'visaPost') {
            const pressed = page.el(id).onclick();
            await page.settle();
            if (!page.el('postDialog').classList.contains('hidden')) {
                asked.push({ title: page.text('postDialogTitle'), by: page.text('postDialogBy'),
                    text: page.text('postDialogText'), ok: page.text('postDialogOk') });
                page.el(yes ? 'postDialogOk' : 'postDialogCancel').onclick();
            }
            await pressed;
            await page.settle();
        }
        const posts = () => page.fetches('/api/').filter(f => f.method === 'POST' && f.url.includes('visa?'));
        const previews = () => page.fetches('/api/').filter(f => f.url.includes('visa/preview?')).map(f => f.url);
        const shownText = id => page.el(id).classList.contains('hidden') ? null : page.text(id);
        const state = () => ({ shown: !page.el('visaPostGroup').classList.contains('hidden'),
            line: shownText('visaLine'), button: shownText('visaPost'),
            off: page.el('visaPost').getAttribute('aria-disabled') === 'true', disabled: page.el('visaPost').disabled,
            why: page.el('visaPostWhy').classList.contains('hidden') ? null : page.el('visaPostWhy').title,
            title: page.el('visaPost').title });
        """;

    /** The page in Russian, for alice. */
    private static String russian() throws IOException {
        return "const CATALOG = " + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + """
            ;
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            """;
    }

    @Test
    void aFinalVerdictIsPostedAfterTheQuestionAndTheGroupLinksTheVisa() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            report({ before, asked, previews: previews(), posts: posts().map(f => f.url),
                line: page.el('visaLine').innerHTML, status: page.el('status').textContent, after: state(),
                lead: page.text('visaLead') });
            """);

        assertThat(out.get("lead").asText()).isEqualTo("Verdict to JIRA:");
        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/line").asText()).isEqualTo("retrying");
        assertThat(out.at("/before/button").asText()).isEqualTo("Post");
        assertThat(out.at("/before/off").asBoolean()).isFalse();
        String posts = "Posts the verdict on this page to IGNITE-29049 as your visa of this run: a comment in JIRA,"
            + " with the JIRA token you saved in Settings. A second press for this run updates that visa.";
        assertThat(out.at("/before/title").asText()).isEqualTo(posts);
        assertThat(out.at("/before/why").asText()).isEqualTo(NOT_TAKEN + posts);
        assertThat(out.get("asked")).hasSize(1);
        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Post the visa of RunAll 9001 to IGNITE-29049?");
        assertThat(out.at("/asked/0/by").asText())
            .isEqualTo("It goes in as your comment in JIRA, with the verdict on this page.");
        assertThat(out.at("/asked/0/text").asText()).as("the visa as the server would post it").isEqualTo(VISA);
        assertThat(out.at("/asked/0/ok").asText()).isEqualTo("Post");
        assertThat(out.get("previews").get(0).asText())
            .isEqualTo("/api/visa/preview?pr=13575&build=9001&issue=IGNITE-29049");
        assertThat(out.get("posts").get(0).asText()).as("the press carries the key of the text shown")
            .isEqualTo("/api/visa?pr=13575&build=9001&issue=IGNITE-29049&key=c0ffee");
        assertThat(out.get("line").asText()).isEqualTo("<a href=\"https://issues.apache.org/jira/browse/IGNITE-29049"
            + "?focusedCommentId=7#comment-7\" target=\"_self\" rel=\"noopener\" title=\"Opens the visa in JIRA.\">"
            + "✓ posted</a>");
        assertThat(out.get("status").asText()).isEqualTo("Visa posted to IGNITE-29049.");
        assertThat(out.at("/after/button").asText()).as("a second press updates the visa").isEqualTo("Update");
        assertThat(out.at("/after/off").asBoolean()).isFalse();
        assertThat(out.at("/after/why").asText()).startsWith("Visa: posted to IGNITE-29049. Updates your visa of this"
            + " run in IGNITE-29049");
    }

    /** The reader's own visa of the run is in the ticket: the dialog says the press updates it, as the answer does. */
    @Test
    void aSecondPressAsksToUpdateTheReadersVisa() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'posted', issue: 'IGNITE-29049', url: URL, yoursIn: 'IGNITE-29049' });
            answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049', url: URL,
                jiraReady: true, yoursIn: 'IGNITE-29049', edited: true } };
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            report({ before, asked, status: page.el('status').textContent });
            """);

        assertThat(out.at("/before/line").asText()).isEqualTo("✓ posted");
        assertThat(out.at("/before/button").asText()).isEqualTo("Update");
        assertThat(out.at("/before/title").asText()).isEqualTo("Updates your visa of this run in IGNITE-29049 with the"
            + " verdict on this page. It uses the JIRA token you saved in Settings.");
        assertThat(out.at("/asked/0/title").asText())
            .isEqualTo("Update your visa of RunAll 9001 in IGNITE-29049 with the verdict on this page?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("The visa is your comment in JIRA.");
        assertThat(out.at("/asked/0/ok").asText()).isEqualTo("Update");
        assertThat(out.get("status").asText()).isEqualTo("Your visa in IGNITE-29049 now has the verdict on this page.");
    }

    /** bob's option posted the visa of his run: alice's goes in as a comment of its own, and the dialog says so. */
    @Test
    void someoneElsesVisaOfTheRunIsNamedInTheQuestion() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'posted', starter: 'bob', mine: false, issue: 'IGNITE-29049', url: URL });
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            await press();
            report({ before, asked });
            """);

        assertThat(out.at("/before/line").asText()).isEqualTo("✓ posted");
        assertThat(out.at("/before/button").asText()).as("the press adds alice's own visa").isEqualTo("Post");
        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Post the visa of RunAll 9001 to IGNITE-29049?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("It goes in as your comment in JIRA, with the verdict on"
            + " this page. bob's visa of this run is in IGNITE-29049 already. Yours goes in as a comment of its own.");
    }

    /**
     * alice's Visa my runs in JIRA is on and auto re-runs still settle her run: the group says the visa comes by
     * itself, and has no button to press before that.
     */
    @Test
    void whileTheOptionStillPostsTheVisaTheGroupSaysAutoWhenFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'due' });
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2 } });
            await page.load('?pr=13575');
            await page.settle();
            const mine = state();
            shown = row({ state: 'on', starter: 'bob', mine: false });
            await page.run('loadVisaLine')(13575);
            const theirs = state();
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ mine, theirs, final: state() });
            """);

        assertThat(out.at("/mine/line").asText()).isEqualTo("auto when final");
        assertThat(out.at("/mine/button").isNull()).isTrue();
        assertThat(out.at("/mine/why").asText())
            .isEqualTo("Visa: will be posted to IGNITE-29049 when the run settles");
        assertThat(out.at("/theirs/line").asText()).isEqualTo("auto when final");
        assertThat(out.at("/theirs/button").isNull()).isTrue();
        assertThat(out.at("/theirs/why").asText())
            .isEqualTo("Visa: bob, who started this run, has Visa my runs in JIRA on");
        assertThat(out.at("/final/line").isNull()).as("the verdict is final and no visa came: the button").isTrue();
        assertThat(out.at("/final/button").asText()).isEqualTo("Post");
        assertThat(out.at("/final/off").asBoolean()).isFalse();
        assertThat(out.at("/final/why").asText())
            .startsWith("Visa: bob, who started this run, has Visa my runs in JIRA on. Posts the verdict");
    }

    /**
     * No JIRA token stored: the button is grey and still takes the focus. A click on it says why in the line under the
     * row, with a link to the settings; the ? says where the visa stands too. Neither asks the server for anything.
     */
    @Test
    void withoutAStoredJiraTokenTheGreyButtonSaysWhyAndLinksTheSettings() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            shown = row({ state: 'off', jiraReady: false });
            await page.load('?pr=13575');
            await page.settle();
            const before = state();
            const hint = () => page.el('postHint').classList.contains('hidden') ? null : page.el('postHint').innerHTML;
            const quiet = hint();
            await press();
            const said = hint();
            await press();
            const again = hint();
            page.el('visaPostWhy').onclick();
            report({ before, quiet, said, again, asked: hint(), dialogs: asked.length, posts: posts().length,
                previews: previews().length, tag: page.el('visaPostWhy').tagName,
                type: page.el('visaPostWhy').getAttribute('type'), live: page.el('postHint').getAttribute('role') });
            """);

        String settings = "<a href=\"/settings?repo=ignite&amp;pr=13575&amp;section=jira\">Settings</a>";
        assertThat(out.at("/before/shown").asBoolean()).isTrue();
        assertThat(out.at("/before/line").isNull()).isTrue();
        assertThat(out.at("/before/button").asText()).isEqualTo("Post");
        assertThat(out.at("/before/off").asBoolean()).isTrue();
        assertThat(out.at("/before/disabled").asBoolean()).as("grey by aria-disabled: the keyboard reaches it").isFalse();
        assertThat(out.at("/before/why").asText())
            .isEqualTo("Visa: Visa my runs in JIRA is off in Settings. " + TOKEN_MISSING);
        assertThat(out.at("/before/title").asText()).isEqualTo(TOKEN_MISSING);
        assertThat(out.get("quiet").isNull()).isTrue();
        assertThat(out.get("said").asText()).isEqualTo(TOKEN_MISSING.replace("Settings", settings));
        assertThat(out.get("again").isNull()).as("a second click takes the line away").isTrue();
        assertThat(out.get("asked").asText()).isEqualTo("Visa: Visa my runs in JIRA is off in " + settings + ". "
            + TOKEN_MISSING.replace("Settings", settings));
        assertThat(out.get("dialogs").asInt()).isZero();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("previews").asInt()).isZero();
        assertThat(out.get("tag").asText()).as("the ? acts on this page: a screen reader calls it a button")
            .isEqualTo("BUTTON");
        assertThat(out.get("type").asText()).isEqualTo("button");
        assertThat(out.get("live").asText()).as("a screen reader hears the line").isEqualTo("status");
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
        assertThat(out.at("/going/why").asText()).isEqualTo(NOT_TAKEN + WAIT);
        assertThat(out.at("/settling/off").asBoolean()).isTrue();
        assertThat(out.at("/settling/why").asText()).isEqualTo(NOT_TAKEN + WAIT);
    }

    /** The RunAll just ended, and the verdict of what it ran has not come yet: the one on screen is not final. */
    @Test
    void whileTheVerdictOfRunsThatJustEndedIsDueTheButtonWaits() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/runs', { body: [{ buildId: 9001, buildTypeId: 'IgniteTests24Java8_RunAll',
                name: 'RunAll', state: 'running', webUrl: 'https://ci2.example/b', by: 'alice', mine: true,
                runAll: true }] });
            await page.load('?pr=13575');
            await page.settle();
            page.route('/api/runs', { body: [] });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ due: state() });
            """);

        assertThat(out.at("/due/off").asBoolean()).isTrue();
        assertThat(out.at("/due/why").asText()).isEqualTo(NOT_TAKEN + WAIT);
    }

    /** #13709 is merged, and the checker still looks for the runs that went at the merge: they may change its verdict. */
    @Test
    void aMergedPrsVerdictWaitsForTheRunsAtTheMerge() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            page.route('/api/analyze', { body: verdict({ blockers: [], mergedAt: Math.floor(page.now() / 1000) - 3600,
                runsAtMergeDue: true }) });
            await page.load('?pr=13575');
            await page.settle();
            report({ due: state() });
            """);

        assertThat(out.at("/due/shown").asBoolean()).isTrue();
        assertThat(out.at("/due/off").asBoolean()).isTrue();
        assertThat(out.at("/due/why").asText()).isEqualTo(NOT_TAKEN + WAIT);
    }

    /**
     * TeamCity errors left tests of the run unchecked a minute ago, and the checker checks them again: the server takes
     * no visa of that verdict for 20 minutes, so the button waits as long and says why.
     */
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
        assertThat(out.at("/checking/why").asText()).isEqualTo(NOT_TAKEN + RETRIED);
        assertThat(out.at("/after/off").asBoolean()).isFalse();
        assertThat(out.at("/after/why").asText()).doesNotContain(RETRIED);
    }

    @Test
    void aTitleWithoutATicketGreysTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            page.run('setPrTitle')(13575, 'Move MDC to JUnit', null);
            await page.run('loadVisaLine')(13575);
            report({ row: state() });
            """);

        assertThat(out.at("/row/shown").asBoolean()).isTrue();
        assertThat(out.at("/row/line").isNull()).isTrue();
        assertThat(out.at("/row/button").asText()).isEqualTo("Post");
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
            await press();
            report({ status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                row: state() });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Visa not posted: JIRA rejected your JIRA token, so the"
            + " checker dropped it and switched off Visa my runs in JIRA. Save a new token in ⚙.");
        assertThat(out.get("err").asBoolean()).isTrue();
        assertThat(out.at("/row/off").asBoolean()).isTrue();
        assertThat(out.at("/row/why").asText()).isEqualTo(NOT_TAKEN + TOKEN_MISSING);
    }

    /**
     * A re-run of the PR started after the page's last poll: the server refuses to show the visa, as it would refuse
     * to post it. The page says why, opens no dialog and posts nothing.
     */
    @Test
    void aVerdictTheServerHoldsBackOpensNoDialog() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            preview = { status: 409, body: { error: 'Post to JIRA waits for the final verdict: runs of this PR can'
                + ' still change it.', going: true } };
            await page.load('?pr=13575');
            await page.settle();
            await press();
            report({ status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                dialogs: asked.length, posts: posts().length, disabled: page.el('visaPost').disabled });
            """);

        assertThat(out.get("status").asText()).isEqualTo("⚠ Visa not posted: " + WAIT);
        assertThat(out.get("err").asBoolean()).isTrue();
        assertThat(out.get("dialogs").asInt()).isZero();
        assertThat(out.get("posts").asInt()).isZero();
        assertThat(out.get("disabled").asBoolean()).as("the button takes the next press").isFalse();
    }

    /**
     * alice's visa of the run is in the ticket. While it says what the verdict says, the group is "✓ posted" alone:
     * there is nothing to update. Once the verdict changed, the group says so and has Update. Where the server cannot
     * tell, as for a visa it kept without its verdict, "✓ posted" has its Update as before. bob's visa is not hers:
     * the group has Post, whatever his visa says. After her press the visa is current, and the button goes.
     */
    @Test
    void updateStandsOnlyByAVisaTheVerdictLeftBehind() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            const posted = { state: 'posted', issue: 'IGNITE-29049', url: URL };
            const mine = current => row(Object.assign({ yoursIn: 'IGNITE-29049' }, posted, current));
            const seen = async now => {
                shown = now;
                await page.tick(90000);
                return Object.assign(state(), { html: page.el('visaLine').innerHTML });
            };
            await page.load('?pr=13575');
            await page.settle();
            const current = await seen(mine({ current: true }));
            const changed = await seen(mine({ current: false }));
            const untold = await seen(mine({}));
            const theirs = await seen(row(Object.assign({ starter: 'bob', mine: false, current: false }, posted)));
            shown = mine({ current: false });
            answer = mine({ current: true, edited: true });
            await page.tick(90000);
            await press();
            report({ current, changed, untold, theirs, asked: asked.length, pressed: state() });
            """);

        String link = "<a href=\"https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=7#comment-7\""
            + " target=\"_self\" rel=\"noopener\" title=\"Opens the visa in JIRA.\">";
        assertThat(out.at("/current/html").asText()).isEqualTo(link + "✓ posted</a>");
        assertThat(out.at("/current/button").isNull()).as("nothing to update").isTrue();
        assertThat(out.at("/current/why").asText())
            .isEqualTo("Visa: posted to IGNITE-29049. It says what the verdict on this page says");
        assertThat(out.at("/changed/html").asText()).isEqualTo(link + "posted</a>, verdict changed");
        assertThat(out.at("/changed/button").asText()).isEqualTo("Update");
        assertThat(out.at("/changed/why").asText()).startsWith("Visa: posted to IGNITE-29049. The verdict changed after"
            + " it was posted. Updates your visa of this run in IGNITE-29049");
        assertThat(out.at("/untold/html").asText()).isEqualTo(link + "✓ posted</a>");
        assertThat(out.at("/untold/button").asText()).isEqualTo("Update");
        assertThat(out.at("/theirs/html").asText()).isEqualTo(link + "✓ posted</a>");
        assertThat(out.at("/theirs/button").asText()).isEqualTo("Post");
        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.at("/pressed/line").asText()).isEqualTo("✓ posted");
        assertThat(out.at("/pressed/button").isNull()).isTrue();
    }

    /**
     * The verdict changed while alice read the visa in the dialog: the server posts only the text of the key the press
     * carries, and refuses. The page says so and takes the next press, which shows the visa as it is now and posts it
     * with the new key.
     */
    @Test
    void aVisaThatChangedWhileTheDialogWasOpenIsNotPostedAndTheNextPressShowsTheNewOne() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + """
            const posted = answer;
            answer = { status: 409, body: { error: '%s', changed: true } };
            await page.load('?pr=13575');
            await page.settle();
            await press();
            const refused = { status: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                dialog: !page.el('postDialog').classList.contains('hidden'), row: state() };
            preview = { body: { text: 'The visa of the mixed run', key: 'decaf' } };
            answer = posted;
            await press();
            report({ refused, asked, posts: posts().map(f => f.url), status: page.el('status').textContent });
            """.formatted(CHANGED));

        assertThat(out.at("/refused/status").asText()).isEqualTo("⚠ Visa not posted: " + CHANGED);
        assertThat(out.at("/refused/err").asBoolean()).isTrue();
        assertThat(out.at("/refused/dialog").asBoolean()).isFalse();
        assertThat(out.at("/refused/row/button").asText()).isEqualTo("Post");
        assertThat(out.at("/refused/row/disabled").asBoolean()).as("the button takes the next press").isFalse();
        assertThat(out.at("/asked/0/text").asText()).isEqualTo(VISA);
        assertThat(out.at("/asked/1/text").asText()).isEqualTo("The visa of the mixed run");
        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/visa?pr=13575&build=9001&issue=IGNITE-29049"
            + "&key=c0ffee\",\"/api/visa?pr=13575&build=9001&issue=IGNITE-29049&key=decaf\"]");
        assertThat(out.get("status").asText()).isEqualTo("Visa posted to IGNITE-29049.");
    }

    /**
     * The client's PR #69: its run has no visa on record, and its verdict is final while the run's option may still try
     * its visa again, so the group has only its button. Post to JIRA asks the client's endpoints, naming the suite's
     * build.
     */
    @Test
    void aClientPrsRunIsPostedToTheClientsEndpoint() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROW + CLIENT_PR + """
            shown = { body: { state: null, starter: null, mine: false, jiraReady: true, yoursIn: null } };
            answer = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29117', url: URL,
                jiraReady: true, yoursIn: 'IGNITE-29117', edited: false } };
            await page.load('/python?pr=69');
            await page.tick(100);
            const before = state();
            await press();
            report({ before, asked, previews: previews(), posts: posts().map(f => f.url),
                status: page.el('status').textContent });
            """);

        assertThat(out.at("/before/line").isNull()).isTrue();
        assertThat(out.at("/before/button").asText()).isEqualTo("Post");
        assertThat(out.at("/before/why").asText()).startsWith("Posts the verdict on this page to IGNITE-29117");
        assertThat(out.at("/asked/0/title").asText())
            .isEqualTo("Post the visa of Thin client: Python build 9424794 to IGNITE-29117?");
        assertThat(out.get("previews").get(0).asText())
            .isEqualTo("/api/client/visa/preview?pr=69&repo=python&build=9424794&issue=IGNITE-29117");
        assertThat(out.get("posts").get(0).asText())
            .isEqualTo("/api/client/visa?pr=69&repo=python&build=9424794&issue=IGNITE-29117&key=c0ffee");
        assertThat(out.get("status").asText()).isEqualTo("Visa posted to IGNITE-29117.");
    }

    /** The Russian page names the group, its button and what its ? says in Russian. */
    @Test
    void theRussianPageSaysItInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + russian() + ROW + """
            shown = row({ jiraReady: false });
            await page.load('?pr=13575');
            await page.settle();
            report({ lead: page.text('visaLead'), line: page.text('visaLine'), button: page.text('visaPost'),
                why: page.el('visaPostWhy').title, label: page.el('visaPostWhy').getAttribute('aria-label') });
            """);

        assertThat(out.get("lead").asText()).isEqualTo("Вердикт в JIRA:");
        assertThat(out.get("line").asText()).isEqualTo("пробует снова");
        assertThat(out.get("button").asText()).isEqualTo("Отправить");
        assertThat(out.get("why").asText()).isEqualTo("Виза: JIRA её не приняла, чекер пробует снова каждые десять"
            + " минут. Кнопка «В JIRA» берёт токен JIRA, который ты сохраняешь в разделе «Настройки», а там его нет."
            + " Когда ты сохранишь токен, заодно включится опция «Ставить визу моим прогонам в JIRA».");
        assertThat(out.get("label").asText()).isEqualTo("О вердикте в JIRA");
    }

    /** The Russian status after a press and the ? of the group name the same event with one word. */
    @Test
    void theRussianStatusAndGroupSayItWithOneWord() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + russian() + ROW + """
            await page.load('?pr=13575');
            await page.settle();
            const title = page.el('visaPost').title;
            await press();
            report({ title, status: page.el('status').textContent, line: page.el('visaLine').textContent,
                why: page.el('visaPostWhy').title, button: page.text('visaPost'), asked,
                cancel: page.text('postDialogCancel') });
            """);

        assertThat(out.get("title").asText()).as("the button's name lost the visa, and its tooltip keeps it")
            .isEqualTo("Отправляет вердикт с этой страницы в IGNITE-29049 твоей визой этого прогона — комментарием в"
                + " JIRA, с токеном JIRA, который ты сохранил в разделе «Настройки». Повторное нажатие для этого"
                + " прогона правит ту же визу.");

        assertThat(out.get("status").asText()).isEqualTo("Виза отправлена в IGNITE-29049.");
        assertThat(out.get("line").asText()).isEqualTo("✓ отправлен");
        assertThat(out.get("why").asText()).startsWith("Виза: отправлена в IGNITE-29049. ");
        assertThat(out.get("button").asText()).isEqualTo("Обновить");
        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Поставить в IGNITE-29049 визу RunAll 9001?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("Она встанет твоим комментарием в JIRA и скажет то же,"
            + " что вердикт на этой странице.");
        assertThat(out.at("/asked/0/ok").asText()).isEqualTo("Отправить");
        assertThat(out.get("cancel").asText()).isEqualTo("Отмена");
    }

    /** The Russian question of a client PR names the ticket first, then the build and the suite it is of. */
    @Test
    void theRussianQuestionOfAClientPrNamesTheTicketFirst() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + russian() + ROW + CLIENT_PR + """
            shown = { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29117', url: URL,
                jiraReady: true, yoursIn: 'IGNITE-29117' } };
            answer = () => shown;
            await page.load('/python?pr=69');
            await page.tick(100);
            await press();
            shown = { body: { state: null, starter: null, mine: false, jiraReady: true, yoursIn: null } };
            await page.run('loadVisaLine')(69);
            await press();
            report({ asked });
            """);

        assertThat(out.at("/asked/0/title").asText()).isEqualTo("Обновить в IGNITE-29117 твою визу сборки 9424794"
            + " сьюты «Thin client: Python» вердиктом с этой страницы?");
        assertThat(out.at("/asked/0/by").asText()).isEqualTo("Виза — твой комментарий в JIRA.");
        assertThat(out.at("/asked/1/title").asText()).isEqualTo("Поставить в IGNITE-29117 визу сборки 9424794 сьюты"
            + " «Thin client: Python»?");
    }
}
