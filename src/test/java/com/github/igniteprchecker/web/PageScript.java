package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs a page's own script in node against the fake browser of {@code page/harness.js} and returns what
 * the scenario reported. Skips the test where node is not installed.
 */
final class PageScript {
    private static final Path HARNESS = Path.of("src/test/resources/page/harness.js");

    /**
     * A signed-in user on a page whose API answers like a healthy service; {@code verdict(overrides)}
     * builds an analysis of PR 13575 with one blocker, its run finished an hour ago.
     */
    static final String SIGNED_IN = """
        const RUN_FINISHED = Math.floor(page.now() / 1000) - 3600;
        function blocker(name, occ) {
            return { testId: '5272433775095107011', name: 'org.apache.ignite.' + name, suite: 'IgniteTests24Java8_Cache',
                suiteBuildId: 9002, suiteName: 'Cache', occurrenceId: occ, blocker: true, watch: false,
                reason: null, branchRuns: 'FFF', codeRuns: 3 };
        }
        function verdict(over) {
            return Object.assign({ prNumber: 13575, buildId: 9001, branchName: 'pull/13575/head',
                computedAt: page.now() - 30000, blockers: [blocker('ClientReconnectTest.testReconnect', 'occ-1')],
                watch: [], filtered: [], brokenSuites: [], shrunkSuites: [], suitesRan: 10, suitesReused: 2,
                interrupted: false, canceledSuites: 0, live: false, liveBuildId: 0,
                queuedAt: RUN_FINISHED - 7200, startedAt: RUN_FINISHED - 6000, finishedAt: RUN_FINISHED,
                branchWatermarkAt: 0 }, over);
        }
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120 } });
        page.route('/api/me', { body: { username: 'alice', jira: false, github: false } });
        page.route('/api/prs', { body: [
            { number: 13575, title: 'IGNITE-29049 Move MDC to JUnit', url: 'https://github.com/apache/ignite/pull/13575',
                triggeredBy: 'alice', blockers: 1, proven: true },
            { number: 13461, title: 'IGNITE-28972 Check marshalled kind', url: 'https://github.com/apache/ignite/pull/13461',
                triggeredBy: null, blockers: 0, proven: true }] });
        page.route('/api/analyze', { body: verdict() });
        page.route('/api/runs', { body: [] });
        page.route('/api/reruns', { body: [] });
        page.route('/api/delta', { body: { delta: null, history: [] } });
        page.route('/api/pending', { body: { pending: false } });
        page.route('/api/auto-visa', { body: { armed: false } });
        page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
        page.route('/api/version', { body: { updateAvailable: false } });
        page.route('/api/logout', { status: 204, body: {} });
        page.route('/api/test-details', { body: { details: 'java.lang.AssertionError: expected:<1> but was:<2>' } });
        """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private PageScript() {
    }

    /** Runs {@code scenario} (the body of an async function of {@code page} and {@code report}) on the page. */
    static JsonNode run(String pageName, String scenario) throws Exception {
        Path script = Files.createTempFile("scenario", ".js");
        Path errors = Files.createTempFile("scenario", ".err");
        try {
            Files.writeString(script, scenario);

            ProcessBuilder pb = new ProcessBuilder("node", HARNESS.toString(),
                Path.of("src/main/resources/static", pageName).toString(), script.toString())
                .redirectError(errors.toFile());
            pb.environment().put("TZ", "UTC");
            Process node;
            try {
                node = pb.start();
            }
            catch (IOException e) {
                assumeTrue(false, "node is not installed");
                throw e;
            }
            String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(node.exitValue()).as(Files.readString(errors)).isZero();

            return JSON.readTree(out);
        }
        finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(errors);
        }
    }
}
