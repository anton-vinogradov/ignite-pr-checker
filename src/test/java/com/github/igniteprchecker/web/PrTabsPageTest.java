package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        const note = () => page.el('prTabNote').classList.contains('hidden') ? null : page.el('prTabNote').textContent;
        const OLD_MINE = { number: 12950, title: 'IGNITE-28000 Old but mine', url: 'u', blockers: null };
        """;

    /** Bob signs in with the form in the browser where alice just logged out; his account is linked too. */
    private static final String BOB_SIGNS_IN = """
        async function bobSignsIn() {
            await page.run('logout')();
            page.route('/api/login', { body: { username: 'bob', admin: false, jiraInCookie: false,
                githubLinked: true } }, 'POST');
            page.el('token').value = 'tok';
            await page.run('login')();
            await page.settle();
        }
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
            .isEqualTo("Lists other people's open PRs where someone asked you to review, you reviewed or you commented.");
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
            report({ tabs: tabs(), rows: rows(), asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("tabs").get(0).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("tabs").get(1).get("pressed").asText()).isEqualTo("false");
        assertThat(out.get("tabs").get(1).get("locked").isNull()).as("Mine is on, only not chosen").isTrue();
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
            .isEqualTo("There are no open PRs by other people where someone asked for your review, you reviewed or you commented.");
        assertThat(out.get("filtered").asBoolean()).as("one line says the tab is empty, not two").isTrue();
        assertThat(out.get("filteredNote").asText())
            .isEqualTo("There are no open PRs by other people where someone asked for your review, you reviewed or you commented.");
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
            .isEqualTo("GitHub did not answer the search for these PRs. Click the tab again in a minute.");
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
            .isEqualTo("This checker has no GitHub account, so it cannot search GitHub for these PRs.");
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

    /** GitHub may take seconds to search: until the PRs come, Mine says they are loading instead of standing empty. */
    @Test
    void theFirstOpenOnMineSaysThePrsAreLoading() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            let release;
            page.route('/api/your-prs', () => new Promise(done => { release = done; }));
            await page.load('');
            const waiting = { note: note(), rows: rows(), pressed: tabs()[1].pressed };
            release({ body: { login: 'alice-gh', problem: null, mine: [OLD_MINE], involved: [] } });
            await page.settle();
            report({ waiting, rows: rows(), note: note() });
            """);

        assertThat(out.at("/waiting/note").asText()).isEqualTo("Loading the PRs…");
        assertThat(out.at("/waiting/rows").toString()).isEqualTo("[]");
        assertThat(out.at("/waiting/pressed").asText()).isEqualTo("true");
        assertThat(out.get("rows").toString()).isEqualTo("[12950]");
        assertThat(out.get("note").isNull()).isTrue();
    }

    /** The search of the new user is slow: their Mine shows none of the PRs of the user who signed in before. */
    @Test
    void anotherUsersLoginShowsNoneOfThePreviousUsersPrs() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + BOB_SIGNS_IN + """
            let release;
            const answers = [{ body: { login: 'alice-gh', problem: null, mine: [OLD_MINE], involved: [] } }];
            page.route('/api/your-prs', () => answers.length ? answers.shift()
                : new Promise(done => { release = done; }));
            await page.load('');
            const alice = rows();
            await bobSignsIn();
            const waiting = { user: page.el('username').textContent, rows: rows(), note: note(),
                filtered: page.run('allPrs').map(p => p.number) };
            release({ body: { login: 'bob-gh', problem: null, mine: [], involved: [] } });
            await page.settle();
            report({ alice, waiting, note: note() });
            """);

        assertThat(out.get("alice").toString()).isEqualTo("[12950]");
        assertThat(out.at("/waiting/user").asText()).isEqualTo("bob");
        assertThat(out.at("/waiting/rows").toString()).isEqualTo("[]");
        assertThat(out.at("/waiting/filtered").toString()).as("Enter in the filter opens none of them").isEqualTo("[]");
        assertThat(out.at("/waiting/note").asText()).isEqualTo("Loading the PRs…");
        assertThat(out.get("note").asText()).isEqualTo("You have no open PRs.");
    }

    /** Alice's search was still running when she logged out; its late answer is not taken as bob's. */
    @Test
    void aLateAnswerForThePreviousUserIsDropped() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + BOB_SIGNS_IN + """
            const waiting = [];
            page.route('/api/your-prs', () => new Promise(done => waiting.push(done)));
            await page.load('');
            await bobSignsIn();
            waiting[1]({ body: { login: 'bob-gh', problem: null, mine: [], involved: [] } });
            await page.settle();
            waiting[0]({ body: { login: 'alice-gh', problem: null, mine: [OLD_MINE], involved: [] } });
            await page.settle();
            page.el('prTabInvolved').listeners.click[0]();
            await page.settle();
            page.el('prTabMine').listeners.click[0]();
            await page.settle();
            report({ user: page.el('username').textContent, rows: rows(), note: note(),
                asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("user").asText()).isEqualTo("bob");
        assertThat(out.get("rows").toString()).isEqualTo("[]");
        assertThat(out.get("note").asText()).isEqualTo("You have no open PRs.");
        assertThat(out.get("asked").asInt()).as("bob's answer is kept a minute").isEqualTo(2);
    }

    @Test
    void aLinkedUserSigningInWithTheFormGetsMine() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/me', { status: 401, body: { error: 'Unauthorized' } });
            page.route('/api/login', { body: { username: 'alice', admin: false, jiraInCookie: false,
                githubLinked: true } }, 'POST');
            await page.load('');
            page.el('token').value = 'tok';
            await page.run('login')();
            await page.settle();
            report({ tabs: tabs(), rows: rows(), asked: page.fetches('/api/your-prs').length });
            """);

        assertThat(out.get("tabs").get(1).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("rows").toString()).isEqualTo("[12950,13575]");
        assertThat(out.get("asked").asInt()).isEqualTo(1);
    }

    /**
     * The account was unlinked in another browser: the server's answer names no login, and the list goes back to All
     * rather than say the user has no open PRs.
     */
    @Test
    void anAnswerWithoutALoginTakesTheListBackToAll() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/your-prs', { body: { login: null, problem: null, mine: [], involved: [] } });
            await page.load('');
            report({ tabs: tabs(), rows: rows(), note: note() });
            """);

        assertThat(out.get("tabs").get(0).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("tabs").get(1).get("locked").asText()).isEqualTo("true");
        assertThat(out.get("rows").toString()).isEqualTo("[13575,13461]");
        assertThat(out.get("note").isNull()).isTrue();
    }

    /** A link made in ⚙ turns the two tabs on at once, with no reload of the page. */
    @Test
    void aLinkInSettingsTurnsTheTabsOn() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false,
                githubLinked: false } });
            page.route('/api/auto-visa-all', { body: { login: 'alice-gh' } });
            await page.load('');
            const before = tabs()[1].locked;
            page.el('settingsBtn').onclick();
            await page.settle();
            report({ before, tabs: tabs(), rows: rows() });
            """);

        assertThat(out.get("before").asText()).isEqualTo("true");
        assertThat(out.get("tabs").get(1).get("locked").isNull()).isTrue();
        assertThat(out.get("tabs").get(1).get("pressed").asText()).isEqualTo("true");
        assertThat(out.get("rows").toString()).isEqualTo("[12950,13575]");
    }

    @Test
    void aServiceErrorSaysThePrsDidNotLoad() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/your-prs', { status: 500, body: { error: 'Internal Server Error' } });
            await page.load('');
            report({ note: note(), rows: rows() });
            """);

        assertThat(out.get("note").asText()).isEqualTo("Could not load these PRs. Click the tab again in a moment.");
        assertThat(out.get("rows").toString()).isEqualTo("[]");
    }

    /**
     * A screen reader user hears what a click on a tab brought, as it is not in the list: that the tab is empty or that
     * GitHub did not answer. The notes of a page that just opened are not read out: nobody clicked yet.
     */
    @Test
    void theNoteOfAClickedTabIsReadOut() throws Exception {
        JsonNode out = PageScript.run("index.html", LINKED + """
            page.route('/api/your-prs', { body: { login: 'alice-gh', problem: 'NO_ANSWER', mine: null,
                involved: null } });
            await page.load('');
            await page.tick(100);
            const opened = { note: note(), said: page.el('actAnswer').textContent };
            page.el('prTabInvolved').listeners.click[0]();
            await page.settle();
            await page.tick(100);
            report({ opened, said: page.el('actAnswer').textContent });
            """);

        assertThat(out.at("/opened/note").asText()).startsWith("GitHub did not answer");
        assertThat(out.at("/opened/said").asText()).isEmpty();
        assertThat(out.get("said").asText())
            .isEqualTo("GitHub did not answer the search for these PRs. Click the tab again in a minute.");
    }

    /**
     * At the narrowest the pane gets, 180 px, the three tabs were wider than the pane: Light, Dark and JetBrains cut
     * off "Участвую", and in Terminal the pane scrolled sideways. The longest name shrinks there and ends with "…".
     */
    @Test
    void theTabsFitANarrowPane() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);

        assertThat(rule(css, ".pr-tabs")).contains("min-width: 0");
        assertThat(rule(css, "#prTabInvolved")).contains("flex-shrink: 1", "min-width: 0", "overflow: hidden",
            "text-overflow: ellipsis");
    }

    /** Terminal takes the underline off a tab under the mouse; an off tab keeps its dotted one there too. */
    @Test
    void anOffTabKeepsItsDottedUnderlineUnderTheMouseInTerminal() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);

        assertThat(rule(css, ".pr-tabs .act[aria-disabled=\"true\"], "
            + "html[data-theme=\"terminal\"] .pr-tabs .act[aria-disabled=\"true\"]:hover"))
            .contains("text-decoration: underline dotted");
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("\\n\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();

        return m.group(1);
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
