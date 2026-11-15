package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The PR command card names each configured repository and the suite that /run-all queues there. */
class ClientPrsStatusPageTest {
    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const status = { version: '1.42.0', signedIn: false, operator: false, uptimeSeconds: 600,
            startedAt: page.now() - 600000, health: 'ok', logHealth: 'ok', healthProblems: [],
            teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
            jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 }, app: { openPrs: 3, pooledTokens: 1 },
            log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] },
            watcher: { prCommands: 4, prCommandsLastPollAt: page.now() - 20000, prCommandsRepo: 'apache/ignite',
                prCommandsClients: [
                    { key: 'python', label: 'Python', repo: 'apache/ignite-python-thin-client', suite: 'Thin client: Python' },
                    { key: 'php', label: 'PHP', repo: 'apache/ignite-php-thin-client', suite: 'Thin client: PHP' },
                    { key: 'nodejs', label: 'Node.js', repo: 'apache/ignite-nodejs-thin-client', suite: 'Thin client: Node.js' }] } };
        page.route('/api/status', { body: status });
        const card = () => page.el('watcherCards').innerHTML.split('<div class="card"')
            .find(c => c.includes('<div class="k">PR commands</div>'));
        """;

    /** Given three client projects, their status card names all repos and explains each configured suite. */
    @Test
    void theCardNamesAllReposAndTheirSuites() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            report({ text: card().replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '').replace(/\\u00a0/g, ' '),
                html: card() });
            """);

        assertThat(out.get("text").asText()).isEqualTo("PR commands4 /run-all handled · poll 20s ago · in apache/ignite"
            + ", apache/ignite-python-thin-client, apache/ignite-php-thin-client, apache/ignite-nodejs-thin-client");
        assertThat(out.get("html").asText()).contains(
            "In apache/ignite-python-thin-client, /run-all runs “Thin client: Python” instead of RunAll.",
            "In apache/ignite-php-thin-client, /run-all runs “Thin client: PHP” instead of RunAll.",
            "In apache/ignite-nodejs-thin-client, /run-all runs “Thin client: Node.js” instead of RunAll.");
    }

    /** Given no client projects, the card names Ignite alone and has no client suite tooltip. */
    @Test
    void aServerWithoutClientsNamesOnlyItsRepo() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.watcher.prCommandsClients = [];
            await page.load('');
            report({ text: card().replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '').replace(/\\u00a0/g, ' '),
                html: card() });
            """);

        assertThat(out.get("text").asText())
            .isEqualTo("PR commands4 /run-all handled · poll 20s ago · in apache/ignite");
        assertThat(out.get("html").asText()).doesNotContain("title=");
    }
}
