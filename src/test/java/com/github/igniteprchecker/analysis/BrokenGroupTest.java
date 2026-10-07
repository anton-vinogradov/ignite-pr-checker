package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
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
