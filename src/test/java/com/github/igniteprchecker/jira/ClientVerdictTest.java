package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The verdict of Thin client: Python for PR #69 of apache/ignite-python-thin-client, build 9424005: it names the two
 * commits the run tested, every failure with its reason, and links only TeamCity and GitHub. A client change that
 * broke the whole suite fails a hundred tests: the comment lists 20 and links the rest in TeamCity.
 */
class ClientVerdictTest {
    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final ClientVerdict.Run RUN = new ClientVerdict.Run(9424005L, "Thin client: Python", "apache/ignite",
        "1f2e3d4c5b6a", "apache/ignite-python-thin-client", 69, "a1b2c3d4e5f6");

    @Test
    void aRunWithNoFailureSaysSo() {
        String md = ClientVerdict.markdown("https://ci2.example/", "Author", RUN,
            result(List.of(), List.of(), List.of()));

        assertThat(md).isEqualTo("@Author the verdict of your `/run-all`: **Thin client: Python** [build 9424005]"
            + "(https://ci2.example/build/9424005) ran this PR's head [`a1b2c3d`](https://github.com/"
            + "apache/ignite-python-thin-client/pull/69/commits/a1b2c3d4e5f6) against Ignite master at [`1f2e3d4`]"
            + "(https://github.com/apache/ignite/commit/1f2e3d4c5b6a).\n\n"
            + "✅ **No blockers** — no test failed.\n\n"
            + "<sub>Ignite is master at the commit above, so a client change that needs an Ignite change is not tested"
            + " with it. What each label means and what to do: [verdict glossary](" + VisaService.GLOSSARY
            + ").</sub>");
    }

    @Test
    void aHundredFailuresListTwentyAndLinkTheRest() {
        List<TestVerdict> failed = IntStream.range(0, 100).mapToObj(i -> new TestVerdict(600L + i,
            "/opt/buildagent/work/7f3a9c1e2b4d6f80/tests/test_cache.py::test_" + i, SUITE, 9424005L,
            "Thin client: Python", "o" + i, false, false, "pre-existing: fails 100/100 on master", "F", 0)).toList();

        String md = ClientVerdict.markdown("https://ci2.example/", "Author", RUN, result(List.of(), failed, List.of()));

        assertThat(md).contains("**100 failing tests are not blamed on this PR:**\n- `tests/test_cache.py::test_0` —"
            + " pre-existing: fails 100/100 on master · [TC](https://ci2.example/buildConfiguration/" + SUITE
            + "/9424005?buildTab=tests&expandedTest=o0#testNameId600)\n")
            .contains("- `tests/test_cache.py::test_19` —")
            .doesNotContain("test_20`")
            .contains("- … and 80 more in [TeamCity](https://ci2.example/build/9424005?buildTab=tests)\n")
            .doesNotContain("/opt/buildagent");
    }

    @Test
    void aSuiteThatRanNoTestSaysWhyAndCannotProveThePrClean() {
        BrokenSuite broken = new BrokenSuite(SUITE, 9424005L, "Thin client: Python", List.of("failed dependency"), 0,
            400);

        String md = ClientVerdict.markdown("https://ci2.example/", null, RUN, result(List.of(broken), List.of(),
            List.of()));

        assertThat(md).startsWith("The verdict of a `/run-all`: **Thin client: Python**")
            .contains("⚠️ **The suite has no reliable result:** failed dependency.\n\n")
            .contains("🔎 **No blockers found — but this run can't prove the PR is clean:** see above.\n\n")
            .doesNotContain("✅");
    }

    /** A test name is the PR's own code: it cannot end the code span and post a link in the checker's voice. */
    @Test
    void aTestNameCannotBreakOutOfItsCodeSpan() {
        TestVerdict blocker = new TestVerdict(700L, "test_x[`` [click](https://evil.example) ``]", SUITE, 9424005L,
            "Thin client: Python", "o1", true, false, "no master history (can't prove pre-existing); failed the only"
            + " run on this branch", "F", 1, List.of(TestVerdict.Doubt.ONE_RUN, TestVerdict.Doubt.NO_MASTER_HISTORY));

        String md = ClientVerdict.markdown("https://ci2.example/", "Author", RUN, result(List.of(), List.of(),
            List.of(blocker)));

        assertThat(md).contains("❌ **1 blocker:**\n- ``` test_x[`` [click](https://evil.example) ``] ``` — no master"
            + " history (can't prove pre-existing); failed the only run on this branch (1 run; new test / no master"
            + " history) · [TC](");
    }

    private static AnalysisResult result(List<BrokenSuite> broken, List<TestVerdict> filtered,
        List<TestVerdict> blockers) {
        return new AnalysisResult(69, 9424005L, "pull/69", 0, blockers, List.of(), filtered, broken, List.of(), 1, 0,
            false, 0, false, 0, 0, 0, 0, 0);
    }
}
