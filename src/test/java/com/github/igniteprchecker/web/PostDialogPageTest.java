package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Post to JIRA and Post to PR asked with the browser's own question box: one line of text, and nothing of what would
 * be posted. alice could not see the visa before it stood in the ticket under her name. They ask in a dialog of the
 * page now: the question, whose comment it will be, and the text as the server would post it. Cancel, Esc and a click
 * outside it post nothing; the keyboard starts on its button, stays inside it, and comes back to the button pressed.
 */
class PostDialogPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** alice's run with a final verdict, a visa to post and a PR comment to post; {@code open()} presses a button. */
    private static final String ROWS = """
        page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
        const TEXT = 'h3. Verdict of <b>PR 13575</b>\\n\\n* no blockers';
        page.route('/api/visa', (url, method) => method === 'POST'
            ? { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049',
                url: 'https://issues.example/c7', jiraReady: true, yoursIn: 'IGNITE-29049', edited: false } }
            : url.includes('/preview?') ? { body: { text: TEXT, key: 'c0ffee' } }
            : { body: { state: 'off', starter: 'alice', mine: true, jiraReady: true, yoursIn: null } });
        page.route('/api/pr-comment', (url, method) => method === 'POST'
            ? { body: { bot: 'ready', url: 'https://github.com/apache/ignite/pull/13575#issuecomment-1', edited: false } }
            : url.includes('/preview?') ? { body: { text: TEXT, key: 'c0ffee' } }
            : { body: { bot: 'ready', url: null } });
        const doc = page.run('document');
        const open = () => !page.el('postDialog').classList.contains('hidden');
        const posts = () => page.fetches('/api/').filter(f => f.method === 'POST').map(f => f.url);
        // Presses the button with the focus on it, as a click or Enter leaves it, and waits for the dialog.
        let pressed;
        async function press(id) {
            page.el(id).focus();
            pressed = page.el(id).onclick();
            await page.settle();
        }
        """;

    /** Each way to say no closes the dialog, posts nothing and gives the focus back to the button pressed. */
    @Test
    void cancelEscAndAClickOutsidePostNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROWS + """
            await page.load('?pr=13575');
            await page.settle();
            const ways = {
                cancel: () => page.el('postDialogCancel').onclick(),
                esc: () => page.keydown('Escape', page.el('postDialogOk')),
                outside: () => page.el('postDialog').onclick({ target: page.el('postDialog') }),
            };
            const result = {};
            for (const [way, say] of Object.entries(ways)) {
                await press(way === 'esc' ? 'prPost' : 'visaPost');
                const was = { open: open(), focus: doc.activeElement.id };
                // A click on the dialog itself is not a click outside it.
                page.el('postDialog').onclick({ target: page.el('postDialogText') });
                const inside = open();
                await say();
                await pressed;
                await page.settle();
                result[way] = { was, inside, open: open(), focus: doc.activeElement.id, posts: posts().length,
                    status: page.el('status').textContent, disabled: page.el('visaPost').disabled };
            }
            report(result);
            """);

        for (String way : new String[] {"cancel", "esc", "outside"}) {
            String button = way.equals("esc") ? "prPost" : "visaPost";
            assertThat(out.at("/" + way + "/was/open").asBoolean()).as(way).isTrue();
            assertThat(out.at("/" + way + "/was/focus").asText()).as(way + ": the focus starts on the dialog's button")
                .isEqualTo("postDialogOk");
            assertThat(out.at("/" + way + "/inside").asBoolean()).as(way).isTrue();
            assertThat(out.at("/" + way + "/open").asBoolean()).as(way).isFalse();
            assertThat(out.at("/" + way + "/focus").asText()).as(way + ": the focus comes back").isEqualTo(button);
            assertThat(out.at("/" + way + "/posts").asInt()).as(way).isZero();
            assertThat(out.at("/" + way + "/status").asText()).as(way + ": nothing went wrong, nothing to say")
                .isEmpty();
            assertThat(out.at("/" + way + "/disabled").asBoolean()).as(way + ": the button takes the next press")
                .isFalse();
        }
    }

    /**
     * The dialog shows the comment as text: what looks like markup in it is not markup of the page. Tab goes round the
     * comment and the two buttons and never reaches the page under the dialog; "/" does not jump to the PR filter.
     */
    @Test
    void theCommentIsTextAndTheKeyboardStaysInTheDialog() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROWS + """
            await page.load('?pr=13575');
            await page.settle();
            await press('visaPost');
            const tab = async shiftKey => {
                await page.keydown('Tab', doc.activeElement, { shiftKey });
                return doc.activeElement.id;
            };
            const text = { text: page.el('postDialogText').textContent, htmlWrites: page.el('postDialogText').htmlWrites };
            const round = [await tab(false), await tab(false), await tab(false), await tab(true)];
            await page.keydown('Tab', page.el('visaPost'));
            const back = doc.activeElement.id;
            await page.keydown('/', page.el('postDialogOk'));
            const slash = doc.activeElement.id;
            page.el('postDialogOk').onclick();
            await pressed;
            await page.settle();
            report({ text, round, back, slash, posts: posts(), open: open(), focus: doc.activeElement.id });
            """);

        assertThat(out.at("/text/text").asText()).isEqualTo("h3. Verdict of <b>PR 13575</b>\n\n* no blockers");
        assertThat(out.at("/text/htmlWrites").asInt()).as("written as text, never as HTML").isZero();
        assertThat(out.get("round").toString())
            .isEqualTo("[\"postDialogText\",\"postDialogCancel\",\"postDialogOk\",\"postDialogCancel\"]");
        assertThat(out.get("back").asText()).as("Tab from the page under the dialog").isEqualTo("postDialogOk");
        assertThat(out.get("slash").asText()).isEqualTo("postDialogOk");
        assertThat(out.get("posts").toString())
            .isEqualTo("[\"/api/visa?pr=13575&build=9001&issue=IGNITE-29049&key=c0ffee\"]");
        assertThat(out.get("open").asBoolean()).isFalse();
        assertThat(out.get("focus").asText()).isEqualTo("visaPost");
    }

    /**
     * The dialog asks about the PR on screen. alice opens it on #13575 and goes Back to #13461, then opens it again and
     * goes to the start page, then to a project this checker does not have: each time the dialog closes as if she had
     * said no, and nothing is posted.
     */
    @Test
    void theDialogClosesWhenAnythingElseOpens() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROWS + """
            await page.load('?pr=13575');
            await page.settle();
            const seen = () => ({ open: open(), posts: posts().length, pr: page.run('selectedPr') });
            const asked = [];
            await press('visaPost');
            asked.push(open());
            page.location.search = '?pr=13461';
            await page.popstate();
            await pressed;
            const back = seen();
            page.run('openPr')(13575, true);
            await page.settle();
            await press('prPost');
            asked.push(open());
            page.el('brand').listeners.click[0]({ button: 0, preventDefault() {} });
            await pressed;
            await page.settle();
            const home = seen();
            page.run('openPr')(13575, true);
            await page.settle();
            await press('visaPost');
            asked.push(open());
            page.location.pathname = '/nosuch';
            await page.popstate();
            await pressed;
            report({ asked, back, home, project: seen(), title: page.el('prTitle').textContent });
            """);

        assertThat(out.get("asked").toString()).isEqualTo("[true,true,true]");
        assertThat(out.get("back").toString()).isEqualTo("{\"open\":false,\"posts\":0,\"pr\":13461}");
        assertThat(out.get("home").toString()).isEqualTo("{\"open\":false,\"posts\":0,\"pr\":null}");
        assertThat(out.get("project").toString()).isEqualTo("{\"open\":false,\"posts\":0,\"pr\":null}");
        assertThat(out.get("title").asText()).startsWith("This project is not available on this checker: nosuch.");
    }

    /** alice's login was ended in another browser while the dialog was open: it does not stay over the login form. */
    @Test
    void theDialogClosesWhenTheLoginEnds() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ROWS + """
            await page.load('?pr=13575');
            await page.settle();
            await press('visaPost');
            const asked = open();
            page.route('/api/runs', { status: 401, body: { error: 'This login was ended.', sessionEnded: true } });
            await page.tick(90000);
            await pressed;
            report({ asked, open: open(), posts: posts().length,
                login: !page.el('loginView').classList.contains('hidden') });
            """);

        assertThat(out.get("asked").asBoolean()).isTrue();
        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("open").asBoolean()).isFalse();
        assertThat(out.get("posts").asInt()).isZero();
    }

    /** A screen reader takes the dialog for one: it has the role, is modal, and is named by its question. */
    @Test
    void theDialogIsOneToAScreenReaderAndFitsAPhone() throws Exception {
        String page = Files.readString(PAGE, UTF_8);

        assertThat(page).contains("<div class=\"dialog\" role=\"dialog\" aria-modal=\"true\""
            + " aria-labelledby=\"postDialogTitle\" aria-describedby=\"postDialogBy\">");
        assertThat(page).contains("<p id=\"postDialogTitle\"", "<p id=\"postDialogBy\"",
            "<pre id=\"postDialogText\" class=\"dialog-text\" tabindex=\"0\">");
        assertThat(rule(page, ".modal")).as("the dialog is never wider than the screen")
            .contains("grid-template-columns: minmax(0, 46rem)", "padding: 1rem");
        assertThat(rule(page, ".dialog")).as("nor taller: the comment scrolls inside it")
            .contains("max-height: calc(100vh - 2rem)", "min-width: 0");
        assertThat(rule(page, ".dialog-text")).contains("overflow: auto", "white-space: pre-wrap",
            "overflow-wrap: anywhere");
        assertThat(page).as("the other questions of the page stay the browser's").doesNotContain("<dialog");
    }

    private static String rule(String page, String selector) {
        Matcher rule = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(page);
        assertThat(rule.find()).as(selector).isTrue();

        return rule.group(1);
    }
}
