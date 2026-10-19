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
 * alice logged in at a colleague's desk and went home. Log out there would have ended her laptop's login too, and
 * from her laptop nothing showed that the colleague's browser was still logged in as her, nor ended it alone. ⚙ lists
 * her logins, this browser first, and logs out the ones she ticks, or every one but this browser.
 */
class LoginListPageTest {
    /** What the answer of Log out everywhere else adds. */
    private static final String COPY_STAYS = "Anyone with a copy of this browser's cookie stays logged in until you "
        + "press Log out in this browser.";

    /** Three logins of alice's: this browser, the colleague's, and one an earlier version began. */
    private static final String LOGINS = """
        const HOUR = 3600000;
        const LOGINS = [
            { id: 'here', browser: 'Firefox on Linux', createdAt: Date.parse('2026-10-01T09:30:00Z'), earlier: false,
                lastSeen: page.now(), current: true },
            { id: 'desk', browser: 'Chrome on Windows', createdAt: Date.parse('2026-10-06T14:05:00Z'), earlier: false,
                lastSeen: page.now() - 3 * HOUR, current: false },
            { id: 'old', browser: 'Safari on macOS', createdAt: Date.parse('2026-09-20T08:00:00Z'), earlier: true,
                lastSeen: page.now() - 50 * HOUR, current: false }];
        page.route('/api/logins', { body: { logins: LOGINS, ended: 0 } });
        const tick = (id, on) => page.el('loginsList').listeners.change[0]({ target: { dataset: { login: id },
            checked: on } });
        """;

    private static final String OPEN = """
        await page.load('?pr=13575');
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    @Test
    void settingsListTheLoginsThisBrowserFirst() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + OPEN + """
            report({ state: page.el('loginsState').textContent, html: page.el('loginsList').innerHTML,
                text: page.el('loginsList').textContent, endDisabled: page.el('loginsEnd').disabled });
            """);

        assertThat(out.get("state").asText()).isEqualTo("3 browsers");
        assertThat(out.get("text").asText()).isEqualTo("Firefox on Linux — this browser, logged in 1 Oct 09:30"
            + "Chrome on Windows — logged in 6 Oct 14:05, last seen 3h ago"
            + "Safari on macOS — logged in with an earlier version of the checker, last seen 2d ago");
        assertThat(out.get("html").asText()).doesNotContain("data-login=\"here\"")
            .contains("data-login=\"desk\"").contains("data-login=\"old\"");
        assertThat(out.get("endDisabled").asBoolean()).as("nothing ticked yet").isTrue();
    }

    @Test
    void logOutSelectedEndsTheTickedLogins() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + """
            page.route('/api/logins/end', { body: { logins: [LOGINS[0], LOGINS[2]], ended: 1 } }, 'POST');
            """ + OPEN + """
            tick('desk', true);
            tick('old', true);
            tick('old', false);
            const enabled = !page.el('loginsEnd').disabled;
            await page.el('loginsEnd').onclick();
            report({ enabled, posts: page.fetches('/api/logins/end').map(f => f.body),
                message: page.el('loginsMsg').textContent, text: page.el('loginsList').textContent,
                endDisabled: page.el('loginsEnd').disabled,
                loginShown: !page.el('loginView').classList.contains('hidden') });
            """);

        assertThat(out.get("enabled").asBoolean()).isTrue();
        assertThat(out.get("posts").toString()).isEqualTo("[\"{\\\"ids\\\":[\\\"desk\\\"]}\"]");
        assertThat(out.get("message").asText()).isEqualTo("Logged out 1 browser.");
        assertThat(out.get("text").asText()).doesNotContain("Chrome on Windows");
        assertThat(out.get("endDisabled").asBoolean()).isTrue();
        assertThat(out.get("loginShown").asBoolean()).as("this browser stays logged in").isFalse();
    }

    /**
     * alice fears someone copied her cookie and presses Log out everywhere else. A copy of this browser's cookie is
     * this browser's login, so it stays logged in: the answer says so, and what ends it.
     */
    @Test
    void logOutEverywhereElseKeepsThisBrowser() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + """
            page.route('/api/logins/end-others', { body: { logins: [LOGINS[0]], ended: 2 } }, 'POST');
            """ + OPEN + """
            await page.el('loginsEndOthers').onclick();
            const first = page.el('loginsMsg').textContent;
            page.route('/api/logins/end-others', { body: { logins: [LOGINS[0]], ended: 0 } }, 'POST');
            await page.el('loginsEndOthers').onclick();
            report({ first, again: page.el('loginsMsg').textContent, state: page.el('loginsState').textContent,
                posts: page.fetches('/api/logins/end-others').length });
            """);

        assertThat(out.get("first").asText()).isEqualTo("Logged out 2 browsers. " + COPY_STAYS);
        assertThat(out.get("again").asText()).isEqualTo("No other browser is logged in now. " + COPY_STAYS);
        assertThat(out.get("state").asText()).isEqualTo("1 browser");
        assertThat(out.get("posts").asInt()).isEqualTo(2);
    }

    /** The title of the button tells the same before alice presses it. */
    @Test
    void theTitleOfLogOutEverywhereElseSaysACopyOfThisBrowsersCookieStays() throws Exception {
        Matcher button = Pattern.compile("<button id=\"loginsEndOthers\"[^>]*title=\"([^\"]*)\"")
            .matcher(Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8));

        assertThat(button.find()).isTrue();
        assertThat(button.group(1)).isEqualTo("Logs out every other browser: each goes to the login form at its next "
            + "request. A copy of this browser's cookie stays logged in until you press Log out in this browser.");
    }

    /**
     * A screen reader user presses Log out everywhere else, then Log out selected, and heard nothing: the answer was
     * shown in a line no reader reads out. Both answers are read out.
     */
    @Test
    void theAnswersOfTheLogOutButtonsAreReadOut() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + """
            page.route('/api/logins/end-others', { body: { logins: [LOGINS[0]], ended: 2 } }, 'POST');
            page.route('/api/logins/end', { body: { logins: [LOGINS[0]], ended: 0 } }, 'POST');
            """ + OPEN + """
            await page.el('loginsEndOthers').onclick();
            await page.tick(16);
            const others = page.el('actAnswer').textContent;
            tick('desk', true);
            await page.el('loginsEnd').onclick();
            await page.tick(16);
            report({ others, selected: page.el('actAnswer').textContent });
            """);

        assertThat(out.get("others").asText()).isEqualTo("Logged out 2 browsers. " + COPY_STAYS);
        assertThat(out.get("selected").asText()).isEqualTo("Those browsers were already logged out.");
    }

    /** The service answered without a reason of its own: the answer is still full sentences, as the others are. */
    @Test
    void aFailedLogOutWithoutAReasonSaysSoInFullSentences() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + """
            page.route('/api/logins/end-others', { status: 500, body: { error: 'Internal Server Error' } }, 'POST');
            page.route('/api/logins/end', { status: 502, body: { error: 'TeamCity could not check the token' } },
                'POST');
            """ + OPEN + """
            await page.el('loginsEndOthers').onclick();
            const restarting = page.el('loginsMsg').textContent;
            tick('desk', true);
            await page.el('loginsEnd').onclick();
            report({ restarting, refused: page.el('loginsMsg').textContent });
            """);

        assertThat(out.get("restarting").asText())
            .isEqualTo("Nothing was logged out. The service may be restarting. Try again in a moment.");
        assertThat(out.get("refused").asText()).isEqualTo("TeamCity could not check the token");
    }

    /**
     * TeamCity refused this browser's token at the check before the end: the page logs this browser out and says so.
     */
    @Test
    void aRefusedTokenEndsNoLoginAndSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + LOGINS + """
            page.route('/api/logins/end-others', { status: 401, body: { error: 'TeamCity rejected your token',
                tokenRejected: true } }, 'POST');
            """ + OPEN + """
            await page.el('loginsEndOthers').onclick();
            report({ loginShown: !page.el('loginView').classList.contains('hidden'),
                message: page.el('loginErr').textContent, logouts: page.fetches('/api/logout').length });
            """);

        assertThat(out.get("loginShown").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("TeamCity rejected your token");
        assertThat(out.get("logouts").asInt()).isEqualTo(1);
    }

    /** A label comes from the browser's User-Agent, which anyone can write: it is text, not markup. */
    @Test
    void aLabelIsShownAsText() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/logins', { body: { logins: [{ id: 'x"><b', browser: '<img src=x onerror=alert(1)>',
                createdAt: page.now(), earlier: false, lastSeen: page.now(), current: false }], ended: 0 } });
            """ + OPEN + """
            report({ html: page.el('loginsList').innerHTML });
            """);

        assertThat(out.get("html").asText()).doesNotContain("<img").contains("&lt;img")
            .contains("data-login=\"x&quot;&gt;&lt;b\"");
    }
}
