package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * For the 3 minutes after a restart the status page said "sweep never" of the standing sweep and of tests added to
 * suites, as it would of a sweep that hangs or stopped, and "no cycle yet" of a warmer whose first cycle never
 * started. It now says when the first pass is due, and "never" turns amber only a minute after that. Runs the page's
 * own script.
 */
class FirstPassPageTest {
    /**
     * The service up for 10 s: the first refresh is due in 0 s, the poll in 20 s, the warm cycle in 50 s, the sweeps
     * in 2 min 50 s.
     */
    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const up = page.now() - 10000;
        const status = { version: '1.38.0', signedIn: false, operator: false, uptimeSeconds: 10, startedAt: up,
            health: 'ok', logHealth: 'ok', healthProblems: [], teamcity: { lastHour, sinceStart: 0 },
            github: { lastHour, sinceStart: 0 }, jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 },
            app: { openPrs: 3, pooledTokens: 1, firstCycleAt: up + 60000 },
            log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] },
            watcher: { tracked: 0, chains: 0, suites: 0, lastRefreshAt: 0, firstRefreshAt: up + 10000,
                standingEnrolled: 2, standingPosted: 0, standingLastSweepAt: 0, standingFirstSweepAt: up + 180000,
                prCommands: 0, prCommandsLastPollAt: 0, prCommandsFirstPollAt: up + 30000,
                suiteFix: { users: 1, acting: 0, pushed: 0, lastSweepAt: 0, firstSweepAt: up + 180000 } } };
        page.route('/api/status', { body: status });
        const html = (name, box = 'watcherCards') => page.el(box).innerHTML.split('<div class="card"')
            .find(c => c.includes(`<div class="k">${name}</div>`));
        const text = (name, box) => html(name, box).replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '')
            .replace(/\\u00a0/g, ' ');
        const warmer = () => ({ text: text('Warmer', 'warmerCards'), html: html('Warmer', 'warmerCards') });
        const cards = () => Object.fromEntries([['refresh', 'Last refresh'], ['standing', 'Standing options'],
            ['commands', 'PR commands'], ['suites', 'Tests added to suites']]
            .flatMap(([key, name]) => [[key, text(name)], [key + 'Html', html(name)]]));
        """;

    @Test
    void beforeTheFirstPassTheCardsSayWhenItIsDue() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.watcher.firstRefreshAt = page.now() + 8000;
            await page.load('');
            report(cards());
            """);

        assertThat(out.get("refresh").asText()).isEqualTo("Last refresh— first refresh in 8s");
        assertThat(out.get("standing").asText()).endsWith("· first sweep in 3m");
        assertThat(out.get("commands").asText()).isEqualTo("PR commands0 /run-all handled · first poll in 20s");
        assertThat(out.get("suites").asText()).endsWith("· first sweep in 3m");
        for (String card : new String[] {"refreshHtml", "standingHtml", "commandsHtml", "suitesHtml"})
            assertThat(out.get(card).asText()).as(card).doesNotContain("class=\"late\"", "v warn", "title=");
    }

    /** The jobs start at the time they are due; until a minute after it, a pass may still be on its way. */
    @Test
    void aFirstPassJustDueIsNotLateYet() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            await page.tick(180000);
            report(cards());
            """);

        assertThat(out.get("standing").asText()).endsWith("· first sweep due now");
        assertThat(out.get("suites").asText()).endsWith("· first sweep due now");
        assertThat(out.get("standingHtml").asText()).doesNotContain("class=\"late\"");
        assertThat(out.get("suitesHtml").asText()).doesNotContain("class=\"late\"");
    }

    @Test
    void aFirstPassAMinuteLateReadsNeverInAmber() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            await page.tick(240000);
            report(cards());
            """);

        assertThat(out.get("standing").asText()).endsWith("· sweep never");
        assertThat(out.get("commands").asText()).endsWith("· poll never");
        assertThat(out.get("refresh").asText()).isEqualTo("Last refreshnever");
        assertThat(out.get("standingHtml").asText()).containsPattern("<span class=\"late\" title=\"The first sweep was "
            + "due at [^\"]+ and has not started: the job may be stuck.\">never</span>");
        assertThat(out.get("commandsHtml").asText()).contains("<span class=\"late\" title=\"The first poll was due");
        assertThat(out.get("refreshHtml").asText()).containsPattern(" title=\"The first refresh was due at [^\"]+ and "
            + "has not started: the job may be stuck.\"><div class=\"k\">Last refresh</div><div class=\"v warn\">never");
    }

    /** Only the title says why "never" is amber, so the word takes the help cursor, as a card with a title does. */
    @Test
    void anAmberNeverShowsThatItHasATitle() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/status.html"), UTF_8);

        assertThat(css).containsPattern("(?m)^\\s*\\.card \\.v small \\.late\\[title] \\{[^}]*cursor: help;");
    }

    @Test
    void oncePassesRunTheCardsSayWhenTheLastStarted() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            Object.assign(status.watcher, { lastRefreshAt: page.now() - 5000, lastRefreshMs: 300,
                standingLastSweepAt: page.now() - 120000, standingLastSweepMs: 9200,
                prCommandsLastPollAt: page.now() - 30000 });
            status.watcher.suiteFix.lastSweepAt = page.now() - 120000;
            await page.load('');
            report(cards());
            """);

        assertThat(out.get("refresh").asText()).isEqualTo("Last refresh5s ago 300ms");
        assertThat(out.get("standing").asText()).endsWith("· sweep 2m ago (9.2s)");
        assertThat(out.get("commands").asText()).endsWith("· poll 30s ago");
        assertThat(out.get("suites").asText()).endsWith("· sweep 2m ago");
    }

    /**
     * A job the server does not run gets no time and stays grey: tests added to suites with automation off, the
     * warmer with warming off.
     */
    @Test
    void aJobThatDoesNotRunHereSaysNeverInGrey() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.watcher.suiteFix.firstSweepAt = 0;
            status.app.firstCycleAt = 0;
            await page.load('');
            await page.tick(240000);
            report(Object.assign(cards(), { warmer: warmer() }));
            """);

        assertThat(out.get("suites").asText()).endsWith("· sweep never");
        assertThat(out.get("suitesHtml").asText()).doesNotContain("class=\"late\"", "title=");
        assertThat(out.get("standingHtml").asText()).contains("class=\"late\"");
        assertThat(out.at("/warmer/text").asText()).isEqualTo("Warmeridle no cycle yet");
        assertThat(out.at("/warmer/html").asText()).doesNotContain("v warn", "title=");
    }

    /** The warm cycle is due a minute after the start: the card counts down to it, then turns amber a minute later. */
    @Test
    void theWarmerSaysWhenItsFirstCycleIsDue() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            const soon = warmer();
            await page.tick(50000);
            const due = warmer();
            await page.tick(65000);
            report({ soon, due, late: warmer() });
            """);

        assertThat(out.at("/soon/text").asText()).isEqualTo("Warmeridle first cycle in 50s");
        assertThat(out.at("/due/text").asText()).isEqualTo("Warmeridle first cycle due now");
        for (String at : new String[] {"/soon/html", "/due/html"})
            assertThat(out.at(at).asText()).as(at).doesNotContain("v warn", "title=");
        assertThat(out.at("/late/text").asText()).isEqualTo("Warmernever");
        assertThat(out.at("/late/html").asText()).containsPattern(" title=\"The first cycle was due at [^\"]+ and has "
            + "not started: the job may be stuck.\"><div class=\"k\">Warmer</div><div class=\"v warn\">never");
    }
}
