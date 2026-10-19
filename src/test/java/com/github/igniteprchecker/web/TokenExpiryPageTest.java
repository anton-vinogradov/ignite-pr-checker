package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ⚙ said whether each token was saved, not how long it would work, so a token could reach its end and stop the options
 * that ran on it with no warning. ⚙ now says after each token's state how long it has left, amber within 14 days with
 * what to do, and says why when the checker cannot tell. Alice saved a JIRA token and a GitHub token.
 */
class TokenExpiryPageTest {
    private static final String SETTINGS = """
        const DAY = 86400000;
        const SETTINGS = { visa: true, rerun: false, gh: false, style: false, commands: true, login: 'alice-gh',
            jiraStored: true, ghStored: true, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: true, loginUnproven: false, githubAvailable: true,
            appLogin: 'ignite-pr-checker', githubLinkable: true, ghTokenNote: null, ghTokenKind: 'fine-grained' };
        page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
        const expires = (service, ms) => page.route('/api/token-expiry?service=' + service,
            { body: { state: 'expires', expiresAt: page.now() + ms } });
        const line = id => ({ text: page.text(id), title: page.el(id + 'Text').title,
            amber: page.el(id + 'Text').classList.contains('expiry-soon'),
            hidden: page.el(id).classList.contains('hidden'),
            hint: page.el(id + 'Text').classList.contains('hint'), lead: page.el(id + 'Lead').textContent,
            outer: page.el(id).classList.contains('expiry-soon') || page.el(id).classList.contains('hint') });
        """;

    private static final String OPEN = """
        await page.load('?pr=13575');
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    @Test
    void eachTokenSaysHowLongItHasLeft() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            expires('teamcity', 43 * DAY);
            expires('jira', 5 * DAY);
            page.route('/api/token-expiry?service=github', { body: { state: 'never' } });
            """ + OPEN + """
            report({ tc: line('tcExpiry'), jira: line('jiraExpiry'), github: line('ghTokenExpiry'),
                tcLine: page.el('tcState').textContent + page.text('tcExpiry'),
                ghLine: page.el('ghTokenState').textContent + page.text('ghTokenExpiry') });
            """);

        assertThat(out.get("tcLine").asText()).isEqualTo("alice, token expires in 43 days");
        assertThat(out.at("/tc/title").asText()).isEqualTo("It expires on 19 Nov at 12:00.");
        assertThat(out.at("/tc/hint").asBoolean()).as("the line shows that it has a tooltip").isTrue();
        assertThat(out.at("/tc/amber").asBoolean()).isFalse();
        assertThat(out.at("/jira/text").asText()).isEqualTo(", expires in 5 days: replace it before then");
        assertThat(out.at("/jira/title").asText()).isEqualTo("It expires on 12 Oct at 12:00. Then Visa my runs in JIRA"
            + " goes off until you save a new token and switch it on again.");
        assertThat(out.at("/jira/amber").asBoolean()).isTrue();
        assertThat(out.get("ghLine").asText()).isEqualTo("saved, fine-grained, never expires: give the next one an"
            + " expiry, say 90 days");
        assertThat(out.at("/github/title").asText()).isEqualTo("Anyone who gets this token can use it until you revoke"
            + " it on GitHub.");
        assertThat(out.at("/github/amber").asBoolean()).as("a token without an expiry is accepted").isFalse();
    }

    /** A line that says 14 days is amber, 14 days and an hour ahead too, and the last day says so. */
    @Test
    void theLastTwoWeeksAreAmber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            expires('teamcity', 14 * DAY + 3600000);
            expires('jira', 15 * DAY);
            expires('github', 3600000);
            """ + OPEN + """
            const tcAt14 = line('tcExpiry');
            expires('teamcity', 14 * DAY);
            await page.el('settingsBtn').onclick();
            await page.el('settingsBtn').onclick();
            await page.settle();
            report({ tc: tcAt14, tc14: line('tcExpiry'), jira: line('jiraExpiry'), github: line('ghTokenExpiry') });
            """);

        assertThat(out.at("/tc/text").asText()).isEqualTo(", token expires in 14 days: log in with a new one before"
            + " then");
        assertThat(out.at("/tc/title").asText()).isEqualTo("It expires on 21 Oct at 13:00. Then you have to log in"
            + " again, and your options pause until you do.");
        assertThat(out.at("/tc/amber").asBoolean()).isTrue();
        assertThat(out.at("/tc14/text").asText()).isEqualTo(out.at("/tc/text").asText());
        assertThat(out.at("/tc14/amber").asBoolean()).isTrue();
        assertThat(out.at("/jira/text").asText()).isEqualTo(", expires in 15 days");
        assertThat(out.at("/jira/amber").asBoolean()).isFalse();
        assertThat(out.at("/github/text").asText()).isEqualTo(", expires in less than a day: replace it before then");
        assertThat(out.at("/github/title").asText()).endsWith("Then the options that commit with it go off until you"
            + " save a new token and switch them on again.");
    }

    @Test
    void anExpiryTheCheckerCannotTellSaysWhy() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/token-expiry?service=teamcity', { body: { state: 'unknown', why: 'several', tokens: 3 } });
            page.route('/api/token-expiry?service=jira', { body: { state: 'unknown', why: 'unlisted' } });
            page.route('/api/token-expiry?service=github', { body: { state: 'refused' } });
            """ + OPEN + """
            const first = { tc: line('tcExpiry'), jira: line('jiraExpiry'), github: line('ghTokenExpiry') };
            page.route('/api/token-expiry?service=teamcity', { body: { state: 'unknown', why: 'unreadable' } });
            page.route('/api/token-expiry?service=github', { body: { state: 'unknown', why: 'no-answer' } });
            await page.el('settingsBtn').onclick();
            await page.el('settingsBtn').onclick();
            await page.settle();
            report(Object.assign(first, { tc2: line('tcExpiry'), github2: line('ghTokenExpiry') }));
            """);

        assertThat(out.at("/tc/text").asText()).isEqualTo(", token expiry unknown: you have 3 tokens that expire at"
            + " different times");
        assertThat(out.at("/tc/title").asText()).isEqualTo("TeamCity does not say which one you logged in with.");
        assertThat(out.at("/jira/text").asText()).isEqualTo(", expiry unknown: JIRA did not list your tokens");
        assertThat(out.at("/jira/hint").asBoolean()).as("no tooltip").isFalse();
        assertThat(out.at("/jira/amber").asBoolean()).isFalse();
        assertThat(out.at("/github/text").asText()).isEqualTo(", GitHub refused it when the checker asked: replace"
            + " it");
        assertThat(out.at("/github/amber").asBoolean()).isTrue();
        assertThat(out.at("/tc2/text").asText()).isEqualTo(", token expiry unknown: TeamCity wrote the date in a form"
            + " the checker cannot read");
        assertThat(out.at("/github2/text").asText()).isEqualTo(", expiry unknown: GitHub did not answer");
        assertThat(out.at("/github2/amber").asBoolean()).isFalse();
    }

    /**
     * Alice's saved JIRA token expired yesterday, and its line said in grey that JIRA did not list her tokens. JIRA
     * refuses the token, and the line says so in amber, as for GitHub.
     */
    @Test
    void aTokenJiraRefusesIsAmber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/token-expiry?service=jira', { body: { state: 'refused' } });
            page.route('/api/token-expiry', { body: { state: 'never' } });
            """ + OPEN + """
            report({ jira: line('jiraExpiry') });
            """);

        assertThat(out.at("/jira/text").asText()).isEqualTo(", JIRA refused it when the checker asked: replace it");
        assertThat(out.at("/jira/title").asText()).isEqualTo("Visa my runs in JIRA goes off when it next tries to post"
            + " a visa.");
        assertThat(out.at("/jira/amber").asBoolean()).isTrue();
    }

    /**
     * The amber of "expires in 5 days" and the dotted line of its tooltip started at the comma after "saved": the
     * comma read as part of the warning. They start at the expiry's words.
     */
    @Test
    void theAmberStartsAtTheExpirysWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            expires('teamcity', 5 * DAY);
            expires('jira', 5 * DAY);
            page.route('/api/token-expiry?service=github', { body: { state: 'never' } });
            """ + OPEN + """
            report({ tc: line('tcExpiry'), jira: line('jiraExpiry'), github: line('ghTokenExpiry') });
            """);

        for (String service : new String[] {"tc", "jira", "github"})
            assertThat(out.at("/" + service + "/outer").asBoolean()).as(service).isFalse();
        assertThat(out.at("/tc/lead").asText()).isEqualTo(", token ");
        assertThat(out.at("/tc/amber").asBoolean()).isTrue();
        assertThat(out.at("/jira/lead").asText()).isEqualTo(", ");
        assertThat(out.at("/jira/amber").asBoolean()).isTrue();
        assertThat(out.at("/github/hint").asBoolean()).isTrue();
    }

    /**
     * In Russian ⚙, the TeamCity line read "alice, токен срок неизвестен: …", and the JIRA lines said "JIRA отверг его"
     * and "JIRA не ответил" while the rest of ⚙ says "JIRA отвергла". The TeamCity line says "срок токена неизвестен",
     * and JIRA takes the feminine.
     */
    @Test
    void theRussianExpiryLinesAgree() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            page.route('/api/token-expiry?service=teamcity', { body: { state: 'unknown', why: 'several', tokens: 2 } });
            page.route('/api/token-expiry?service=jira', { body: { state: 'refused' } });
            page.route('/api/token-expiry?service=github', { body: { state: 'unknown', why: 'no-answer' } });
            """ + OPEN + """
            const first = { tc: page.el('tcState').textContent + page.text('tcExpiry'), jira: page.text('jiraExpiry'),
                github: page.text('ghTokenExpiry') };
            page.route('/api/token-expiry?service=teamcity', { body: { state: 'unknown', why: 'no-answer' } });
            page.route('/api/token-expiry?service=jira', { body: { state: 'unknown', why: 'no-answer' } });
            await page.el('settingsBtn').onclick();
            await page.el('settingsBtn').onclick();
            await page.settle();
            report(Object.assign(first, { tc2: page.text('tcExpiry'), jira2: page.text('jiraExpiry') }));
            """);

        assertThat(out.get("tc").asText()).isEqualTo("alice, срок токена неизвестен: у тебя 2 токена, которые истекают"
            + " в разное время");
        assertThat(out.get("tc2").asText()).isEqualTo(", срок токена неизвестен: TeamCity не ответил");
        assertThat(out.get("jira").asText()).isEqualTo(", JIRA отвергла его, когда чекер спросил: замени его");
        assertThat(out.get("jira2").asText()).isEqualTo(", срок неизвестен: JIRA не ответила");
        assertThat(out.get("github").asText()).isEqualTo(", срок неизвестен: GitHub не ответил");
    }

    /**
     * JIRA did not answer when alice saved her token, and the Russian ⚙ said "JIRA не ответил — попробуй чуть позже"
     * beside "JIRA отвергла токен". TeamCity and GitHub keep the masculine.
     */
    @Test
    void aJiraThatDoesNotAnswerIsFeminine() throws Exception {
        String catalog = Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8);
        JsonNode out = PageFunctions.run("common.js", "const CATALOG = " + catalog + ";\n" + """
            const sections = Object.entries(CATALOG);
            RU = new Map(sections.flatMap(([, entries]) => Object.entries(entries)));
            RU_SERVER = sections.filter(([name]) => name.startsWith('server')).flatMap(([, e]) => Object.entries(e));
            LANG = 'ru';
            out(['JIRA', 'TeamCity', 'GitHub'].map(s => tr(s + ' did not answer — try again in a moment')));
            """);

        assertThat(out.get(0).asText()).isEqualTo("JIRA не ответила — попробуй чуть позже");
        assertThat(out.get(1).asText()).isEqualTo("TeamCity не ответил — попробуй чуть позже");
        assertThat(out.get(2).asText()).isEqualTo("GitHub не ответил — попробуй чуть позже");
    }

    /** An answer kept for an hour can be about a token that has expired since. */
    @Test
    void anExpiredTokenSaysSo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            expires('jira', -3600000);
            page.route('/api/token-expiry', { body: { state: 'never' } });
            """ + OPEN + """
            report({ jira: line('jiraExpiry') });
            """);

        assertThat(out.at("/jira/text").asText()).isEqualTo(", expired: replace it");
        assertThat(out.at("/jira/title").asText()).isEqualTo("It expired on 7 Oct at 11:00.");
        assertThat(out.at("/jira/amber").asBoolean()).isTrue();
    }

    /** A token the server does not store has no line, and nobody asks about it. */
    @Test
    void onlyTheTokensTheServerHoldsAreAskedAbout() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { visa: false, jiraStored: false,
                suites: false, ghStored: false, ghTokenKind: null }) }, 'GET');
            page.route('/api/token-expiry', { body: { state: 'never' } });
            """ + OPEN + """
            report({ asked: page.fetches('/api/token-expiry').map(f => f.url), jira: line('jiraExpiry'),
                github: line('ghTokenExpiry'), tc: line('tcExpiry') });
            """);

        assertThat(out.get("asked").toString()).isEqualTo("[\"/api/token-expiry?service=teamcity\"]");
        assertThat(out.at("/jira/hidden").asBoolean()).isTrue();
        assertThat(out.at("/github/hidden").asBoolean()).isTrue();
        assertThat(out.at("/tc/text").asText()).isEqualTo(", token never expires: give the next one an expiry, say"
            + " 90 days");
    }

    /**
     * The operator restored the state from a copy older than two days, which keeps no tokens: the visa and the tests to
     * suites stay on and wait. ⚙ says what to save; the token lines have no expiry, and nobody asks about them.
     */
    @Test
    void aTokenARestoredCopyLackedHasNoExpiry() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { jiraStored: false, ghStored: false,
                ghTokenKind: null, tokensRemoved: ['jira', 'github'] }) }, 'GET');
            page.route('/api/token-expiry', { body: { state: 'none' } });
            """ + OPEN + """
            report({ asked: page.fetches('/api/token-expiry').map(f => f.url), jira: line('jiraExpiry'),
                github: line('ghTokenExpiry'), jiraState: page.el('jiraState').textContent,
                ghState: page.el('ghTokenState').textContent,
                notes: !page.el('jiraTokenRemoved').classList.contains('hidden')
                    && !page.el('ghTokenRemoved').classList.contains('hidden') });
            """);

        assertThat(out.get("asked").toString()).isEqualTo("[\"/api/token-expiry?service=teamcity\"]");
        assertThat(out.at("/jira/hidden").asBoolean()).isTrue();
        assertThat(out.at("/github/hidden").asBoolean()).isTrue();
        assertThat(out.get("jiraState").asText()).isEqualTo("not saved");
        assertThat(out.get("ghState").asText()).isEqualTo("not saved");
        assertThat(out.get("notes").asBoolean()).as("the options wait for the tokens").isTrue();
    }

    /** A new JIRA token gets its own answer; a token that goes with its option loses its line. */
    @Test
    void aSavedTokenIsAskedAboutAndAnEndedOneLosesItsLine() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            let jiraLeft = 5;
            page.route('/api/token-expiry?service=jira', () => ({ body: { state: 'expires',
                expiresAt: page.now() + jiraLeft * DAY } }));
            page.route('/api/token-expiry', { body: { state: 'never' } });
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'POST');
            """ + OPEN + """
            const before = line('jiraExpiry').text;
            jiraLeft = 90;
            await page.el('jiraTokenBtn').onclick();
            page.el('settingsJiraToken').value = 'jira-pat-new';
            await page.el('settingsJiraSave').onclick();
            await page.settle();
            const after = line('jiraExpiry').text;
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { visa: false, jiraStored: false }) },
                'POST');
            page.el('standingVisaToggle').checked = false;
            await page.el('standingVisaToggle').onchange();
            await page.settle();
            report({ before, after, off: line('jiraExpiry') });
            """);

        assertThat(out.get("before").asText()).isEqualTo(", expires in 5 days: replace it before then");
        assertThat(out.get("after").asText()).isEqualTo(", expires in 90 days");
        assertThat(out.at("/off/hidden").asBoolean()).isTrue();
    }

    /** A new GitHub token gets its own answer, so the amber line of the old one goes. */
    @Test
    void aSavedGithubTokenIsAskedAbout() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            let githubLeft = 5;
            page.route('/api/token-expiry?service=github', () => ({ body: { state: 'expires',
                expiresAt: page.now() + githubLeft * DAY } }));
            page.route('/api/token-expiry', { body: { state: 'never' } });
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { style: true }) }, 'POST');
            """ + OPEN + """
            const before = line('ghTokenExpiry').text;
            githubLeft = 90;
            page.el('settingsGhToken').value = 'github_pat_new';
            page.el('styleFixToggle').checked = true;
            await page.el('styleFixToggle').onchange();
            await page.settle();
            report({ before, after: line('ghTokenExpiry').text });
            """);

        assertThat(out.get("before").asText()).isEqualTo(", expires in 5 days: replace it before then");
        assertThat(out.get("after").asText()).isEqualTo(", expires in 90 days");
    }

    /** The answer about the JIRA token comes after Visa my runs in JIRA went off, and the token with it. */
    @Test
    void aLateAnswerAboutATokenThatWentShowsNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            let answerJira;
            page.route('/api/token-expiry?service=jira', () => new Promise(r => { answerJira = r; }));
            page.route('/api/token-expiry', { body: { state: 'never' } });
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { visa: false, jiraStored: false }) },
                'POST');
            """ + OPEN + """
            page.el('standingVisaToggle').checked = false;
            await page.el('standingVisaToggle').onchange();
            await page.settle();
            answerJira({ body: { state: 'expires', expiresAt: page.now() + 5 * DAY } });
            await page.settle();
            report({ jira: line('jiraExpiry'), asked: page.fetches('/api/token-expiry?service=jira').length });
            """);

        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.at("/jira/hidden").asBoolean()).isTrue();
        assertThat(out.at("/jira/text").asText()).isEmpty();
    }
}
