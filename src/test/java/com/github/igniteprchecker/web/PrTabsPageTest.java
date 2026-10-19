package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A user with a linked GitHub account asked for the list to show only their PRs by default, and for a second view of
 * the PRs they review or commented on. The list showed the 50 most recently updated PRs of everyone, and a PR of
 * theirs that nobody touched for a while was not in it. Three tabs stand over the list now: All, Mine and Involved;
 * Mine opens first for a user with a linked account, and a tab chosen once stays chosen in this browser.
 */
class PrTabsPageTest {
    /** A user with a linked account: PR 12950 is theirs and not among the 50 of All. */
    private static final String LINKED = PageScript.SIGNED_IN + """
        page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, githubLinked: true } });
        page.route('/api/your-prs', { body: { login: 'alice-gh', problem: null,
            mine: [{ number: 12950, title: 'IGNITE-28000 Old but mine', url: 'https://github.com/apache/ignite/pull/12950',
                    triggeredBy: null, blockers: 0, proven: true, standing: 'CLEAN' },
                { number: 13575, title: 'IGNITE-29049 Move MDC to JUnit', url: 'https://github.com/apache/ignite/pull/13575',
                    triggeredBy: 'alice', blockers: 1, proven: true }],
            involved: [{ number: 13461, title: 'IGNITE-28972 Check marshalled kind',
                url: 'https://github.com/apache/ignite/pull/13461', triggeredBy: null, blockers: 0, proven: true }] } });
        const tabs = () => ['prTabAll', 'prTabMine', 'prTabInvolved'].map(id => ({
            pressed: page.el(id).getAttribute('aria-pressed'), locked: page.el(id).getAttribute('aria-disabled'),
            title: page.el(id).title }));
        const rows = () => [...page.el('prList').innerHTML.matchAll(/data-num="(\\d+)" data-url/g)].map(m => +m[1]);
        """;

    @Test
    void aLinkedUserGetsTheirOwnPrsFirstOneOffTheListOfAllAmongThem() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            await page.load('');
            report({ tabs: tabs(), rows: rows(), count: page.el('prCount').textContent,
                asked: page.fetches('/api/your-prs').length, note: page.el('prTabNote').classList.contains('hidden') });
            """);

        assertThat(out.get("rows").toString()).isEqualTo("[12950,13575]");
        assertThat(out.get("count").asText()).isEqualTo("(2)");
        assertThat(out.get("tabs").get(1).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("tabs").get(0).get("pressed").asText()).isEqualTo("false");
        assertThat(out.get("tabs").get(1).get("title").asText())
            .isEqualTo("Lists the open PRs your linked GitHub account created.");
        assertThat(out.get("tabs").get(2).get("title").asText())
            .isEqualTo("Lists other people's open PRs where you were asked to review, reviewed or commented.");
        assertThat(out.get("tabs").get(0).get("title").asText())
            .isEqualTo("Lists the 50 most recently updated open PRs.");
        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.get("note").asBoolean()).as("no note over a list of PRs").isTrue();
    }

    @Test
    void theTabsSwitchTheListAndTheChoiceStaysInTheBrowser() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            await page.load('');
            page.el('prTabInvolved').listeners.click[0]();
            await page.settle();
            const involved = { rows: rows(), saved: page.run("localStorage.getItem('prTab')"), tabs: tabs() };
            page.el('prTabAll').listeners.click[0]();
            await page.settle();
            report({ involved, all: rows(), saved: page.run("localStorage.getItem('prTab')"),
                asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("involved").get("rows").toString()).isEqualTo("[13461]");
        assertThat(out.get("involved").get("saved").asText()).isEqualTo("involved");
        assertThat(out.get("involved").get("tabs").get(2).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("all").toString()).isEqualTo("[13575,13461]");
        assertThat(out.get("saved").asText()).isEqualTo("all");
        assertThat(out.get("asked").asInt()).as("Mine and Involved come in one answer, kept a minute").isEqualTo(1);
    }

    @Test
    void theTabChosenBeforeIsTheOneThePageOpensOn() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.run("localStorage.setItem('prTab', 'all')");
            await page.load('');
            report({ rows: rows(), asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("rows").toString()).isEqualTo("[13575,13461]");
        assertThat(out.get("asked").asInt()).as("All asks GitHub for nobody's PRs").isZero();
    }

    /** Without a linked account the two tabs are there but off, and a click on one opens ⚙, where the link is made. */
    @Test
    void withoutALinkMineAndInvolvedAreOffAndLeadToSettings() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.run("localStorage.setItem('prTab', 'mine')");
            await page.load('');
            const before = page.el('settingsPanel').classList.contains('hidden');
            page.el('prTabMine').listeners.click[0]();
            await page.settle();
            report({ before, after: page.el('settingsPanel').classList.contains('hidden'),
                mine: { locked: page.el('prTabMine').getAttribute('aria-disabled'), title: page.el('prTabMine').title,
                    pressed: page.el('prTabMine').getAttribute('aria-pressed') },
                involved: page.el('prTabInvolved').getAttribute('aria-disabled'),
                all: page.el('prTabAll').getAttribute('aria-pressed'), saved: page.run("localStorage.getItem('prTab')"),
                list: page.el('prList').innerHTML.includes('data-num="13575"'),
                asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("before").asBoolean()).isTrue();
        assertThat(out.get("after").asBoolean()).as("⚙ opened").isFalse();
        assertThat(out.get("mine").get("locked").asText()).isEqualTo("true");
        assertThat(out.get("mine").get("pressed").asText()).isEqualTo("false");
        assertThat(out.get("mine").get("title").asText())
            .isEqualTo("Link your GitHub account in ⚙ to use this tab. A click opens ⚙.");
        assertThat(out.get("involved").asText()).isEqualTo("true");
        assertThat(out.get("all").asText()).isEqualTo("true");
        assertThat(out.get("saved").asText()).as("a click on an off tab chooses nothing").isEqualTo("mine");
        assertThat(out.get("list").asBoolean()).isTrue();
        assertThat(out.get("asked").asInt()).isZero();
    }

    @Test
    void anEmptyTabSaysWhyItIsEmpty() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/your-prs', { body: { login: 'alice-gh', problem: null, mine: [], involved: [] } });
            await page.load('');
            const note = () => page.el('prTabNote').classList.contains('hidden') ? null
                : page.el('prTabNote').textContent;
            const mine = { note: note(), count: page.el('prCount').textContent };
            page.el('prTabInvolved').listeners.click[0]();
            await page.settle();
            const involved = note();
            page.el('prFilter').value = 'MDC';
            page.run('applyPrFilter')();
            report({ mine, involved, filtered: page.el('prNone').classList.contains('hidden'), filteredNote: note() });
            """);

        assertThat(out.get("mine").get("note").asText()).isEqualTo("You have no open PRs.");
        assertThat(out.get("mine").get("count").asText()).isEmpty();
        assertThat(out.get("involved").asText())
            .isEqualTo("No open PRs of others where you were asked to review, reviewed or commented.");
        assertThat(out.get("filtered").asBoolean()).as("one line says the tab is empty, not two").isTrue();
        assertThat(out.get("filteredNote").asText())
            .isEqualTo("No open PRs of others where you were asked to review, reviewed or commented.");
    }

    @Test
    void aTabGitHubDidNotAnswerForSaysSoAndAsksAgainOnAClick() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            let answers = [{ login: 'alice-gh', problem: 'NO_ANSWER', mine: null, involved: null },
                { login: 'alice-gh', problem: null, mine: [{ number: 12950, title: 'IGNITE-28000 Old but mine',
                    url: 'u', blockers: null }], involved: [] }];
            page.route('/api/your-prs', () => ({ body: answers.shift() }));
            await page.load('');
            const failed = page.el('prTabNote').textContent;
            page.el('prTabMine').listeners.click[0]();
            await page.settle();
            report({ failed, rows: rows(), hidden: page.el('prTabNote').classList.contains('hidden') });
            """);

        assertThat(out.get("failed").asText())
            .isEqualTo("GitHub did not answer the search for your PRs. Click the tab again in a minute.");
        assertThat(out.get("rows").toString()).isEqualTo("[12950]");
        assertThat(out.get("hidden").asBoolean()).isTrue();
    }

    @Test
    void aCheckerWithoutAGithubAccountSaysItCannotSearch() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/your-prs', { body: { login: 'alice-gh', problem: 'NO_ACCOUNT', mine: null,
                involved: null } });
            await page.load('');
            report({ note: page.el('prTabNote').textContent });
            """);

        assertThat(out.get("note").asText())
            .isEqualTo("This checker has no GitHub account, so it cannot search GitHub for your PRs.");
    }

    /** The filter narrows the tab's own PRs; a number that is not among them is still offered to open. */
    @Test
    void theFilterWorksInsideTheTab() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            await page.load('');
            const match = q => page.run('prMatches')(page.run('allPrs'), q);
            report({ old: match('Old'), other: match('13461') });
            """);

        assertThat(out.get("old").get("nums").toString()).isEqualTo("[12950]");
        assertThat(out.get("other").get("nums").toString()).isEqualTo("[]");
        assertThat(out.get("other").get("open").asInt()).isEqualTo(13461);
    }

    /** Unlinking in ⚙ turns the two tabs off and takes the list back to All. */
    @Test
    void anUnlinkInSettingsTakesTheListBackToAll() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/auto-visa-all', { body: { login: null } });
            await page.load('');
            page.el('settingsBtn').onclick();
            await page.settle();
            report({ tabs: tabs(), rows: rows() });
            """);

        assertThat(out.get("tabs").get(0).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("tabs").get(1).get("locked").asText()).isEqualTo("true");
        assertThat(out.get("rows").toString()).isEqualTo("[13575,13461]");
    }

    @Test
    void theTabsSpeakRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', lang: 'ru', githubLinked: true } });
            page.route('/api/your-prs', { body: { login: 'alice-gh', problem: null, mine: [], involved: [] } });
            await page.load('');
            report({ names: ['prTabAll', 'prTabMine', 'prTabInvolved'].map(id => page.text(id)),
                group: page.el('prTabs').getAttribute('aria-label'), title: page.el('prTabMine').title,
                note: page.el('prTabNote').textContent });
            """);

        assertThat(out.get("names").toString()).isEqualTo("[\"Все\",\"Мои\",\"Участвую\"]");
        assertThat(out.get("group").asText()).isEqualTo("Какие открытые PR показывать");
        assertThat(out.get("title").asText())
            .isEqualTo("Показывает открытые PR, которые создал твой привязанный аккаунт GitHub.");
        assertThat(out.get("note").asText()).isEqualTo("У тебя нет открытых PR.");
    }
}
