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
            + "<sub>This run uses Ignite master at the commit above. If this PR needs an Ignite change that is not in"
            + " that commit, the run tests this PR without that change. What each label means and what to do: [verdict"
            + " glossary](" + VisaService.GLOSSARY + ").</sub>");
    }

    /**
     * A test failed the only run of this PR's head with Ignite master at 1f2e3d4, after passes on other commits. The
     * suite timed out after running nearly all of master's tests. The comment says which /run-all decides the test,
     * and that the crash does not void the run.
     */
    @Test
    void aTestThatStartedFailingSaysWhichRunAllDecidesIt() {
        TestVerdict watched = new TestVerdict(800L, "tests/test_sql.py::test_sql_fields_query", SUITE, 9424005L,
            "Thin client: Python", "o8", false, true, "failed the only run on commits 1f2e3d4 and a1b2c3d", "F", 1);
        BrokenSuite crashed = new BrokenSuite(SUITE, 9424005L, "Thin client: Python", List.of("execution timeout"),
            398, 400);

        String md = ClientVerdict.markdown("https://ci2.example/", "Author", RUN, new AnalysisResult(69, 9424005L,
            "pull/69", 0, List.of(), List.of(watched), List.of(), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0,
            0, 0, List.of(crashed), List.of(), List.of(), 0));

        assertThat(md).contains("ℹ️ **Crashed near the end of the run:** execution timeout. It ran nearly all of"
            + " master's tests, so its results are reliable.\n\n")
            .contains("👀 **1 test started failing on this code** — not proven blockers yet. There are too few runs of"
                + " this code to tell a break from a flake. A new `/run-all` decides it while this PR's head and Ignite"
                + " master stay at the commits above.\n- `tests/test_sql.py::test_sql_fields_query` — failed the only"
                + " run on commits 1f2e3d4 and a1b2c3d");
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

    /**
     * A JIRA ticket gets visas of whole RunAlls: the visa of this run opens by saying that it is of one suite, which
     * tested the PR's head against Ignite master at a commit, so nobody takes it for a RunAll's. It lists each failed
     * test with its reason, as the PR comment does.
     */
    @Test
    void theVisaSaysItIsOfOneSuiteAgainstIgniteMasterAndListsWhatFailed() {
        TestVerdict blocker = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, 9424005L,
            "Thin client: Python", "o2", true, false, "not seen failing in 100 master runs; failed the only run on"
            + " this branch", "F", 1, List.of(TestVerdict.Doubt.ONE_RUN));
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get[{x}]", SUITE, 9424005L,
            "Thin client: Python", "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        String wiki = ClientVerdict.wiki("https://ci2.example/", RUN, result(List.of(), List.of(shared),
            List.of(blocker)), null);

        assertThat(wiki).isEqualTo("Ignite PR Checker verdict for [PR 69 of apache/ignite-python-thin-client|"
            + "https://github.com/apache/ignite-python-thin-client/pull/69] · *one suite only, not a RunAll*\n\n"
            + "*Thin client: Python* [build 9424005|https://ci2.example/build/9424005] tested the PR's head [a1b2c3d|"
            + "https://github.com/apache/ignite-python-thin-client/pull/69/commits/a1b2c3d4e5f6] against Ignite master"
            + " at [1f2e3d4|https://github.com/apache/ignite/commit/1f2e3d4c5b6a]. No other suite ran.\n\n"
            + "(x) *1 blocker:*\n- {{test_sql.test_sql_fields_query}} — not seen failing in 100 master runs; failed"
            + " the only run on this branch (1 run) · [TC|https://ci2.example/buildConfiguration/" + SUITE + "/9424005"
            + "?buildTab=tests&expandedTest=o2#testNameId502]\n\n"
            + "*1 failing test is not blamed on this PR:*\n- {{test_cache_config.test_get\\[\\{x\\}\\]}} — pre-existing:"
            + " fails 3/100 on master · [TC|https://ci2.example/buildConfiguration/" + SUITE + "/9424005?buildTab=tests"
            + "&expandedTest=o1#testNameId501]\n\n"
            + "_1 run: the test ran once on this code and failed; a re-run of its suite confirms or clears it._\n\n"
            + "_This run uses Ignite master at the commit above. If this PR needs an Ignite change that is not in that"
            + " commit, the run tests this PR without that change. What each label means and what to do: [verdict"
            + " glossary|" + VisaService.GLOSSARY + "]._");
    }

    /** The auto re-run ran the suite again on the same commits: the comment and the visa say the verdict counts it. */
    @Test
    void aVerdictAfterTheAutoRerunSaysSo() {
        AnalysisResult r = result(List.of(), List.of(), List.of());

        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, r,
            ClientVerdict.Rerun.counted(9424010L), ClientVisa.NONE)).contains("\n\n♻️ Settled after one auto re-run:"
            + " [build 9424010](https://ci2.example/build/9424010) ran the suite again on the same commits.\n\n<sub>");
        assertThat(ClientVerdict.wiki("https://ci2.example/", RUN, r, ClientVerdict.Rerun.counted(9424010L)))
            .contains("\n\n♻️ Settled after one auto re-run: [build 9424010|https://ci2.example/build/9424010] ran the"
                + " suite again on the same commits.\n\n_This run");
    }

    /**
     * TeamCity cancelled build 9424005 by itself, and the auto re-run 9424010 ran the suite in its place: the comment
     * and the visa name the re-run as the build that ran, and say that the run was cancelled.
     */
    @Test
    void aVerdictOfTheRerunOfACancelledRunSaysSo() {
        AnalysisResult r = result(List.of(), List.of(), List.of());

        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, r,
            ClientVerdict.Rerun.standsIn(9424010L), ClientVisa.NONE))
            .startsWith("@Author the verdict of your `/run-all`: **Thin client: Python** [build 9424010]"
                + "(https://ci2.example/build/9424010) ran this PR's head")
            .contains("\n\n♻️ TeamCity cancelled [build 9424005](https://ci2.example/build/9424005) by itself. The"
                + " checker ran the suite again on the same commits. This verdict comes from that re-run."
                + "\n\n<sub>");
        assertThat(ClientVerdict.wiki("https://ci2.example/", RUN, r, ClientVerdict.Rerun.standsIn(9424010L)))
            .contains("\n\n*Thin client: Python* [build 9424010|https://ci2.example/build/9424010] tested the PR's"
                + " head")
            .contains("\n\n♻️ TeamCity cancelled [build 9424005|https://ci2.example/build/9424005] by itself. The"
                + " checker ran the suite again on the same commits. This verdict comes from that re-run."
                + "\n\n_This run");
    }

    /** The re-run did not give a result: the verdict says why it counts the first run only. */
    @Test
    void aVerdictWithoutTheRerunSaysWhy() {
        AnalysisResult r = result(List.of(), List.of(), List.of());

        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, r,
            ClientVerdict.Rerun.cancelled(9424010L), ClientVisa.NONE)).contains("♻️ The auto re-run [build 9424010]"
            + "(https://ci2.example/build/9424010) was cancelled, so this verdict counts the first run only.");
        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, r,
            ClientVerdict.Rerun.gone(9424010L), ClientVisa.NONE)).contains("♻️ The auto re-run [build 9424010]"
            + "(https://ci2.example/build/9424010) is gone from TeamCity, so this verdict counts the first run only.");
        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, r,
            ClientVerdict.Rerun.notQueued("TeamCity answered HTTP 500"), ClientVisa.NONE)).contains("♻️ The checker"
            + " could not queue the auto re-run: TeamCity answered HTTP 500. This verdict counts the first run only.");
    }

    /** The comment says where the visa went, or why none did, and what the commander does for the next one. */
    @Test
    void theCommentSaysWhatBecameOfTheVisa() {
        assertThat(visaLine(new ClientVisa(ClientVisa.POSTED, "IGNITE-29117", "https://issues.example/c/1", null)))
            .isEqualTo("📝 The JIRA visa is in [IGNITE-29117](https://issues.example/c/1).");
        assertThat(visaLine(new ClientVisa(ClientVisa.SAME, "IGNITE-29117", "https://issues.example/c/1", null)))
            .isEqualTo("📝 No new JIRA visa: your last one in [IGNITE-29117](https://issues.example/c/1) has the same"
                + " verdict for the same commits.");
        assertThat(visaLine(new ClientVisa(ClientVisa.NO_TICKET, null, null, null)))
            .isEqualTo("📝 No JIRA visa: the PR title names no IGNITE ticket.");
        assertThat(visaLine(new ClientVisa(ClientVisa.CLOSED, null, null, "merged")))
            .isEqualTo("📝 No JIRA visa: the PR was merged before the run's verdict.");
        assertThat(visaLine(new ClientVisa(ClientVisa.WAITS, null, null, StandingVisas.TOKEN_REMOVED)))
            .isEqualTo("📝 No JIRA visa: the checker's state was restored from a backup older than 2 days, which keeps"
                + " no tokens. Save your JIRA token in ⚙ again, and the next `/run-all` gets a visa.");
        assertThat(visaLine(new ClientVisa(ClientVisa.WAITS, null, null, SettingsJournal.Entry.KEY_CHANGED)))
            .isEqualTo("📝 No JIRA visa: the server key was changed, so the checker could no longer read your JIRA"
                + " token. Save a new one in ⚙, and the next `/run-all` gets a visa.");
        assertThat(visaLine(new ClientVisa(ClientVisa.REFUSED, null, null, null)))
            .isEqualTo("📝 No JIRA visa: JIRA refused your JIRA token, so the checker dropped it and switched off"
                + " **Visa my runs in JIRA**. Save a new token in ⚙ to switch it on again.");
        assertThat(visaLine(new ClientVisa(ClientVisa.FAILED, null, null, "JIRA did not take it (HTTP 503)")))
            .isEqualTo("📝 No JIRA visa: JIRA did not take it (HTTP 503). The checker tried for 10 minutes.");
        assertThat(ClientVerdict.markdown("https://ci2.example/", "Author", RUN, result(List.of(), List.of(),
            List.of()), null, ClientVisa.NONE)).doesNotContain("📝");
    }

    /** The line about the visa in the verdict comment of a clean run, just before the footer. */
    private static String visaLine(ClientVisa visa) {
        String md = ClientVerdict.markdown("https://ci2.example/", "Author", RUN, result(List.of(), List.of(),
            List.of()), null, visa);

        return md.substring(md.indexOf("📝"), md.indexOf("\n\n<sub>"));
    }

    private static AnalysisResult result(List<BrokenSuite> broken, List<TestVerdict> filtered,
        List<TestVerdict> blockers) {
        return new AnalysisResult(69, 9424005L, "pull/69", 0, blockers, List.of(), filtered, broken, List.of(), 1, 0,
            false, 0, false, 0, 0, 0, 0, 0);
    }
}
