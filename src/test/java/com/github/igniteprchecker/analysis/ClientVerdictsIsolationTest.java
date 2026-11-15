package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.ClientSpec;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.ThinClients;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Client PRs with the same number keep the verdict and commits of their own TeamCity suite. */
class ClientVerdictsIsolationTest {
    private static final String TOKEN = "tc";

    private static final int PR = 69;

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String PYTHON_SHA = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final String NODE_SHA = "b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4e5";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final ThinClients registry = new ThinClients(new PythonClientProperties(null, null, null, null),
        new ThinClientProperties(null, null));

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc,
        new ChainCollector(tc, new SuiteBaseline(Jackson2Defaults.mapper(), tc)), cfg, pool, pool, pool,
        new AnalysisCache(cfg, Jackson2Defaults.mapper()), new RunDeltaStore(Jackson2Defaults.mapper()));

    private final ClientVerdicts clients = new ClientVerdicts(tc, analyzer, github, cfg);

    @BeforeEach
    void masterCounts() {
        when(tc.masterSuiteStats(TOKEN)).thenReturn(new TcClient.MasterSuiteStats(Map.of(
            registry.require("python").suite(), 100, registry.require("php").suite(), 100,
            registry.require("nodejs").suite(), 100), Map.of(), 3));
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    /**
     * Given Python and Node.js PR #69 on pull/69, reading and refreshing both keeps each suite's own result.
     * The Python run fails a new test; the Node.js run passes on another client commit.
     */
    @Test
    void pythonAndNodePrsWithTheSameNumberKeepTheirOwnVerdicts() {
        ClientSpec python = registry.require("python");
        ClientSpec node = registry.require("nodejs");
        suite(python, 9424200L, PYTHON_SHA, "alice", "FAILURE");
        suite(node, 9424300L, NODE_SHA, "bob", "SUCCESS");
        when(tc.getFailedTests(TOKEN, 9424200L)).thenReturn(List.of(new TcModel.TestOccurrence("o1", "new_test",
            "FAILURE", new TcModel.TestRef(501L), null, null)));
        when(tc.getBaseBranchHistory(TOKEN, 501L, python.suite())).thenReturn(IntStream.range(0, 100)
            .mapToObj(i -> new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null)).toList());
        when(tc.finishedRunsParameter(TOKEN, python.suite(), "pull/69", TcModel.OVERRIDDEN_BRANCH))
            .thenReturn(Map.of(9424200L, PYTHON_SHA));
        when(tc.branchRuns(TOKEN, "pull/69", 501L, python.suite())).thenReturn(List.of(new TcModel.TestOccurrence(
            "b1", null, "FAILURE", null, new TcModel.BuildRef(9424200L, "pull/69", "finished", "FAILURE",
                python.suite(), null, revisions(PYTHON_SHA), null), null)));

        ClientVerdicts.Verdict pythonVerdict = clients.verdict(TOKEN, python, PR).orElseThrow();
        ClientVerdicts.Verdict nodeVerdict = clients.verdict(TOKEN, node, PR).orElseThrow();
        clients.fresh(TOKEN, python, PR);

        assertThat(pythonVerdict.result().blockers()).extracting(TestVerdict::name).containsExactly("new_test");
        assertThat(nodeVerdict.result().blockers()).isEmpty();
        assertThat(clients.kept(python, PR).orElseThrow().result().buildId()).isEqualTo(9424200L);
        assertThat(clients.kept(node, PR).orElseThrow().result().buildId()).isEqualTo(9424300L);
        assertThat(clients.glance(python, PR).blockers()).isEqualTo(1);
        assertThat(clients.glance(node, PR).blockers()).isZero();
        assertThat(clients.starter(python, PR)).isEqualTo("alice");
        assertThat(clients.starter(node, PR)).isEqualTo("bob");
        assertThat(clients.head(python, PR)).isEqualTo(PYTHON_SHA);
        assertThat(clients.head(node, PR)).isEqualTo(NODE_SHA);
        verify(tc, times(1)).finishedRuns(TOKEN, node.suite(), "pull/69", TcModel.OVERRIDDEN_BRANCH);
    }

    /** Given PR #69 in every client repository, one warm cycle stores all three suites' actual verdicts. */
    @Test
    void oneWarmCycleKeepsEveryClientsPrOfTheSameNumber() {
        suite(registry.require("python"), 9424200L, PYTHON_SHA, "alice", "SUCCESS");
        suite(registry.require("php"), 9424400L, NODE_SHA, "carol", "SUCCESS");
        suite(registry.require("nodejs"), 9424300L, NODE_SHA, "bob", "SUCCESS");
        ExecutorService warms = Executors.newSingleThreadExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Warmer warmer = new Warmer(analyzer, github, new WarmProperties(true, 1, 10, 60), warms, pool, worker,
                clients, registry);

            warmer.offerToken("alice", TOKEN);

            await().atMost(Duration.ofSeconds(10)).until(() -> warmer.cyclesCompleted() == 1);
            assertThat(registry.all().stream().filter(client -> !client.composite()).toList())
                .extracting(client -> clients.kept(client, PR).orElseThrow().result().buildId())
                .containsExactly(9424200L, 9424400L, 9424300L);
            assertThat(warmer.lastWarmed()).isEqualTo(3);
            assertThat(warmer.cycleTotal()).isEqualTo(3);
        }
        finally {
            warms.shutdownNow();
            worker.shutdownNow();
        }
    }

    /** Given a PHP run with rooted revisions, warming its PR uses the PHP suite and pull/69/head. */
    @Test
    void phpUsesItsOwnSuiteBranchAndClientRoot() {
        ClientSpec php = registry.require("php");
        suite(php, 9424400L, NODE_SHA, "carol", "SUCCESS");

        assertThat(clients.warm(TOKEN, php, PR)).isTrue();

        ClientVerdicts.Verdict verdict = clients.kept(php, PR).orElseThrow();
        assertThat(verdict.result().branchName()).isEqualTo("pull/69/head");
        assertThat(verdict.run().suiteName()).isEqualTo("Thin client: PHP");
        assertThat(verdict.run().clientSha()).isEqualTo(NODE_SHA);
        assertThat(verdict.run().igniteSha()).isEqualTo(IGNITE);
        verify(tc).finishedRuns(TOKEN, "IgniteTests24Java8_ThinClientPhp", "pull/69/head", TcModel.OVERRIDDEN_BRANCH);
    }

    /** Supplies GitHub heads and a successful or failed finished build using the selected client's VCS roots. */
    private void suite(ClientSpec client, long buildId, String sha, String starter, String status) {
        when(github.openPrs(client.repo())).thenReturn(List.of(new PrSummary(PR, "IGNITE-29117 Fix",
            "https://github.com/" + client.repo() + "/pull/" + PR, null, null, null, sha, null)));
        when(tc.finishedRuns(TOKEN, client.suite(), client.branch(PR), TcModel.OVERRIDDEN_BRANCH))
            .thenReturn(List.of(new TcModel.BranchRun(buildId, null,
                new TcModel.Triggered("user", new TcModel.User(starter)), new TcModel.RootedRevisions(List.of(
                    new TcModel.RootedRevision(IGNITE, new TcModel.RootInstance("335", client.igniteVcsRoot())),
                    new TcModel.RootedRevision(sha, new TcModel.RootInstance("314", client.clientVcsRoot())))), null)));
        when(tc.suiteRun(TOKEN, buildId)).thenReturn(new TcModel.Build(buildId, status, "finished", client.branch(PR),
            client.suite(), null, null, null, null, null, null,
            new TcModel.BuildType(client.suite(), client.suiteLabel()), null, null, null, null, revisions(sha),
            new TcModel.TestOccurrences(100, null)));
    }

    /** The two commits of a client's run, with Ignite first as TeamCity returns them. */
    private static TcModel.Revisions revisions(String sha) {
        return new TcModel.Revisions(List.of(new TcModel.Revision(IGNITE), new TcModel.Revision(sha)));
    }
}
