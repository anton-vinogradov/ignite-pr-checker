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
 * The PR list explains each badge and distinguishes run ownership from PR authorship: a row names who started the PR's
 * latest run where its tab does not say it, "my runs" keeps the PRs whose latest run the viewer started, and a PR whose
 * run is under way says how far the run is.
 */
class PrListPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /**
     * The filter, the head and the visa read the title from data-title and the link's title, which keep the key. The
     * title of PR 13640 starts with a space, and its key stood on the second line.
     */
    @Test
    void theTicketKeyStandsByTheNumberAndTheTitleStartsWithItsOwnWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/prs', { body: [
                { number: 13575, title: 'IGNITE-29049: Move MDC to JUnit', url: 'https://github.com/apache/ignite/pull/13575',
                    triggeredBy: 'alice', blockers: 1, proven: true },
                { number: 13600, title: 'Fix a typo in the README', url: 'https://github.com/apache/ignite/pull/13600',
                    triggeredBy: null, blockers: 0, proven: true, standing: 'CLEAN' },
                { number: 13601, title: 'IGNITE-29100', url: 'https://github.com/apache/ignite/pull/13601',
                    triggeredBy: null, blockers: null },
                { number: 13602, title: 'IGNITE-29101, IGNITE-29102: Two tickets', url: 'https://github.com/apache/ignite/pull/13602',
                    triggeredBy: null, blockers: 0, proven: true },
                { number: 13640, title: ' IGNITE-29079 Implemented relaying', url: 'https://github.com/apache/ignite/pull/13640',
                    triggeredBy: null, blockers: null }] });
            await page.load('');
            report({ html: page.el('prList').innerHTML });
            """);

        String html = out.get("html").asText();
        assertThat(html)
            .contains("<span class=\"pr-num\">#13575</span> <span class=\"pr-key\">IGNITE-29049</span>"
                + "<span class=\"pr-badge bad\" title=\"1 blocker\" role=\"img\" aria-label=\"1 blocker\">1</span>"
                + "<span class=\"pr-by me\" "
                + "title=\"You started its latest RunAll. The PR may be someone else&#39;s.\">by me</span>")
            .contains("<span class=\"pr-ttl\">Move MDC to JUnit</span>")
            .contains("data-title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("title=\"IGNITE-29049: Move MDC to JUnit\"")
            .contains("<span class=\"pr-num\">#13600</span><span class=\"pr-badge ok\"")
            .contains("<span class=\"pr-ttl\">Fix a typo in the README</span>")
            .contains("<span class=\"pr-num\">#13601</span> <span class=\"pr-key\">IGNITE-29100</span></span>")
            .contains("<span class=\"pr-ttl\">IGNITE-29100</span>")
            .contains("<span class=\"pr-num\">#13602</span> <span class=\"pr-key\">IGNITE-29101</span>")
            .contains("<span class=\"pr-ttl\">IGNITE-29102: Two tickets</span>")
            .contains("<span class=\"pr-num\">#13640</span> <span class=\"pr-key\">IGNITE-29079</span>")
            .contains("<span class=\"pr-ttl\">Implemented relaying</span>")
            .contains("data-title=\" IGNITE-29079 Implemented relaying\"")
            .doesNotContain("My?", "blocker(s)", "<span class=\"pr-ttl\">, IGNITE", "data-triggeredby");
    }

    /** Given each verdict standing, when a badge is drawn, its tooltip explains that standing and the blocker count. */
    @Test
    void theRowsExplainEveryBadge() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('');
            const badges = page.run('STANDING_BADGES');
            report({ watch: badges.WATCH[2], unproven: badges.UNPROVEN[2], checking: badges.CHECKING[2],
                clean: page.run('CLEAN_BADGE_TITLE'),
                one: page.run('blockerBadge')(1, false, null), many: page.run('blockerBadge')(33, false, null),
                old: page.run('blockerBadge')(0, false, 'OLD_CODE'), proven: page.run('blockerBadge')(0, true, null) });
            """);

        assertThat(out.get("watch").asText()).isEqualTo("No blockers yet, but some tests started failing on the code "
            + "the run tested. A re-run shows whether they are blockers.");
        assertThat(out.get("unproven").asText()).isEqualTo("No blockers found, but the run can't prove the PR clean: "
            + "it was interrupted, suites broke or ran fewer tests than on master, failed tests could not be "
            + "checked, suites re-ran on other commits, or a newer run is still going.");
        assertThat(out.get("one").asText()).startsWith("<span class=\"pr-badge bad\" title=\"1 blocker\"")
            .endsWith(">1</span>");
        assertThat(out.get("many").asText()).startsWith("<span class=\"pr-badge bad\" title=\"33 blockers\"")
            .endsWith(">33</span>");
        assertThat(out.get("old").asText())
            .startsWith("<span class=\"pr-badge unproven\" title=\"No blockers found, but commits were pushed after this "
                + "run.\"").endsWith(">?</span>");
        assertThat(out.get("proven").asText())
            .startsWith("<span class=\"pr-badge ok\" title=\"A clean run of the PR&#39;s current code.\"")
            .endsWith(">✓</span>");
    }

    /**
     * Given the list styles, unproven badges stay amber, and who started the run is plain text at the right end of the
     * row's first line, which stays one line. "by me" is all that marks a row whose run the viewer started: the row
     * has no stripe at its left edge that would say it a second time.
     */
    @Test
    void theUnprovenBadgeIsAmberAndTheStarterPlainTextAtTheEndOfTheLine() throws IOException {
        String css = Files.readString(PAGE, UTF_8);

        assertThat(rule(css, ".pr-badge.unproven")).contains("color: var(--warn)", "border: 1px solid var(--warn)");
        assertThat(rule(css, ".pr-badge.bad")).contains("background: var(--bad)", "color: var(--on-bad)");
        assertThat(rule(css, ".pr-line")).contains("display: flex", "white-space: nowrap", "overflow: hidden");
        assertThat(rule(css, ".pr-by")).contains("margin-left: auto", "color: var(--muted)", "text-overflow: ellipsis")
            .doesNotContain("background", "border");
        assertThat(rule(css, ".pr-short.why")).contains("color: var(--warn)");
        assertThat(rule(css, ".pr-key")).contains("color: var(--muted)", "font-family: ui-monospace, monospace");
        assertThat(css).doesNotContain(".my-tag", "li.mine");
    }

    /**
     * alice started the latest run of 13575 and of 13461, bob that of 13600, and nobody is known to have started one
     * of 13601. On All and Involved a row says "by me" where alice did, and on Mine "by bob" where she did not: the
     * rest is what the tab leads her to expect.
     */
    @Test
    void aRowNamesWhoStartedTheRunWhereTheTabDoesNotSayIt() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STARTERS + """
            await page.load('');
            const mine = starters();
            page.run('pickPrTab')('all');
            const all = starters();
            page.run('pickPrTab')('involved');
            report({ all, mine, involved: starters() });
            """);

        String byMe = "<span class=\"pr-by me\" title=\"You started its latest RunAll. The PR may be someone else&#39;s.\">"
            + "by me</span>";
        assertThat(out.get("all").get("13575").asText()).isEqualTo(byMe);
        assertThat(out.get("all").get("13600").asText()).isEmpty();
        assertThat(out.get("all").get("13601").asText()).isEmpty();
        assertThat(out.get("mine").get("13575").asText()).isEmpty();
        assertThat(out.get("mine").get("13600").asText())
            .isEqualTo("<span class=\"pr-by\" title=\"bob started its latest RunAll.\">by bob</span>");
        assertThat(out.get("involved").get("13461").asText()).isEqualTo(byMe);
        assertThat(out.get("involved").get("13601").asText()).isEmpty();
    }

    /**
     * "my runs" narrows the tab to the PRs whose latest run alice started, together with the filter. When the box
     * leaves no PR on the tab, the list says so. When the box keeps a PR and the filter matches none, the list says
     * what it says of a filter that matches nothing.
     */
    @Test
    void myRunsKeepsThePrsWhoseLatestRunTheViewerStarted() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STARTERS + """
            const box = page.el('myRuns');
            const tick = on => { box.checked = on; for (const fn of box.listeners.change) fn({ target: box }); };
            const shown = () => ({ prs: Object.keys(starters()), note: page.el('prTabNote').textContent,
                noteShown: !page.el('prTabNote').classList.contains('hidden'),
                none: page.el('prNone').classList.contains('hidden') ? '' : page.text('prNone') });
            await page.load('');
            tick(true);
            const mine = shown();
            page.run('pickPrTab')('all');
            const all = shown();
            page.el('prFilter').value = 'no such title';
            page.run('applyPrFilter')();
            const filtered = shown();
            page.el('prFilter').value = '';
            page.run('pickPrTab')('involved');
            const involved = shown();
            page.run('tabPrs').involved = [page.run('tabPrs').involved[1]];
            page.run('renderPrList')();
            const nothing = shown();
            page.el('prFilter').value = 'no such title';
            page.run('applyPrFilter')();
            const both = shown();
            page.el('prFilter').value = '';
            tick(false);
            report({ all, filtered, mine, involved, nothing, both, off: shown() });
            """);

        assertThat(out.get("all").get("prs").toString()).isEqualTo("[\"13575\"]");
        assertThat(out.get("all").get("none").asText()).isEmpty();
        assertThat(out.get("filtered").get("prs").toString()).isEqualTo("[\"13575\"]");
        assertThat(out.get("filtered").get("none").asText()).as("the box keeps a PR: the filter is what matches nothing")
            .isEqualTo("No open PR matches.");
        assertThat(out.get("filtered").get("noteShown").asBoolean()).isFalse();
        assertThat(out.get("mine").get("prs").toString()).isEqualTo("[\"13575\"]");
        assertThat(out.get("involved").get("prs").toString()).isEqualTo("[\"13461\"]");
        assertThat(out.get("nothing").get("prs").toString()).isEqualTo("[]");
        assertThat(out.get("nothing").get("note").asText()).isEqualTo("No runs by you here.");
        assertThat(out.get("nothing").get("noteShown").asBoolean()).isTrue();
        assertThat(out.get("both").get("note").asText()).as("the box alone leaves the tab empty, whatever the filter")
            .isEqualTo("No runs by you here.");
        assertThat(out.get("both").get("none").asText()).isEmpty();
        assertThat(out.get("off").get("prs").toString()).isEqualTo("[\"13601\"]");
        assertThat(out.get("off").get("noteShown").asBoolean()).isFalse();
    }

    /**
     * 13575's RunAll runs with 55 minutes left, 13600's waits in the queue, auto re-runs settle 13601's run, and
     * commits were pushed after 13461's: each says it next to its badge in a few signs, and in full in the tooltip.
     * A "?" is left out while a run goes: the step says already that the PR is not proven clean.
     * A PR with a clean run of its head and no run under way says nothing more than its badge.
     */
    @Test
    void aRowSaysHowFarARunUnderWayIsAndWhyAQuestionMarkStands() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const pr = (number, more) => Object.assign({ number, title: 'IGNITE-1 x', url: 'u', triggeredBy: null,
                blockers: 0, proven: true, standing: 'CLEAN' }, more);
            page.route('/api/prs', { body: [
                pr(13575, { blockers: 1, proven: false, standing: 'BLOCKERS', run: { step: 2, leftSec: 3300 } }),
                pr(13600, { blockers: null, proven: null, standing: null, run: { step: 1, leftSec: 1500 } }),
                pr(13601, { proven: false, standing: 'OLD_CODE', run: { step: 3 } }),
                pr(13461, { proven: false, standing: 'OLD_CODE' }),
                pr(13462, {})] });
            await page.load('');
            const html = page.el('prList').innerHTML;
            const short = n => /<\\/span>(<span class="pr-short.*?)?<\\/span>\\s*<span class="pr-ttl">/
                .exec(html.slice(html.indexOf('#' + n)))[1] || '';
            // The badges of the two PRs whose "?" would stand for a run of older code.
            const badge = n => /class="pr-badge [^"]*"[^>]*>([^<]*)</.exec(
                html.slice(html.indexOf('#' + n), html.indexOf('pr-ttl', html.indexOf('#' + n))))?.[1] || '';
            report({ running: short(13575), queued: short(13600), settling: short(13601), old: short(13461),
                clean: short(13462), badges: [13575, 13601, 13461].map(badge) });
            """);

        assertThat(out.get("running").asText()).isEqualTo(runShort("Step 2 of 4: RunAll · ~55m left", "2/4 · ~55m"));
        assertThat(out.get("queued").asText()).isEqualTo(runShort("Step 1 of 4: Queue · starts ~25m", "1/4 · ~25m"));
        assertThat(out.get("settling").asText()).as("no estimate, and no word of old code next to a run under way")
            .isEqualTo(runShort("Step 3 of 4: Auto re-run", "3/4"));
        assertThat(out.get("old").asText()).isEqualTo("<span class=\"pr-short why\">outdated</span>");
        assertThat(out.get("clean").asText()).isEmpty();
        assertThat(out.get("badges")).as("a run under way stands in place of the ?; a count of blockers stays")
            .extracting(JsonNode::asText).containsExactly("1", "", "?");
    }

    /**
     * The list is as the server told it when the page opened, and "2/4 · ~55m" would stand for hours. While the page
     * is visible it asks every minute for the steps of the runs under way, which the server has in memory, and draws
     * anew the first line of a row that says another thing by then. The list is not asked for again and not redrawn.
     * alice's page is on Mine: the RunAll of her 13575 starts, goes on, and ends.
     */
    @Test
    void theRowsFollowTheirRunsEveryMinuteWithoutAskingForTheList() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + STARTERS + """
            // The rows as a browser holds them: each has its first line, which the page draws anew in place.
            const lines = {};
            await page.load('');
            page.el('prList').children = [...page.el('prList').innerHTML.matchAll(
                /<li data-num="(\\d+)"[\\s\\S]*?<span class="pr-line">([\\s\\S]*?)<\\/span>\\s*<span class="pr-ttl">/g)]
                .map(m => {
                    const li = page.el('li-' + m[1]), line = lines[m[1]] = page.el('line-' + m[1]);
                    li.dataset.num = m[1];
                    line.innerHTML = m[2];
                    line.htmlWrites = 0;
                    li.querySelector = sel => (sel === '.pr-line' ? line : null);
                    return li;
                });
            const before = lines[13575].innerHTML;
            const listDrawn = page.el('prList').htmlWrites;
            const drawn = () => Object.fromEntries(Object.entries(lines).map(([pr, line]) => [pr, line.htmlWrites]));
            page.route('/api/run-steps', { body: { 13575: { step: 2, leftSec: 3300 }, 13999: { step: 1 } } });
            await page.tick(60000);
            const started = { line: lines[13575].innerHTML, drawn: drawn() };
            page.route('/api/run-steps', { body: { 13575: { step: 2, leftSec: 3290 } } });
            await page.tick(60000);
            const same = drawn();
            page.route('/api/run-steps', { body: {} });
            await page.tick(60000);
            const ended = { line: lines[13575].innerHTML, drawn: drawn() };
            const asked = page.fetches('/api/run-steps').length;
            await page.setHidden(true);
            await page.tick(180000);
            report({ before, started, same, ended, asked, hidden: page.fetches('/api/run-steps').length,
                lists: page.fetches('/api/prs').length, searches: page.fetches('/api/your-prs').length,
                listDrawn: page.el('prList').htmlWrites - listDrawn });
            """);

        assertThat(out.get("before").asText()).contains("#13575").doesNotContain("pr-short");
        assertThat(out.at("/started/line").asText()).isEqualTo(out.get("before").asText()
            + runShort("Step 2 of 4: RunAll · ~55m left", "2/4 · ~55m"));
        assertThat(out.at("/started/drawn").toString()).as("only the row whose run started")
            .isEqualTo("{\"13575\":1,\"13600\":0}");
        assertThat(out.get("same").toString()).as("ten seconds less still read ~55m: nothing is drawn")
            .isEqualTo("{\"13575\":1,\"13600\":0}");
        assertThat(out.at("/ended/line").asText()).isEqualTo(out.get("before").asText());
        assertThat(out.at("/ended/drawn").toString()).isEqualTo("{\"13575\":2,\"13600\":0}");
        assertThat(out.get("asked").asInt()).isEqualTo(3);
        assertThat(out.get("hidden").asInt()).as("a hidden page does not ask").isEqualTo(3);
        assertThat(out.get("lists").asInt()).as("the list is loaded once, with the page").isEqualTo(1);
        assertThat(out.get("searches").asInt()).as("GitHub is not searched again").isEqualTo(1);
        assertThat(out.get("listDrawn").asInt()).as("the list is not drawn anew").isZero();
    }

    /** The checker knows the steps of Ignite's runs only: the page of a client project does not ask for them. */
    @Test
    void aClientProjectsListAsksForNoSteps() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/client/prs', { body: [{ number: 69, title: 'IGNITE-29117 x', url: 'u', triggeredBy: null,
                blockers: 0, proven: true, standing: 'CLEAN' }] });
            await page.load('/python');
            await page.tick(180000);
            report({ list: page.el('prList').innerHTML, asked: page.fetches('/api/run-steps').length });
            """);

        assertThat(out.get("list").asText()).contains("#69");
        assertThat(out.get("asked").asInt()).isZero();
    }

    @Test
    void theRowsAndMyRunsSpeakRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', lang: 'ru' } });
            page.route('/api/prs', { body: [
                { number: 13575, title: 'IGNITE-1 x', url: 'u', triggeredBy: 'alice', blockers: 1, proven: false,
                    standing: 'BLOCKERS', run: { step: 2, leftSec: 3300 } },
                { number: 13461, title: 'IGNITE-2 y', url: 'u', triggeredBy: 'bob', blockers: 0, proven: false,
                    standing: 'OLD_CODE' }] });
            await page.load('');
            const label = page.staticElements(e => e.attributes.class === 'my-runs')[0];
            const html = page.el('prList').innerHTML;
            page.el('myRuns').checked = true;
            for (const fn of page.el('myRuns').listeners.change) fn({ target: page.el('myRuns') });
            page.el('prFilter').value = 'no such title';
            page.run('applyPrFilter')();
            const none = page.el('prNone').classList.contains('hidden') ? null : page.text('prNone');
            page.run('tabPrs').all = [page.run('tabPrs').all[1]];
            page.run('renderPrList')();
            report({ html, label: page.text(label).trim(), title: label.getAttribute('title'), none,
                note: page.el('prTabNote').textContent });
            """);

        assertThat(out.get("html").asText())
            .contains("aria-label=\"Шаг 2 из 4: RunAll · осталось ~55\u00a0мин\"", "</i>2/4 · ~55\u00a0мин</span>")
            .contains("title=\"Последний RunAll запустил ты. PR может быть чужим.\">мой</span>")
            .contains("<span class=\"pr-short why\">устарел</span>");
        assertThat(out.get("label").asText()).isEqualTo("мои прогоны");
        assertThat(out.get("title").asText()).isEqualTo("Показывает только PR, последний прогон которых запустил ты.");
        assertThat(out.get("none").asText()).isEqualTo("Ни один открытый PR не подходит.");
        assertThat(out.get("note").asText()).isEqualTo("Здесь нет прогонов, которые запустил ты.");
    }

    /** alice's page, which opens on Mine: All lists three PRs, Mine two of hers, Involved two of other people. */
    private static final String STARTERS = """
        const row = (number, triggeredBy) => ({ number, title: 'IGNITE-1 x', url: 'u', triggeredBy, blockers: 0,
            proven: true, standing: 'CLEAN' });
        const ALL = [row(13575, 'alice'), row(13600, 'bob'), row(13601, null)];
        page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, githubLinked: true } });
        page.route('/api/prs', { body: ALL });
        page.route('/api/your-prs', { body: { login: 'alice-gh', problem: null,
            mine: [row(13575, 'alice'), row(13600, 'bob')], involved: [row(13461, 'Alice'), row(13601, null)] } });
        // What stands at the end of each row's first line, by PR.
        const starters = () => Object.fromEntries([...page.el('prList').innerHTML.matchAll(
            /pr-num">#(\\d+)<.*?<\\/span>(<span class="pr-by.*?<\\/span>)?<\\/span>\\s*<span class="pr-ttl">/g)]
            .map(m => [m[1], m[2] || '']));
        """;

    /** The state of a run under way as a row draws it: the tooltip's words are also what a screen reader reads. */
    private static String runShort(String title, String text) {
        return "<span class=\"pr-short\" title=\"" + title + "\" role=\"img\" aria-label=\"" + title + "\">"
            + "<i class=\"pr-now\" aria-hidden=\"true\"></i>" + text + "</span>";
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + " \\{([^}]*)}").matcher(css);
        assertThat(m.find()).as(selector).isTrue();
        return m.group(1);
    }
}
