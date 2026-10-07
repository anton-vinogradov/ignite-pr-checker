package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The status page put its "service started" line at the browser's clock minus a whole-second uptime, so the error
 * about a snapshot that failed to load at startup landed under it, among the previous run's problems. The health
 * dot's tooltip listed the problems alone, so a dot red from a fresh error in the log explained itself with a yellow
 * warning. Runs the page's own functions.
 */
class StatusPageTest {
    private static final String[] FUNCTIONS = {"card", "agoShort", "logTime", "renderLog", "healthProblems",
        "healthTitle", "persistenceCard", "versionCard", "configRows"};

    @Test
    void anErrorLoggedWhileStartingIsThisRunsNotThePreviousOnes() throws Exception {
        String html = run("""
            const start = Date.now() - 3700;
            const el = {};
            renderLog(el, { signedIn: true, startedAt: start, uptimeSeconds: 3, log: { recent: [
                { t: start + 251, level: 'ERROR', logger: 'CacheStore', message: 'could not read standing-visas.json' },
                { t: start - 3600000, level: 'WARN', logger: 'TcClient', message: 'TeamCity did not answer' },
            ] } });
            out(el.innerHTML);
            """).asText();

        assertThat(html.indexOf("could not read standing-visas.json")).isNotNegative()
            .isLessThan(html.indexOf("service started"));
        assertThat(html.indexOf("service started")).isLessThan(html.indexOf("TeamCity did not answer"));
    }

    @Test
    void theDotNamesTheErrorInTheLogNextToAWarning() throws Exception {
        String title = run("""
            const d = { health: 'error', logHealth: 'error', log: { lastErrorAt: Date.now() - 5 * 60000 },
                healthProblems: [{ level: 'warn', text: 'no TeamCity token in the pool' }] };
            out(healthTitle(d, healthProblems(d)));
            """).asText();

        assertThat(title).isEqualTo("no TeamCity token in the pool; error logged 5m ago");
    }

    @Test
    void anonymousViewersLearnThatSomethingIsWrongButNotWhat() throws Exception {
        JsonNode page = run("""
            const d = { health: 'error', logHealth: 'ok', log: {},
                healthProblems: [{ level: 'error', text: null }, { level: 'warn', text: null }] };
            const problems = healthProblems(d);
            out({ problems, title: healthTitle(d, problems),
                card: persistenceCard({ enabled: true, active: true, problems: 1, lastBackup: null }) });
            """);

        assertThat(page.get("problems").toString())
            .isEqualTo("[{\"level\":\"error\",\"text\":\"2 problems — log in on the main page to see them\"}]");
        assertThat(page.get("title").asText()).isEqualTo("2 problems — log in on the main page to see them");
        assertThat(page.get("card").asText()).contains("1 problem", "log in on the main page to see it");
    }

    /** Prod ran "1.20.10-dev", a local build whose code no tag and no release matched. */
    @Test
    void theVersionNamesItsCommit() throws Exception {
        JsonNode page = run("""
            out({ dev: versionCard({ version: '1.21.0-3-g5f2c9e1-dirty', commit: '5f2c9e1d8a', dirty: true }),
                release: versionCard({ version: '1.21.1', commit: 'a7b3c4d9e0', dirty: false }),
                old: versionCard({ version: '1.20.10-dev' }) });
            """);

        assertThat(page.get("dev").asText()).contains("1.21.0-3-g5f2c9e1-dirty", "commit 5f2c9e1 + uncommitted changes",
            "v warn");
        assertThat(page.get("release").asText()).contains("1.21.1", "commit a7b3c4d<").doesNotContain("uncommitted");
        assertThat(page.get("old").asText()).contains("1.20.10-dev").doesNotContain("commit");
    }

    /** What the service ran with was nowhere to be seen; the settings name server paths and the operators. */
    @Test
    void signedInViewersSeeTheSettings() throws Exception {
        JsonNode page = run("""
            out({ signedIn: configRows({ config: { APP_PUBLIC_URL: 'https://prc.example.org', SESSION_SECRET: 'set' } }),
                anonymous: configRows({}) });
            """);

        assertThat(page.get("signedIn").asText()).contains("APP_PUBLIC_URL", "https://prc.example.org",
            "SESSION_SECRET");
        assertThat(page.get("anonymous").asText()).contains("log in on the main page to see the settings");
    }

    /** The page as a signed-in viewer sees it after its first poll: the commit beside the version, and the settings. */
    @Test
    void thePageShowsTheCommitAndTheSettings() throws Exception {
        JsonNode page = PageScript.run("status.html", """
            page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
            const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
            page.route('/api/status', { body: { version: '1.21.0-3-g5f2c9e1-dirty', commit: '5f2c9e1d8a', dirty: true,
                signedIn: true, uptimeSeconds: 60, startedAt: page.now() - 60000, health: 'ok', logHealth: 'ok',
                healthProblems: [], teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
                jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 }, app: { openPrs: 3, pooledTokens: 1 },
                log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] },
                config: { APP_PUBLIC_URL: 'https://prc.example.org', SESSION_SECRET: 'set' } } });
            await page.load('');
            report({ error: page.el('err').textContent, system: page.el('systemCards').textContent,
                config: page.el('configList').textContent });
            """);

        assertThat(page.get("error").asText()).isEmpty();
        assertThat(page.get("system").asText())
            .contains("1.21.0-3-g5f2c9e1-dirty commit 5f2c9e1 + uncommitted changes");
        assertThat(page.get("config").asText()).contains("APP_PUBLIC_URL", "https://prc.example.org",
            "SESSION_SECRET");
    }

    /** Runs {@code script} after the page's functions; whatever it passes to {@code out} comes back. */
    private static JsonNode run(String script) throws Exception {
        return PageFunctions.run("status.js", script, FUNCTIONS);
    }
}
