package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The page took a PR's title only from its row in the list of 50 open PRs. Merged PR 13692, and an open PR past the
 * list, showed "#13692" alone and "JIRA visa: the PR title names no IGNITE ticket.", although its title named
 * IGNITE-29117; the visa and Auto visa of such an open PR could not be used. Links from the checker's visas and PR
 * comments lead to exactly such pages. A key in lower case was not seen at all, while the server posts visas for it.
 */
class PrOffTheListPageTest {
    /** Holds the answers of /api/pr-title until the scenario lets one go: {@code titled(pr, title)}. */
    private static final String HELD_TITLES = """
        const asked = {};
        page.route('/api/pr-title', url => new Promise(resolve => {
            asked[new URLSearchParams(url.split('?')[1]).get('pr')] = resolve; }));
        async function titled(pr, title) {
            asked[pr](title == null ? { status: 404, body: { error: 'GitHub has no title of PR ' + pr } }
                : { body: { title } });
            await page.settle();
        }
        const visa = () => ({ group: !page.el('visaGroup').classList.contains('hidden'),
            noTicket: !page.el('noTicket').classList.contains('hidden'), head: page.el('prTitle').textContent,
            html: page.el('prTitle').innerHTML, key: page.run('lastIssueKey'), post: page.el('visaBtn').title });
        """;

    @Test
    void aPrOffTheListSaysNothingOfItsTicketUntilItsTitleComes() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            page.route('/api/analyze', { body: verdict({ prNumber: 13692,
                mergedAt: Math.floor(page.now() / 1000) - 86400 }) });
            await page.load('?pr=13692');
            const waiting = visa();
            await titled('13692', 'IGNITE-29117 Python thin client: fix examples');
            report({ waiting, titled: visa(), asked: page.fetches('/api/pr-title')[0].url });
            """);

        JsonNode waiting = out.get("waiting");
        assertThat(waiting.get("noTicket").asBoolean()).as("no word of a ticket before the title is known").isFalse();
        assertThat(waiting.get("group").asBoolean()).isFalse();
        assertThat(waiting.get("head").asText()).isEqualTo("#13692");
        assertThat(out.get("asked").asText()).isEqualTo("/api/pr-title?pr=13692");

        JsonNode titled = out.get("titled");
        assertThat(titled.get("head").asText()).isEqualTo("#13692 IGNITE-29117 Python thin client: fix examples");
        assertThat(titled.get("html").asText()).contains("<a href=\"https://issues.apache.org/jira/browse/IGNITE-29117\" "
            + "target=\"_self\" rel=\"noopener\" title=\"Open IGNITE-29117 in JIRA\">IGNITE-29117</a>");
        assertThat(titled.get("group").asBoolean()).isTrue();
        assertThat(titled.get("noTicket").asBoolean()).isFalse();
        assertThat(titled.get("post").asText()).isEqualTo("Post the verdict as a comment to IGNITE-29117 now");
    }

    @Test
    void aKeyInLowerCaseIsTheTicketToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            await page.load('?pr=13575');
            await titled('13575', 'ignite-29049 Move MDC to JUnit');
            report(visa());
            """);

        assertThat(out.get("key").asText()).isEqualTo("IGNITE-29049");
        assertThat(out.get("group").asBoolean()).isTrue();
        assertThat(out.get("html").asText()).contains("<a href=\"https://issues.apache.org/jira/browse/IGNITE-29049\" "
            + "target=\"_self\" rel=\"noopener\" title=\"Open IGNITE-29049 in JIRA\">ignite-29049</a>");
    }

    /** "from Ignite 3" or a three-digit number is no ticket, as the server reads titles. */
    @Test
    void aTitleWithoutAKeySaysSoOnceKnown() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            await page.load('?pr=13575');
            await titled('13575', 'Port the fix from Ignite 3, see IGNITE-123');
            report(visa());
            """);

        assertThat(out.get("noTicket").asBoolean()).isTrue();
        assertThat(out.get("group").asBoolean()).isFalse();
        assertThat(out.get("key").asText()).isEmpty();
    }

    @Test
    void aTitleGithubDoesNotGiveLeavesTheTicketUnknown() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            await page.load('?pr=13575');
            await titled('13575', null);
            report(visa());
            """);

        assertThat(out.get("noTicket").asBoolean()).isFalse();
        assertThat(out.get("group").asBoolean()).isFalse();
        assertThat(out.get("head").asText()).isEqualTo("#13575");
    }

    /** The reader moved on to 13461 before 13692's title came: it is not drawn over 13461's head. */
    @Test
    void aTitleThatComesAfterAnotherPrOpenedIsDropped() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            await page.load('?pr=13692');
            page.run('openPr')(13461, true);
            await page.settle();
            await titled('13692', 'IGNITE-29117 Python thin client: fix examples');
            const late = visa();
            await titled('13461', 'IGNITE-28972 Check marshalled kind');
            report({ late, own: visa() });
            """);

        assertThat(out.get("late").get("head").asText()).isEqualTo("#13461");
        assertThat(out.get("late").get("group").asBoolean()).isFalse();
        assertThat(out.get("own").get("key").asText()).isEqualTo("IGNITE-28972");
    }

    /** The AI prompt quotes the PR's title, which the page now has for a PR off the list too. */
    @Test
    void theAiPromptQuotesTheTitleOfAPrOffTheList() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_TITLES + """
            const copied = [];
            page.run('navigator').clipboard.writeText = async text => { copied.push(text); };
            page.route('/api/analyze', { body: verdict({ prNumber: 13692 }) });
            await page.load('?pr=13692');
            await titled('13692', 'IGNITE-29117 Python thin client: fix examples');
            const btn = page.el('ai-button');
            btn.dataset.suite = 'IgniteTests24Java8_Cache';
            btn.dataset.name = 'org.apache.ignite.ClientReconnectTest.testReconnect';
            await page.run('aiFixPrompt')(btn);
            await page.settle();
            report({ prompt: copied[0] || '' });
            """);

        assertThat(out.get("prompt").asText()).contains("PR title:\n```\nIGNITE-29117 Python thin client: fix examples\n```");
    }
}
