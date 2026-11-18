package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.igniteprchecker.Jackson2Defaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
            starCount: 1, refreshAfterSeconds: 120, clients: [
                { key: 'python', label: 'Python', repo: 'apache/ignite-python-thin-client', suite: 'Thin client: Python' },
                { key: 'php', label: 'PHP', repo: 'apache/ignite-php-thin-client', suite: 'Thin client: PHP' },
                { key: 'nodejs', label: 'Node.js', repo: 'apache/ignite-nodejs-thin-client', suite: 'Thin client: Node.js' },
                { key: 'extensions', label: 'Extensions', repo: 'apache/ignite-extensions', suite: 'Extensions: All Tests', composite: true }
            ] } });
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
        // The options in ⚙, under a prefix shorter than their path: a scenario's own route of the path wins.
        page.route('/api/auto-visa-', { body: {} });
        page.route('/api/visa', { body: { state: 'due', starter: 'alice', mine: true } });
        page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
        page.route('/api/version', { body: { updateAvailable: false } });
        page.route('/api/logout', { status: 204, body: {} });
        page.route('/api/logins', { body: { logins: [{ id: 'here', browser: 'Firefox on Linux',
            createdAt: page.now() - 86400000, earlier: false, lastSeen: page.now(), current: true }], ended: 0 } });
        page.route('/api/journal', { body: { entries: [], full: false } });
        page.route('/api/test-details', { body: { details: 'java.lang.AssertionError: expected:<1> but was:<2>' } });
        """;

    /**
     * The master board's API and its reader's hands: {@code history}, {@code test}, {@code broke} and {@code always}
     * build what the server sends of a test, {@code board(tests)} is its answer, {@code press(data)} clicks the
     * control of the board that carries that data attribute. Three tests of the Cache suite are ready: {@code tx}
     * broke 12 runs ago, {@code rebalance} fails a run in four, {@code nearReader} never passed. {@code commitsOf}
     * builds what the server sends of a cause's commits, for {@code board(tests, { causes })}. {@code suite} builds
     * what it sends of a problem suite, for {@code board(tests, { suites })}: without them it read 100 master chains
     * and found no problem run.
     */
    static final String MASTER_BOARD = """
        const copied = [];
        page.run('navigator').clipboard.writeText = async text => { copied.push(text); };
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite' } });
        page.route('/api/reruns', { body: [] });
        page.route('/api/test-details', { body: { kind: 'assertion', details: 'java.lang.AssertionError: IGNITE-627' } });
        // A history as the server sends it: its latest run finished 44 minutes ago in build 9000, each run before it an
        // hour earlier in the build before. A test that never passed names the occurrence of its latest run alone.
        const END = page.now() / 1000 - 44 * 60;
        function history(status, whole = false) {
            const builds = [...status].map((_, i) => 9001 - status.length + i);
            const failed = builds.filter((_, i) => status[i] === 'F').map(build => 'occ-' + build);
            return { status, builds, finished: builds.map(build => END - 3600 * (9000 - build)),
                occ: status.includes('P') ? failed : failed.slice(-1), whole };
        }
        // A test of the Cache suite with these master runs, oldest first; `over` is what a scenario changes.
        function test(id, name, status, over) {
            return Object.assign({ testId: String(id), name: 'org.apache.ignite.' + name,
                suite: 'IgniteTests24Java8_Cache', suiteName: 'Cache', occurrenceId: 'occ-pr-' + id,
                masterFails: status.split('F').length - 1, masterRuns: status.length, failStreak: 0, broken: false,
                prCount: 0, prs: [], history: history(status), historyCheckedAt: page.now() - 3600000,
                attention: null }, over);
        }
        // A newly broken test: the server names the pass before the failures at the end of its history and the first
        // of them, which tested `commit`.
        function broke(id, name, status, commit, over) {
            const h = history(status), runsBefore = status.replace(/F+$/, '').length;
            const run = (i, sha) => ({ buildId: h.builds[i], finishedAt: h.finished[i], commit: sha });
            return test(id, name, status, Object.assign({ attention: { lastPass: run(runsBefore - 1, null),
                firstFailure: run(runsBefore, commit), failedRuns: status.length - runsBefore,
                failsBefore: status.slice(0, runsBefore).split('F').length - 1, runsBefore } }, over));
        }
        // The commits after `base` up to `commit`, the one a cause first failed on, as the server sends them: those
        // with these titles, the newest first, which is `commit` itself; `total` when there are more than it keeps.
        function commitsOf(commit, base, titles, total = titles.length) {
            return { commit, base, total, commits: titles.map((title, i) => (
                { sha: i ? String(i).padStart(7, '0') + 'f'.repeat(33) : commit, title })) };
        }
        // A test the server calls broken: it failed each of its newest runs.
        function always(id, name, status, over) {
            return test(id, name, status, Object.assign({ broken: true,
                failStreak: status.length - status.replace(/F+$/, '').length }, over));
        }
        // A suite with these master runs, oldest first, a letter a run: P reported its tests, another letter is the
        // kind of the problem that ended the run, as KINDS names them.
        function suite(id, name, status, over) {
            const { builds, finished } = history(status);
            return Object.assign({ suite: 'IgniteTests24Java8_' + id, suiteName: name,
                history: { status, builds, finished }, prCount: 0, prs: [] }, over);
        }
        const KINDS = { C: 'compilation error', T: 'execution timeout', O: 'JVM crash / out of memory',
            X: 'non-zero exit code', D: 'failed dependency', U: 'another problem' };
        // The server's answer with these tests: the newly broken, the flaky and the always failing ones, in that order.
        function board(tests, over) {
            const count = kind => tests.filter(t => (t.attention ? 'new' : t.broken ? 'always' : 'flaky') === kind).length;
            page.route('/api/top-flaky', { body: Object.assign({ tracked: tests.length, muted: 2, prDays: 14,
                window: 100, attentionCount: count('new'), flakyCount: count('flaky'), brokenCount: count('always'),
                tests, suites: [], suiteKinds: KINDS, suiteChains: 100 }, over) });
        }
        // A control of the board, as the page finds it by its data attribute, and a click on it.
        let controls = 0;
        function control(data) {
            const el = page.el('control' + controls++);
            Object.assign(el.dataset, data);
            el.closest = () => el;
            return el;
        }
        const press = data => page.mouse('click', control(data));
        const key = t => (t.testId ?? 'suite') + '@' + t.suite;
        const tx = broke(101, 'TxTest.testCommit', 'P'.repeat(88) + 'F'.repeat(12), 'c27a40e',
            { prCount: 2, prs: [13583, 13655] });
        const rebalance = test(102, 'RebalanceTest.testRebalance', 'PPPF'.repeat(25), { prCount: 1, prs: [13655] });
        const nearReader = always(103, 'CacheNearReaderUpdateTest.testUpdate', 'F'.repeat(100),
            { prCount: 3, prs: [13583, 13655, 13660] });
        """;

    /**
     * The master board of seven causes, a test each, as {@code tests} and {@code causes}, with what the server knows
     * of their commits: one commit; three; four; 25, of which it keeps 20; none, as both runs tested the same commit;
     * two commits GitHub has not listed the commits between; and runs that name no commit.
     */
    static final String MASTER_CAUSES = MASTER_BOARD + """
        const brokeOn = (id, commit) => broke(id, 'Tx' + id + 'Test.testCommit', 'P'.repeat(88) + 'F'.repeat(12), commit);
        // The titles of n commits, the newest first: "IGNITE-29303 Change 3 (#13803)" opens those of three.
        const titles = n => Array.from({ length: n },
            (_, i) => `IGNITE-${29300 + n - i} Change ${n - i} (#${13800 + n - i})`);
        const tests = [brokeOn(1, 'c27a40e'), brokeOn(2, '4f1c9ae'), brokeOn(3, '81d3f0b'), brokeOn(4, 'f93b6d2'),
            brokeOn(5, '5d20c8f'), brokeOn(6, '77f1e23'), brokeOn(7, null)];
        const causes = [commitsOf('c27a40e', 'b1b1b1b', ['IGNITE-29314 Enforce the lock wait timeout (#13853)']),
            commitsOf('4f1c9ae', 'b2b2b2b', titles(3)), commitsOf('81d3f0b', 'b3b3b3b', titles(4)),
            commitsOf('f93b6d2', 'b4b4b4b', titles(20), 25), { commit: '5d20c8f', base: '5d20c8f', total: 0, commits: [] },
            { commit: '77f1e23', base: 'b6b6b6b' }];
        board(tests, { causes });
        """;

    private static final ObjectMapper JSON = Jackson2Defaults.mapper();

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
