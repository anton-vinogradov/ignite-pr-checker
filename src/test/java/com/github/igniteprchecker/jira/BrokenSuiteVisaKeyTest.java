package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ticket gets no second visa of a revision that says what the last one said. Control Utility (Zookeeper) of PR
 * 13653 broke the same way in two RunAlls of one revision, TeamCity naming another run in each message ("Number of
 * tests 0 is 100% less than 266 in build #23327", then "… in build #23340"): that is the same verdict, and so is one
 * a visa of v1.22.1 was posted for.
 */
class BrokenSuiteVisaKeyTest {
    @Test
    void twoRunsOfARevisionThatBrokeTheSameWayAreOneVerdict() {
        assertThat(StandingVisas.verdictKey(zookeeperBroken("#23340")))
            .isEqualTo(StandingVisas.verdictKey(zookeeperBroken("#23327")));
    }

    @Test
    void aVisaPostedByTheOlderReleaseStillMatches() {
        assertThat(StandingVisas.verdictKey(zookeeperBroken("#23327")))
            .isEqualTo("|||IgniteTests24Java8_Zk|1 suite(s) have no reliable result (compilation error, timeout, "
                + "crash)");
    }

    private static AnalysisResult zookeeperBroken(String run) {
        return BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(new BrokenSuite("IgniteTests24Java8_Zk",
            9392101L, "Control Utility (Zookeeper)", List.of("Number of tests 0 is 100% less than 266 in build " + run),
            0, 266)));
    }
}
