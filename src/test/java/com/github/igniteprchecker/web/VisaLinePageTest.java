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
 * On PR 13575 alice saw Post now and Auto visa, which kept her JIRA token in her cookie, and no word of what her own
 * Visa my runs in JIRA would do with the run. One quiet line stands in the verdict's heading instead: where the visa
 * of the run stands, from the option of whoever started it. ⚙ tells her that the checker does not use the JIRA token
 * her browser kept.
 */
class VisaLinePageTest {
    /** What the line says in a state; {@code who} started the run, and {@code mine} when that is alice. */
    private static final String SAY = """
        const say = (state, mine, more) => page.run('visaLineText')(Object.assign({ state, mine,
            starter: mine ? 'alice' : 'bob' }, more || {}), 'IGNITE-29049');
        """;

    @Test
    void eachStateSaysWhereTheVisaIs() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SAY + """
            await page.load('');
            report({
                posted: say('posted', false, { issue: 'IGNITE-29049', url: 'https://issues.example/c1' }),
                postedLong: say('posted', true, { issue: null, url: null }),
                due: say('due', true), retrying: say('retrying', true), on: say('on', false),
                off: say('off', true), refused: say('off', true, { jiraRefused: true }), offTheirs: say('off', false),
                paused: say('paused', true), jiraRemoved: say('jira-token-removed', true), late: say('late', true),
                noTicket: say('no-ticket', false),
                cancelled: say('cancelled', false), replaced: say('replaced', false), closed: say('closed', false),
                unknown: say('unknown', false, { starter: null }), strange: say('something new', true),
            });
            """);

        assertThat(out.get("posted").asText()).isEqualTo("Visa: posted to <a href=\"https://issues.example/c1\" "
            + "target=\"_self\" rel=\"noopener\" title=\"Opens the visa in JIRA.\">IGNITE-29049</a>");
        assertThat(out.get("postedLong").asText()).as("a visa of v1.20.11 on a PR no longer open")
            .isEqualTo("Visa: posted to IGNITE-29049");
        assertThat(out.get("due").asText()).isEqualTo("Visa: will be posted to IGNITE-29049 when the run settles");
        assertThat(out.get("retrying").asText())
            .isEqualTo("Visa: JIRA did not take it, the checker tries again every 10 minutes");
        assertThat(out.get("on").asText()).isEqualTo("Visa: bob, who started this run, has Visa my runs in JIRA on");
        assertThat(out.get("off").asText()).isEqualTo("Visa: Visa my runs in JIRA is off in ⚙");
        assertThat(out.get("refused").asText())
            .isEqualTo("Visa: Visa my runs in JIRA is off in ⚙, because JIRA rejected your token");
        assertThat(out.get("offTheirs").asText())
            .isEqualTo("Visa: none, bob started this run and has Visa my runs in JIRA off");
        assertThat(out.get("paused").asText())
            .isEqualTo("Visa: waits until you log in again, because TeamCity refused the token your options use");
        assertThat(out.get("jiraRemoved").asText()).isEqualTo("Visa: waits until you save your JIRA token in ⚙ again");
        assertThat(out.get("late").asText())
            .isEqualTo("Visa: none, this run finished before you switched on Visa my runs in JIRA");
        assertThat(out.get("noTicket").asText())
            .isEqualTo("Visa: none, the PR title named no IGNITE ticket when the run settled");
        assertThat(out.get("cancelled").asText()).isEqualTo("Visa: none, this run was cancelled");
        assertThat(out.get("replaced").asText()).isEqualTo("Visa: none for this run, a newer RunAll replaces it");
        assertThat(out.get("closed").asText()).isEqualTo("Visa: none, the PR is closed");
        assertThat(out.get("unknown").asText()).isEqualTo("Visa: unknown, TeamCity does not say who started this run");
        assertThat(out.get("strange").isNull()).as("a state this page does not know shows no line").isTrue();
    }

    /** A merged PR's line said "the PR is closed", as of a PR closed without a merge. */
    @Test
    void aMergedPrSaysItIsMerged() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SAY + """
            await page.load('');
            page.run('selectedPr = 13709');
            const title = state => page.run('setPrTitle')(13709, 'IGNITE-29174 Cleanup of binary modules', null,
                { state, closedAt: 1792491159, head: '9b0256173a55' });
            title('merged');
            const merged = say('closed', false);
            title('closed');
            report({ merged, closed: say('closed', false) });
            """);

        assertThat(out.get("merged").asText()).isEqualTo("Visa: none, the PR is merged");
        assertThat(out.get("closed").asText()).isEqualTo("Visa: none, the PR is closed");
    }

    /** A TeamCity username goes into the line as text, whatever it holds. */
    @Test
    void theStartersNameIsText() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            report({ text: page.run('visaLineText')({ state: 'off', mine: false, starter: '<img src=x>' },
                'IGNITE-29049') });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Visa: none, &lt;img src=x&gt; started this run and has Visa "
            + "my runs in JIRA off");
    }

    /** The line is asked for the run whose verdict is on screen; the server knows when that run finished. */
    @Test
    void theLineIsAskedForTheRunOnScreen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            await page.run('loadVisaLine')(13575);
            report({ asked: page.fetches('/api/visa').map(f => f.url),
                shown: !page.el('visaLine').classList.contains('hidden') });
            """);

        assertThat(out.get("asked").get(0).asText()).isEqualTo("/api/visa?pr=13575&build=9001");
        assertThat(out.get("shown").asBoolean()).isTrue();
    }

    /**
     * alice opened PR 13575, whose run bob started. The line named bob only in a tooltip, which neither a keyboard nor
     * a phone shows.
     */
    @Test
    void someoneElsesRunNamesThemInTheText() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/visa', { body: { state: 'on', starter: 'bob', mine: false } });
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            await page.load('?pr=13575');
            await page.settle();
            report({ text: page.el('visaLine').textContent, title: page.el('visaLine').title });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Visa: bob, who started this run, has Visa my runs in JIRA on");
        assertThat(out.get("title").asText()).isEmpty();
    }

    /**
     * alice reached the link of the posted visa with Tab. Every poll of the runs wrote the same line again, and the
     * new link took the focus away from her.
     */
    @Test
    void aPollWithTheSameAnswerLeavesTheLineAlone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/visa', { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049',
                url: 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1#comment-1' } });
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            await page.load('?pr=13575');
            await page.settle();
            const writes = page.el('visaLine').htmlWrites;
            const asked = page.fetches('/api/visa').length;
            await page.tick(5 * 60 * 1000);
            report({ writes: page.el('visaLine').htmlWrites - writes, asked: page.fetches('/api/visa').length - asked,
                text: page.el('visaLine').textContent });
            """);

        assertThat(out.get("asked").asInt()).as("the polls asked").isPositive();
        assertThat(out.get("writes").asInt()).isZero();
        assertThat(out.get("text").asText()).isEqualTo("Visa: posted to IGNITE-29049");
    }

    /**
     * On a phone the line came after the verdict, took a new row in the heading and pushed every card below down. It
     * stands on its own row from the verdict's first paint, and its text changes in place.
     */
    @Test
    void theLineHoldsItsRowFromTheFirstPaint() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            let answer;
            page.route('/api/visa', () => new Promise(resolve => { answer = resolve; }));
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            await page.load('?pr=13575');
            await page.settle();
            const before = { results: !page.el('results').classList.contains('hidden'),
                line: !page.el('visaLine').classList.contains('hidden'), text: page.el('visaLine').textContent };
            answer({ body: { state: 'due', starter: 'alice', mine: true } });
            await page.settle();
            report({ before, text: page.el('visaLine').textContent });
            """);

        assertThat(out.at("/before/results").asBoolean()).isTrue();
        assertThat(out.at("/before/line").asBoolean()).isTrue();
        assertThat(out.at("/before/text").asText()).isEqualTo("Visa: …");
        assertThat(out.get("text").asText()).isEqualTo("Visa: will be posted to IGNITE-29049 when the run settles");
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);
        Matcher rule = Pattern.compile("\\.visa-row \\{([^}]*)}").matcher(page);
        assertThat(rule.find()).isTrue();
        assertThat(rule.group(1)).as("the row of the line and Post to JIRA").contains("flex-basis: 100%")
            .doesNotContain("margin-left: auto");
    }

    /** The server could not say where the visa stands: "Visa: …" does not stay on the page as if it would. */
    @Test
    void aFailedFirstAnswerTakesThePlaceholderAway() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/visa', { status: 502, body: { error: 'TeamCity did not answer' } });
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            await page.load('?pr=13575');
            await page.settle();
            report({ asked: page.fetches('/api/visa').length, hidden: page.el('visaLine').classList.contains('hidden'),
                text: page.el('visaLine').textContent });
            """);

        assertThat(out.get("asked").asInt()).isPositive();
        assertThat(out.get("hidden").asBoolean()).isTrue();
        assertThat(out.get("text").asText()).isEmpty();
    }

    @Test
    void settingsSayTheJiraTokenTheBrowserKeptIsNotUsed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: true } });
            page.route('/api/auto-visa-all', { body: { visa: false, jiraStored: false } }, 'GET');
            page.route('/api/auto-visa-all', { body: { visa: true, jiraStored: true } }, 'POST');
            await page.load('');
            await page.el('settingsBtn').onclick();
            await page.settle();
            const off = !page.el('jiraInCookie').classList.contains('hidden');
            page.el('settingsJiraToken').value = 'FAKEjiraPAT0123456789abcdefghijklmnopqrstuv';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            report({ off, on: !page.el('jiraInCookie').classList.contains('hidden') });
            """);

        assertThat(out.get("off").asBoolean()).isTrue();
        assertThat(out.get("on").asBoolean()).isFalse();
        assertThat(Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8).replaceAll("\\s+", " "))
            .contains("⚠ The checker does not use the JIRA token your browser kept. Visas go to JIRA only while Visa "
                + "my runs in JIRA is on: switch it on and paste the token.");
    }

    @Test
    void settingsSayNothingOfACookieWithoutAJiraToken() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { visa: false, jiraStored: false } }, 'GET');
            await page.load('');
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ shown: !page.el('jiraInCookie').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }
}
