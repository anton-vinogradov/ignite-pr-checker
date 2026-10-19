package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The checker reads commands in the PRs of apache/ignite and of apache/ignite-python-thin-client, where a /run-all runs
 * the Python thin client suite instead of RunAll. The PR commands card of the status page names both repos and says
 * what a /run-all does in the client's. Runs the page's own script.
 */
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
                prCommandsClientRepo: 'apache/ignite-python-thin-client' } };
        page.route('/api/status', { body: status });
        const card = () => page.el('watcherCards').innerHTML.split('<div class="card"')
            .find(c => c.includes('<div class="k">PR commands</div>'));
        """;

    @Test
    void theCardNamesBothReposAndWhatARunAllDoesInTheClients() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            report({ text: card().replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '').replace(/\\u00a0/g, ' '),
                html: card() });
            """);

        assertThat(out.get("text").asText()).isEqualTo("PR commands4 /run-all handled · poll 20s ago · in apache/ignite"
            + " and apache/ignite-python-thin-client");
        assertThat(out.get("html").asText()).contains("title=\"In apache/ignite-python-thin-client, /run-all runs the"
            + " Python thin client suite instead of RunAll.\"");
    }

    @Test
    void aServerWithoutTheClientNamesOnlyItsRepo() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            delete status.watcher.prCommandsClientRepo;
            await page.load('');
            report({ text: card().replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '').replace(/\\u00a0/g, ' '),
                html: card() });
            """);

        assertThat(out.get("text").asText())
            .isEqualTo("PR commands4 /run-all handled · poll 20s ago · in apache/ignite");
        assertThat(out.get("html").asText()).doesNotContain("title=");
    }
}
