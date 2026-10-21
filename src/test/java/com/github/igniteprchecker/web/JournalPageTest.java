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
 * alice switched her options from her laptop, and someone at a browser she forgot to log out of linked his GitHub
 * account to her ci2 account. ⚙ showed the options as they stood and the GitHub login, and nothing said who changed
 * what, or when. ⚙ now ends with her journal, the newest first, and the line of her GitHub account says when and from
 * which browser it was linked.
 */
class JournalPageTest {
    private static final String ENTRIES = """
        const MIN = 60000;
        const at = Date.parse('2026-10-19T14:02:00Z');
        const ENTRIES = [
            { at, text: 'Linked GitHub account @mallory-gh and switched on PR commands.',
                from: 'from Chrome on Windows' },
            { at: at - MIN, text: 'Posted a visa to IGNITE-28867 for PR #13335, RunAll 9389046.',
                from: 'by the checker', linkText: 'IGNITE-28867',
                url: 'https://issues.example/browse/IGNITE-28867?focusedCommentId=1' },
            { at: at - 2 * MIN, text: 'Switched on Visa my runs in JIRA with a new JIRA token.',
                from: 'from this browser' }];
        """;

    private static final String OPEN = """
        await page.load('?pr=13575');
        await page.el('settingsBtn').onclick();
        await page.settle();
        """;

    @Test
    void settingsEndWithTheJournalTheNewestFirst() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + ENTRIES + """
            page.route('/api/journal', { body: { entries: ENTRIES, full: false } });
            """ + OPEN + """
            report({ state: page.el('journalState').textContent, text: page.el('journalList').textContent,
                html: page.el('journalList').innerHTML, more: page.el('journalMore').classList.contains('hidden') });
            """);

        assertThat(out.get("state").asText()).isEqualTo("3 entries");
        assertThat(out.get("text").asText()).isEqualTo(
            "19 Oct 14:02 — Linked GitHub account @mallory-gh and switched on PR commands. from Chrome on Windows"
                + "19 Oct 14:01 — Posted a visa to IGNITE-28867 for PR #13335, RunAll 9389046. by the checker"
                + "19 Oct 14:00 — Switched on Visa my runs in JIRA with a new JIRA token. from this browser");
        assertThat(out.get("html").asText()).contains("<a href=\"https://issues.example/browse/IGNITE-28867?"
            + "focusedCommentId=1\" target=\"_self\" rel=\"noopener\" title=\"Opens the visa in JIRA.\">"
            + "IGNITE-28867</a> for PR #13335");
        assertThat(out.get("more").asBoolean()).as("no more entries to show").isTrue();
    }

    /**
     * An entry of Post to PR links the verdict comment on GitHub, and its tooltip says so: it said that it opens the
     * visa in JIRA.
     */
    @Test
    void theLinkOfAVerdictCommentSaysItOpensGithub() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/journal', { body: { entries: [{ at: page.now(), text: 'Posted the verdict of RunAll 9001'
                + ' to PR #13575 with Post to PR on the PR page.', from: 'from this browser', linkText: 'PR #13575',
                url: 'https://github.com/apache/ignite/pull/13575#issuecomment-4100' }], full: false } });
            """ + OPEN + """
            report({ html: page.el('journalList').innerHTML });
            """);

        assertThat(out.get("html").asText()).contains("<a href=\"https://github.com/apache/ignite/pull/13575#"
            + "issuecomment-4100\" target=\"_self\" rel=\"noopener\" title=\"Opens the comment on GitHub.\">PR"
            + " #13575</a> with Post to PR").doesNotContain("JIRA");
    }

    @Test
    void tenEntriesShowUntilAliceAsksForAll() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const many = Array.from({ length: 12 }, (_, i) => ({ at: page.now() - i * 60000,
                text: 'Switched on PR commands ' + i + '.', from: 'from this browser' }));
            page.route('/api/journal', { body: { entries: many, full: false } });
            """ + OPEN + """
            const shown = page.el('journalList').innerHTML.split('<li>').length - 1;
            const button = page.el('journalMore').textContent;
            const closed = page.el('journalMore').getAttribute('aria-expanded');
            page.el('journalMore').onclick();
            const all = page.el('journalList').innerHTML.split('<li>').length - 1;
            report({ shown, button, closed, all, after: page.el('journalMore').textContent,
                open: page.el('journalMore').getAttribute('aria-expanded'), state: page.el('journalState').textContent });
            """);

        assertThat(out.get("shown").asInt()).isEqualTo(10);
        assertThat(out.get("button").asText()).isEqualTo("Show all 12");
        assertThat(out.get("closed").asText()).as("a screen reader hears that the list is folded").isEqualTo("false");
        assertThat(out.get("all").asInt()).isEqualTo(12);
        assertThat(out.get("after").asText()).isEqualTo("Show fewer");
        assertThat(out.get("open").asText()).isEqualTo("true");
        assertThat(out.get("state").asText()).isEqualTo("12 entries");
        assertThat(Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8))
            .contains("aria-controls=\"journalList\"");
    }

    /**
     * The journal keeps 100 entries of each sort: 150 entries short of each limit are all there is, and the count
     * does not call them the last ones.
     */
    @Test
    void aLongJournalShortOfItsLimitsSaysHowManyEntries() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const many = Array.from({ length: 150 }, (_, i) => ({ at: page.now() - i * 60000, text: 'Saved a new JIRA'
                + ' token.', from: 'from this browser' }));
            page.route('/api/journal', { body: { entries: many, full: false } });
            """ + OPEN + """
            report({ state: page.el('journalState').textContent });
            """);

        assertThat(out.get("state").asText()).isEqualTo("150 entries");
    }

    /**
     * The body is a column as tall as the window, and the PR page takes what ⚙ leaves. With the journal, ⚙ grew
     * taller than a laptop's window and left the PR page no room at all. ⚙ now takes at most 60% of the window and
     * scrolls inside.
     */
    @Test
    void settingsLeaveRoomForThePrPage() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);
        Matcher rule = Pattern.compile("#settingsPanel \\{([^}]*)}").matcher(page);

        assertThat(rule.find()).isTrue();
        assertThat(rule.group(1)).contains("max-height: 60vh").contains("overflow-y: auto");
    }

    /**
     * The note over the journal said that a browser "is named as it names itself", and the reader could not tell
     * where that name comes from. The note says it comes from the browser's User-Agent.
     */
    @Test
    void theNoteSaysWhereTheNameOfABrowserComesFrom() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/index.html"), UTF_8);
        Matcher note = Pattern.compile("<section id=\"groupJournal\"[^>]*>.*?<p class=\"muted\">(.*?)</p>",
            Pattern.DOTALL).matcher(page);

        assertThat(note.find()).isTrue();
        assertThat(note.group(1).replaceAll("\\s+", " ")).isEqualTo("The journal lists each change of your settings"
            + " and the browser it came from. It also lists each visa the checker posted or edited with your JIRA"
            + " token. The browser name comes from the browser's User-Agent.");
    }

    /** Older entries are gone past the limit: the count says these are the last ones. */
    @Test
    void aFullJournalSaysTheseAreTheLastEntries() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const full = Array.from({ length: 100 }, (_, i) => ({ at: page.now() - i * 60000, text: 'Saved a new JIRA'
                + ' token.', from: 'from this browser' }));
            page.route('/api/journal', { body: { entries: full, full: true } });
            """ + OPEN + """
            report({ state: page.el('journalState').textContent });
            """);

        assertThat(out.get("state").asText()).isEqualTo("the last 100 entries");
    }

    /** The label of a browser comes from its User-Agent, which anyone can write: it is text, not markup. */
    @Test
    void aBrowserLabelIsShownAsText() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/journal', { body: { entries: [{ at: page.now(), text: 'Switched off PR commands.',
                from: 'from <img src=x onerror=alert(1)>' }], full: false } });
            """ + OPEN + """
            report({ html: page.el('journalList').innerHTML });
            """);

        assertThat(out.get("html").asText()).doesNotContain("<img").contains("&lt;img");
    }

    /** A switch in ⚙ is in the journal at once: the page asks for it again. */
    @Test
    void aSwitchAsksForTheJournalAgain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/journal', { body: { entries: [], full: false } });
            page.route('/api/auto-visa-all', { body: { rerun: true } }, 'POST');
            """ + OPEN + """
            const before = page.fetches('/api/journal').length;
            page.el('autoRerunToggle').checked = true;
            page.el('autoRerunToggle').onchange();
            await page.settle();
            report({ before, after: page.fetches('/api/journal').length, state: page.el('journalState').textContent });
            """);

        assertThat(out.get("before").asInt()).isEqualTo(1);
        assertThat(out.get("after").asInt()).isEqualTo(2);
        assertThat(out.get("state").asText()).isEqualTo("nothing yet");
    }

    @Test
    void aJournalThatDidNotLoadSaysSo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/journal', { status: 500, body: {} });
            """ + OPEN + """
            report({ message: page.el('journalMsg').textContent });
            """);

        assertThat(out.get("message").asText()).isEqualTo("Could not load your journal — close ⚙ and open it again.");
    }

    @Test
    void theGithubAccountSaysWhenAndFromWhereItWasLinked() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { commands: true, login: 'alice-gh', githubLinkable: true,
                linkedAt: Date.parse('2026-10-19T14:02:00Z'), linkedFrom: 'Firefox on Linux' } });
            """ + OPEN + """
            report({ state: page.el('ghLoginState').textContent });
            """);

        assertThat(out.get("state").asText()).isEqualTo("@alice-gh, linked 19 Oct 14:02 from Firefox on Linux");
    }

    /** A link an earlier version made has no date: the line says only what it said before. */
    @Test
    void aLinkWithoutADateSaysWhatItSaidBefore() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/auto-visa-all', { body: { commands: true, login: 'alice-gh', ghStored: true } });
            """ + OPEN + """
            report({ state: page.el('ghLoginState').textContent });
            """);

        assertThat(out.get("state").asText()).isEqualTo("@alice-gh, linked by your GitHub token for commits");
    }
}
