package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.Upstream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Broken suites listed one by one hid what broke them. PR 13655 showed 62 rows, 60 of them one ci2 glitch: the
 * artifacts of a Build that had passed could not be fetched, TeamCity naming that Build two ways. PR 13583's Build
 * failed, and it stood fifth among the suites it had kept from running. They are grouped by cause, and a re-run of a
 * group re-queues what can settle it: a failed Build alone, unless it failed to compile.
 */
class BrokenGroupTest {
    @Test
    void aFailedBuildComesFirstWithTheSuitesItKeptFromRunning() {
        List<BrokenGroup> groups = BrokenGroup.of(BrokenRuns.buildFailed(false));

        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.title()).isEqualTo("Build failed — nothing else ran; fix the build, then /run-all");
            assertThat(g.root().suite()).isEqualTo(BrokenRuns.BUILD);
            assertThat(g.suites()).hasSize(8);
            assertThat(g.cancelled()).hasSize(137);
            assertThat(g.rerun()).containsExactly(BrokenRuns.BUILD);
        });
    }

    @Test
    void aBuildThatFailedToCompileIsNotReRun() {
        assertThat(BrokenGroup.of(BrokenRuns.buildFailed(true))).singleElement()
            .satisfies(g -> assertThat(g.rerun()).isEmpty());
    }

    /** Before the suites that need it fail, a Build that failed to compile is a broken suite of its own. */
    @Test
    void noSuiteThatFailedToCompileIsReRun() {
        AnalysisResult r = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite(BrokenRuns.BUILD, 1L, "> Build", List.of("compilation error"), 0, 0),
            new BrokenSuite("IgniteTests24Java8_Cache5", 2L, "Cache 5", List.of("execution timeout"), 3, 40)));

        assertThat(BrokenGroup.rerunSuites(BrokenGroup.of(r))).containsExactly("IgniteTests24Java8_Cache5");
    }

    @Test
    void aBuildThatPassedOnARerunLeavesTheSuitesToARunAll() {
        assertThat(BrokenGroup.of(BrokenRuns.buildPassedOnRerun())).singleElement().satisfies(g -> {
            assertThat(g.root()).isNull();
            assertThat(g.title()).isEqualTo("Build failed in this run and passed on a re-run since — 145 suites that "
                + "need it never ran; /run-all to run them");
            assertThat(g.rerun()).isEmpty();
        });
    }

    @Test
    void aFailedBuildDoesNotSayNothingElseRanWhenSomethingDid() {
        AnalysisResult r = BrokenRuns.buildFailed(false);
        List<BrokenSuite> broken = new ArrayList<>(r.brokenSuites());
        broken.add(new BrokenSuite("IgniteTests24Java8_Cache5", 9384900L, "Cache 5", List.of("execution timeout"), 3,
            40));

        assertThat(BrokenGroup.of(BrokenRuns.withBroken(r, broken)).get(0).title())
            .isEqualTo("Build failed — 145 suites that need it did not run; fix the build, then /run-all");
    }

    @Test
    void theArtifactGlitchIsOneGroupWhicheverWayTeamCityNamedTheBuild() {
        List<BrokenGroup> groups = BrokenGroup.of(BrokenRuns.artifactsGlitch());

        assertThat(groups)
            .extracting(BrokenGroup::kind, BrokenGroup::title, g -> g.suites().size(), g -> g.rerun().size())
            .containsExactly(
                tuple(BrokenGroup.Kind.ARTIFACTS, "ci2 glitch: artifacts unavailable (60 suites)", 60, 60),
                tuple(BrokenGroup.Kind.PROBLEM, "non-zero exit code", 2, 2),
                tuple(BrokenGroup.Kind.PROBLEM, "execution timeout", 1, 1),
                tuple(BrokenGroup.Kind.PROBLEM, "Failed to create temporary custom script file in directory "
                    + "'/opt/teamcity/temp/agentTmp': java.io.IOException: No space left on device", 1, 1));
    }

    /** PR 13653: 67 suites broke on one agent with a full disk; two shrunk-run messages differ only in numbers. */
    @Test
    void problemsThatDifferOnlyInNumbersAreOneGroup() {
        List<BrokenSuite> broken = new ArrayList<>(IntStream.rangeClosed(1, 67)
            .mapToObj(i -> new BrokenSuite("IgniteTests24Java8_Suite" + i, 9392000L + i, "Suite " + i, List.of(
                "Agent failed to create agent temp directory at /opt/teamcity/temp/agentTmp. Please check agent has "
                    + "necessary permissions and there is enough free space on disk."), 0, 50))
            .toList());
        broken.add(new BrokenSuite("IgniteTests24Java8_Zk", 9392101L, "Control Utility (Zookeeper)",
            List.of("Number of tests 0 is 100% less than 266 in build #23327"), 0, 266));
        broken.add(new BrokenSuite("IgniteTests24Java8_Ml", 9392102L, "Machine Learning",
            List.of("Number of tests 12 is 66% less than 35 in build #42180"), 12, 35));

        assertThat(BrokenGroup.of(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), broken)))
            .extracting(BrokenGroup::kind, g -> g.suites().size())
            .containsExactly(tuple(BrokenGroup.Kind.PROBLEM, 67), tuple(BrokenGroup.Kind.PROBLEM, 2));
    }

    /**
     * Two suites hung in one run, as in PR 13632 and 13649, each with its own shortfall in TeamCity's message: the
     * group names neither suite's numbers, only those they share.
     */
    @Test
    void aGroupIsNotTitledWithTheNumbersOfOneOfItsSuites() {
        AnalysisResult hung = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_CacheFailover5", 1L, "Cache (Failover) 5", List.of("execution timeout",
                "non-zero exit code", "Number of tests 5 is 93% less than 67 in build #1141"), 5, 67),
            new BrokenSuite("IgniteTests24Java8_DiskPageCompressions8", 2L, "Disk Page Compressions 8",
                List.of("execution timeout", "non-zero exit code",
                    "Number of tests 50 is 77% less than 216 in build #2313"), 50, 216)));
        AnalysisResult killed = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Zk", 1L, "Control Utility (Zookeeper)", List.of(
                "Process exited with code 137", "Number of tests 0 is 100% less than 266 in build #23327"), 0, 266),
            new BrokenSuite("IgniteTests24Java8_Ml", 2L, "Machine Learning", List.of(
                "Process exited with code 137", "Number of tests 12 is 66% less than 35 in build #42180"), 12, 35)));

        assertThat(BrokenGroup.of(hung)).singleElement().extracting(BrokenGroup::title).isEqualTo("execution timeout · "
            + "non-zero exit code · Number of tests N is N% less than N in build #N");
        assertThat(BrokenGroup.of(killed)).singleElement().extracting(BrokenGroup::title)
            .isEqualTo("Process exited with code 137 · Number of tests N is N% less than N in build #N");
    }

    /**
     * TeamCity can run a suite past a failed Build and add a problem: Cache 5 ran 250 tests and timed out. It ran, so
     * it is a broken suite of its own, re-run like any other, and not among those the Build kept from running.
     */
    @Test
    void aSuiteThatRanTestsPastAFailedBuildIsBrokenItsOwnWay() {
        AnalysisResult r = BrokenRuns.buildFailed(false);
        List<BrokenSuite> broken = new ArrayList<>(r.brokenSuites());
        broken.add(new BrokenSuite("IgniteTests24Java8_Cache5", 9384900L, "Cache 5",
            List.of("failed dependency", "execution timeout"), 250, 300, List.of("SNAPSHOT_DEPENDENCY_ERROR",
            "TC_EXECUTION_TIMEOUT"), r.brokenSuites().get(1).failedUpstream()));

        List<BrokenGroup> groups = BrokenGroup.of(BrokenRuns.withBroken(r, broken));

        assertThat(groups).extracting(BrokenGroup::kind, BrokenGroup::title, g -> g.suites().size())
            .containsExactly(
                tuple(BrokenGroup.Kind.UPSTREAM, "Build failed — 145 suites that need it did not run; fix the build, "
                    + "then /run-all", 8),
                tuple(BrokenGroup.Kind.PROBLEM, "failed dependency · execution timeout", 1));
        assertThat(BrokenGroup.rerunSuites(groups)).containsExactly(BrokenRuns.BUILD, "IgniteTests24Java8_Cache5");
    }

    /** The one suite that needed the failed Build ran past it: the Build kept no suite from running. */
    @Test
    void aBuildThatKeptNoSuiteFromRunningIsABrokenSuiteOfItsOwn() {
        AnalysisResult r = BrokenRuns.buildFailed(false);
        BrokenSuite build = r.brokenSuites().get(0);
        BrokenSuite cache = new BrokenSuite("IgniteTests24Java8_Cache5", 9384900L, "Cache 5",
            List.of("failed dependency", "execution timeout"), 250, 300, List.of("SNAPSHOT_DEPENDENCY_ERROR",
            "TC_EXECUTION_TIMEOUT"), r.brokenSuites().get(1).failedUpstream());
        AnalysisResult ranPast = new AnalysisResult(r.prNumber(), r.buildId(), r.branchName(), r.computedAt(),
            List.of(), List.of(), List.of(), List.of(build, cache), List.of(), 2, 0, false, 0, false, 0, 0, 0, 0, 0);

        assertThat(BrokenGroup.of(ranPast)).extracting(BrokenGroup::kind, BrokenGroup::title)
            .containsExactlyInAnyOrder(tuple(BrokenGroup.Kind.PROBLEM, "non-zero exit code"),
                tuple(BrokenGroup.Kind.PROBLEM, "failed dependency · execution timeout"));
    }

    /**
     * Two runs the others need failed and a re-run of the Build passed since: the one still broken comes first, as the
     * thing to fix, in the groups and in the caveats.
     */
    @Test
    void aRunStillBrokenComesBeforeOneThatPassedOnARerun() {
        AnalysisResult r = BrokenRuns.buildPassedOnRerun();
        Upstream dotNet = new Upstream("IgniteTests24Java8_BuildDotNet", 9384650L, "> Build .NET");
        List<BrokenSuite> broken = new ArrayList<>(r.brokenSuites());
        broken.add(new BrokenSuite(dotNet.suite(), dotNet.buildId(), dotNet.name(), List.of("non-zero exit code"), 0,
            0, List.of("TC_EXIT_CODE"), null));
        broken.add(new BrokenSuite("IgniteTests24Java8_PlatformNetLinux", 9384660L, "Platform .NET (Linux)",
            List.of("failed dependency"), 0, 100, List.of("SNAPSHOT_DEPENDENCY_ERROR"), dotNet));

        AnalysisResult two = BrokenRuns.withBroken(r, broken);

        assertThat(BrokenGroup.of(two)).extracting(BrokenGroup::title).containsExactly(
            "Build .NET failed — 1 suite that needs it did not run; fix the build, then /run-all",
            "Build failed in this run and passed on a re-run since — 145 suites that need it never ran; /run-all to "
                + "run them");
        assertThat(Caveats.of(two, null)).containsExactly("Build .NET failed — 1 suite that needs it did not run",
            "Build failed in this run and passed on a re-run since — 145 suites that need it never ran");
    }

    /** TeamCity names a run two ways in its messages; a problem naming it either way is one problem. */
    @Test
    void theTwoWaysTeamCityNamesARunAreOneProblem() {
        AnalysisResult r = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Cache1", 1L, "Cache 1",
                List.of("Failed to start: build #30372 [id 9391124] is gone"), 0, 0),
            new BrokenSuite("IgniteTests24Java8_Cache2", 2L, "Cache 2",
                List.of("Failed to start: build with id: 9391124 is gone"), 0, 0)));

        assertThat(BrokenGroup.of(r)).singleElement().extracting(g -> g.suites().size()).isEqualTo(2);
    }

    /** A verdict kept by v1.22.1 does not say which run its suites needed, only that one failed. */
    @Test
    void suitesOfAnOlderVerdictThatFailedOnADependencyAreNotReRun() {
        AnalysisResult r = BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite(BrokenRuns.BUILD, 1L, "> Build", List.of("non-zero exit code"), 0, 0),
            new BrokenSuite("IgniteTests24Java8_ThinClientNodeJs", 2L, "Thin client: Node.js",
                List.of("failed dependency", "Failed to resolve artifacts from <> Build, build #30306 [id 1]>"), 0,
                0)));

        assertThat(BrokenGroup.of(r))
            .extracting(BrokenGroup::kind, BrokenGroup::title, BrokenGroup::rerun)
            .containsExactly(
                tuple(BrokenGroup.Kind.UPSTREAM, "1 suite failed only because a run it needs failed", List.of()),
                tuple(BrokenGroup.Kind.PROBLEM, "non-zero exit code", List.of(BrokenRuns.BUILD)));
    }

    @Test
    void theCaveatNamesTheRealCauses() {
        assertThat(Caveats.of(BrokenRuns.artifactsGlitch(), null)).containsExactly("64 suite(s) have no reliable "
            + "result (60× ci2 glitch: artifacts unavailable; 2× non-zero exit code; execution timeout; …)");
        assertThat(Caveats.of(BrokenRuns.buildFailed(false), null))
            .containsExactly("Build failed — nothing else ran");
    }

    /** The page reads the groups from the verdict; a verdict written to disk reads back without them. */
    @Test
    void theGroupsGoToThePageAndAreNotReadBack() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(BrokenRuns.buildFailed(false));

        JsonNode groups = mapper.readTree(json).get("brokenGroups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("title").asText()).startsWith("Build failed — nothing else ran");
        assertThat(groups.get(0).get("rerun").get(0).asText()).isEqualTo(BrokenRuns.BUILD);
        AnalysisResult back = mapper.readValue(json, AnalysisResult.class);
        assertThat(back.brokenSuites().get(1).failedUpstream().suite()).isEqualTo(BrokenRuns.BUILD);
        assertThat(back.cancelledSuites().get(0).failedUpstream().buildId()).isEqualTo(9384622L);
    }

    /** A broken suite as v1.22.1 wrote it to disk, without problem types or the run it needed. */
    @Test
    void aBrokenSuiteOfTheOlderReleaseReadsBack() throws Exception {
        BrokenSuite old = new ObjectMapper().readValue("{\"suite\":\"IgniteTests24Java8_Cache5\",\"suiteBuildId\":7,"
            + "\"suiteName\":\"Cache 5\",\"problems\":[\"execution timeout\"],\"tests\":3,\"baseline\":40}",
            BrokenSuite.class);

        assertThat(old.problemTypes()).isEmpty();
        assertThat(old.failedUpstream()).isNull();
        assertThat(old.compileError()).isFalse();
    }

    /** Without the problem types, TeamCity's own words tell the artifact glitch and a compile error. */
    @Test
    void anOlderVerdictsProblemsAreToldByTheirWords() {
        BrokenSuite artifacts = new BrokenSuite("IgniteTests24Java8_Pds3", 1L, "PDS 3", List.of("Failed to resolve "
            + "artifacts from <[Apache Ignite 2.x / Tests] / > Build, build with id: 9391272>"), 0, 42);
        BrokenSuite build = new BrokenSuite(BrokenRuns.BUILD, 2L, "> Build", List.of("compilation error"), 0, 0);

        assertThat(artifacts.artifactsUnavailable()).isTrue();
        assertThat(build.compileError()).isTrue();
        assertThat(build.artifactsUnavailable()).isFalse();
    }
}
