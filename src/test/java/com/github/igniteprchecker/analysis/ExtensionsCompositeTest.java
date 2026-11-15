package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.ClientSpec;
import com.github.igniteprchecker.github.ClientRuns;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.ThinClients;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Extensions PRs are composite chains with an Extensions root and an Ignite Build dependency. */
class ExtensionsCompositeTest {
    private static final String TOKEN = "tc";
    private static final int PR = 370;
    private static final String EXT = "d3b836fc2208a682783efdaf1d81c65b75454983";
    private static final String IGNITE = "ec446ff45af7b3e09e6d40a3e4397b82a6ce6d5d";
    private static final String AOP = "IgniteExtensions_Tests_Aop";
    private static final String KAFKA = "IgniteExtensions_Tests_Kafka";
    private static final String DATE = "20261115T120000+0000";
    private final ThinClients projects = ThinClients.defaults();
    private final ClientSpec extensions = projects.require("extensions");
    private final TcClient tc = mock(TcClient.class);
    private final GithubClient github = mock(GithubClient.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AnalysisProperties cfg = new AnalysisProperties(null, "MainRunAll", null, null, null, null, null);
    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc,
        new ChainCollector(tc, new SuiteBaseline(Jackson2Defaults.mapper(), tc)), cfg, pool, pool, pool,
        new AnalysisCache(cfg, Jackson2Defaults.mapper()), new RunDeltaStore(Jackson2Defaults.mapper()));
    private final ClientVerdicts clients = new ClientVerdicts(tc, analyzer, github, cfg);

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    /** Given a successful composite, when listed, then only two complete, unambiguous root commits admit its verdict. */
    @ParameterizedTest
    @MethodSource("compositeRootCommits")
    void acceptsOnlyCompleteCommitsBeforeAnalyzingASuccessfulComposite(List<String> projectCommits,
        List<String> igniteCommits, boolean accepted) {
        TcModel.RootedRevisions own = rooted(extensions.clientVcsRoot(), projectCommits);
        TcModel.RootedBuild dependency = new TcModel.RootedBuild(rooted(extensions.igniteVcsRoot(), igniteCommits));
        TcModel.RootedBuild suite = new TcModel.RootedBuild(own, new TcModel.RootedBuilds(List.of(dependency)));
        TcModel.BranchRun finished = new TcModel.BranchRun(9492239, null, null, own, null,
            new TcModel.RootedBuilds(List.of(suite)));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(finished));
        chain(9492239, List.of(build(9492213, AOP, "AOP", "SUCCESS", 100, null, List.of())));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));

        var verdict = clients.verdict(TOKEN, extensions, PR);

        assertThat(verdict.isPresent()).isEqualTo(accepted);
        if (accepted) {
            assertThat(verdict.orElseThrow().run().clientSha()).isEqualTo(EXT);
            assertThat(verdict.orElseThrow().run().igniteSha()).isEqualTo(IGNITE);
            assertThat(verdict.orElseThrow().result().blockers()).isEmpty();
        }
        else {
            assertThat(clients.kept(extensions, PR)).isEmpty();
            verify(tc, never()).getBuildWithDeps(TOKEN, 9492239);
        }
    }

    /** Supplies successful-run metadata with valid roots, unknown values, missing roots, or conflicting root values. */
    private static Stream<Arguments> compositeRootCommits() {
        return Stream.of(
            Arguments.of(List.of(EXT), List.of(IGNITE), true),
            Arguments.of(List.of("N/A"), List.of(IGNITE), false),
            Arguments.of(List.of(EXT), List.of("N/A"), false),
            Arguments.of(List.of("not-a-sha"), List.of(IGNITE), false),
            Arguments.of(List.of(EXT), List.of(""), false),
            Arguments.of(List.of(), List.of(IGNITE), false),
            Arguments.of(List.of(EXT), List.of(), false),
            Arguments.of(List.of(EXT, "N/A"), List.of(IGNITE), false),
            Arguments.of(List.of(EXT), List.of(IGNITE, "N/A"), false),
            Arguments.of(java.util.Arrays.asList(EXT, null), List.of(IGNITE), false));
    }

    /**
     * Given an Extensions chain with a failed test and a broken sibling, when its verdict and Node.js PR #370 are read,
     * then the Extensions verdict contains both real suite outcomes and the projects keep their own commits.
     */
    @Test
    void readsDependentSuitesAndKeepsTheSamePrNumberSeparate() {
        TcModel.Build aop = build(9492213, AOP, "AOP", "FAILURE", 100, null, List.of());
        TcModel.Build kafka = build(9492224, KAFKA, "Kafka", "FAILURE", 0, "TC_COMPILATION_ERROR", List.of());
        chain(9492239, List.of(aop, kafka));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100, KAFKA, 100), Map.of(), 2));
        failedTest(9492213);
        when(tc.branchRuns(TOKEN, extensions.branch(PR), 501, AOP, true))
            .thenReturn(List.of(occurrence(9492213, "FAILURE", IGNITE)));
        ClientSpec node = projects.require("nodejs");
        when(tc.finishedRuns(TOKEN, node.suite(), node.branch(PR), TcModel.OVERRIDDEN_BRANCH))
            .thenReturn(List.of(new TcModel.BranchRun(9500000, null, null, rooted(node.clientVcsRoot(), "2".repeat(40)),
                null)));
        when(tc.suiteRun(TOKEN, 9500000)).thenReturn(build(9500000, node.suite(), "Node.js", "SUCCESS", 100,
            null, List.of()));
        when(tc.masterSuiteStats(TOKEN)).thenReturn(new TcClient.MasterSuiteStats(Map.of(), Map.of(), 0));

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();
        ClientVerdicts.Verdict other = clients.verdict(TOKEN, node, PR).orElseThrow();

        assertThat(verdict.result().blockers()).extracting(TestVerdict::suite).containsExactly(AOP);
        assertThat(verdict.result().brokenSuites()).singleElement().satisfies(s -> {
            assertThat(s.suite()).isEqualTo(KAFKA);
            assertThat(s.compileError()).isTrue();
        });
        assertThat(verdict.result().suitesRan()).isEqualTo(2);
        assertThat(verdict.run().clientSha()).isEqualTo(EXT);
        assertThat(verdict.run().igniteSha()).isEqualTo(IGNITE);
        assertThat(other.run().clientSha()).isEqualTo("2".repeat(40));
        assertThat(other.result().blockers()).isEmpty();
        assertThat(clients.kept(extensions, PR).orElseThrow().result().buildId()).isEqualTo(9492239);
        assertThat(clients.kept(node, PR).orElseThrow().result().buildId()).isEqualTo(9500000);
        verify(tc, never()).recentChains(anyString(), anyInt(), anyInt());
    }

    /**
     * Given a failed chain followed by a passing chain on both original commits, when its verdict is read,
     * then the failure is compared with the passing suite run and the old compilation failure is cleared.
     */
    @Test
    void aWholeChainRerunCountsItsSuitesAndClearsOldBreaks() {
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 100, null, List.of()),
            build(9492224, KAFKA, "Kafka", "FAILURE", 0, "TC_COMPILATION_ERROR", List.of())));
        chain(9493000, List.of(build(9493001, AOP, "AOP", "SUCCESS", 100, null, List.of()),
            build(9493002, KAFKA, "Kafka", "SUCCESS", 100, null, List.of())));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9493000, IGNITE), rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100, KAFKA, 100), Map.of(), 2));
        failedTest(9492213);
        when(tc.branchRuns(TOKEN, extensions.branch(PR), 501, AOP, true)).thenReturn(List.of(
            occurrence(9492213, "FAILURE", IGNITE), occurrence(9493001, "SUCCESS", IGNITE)));

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(verdict.run().rerunId()).isEqualTo(9493000);
        assertThat(verdict.result().blockers()).isEmpty();
        assertThat(verdict.result().brokenSuites()).isEmpty();
        assertThat(verdict.result().filtered()).extracting(TestVerdict::name).containsExactly("AopTest.fails");
    }

    /** Given a failed Ignite Build, when the chain runs again successfully, then its old blocked suites and Build clear. */
    @ParameterizedTest
    @ValueSource(strings = {"TC_EXIT_CODE", "TC_COMPILATION_ERROR"})
    void aPassingIgniteBuildReplacesTheFailedDependency(String problem) {
        TcModel.Build failedBuild = build(9492212, extensions.igniteSuite(), "Build", "FAILURE", 0, problem, List.of());
        when(tc.suiteRun(TOKEN, failedBuild.id())).thenReturn(failedBuild);
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 0, "SNAPSHOT_DEPENDENCY_ERROR",
            List.of(failedBuild))));
        TcModel.Build passedBuild = build(9493003, extensions.igniteSuite(), "Build", "SUCCESS", 0, null, List.of());
        chain(9493000, List.of(build(9493001, AOP, "AOP", "SUCCESS", 100, null, List.of(passedBuild))));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));

        ClientVerdicts.Verdict first = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(first.result().brokenSuites()).hasSize(2);
        assertThat(ClientRuns.worthRerunning(first.result(), extensions)).isEqualTo(!problem.equals("TC_COMPILATION_ERROR"));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9493000, IGNITE), rootedChain(9492239, IGNITE)));

        ClientVerdicts.Verdict recovered = clients.fresh(TOKEN, extensions, PR).orElseThrow();

        assertThat(recovered.result().brokenSuites()).isEmpty();
    }

    /** Given a failing Ignite master Build, when the Extensions chain is read, then it is broken without PR test blame. */
    @Test
    void aFailureInTheIgniteMasterDependencyDoesNotBlameTheExtensionsPr() {
        TcModel.Build failedBuild = build(9492212, extensions.igniteSuite(), "Build", "FAILURE", 10, null, List.of());
        when(tc.suiteRun(TOKEN, failedBuild.id())).thenReturn(failedBuild);
        when(tc.getFailedTests(TOKEN, failedBuild.id())).thenReturn(List.of(new TcModel.TestOccurrence("core-failure",
            "IgniteBuildTest.fails", "FAILURE", new TcModel.TestRef(999), null, null)));
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 0, "SNAPSHOT_DEPENDENCY_ERROR",
            List.of(failedBuild))));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(verdict.result().brokenSuites()).extracting(s -> s.suite()).contains(extensions.igniteSuite());
        assertThat(verdict.result().blockers()).isEmpty();
        assertThat(verdict.result().unverified()).isEmpty();
        verify(tc, never()).getFailedTests(TOKEN, failedBuild.id());
    }

    /** Given failures on other Extensions PR branches, when judged, then only three other PRs make this test flaky. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comparesExtensionsPrBranchesWithoutCountingTheCurrentPr(boolean threeOtherPrs) {
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 100, null, List.of())));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));
        failedTest(9492213);
        when(tc.branchRuns(TOKEN, extensions.branch(PR), 501, AOP, true)).thenReturn(List.of(
            occurrence(9492211, "FAILURE", IGNITE), occurrence(9492212, "FAILURE", IGNITE),
            occurrence(9492213, "FAILURE", IGNITE)));
        List<TcModel.TestOccurrence> others = new java.util.ArrayList<>(IntStream.range(43, threeOtherPrs ? 46 : 45)
            .mapToObj(pr -> otherPrOccurrence(pr, "FAILURE")).toList());
        others.add(otherPrOccurrence(PR, "FAILURE"));
        others.add(otherPrOccurrence(46, "SUCCESS"));
        when(tc.otherBranchRuns(TOKEN, 501, AOP)).thenReturn(others);

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(verdict.result().blockers().isEmpty()).isEqualTo(threeOtherPrs);
        if (threeOtherPrs)
            assertThat(verdict.result().filtered()).singleElement().satisfies(v -> assertThat(v.reason())
                .contains("fails 3/4 in 3 other PRs"));
    }

    /** Given no usable Extensions commit in test history, when judged, then Ignite's commit alone proves nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "N/A"})
    void missingExtensionsCommitsCannotProduceAProvenVerdict(String metadata) {
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 100, null, List.of())));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));
        failedTest(9492213);
        TcModel.BuildRef ref = new TcModel.BuildRef(9492213, extensions.branch(PR), "finished", "FAILURE", AOP, null,
            metadata.equals("missing") ? null : new TcModel.Revisions(List.of(new TcModel.Revision(metadata))), null,
            null, new TcModel.RootedBuilds(List.of(new TcModel.RootedBuild(rooted(extensions.igniteVcsRoot(), IGNITE)))));
        when(tc.branchRuns(TOKEN, extensions.branch(PR), 501, AOP, true)).thenReturn(List.of(
            new TcModel.TestOccurrence("o1", null, "FAILURE", null, ref, null)));

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(verdict.result().unverified()).extracting(TestVerdict::name).containsExactly("AopTest.fails");
        assertThat(verdict.result().blockers()).isEmpty();
        assertThat(verdict.result().incompleteSince()).isPositive();
    }

    /** Given missing dependency commits in test history, when classified, then the test remains unchecked. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "N/A", "wrong-root"})
    void missingDependencyCommitsCannotProduceAProvenVerdict(String metadata) {
        chain(9492239, List.of(build(9492213, AOP, "AOP", "FAILURE", 100, null, List.of())));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));
        failedTest(9492213);
        TcModel.RootedBuilds dependency = metadata.equals("missing") ? null : new TcModel.RootedBuilds(List.of(
            new TcModel.RootedBuild(rooted(metadata.equals("wrong-root") ? "another-root" : extensions.igniteVcsRoot(),
                metadata.equals("N/A") ? "N/A" : IGNITE))));
        TcModel.BuildRef withoutDependency = new TcModel.BuildRef(9492213, extensions.branch(PR), "finished",
            "FAILURE", AOP, null, new TcModel.Revisions(List.of(new TcModel.Revision(EXT))), null, null, dependency);
        when(tc.branchRuns(TOKEN, extensions.branch(PR), 501, AOP, true)).thenReturn(List.of(
            new TcModel.TestOccurrence("o1", null, "FAILURE", null, withoutDependency, null)));

        ClientVerdicts.Verdict verdict = clients.verdict(TOKEN, extensions, PR).orElseThrow();

        assertThat(verdict.result().unverified()).extracting(TestVerdict::name).containsExactly("AopTest.fails");
        assertThat(verdict.result().blockers()).isEmpty();
        assertThat(verdict.result().incompleteSince()).isPositive();
    }

    /** Given missing suites of a composite or its re-run, when analysed, then its empty top cannot stand for passing tests. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCompositeDependenciesNeverProduceACleanVerdict(boolean rerunMissing) {
        chain(9492239, rerunMissing ? List.of(build(9492213, AOP, "AOP", "SUCCESS", 100, null, List.of())) : List.of());
        chain(9493000, List.of());
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(rerunMissing ? List.of(rootedChain(9493000, IGNITE), rootedChain(9492239, IGNITE))
                : List.of(rootedChain(9492239, IGNITE)));
        when(tc.masterSuiteStats(TOKEN, extensions.suite()))
            .thenReturn(new TcClient.MasterSuiteStats(Map.of(AOP, 100), Map.of(), 1));

        assertThatThrownBy(() -> clients.verdict(TOKEN, extensions, PR)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not return dependent suites for composite build "
                + (rerunMissing ? "9493000" : "9492239"));
        assertThat(clients.kept(extensions, PR)).isEmpty();
    }

    /** Given two different Ignite revisions across one chain, when listed, then that chain is not presented as pinned. */
    @Test
    void rejectsMixedIgniteRevisionsWithinOneComposite() {
        TcModel.BranchRun normal = rootedChain(9492239, IGNITE);
        TcModel.RootedBuild mixed = new TcModel.RootedBuild(rooted(extensions.igniteVcsRoot(), "f".repeat(40)));
        when(tc.finishedRuns(TOKEN, extensions.suite(), extensions.branch(PR), TcModel.OVERRIDDEN_BRANCH, true))
            .thenReturn(List.of(new TcModel.BranchRun(normal.id(), null, null, normal.revisions(), null,
                new TcModel.RootedBuilds(List.of(normal.snapshotDependencies().build().get(0), mixed)))));

        assertThat(clients.verdict(TOKEN, extensions, PR)).isEmpty();
    }

    /** Supplies the real composite shape: test suites at the PR revision, each with an Ignite master dependency. */
    private TcModel.BranchRun rootedChain(long id, String ignite) {
        TcModel.RootedBuild dependency = new TcModel.RootedBuild(rooted(extensions.igniteVcsRoot(), ignite));
        TcModel.RootedBuild suite = new TcModel.RootedBuild(rooted(extensions.clientVcsRoot(), EXT),
            new TcModel.RootedBuilds(List.of(dependency)));
        return new TcModel.BranchRun(id, new TcModel.BuildType(extensions.suite(), "All Tests"), null,
            rooted(extensions.clientVcsRoot(), EXT), null, new TcModel.RootedBuilds(List.of(suite)));
    }

    private static TcModel.RootedRevisions rooted(String root, String sha) {
        return new TcModel.RootedRevisions(List.of(new TcModel.RootedRevision(sha, new TcModel.RootInstance(null, root))));
    }

    /** Supplies every reported value of one VCS root, including unknown values rather than dropping them. */
    private static TcModel.RootedRevisions rooted(String root, List<String> commits) {
        return new TcModel.RootedRevisions(commits.stream()
            .map(sha -> new TcModel.RootedRevision(sha, new TcModel.RootInstance(null, root))).toList());
    }

    private void chain(long id, List<TcModel.Build> suites) {
        String status = suites.stream().anyMatch(b -> "FAILURE".equals(b.status())) ? "FAILURE" : "SUCCESS";
        when(tc.getBuildWithDeps(TOKEN, id)).thenReturn(build(id, extensions.suite(), "All Tests", status,
            0, null, suites));
    }

    private TcModel.Build build(long id, String type, String name, String status, int tests, String problem,
        List<TcModel.Build> dependencies) {
        String branch = type.equals(extensions.igniteSuite()) ? "refs/heads/master"
            : type.startsWith("IgniteExtensions_") ? extensions.branch(PR) : projects.require("nodejs").branch(PR);
        String sha = type.equals(extensions.igniteSuite()) ? IGNITE : EXT;
        return new TcModel.Build(id, status, "finished", branch, type, null, DATE, DATE, DATE, null,
            null, new TcModel.BuildType(type, name), null, new TcModel.SnapshotDeps(dependencies.size(), dependencies),
            null, problem == null ? null : new TcModel.ProblemOccurrences(List.of(
                new TcModel.ProblemOccurrence(problem, "compile failed"))),
            new TcModel.Revisions(List.of(new TcModel.Revision(sha))), new TcModel.TestOccurrences(tests, null));
    }

    private void failedTest(long build) {
        when(tc.getFailedTests(TOKEN, build)).thenReturn(List.of(new TcModel.TestOccurrence("o1", "AopTest.fails",
            "FAILURE", new TcModel.TestRef(501), null, null)));
        when(tc.getBaseBranchHistory(TOKEN, 501, AOP)).thenReturn(IntStream.range(0, 100)
            .mapToObj(i -> new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null)).toList());
    }

    private TcModel.TestOccurrence occurrence(long build, String status, String ignite) {
        TcModel.BuildRef ref = new TcModel.BuildRef(build, extensions.branch(PR), "finished", status, AOP, null,
            new TcModel.Revisions(List.of(new TcModel.Revision(EXT))), null, null,
            new TcModel.RootedBuilds(List.of(new TcModel.RootedBuild(rooted(extensions.igniteVcsRoot(), ignite)))));
        return new TcModel.TestOccurrence("o" + build, null, status, null, ref, null);
    }

    /** Supplies other PR history in the Extensions build type's logical branch format. */
    private TcModel.TestOccurrence otherPrOccurrence(int pr, String status) {
        TcModel.BuildRef ref = new TcModel.BuildRef(9000000 + pr, extensions.branch(pr), "finished", status, AOP,
            null, null, null);

        return new TcModel.TestOccurrence("other" + pr, null, status, null, ref, null);
    }
}
