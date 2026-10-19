package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * Ignite's check found calcite's IgniteFunctionParameterTest, added by the author's own PR, in no test suite, and the
 * author had to add it by hand and push again. With "Add my PRs' tests to a suite" on, the sweep puts it next to the
 * class of its module nearest to it, IgniteSqlFunctionsTest of the same package in UtilTestSuite, and pushes one commit
 * from the author's account; the PR gets a comment saying what went where and why and, later, what Ignite's check says
 * of that commit. Each head is tried once, and nobody else's PR is touched. GitHub and TeamCity are stubs.
 */
class SuiteFixServiceTest {
    private static final int PR = 13800;

    private static final String HEAD = "abc1234def5678abc1234def5678abc1234def56";

    private static final String PUSHED = "def5678abc1234def5678abc1234def5678abc12";

    private static final String ROOT = "modules/calcite/src/test/java";

    private static final String UTIL = ROOT + "/org/apache/ignite/testsuites/UtilTestSuite.java";

    private static final String EXECUTION = ROOT + "/org/apache/ignite/testsuites/ExecutionTestSuite.java";

    private static final String FQCN =
        "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest";

    private static final String CLASS = ROOT + "/" + FQCN.replace('.', '/') + ".java";

    private static final String JOB = "https://github.com/apache/ignite/actions/runs/1/job/2";

    private static final String UTIL_SUITE = """
        package org.apache.ignite.testsuites;

        import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;
        import org.junit.runner.RunWith;
        import org.junit.runners.Suite;

        @RunWith(Suite.class)
        @Suite.SuiteClasses({
            IgniteSqlFunctionsTest.class,
        })
        public class UtilTestSuite {
        }
        """;

    private static final String EXECUTION_SUITE = UTIL_SUITE.replace("UtilTestSuite", "ExecutionTestSuite")
        .replace("exec.exp.IgniteSqlFunctionsTest", "exec.rel.ExecutionTest").replace("IgniteSqlFunctionsTest.class",
            "ExecutionTest.class");

    private static final long MINUTE = 60_000L;

    private static final long HOUR = 60 * MINUTE;

    private static final long DAY = 24 * HOUR;

    private static final String NO_JOB = "GitHub shows no Check java code job for it (Ignite's workflow does not run"
        + " on a PR that conflicts with master)";

    private static final StandingVisas.GhActor ALICE = new StandingVisas.GhActor("alice", "tc", "gh-pat", null);

    private final GithubClient github = AppWriting.github();

    private final PrTestRuns prTests = mock(PrTestRuns.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private SuiteFixService service = new SuiteFixService(github, prTests, standing, mapper, Runnable::run, now::get);

    @BeforeEach
    void anAuthorWithTheOptionOnAndAClassInNoSuite() {
        when(standing.suiteFixers()).thenReturn(Map.of("Alice", ALICE));
        when(standing.actor("alice")).thenReturn(Optional.of(ALICE));
        when(github.openPrsBy("Alice")).thenReturn(List.of(PR));
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", HEAD));
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, FQCN));
        when(github.prJavaFiles(PR)).thenReturn(List.of(CLASS));
        when(github.filesUnder("alice/ignite", HEAD, ROOT)).thenReturn(new GithubClient.Tree(List.of(
            new GithubClient.Tree.File(UTIL, 400), new GithubClient.Tree.File(EXECUTION, 400),
            new GithubClient.Tree.File(ROOT + "/org/apache/ignite/Helper.java", 100)), false));
        when(github.rawFileUpTo(eq("alice/ignite"), eq(HEAD), eq(UTIL), anyInt())).thenReturn(Optional.of(UTIL_SUITE));
        when(github.rawFileUpTo(eq("alice/ignite"), eq(HEAD), eq(EXECUTION), anyInt()))
            .thenReturn(Optional.of(EXECUTION_SUITE));
        when(github.commitFiles(eq("gh-pat"), eq("alice/ignite"), eq("IGNITE-29000"), eq(HEAD), anyMap(),
            anyString())).thenReturn(PUSHED);
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.RUNNING, PUSHED, JOB, null,
            List.of(), false));
    }

    @Test
    void theClassGoesIntoThePickedSuiteInOneCommitAndThePrSaysWhere() {
        service.sweep();

        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.captor();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(github).commitFiles(eq("gh-pat"), eq("alice/ignite"), eq("IGNITE-29000"), eq(HEAD), files.capture(),
            message.capture());
        assertThat(files.getValue()).containsOnlyKeys(UTIL);
        assertThat(files.getValue().get(UTIL))
            .contains("import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest;\n"
                + "import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;\n")
            .contains("    IgniteSqlFunctionsTest.class,\n    IgniteFunctionParameterTest.class,\n");
        assertThat(message.getValue()).startsWith("Add test classes to suites (Ignite PR Checker)\n\n")
            .contains("each goes next to the class of its module nearest by package, then by name",
                "- IgniteFunctionParameterTest -> UtilTestSuite");
        verify(github, never()).rawFileUpTo(any(), any(), eq(CLASS), anyInt());

        assertThat(comment()).isEqualTo("@alice 🧩 **Ignite PR Checker: test suites.** Ignite's [abandoned-tests check]("
            + JOB
            + ") on `abc1234` found 1 test class of this PR in no test suite, so CI never runs it. `def5678` on your"
            + " branch `IGNITE-29000` adds it to a suite (`git pull` before your next push):\n\n"
            + "- `IgniteFunctionParameterTest` → `UtilTestSuite`, next to `IgniteSqlFunctionsTest`: it lists 1 class of"
            + " the same package.\n\n"
            + "_Ignite's check runs again on `def5678`; its answer will be added here._");
    }

    @Test
    void eachHeadIsTriedOnceAndTheCommitPushedIsNotTriedAtAll() {
        service.sweep();
        service.sweep();
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", PUSHED));
        when(prTests.suiteCheck(PUSHED)).thenReturn(failed(PUSHED, FQCN));
        service.sweep();

        verify(github, times(1)).prJavaFiles(PR);
        verify(github, times(1)).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, times(1)).appComment(anyInt(), any(), any());
    }

    /** An Ignite PR often waits for review over a month with no push: its head gets no second try, nor comment. */
    @Test
    void aHeadThePrStillSitsOnAMonthLaterIsNotTriedAgain() {
        aClassOfAPackageNoSuiteComesNear();
        when(github.prOutcome(PR)).thenReturn(open(HEAD));
        service.sweep();

        now.addAndGet(31 * DAY);
        service.sweep();
        service.sweep();

        verify(github, times(1)).prJavaFiles(PR);
        verify(github, times(1)).appComment(anyInt(), any(), any());
    }

    /** The checker's commit may stay the head for weeks, its check naming a class still in no suite: never tried. */
    @Test
    void theCommitPushedIsNotTriedAMonthLater() {
        service.sweep();
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", PUSHED));
        when(prTests.suiteCheck(PUSHED)).thenReturn(failed(PUSHED, FQCN));
        when(github.prOutcome(PR)).thenReturn(open(PUSHED));

        now.addAndGet(31 * DAY);
        service.sweep();
        service.sweep();

        verify(github, times(1)).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, times(1)).appComment(anyInt(), any(), any());
    }

    @Test
    void aTryIsForgottenAMonthOnOnceItsPrHasMovedOn() {
        service.sweep();
        String newer = "0123456789abcdef0123456789abcdef01234567";
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", newer));
        when(prTests.suiteCheck(newer)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, newer, JOB, null,
            List.of(), true));
        when(github.prOutcome(PR)).thenReturn(open(newer));

        now.addAndGet(29 * DAY);
        service.sweep();
        assertThat(service.stats()).containsEntry("attempts", 1);

        now.addAndGet(2 * DAY);
        service.sweep();
        assertThat(service.stats()).containsEntry("attempts", 0);
    }

    /**
     * The status page waits a minute past the time the first sweep is due before it calls the sweep late. A first
     * sweep through the PRs of several authors can take longer, and it counted only once it ended.
     */
    @Test
    void aSweepCountsFromItsStart() {
        long started = now.get();
        List<Object> seen = new ArrayList<>();
        when(github.openPrsBy("Alice")).thenAnswer(inv -> {
            seen.add(service.stats().get("lastSweepAt"));
            now.addAndGet(5 * MINUTE);

            return List.of();
        });

        service.sweep();

        assertThat(seen).containsExactly(started);
        assertThat(service.stats()).containsEntry("lastSweepAt", started);
    }

    @Test
    void aTryIsForgottenAMonthOnOnceItsPrIsClosed() {
        service.sweep();
        when(github.openPrsBy("Alice")).thenReturn(List.of());
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(true, true, 1, PUSHED, HEAD, "IGNITE-29000"));

        now.addAndGet(31 * DAY);
        service.sweep();

        assertThat(service.stats()).containsEntry("attempts", 0);
    }

    /** A class of the module's tree outside org.apache.ignite has no class near it in any suite to stand next to. */
    @Test
    void aClassNoSuiteListsANearClassForIsNotCommitted() {
        aClassOfAPackageNoSuiteComesNear();

        service.sweep();

        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        assertThat(comment()).contains("Nothing was pushed:")
            .contains("- `StrayTest`: not added, no suite of its module lists a class of a package near its own.")
            .endsWith("Add it to a test suite by hand, or mark it `@Ignore`.");
    }

    /** A class costs no call to any service to place, so a PR that adds eleven classes gets all eleven in. */
    @Test
    void elevenClassesAllGoIn() {
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String fqcn = FQCN.replace("IgniteFunctionParameterTest", "IgniteFunction" + i + "Test");
            names.add(fqcn);
            paths.add(ROOT + "/" + fqcn.replace('.', '/') + ".java");
        }
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, names.toArray(String[]::new)));
        when(github.prJavaFiles(PR)).thenReturn(paths);

        service.sweep();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(github).commitFiles(any(), any(), any(), any(), anyMap(), message.capture());
        assertThat(message.getValue()).contains("- IgniteFunction0Test -> UtilTestSuite",
            "- IgniteFunction10Test -> UtilTestSuite");
        assertThat(comment()).contains("adds them, each to a suite").doesNotContain("not added");
    }

    /**
     * Two classes of a PR for one suite: the second is picked with the first already in the suite, so it stands next
     * to the first, not above it next to IgniteSqlFunctionsTest.
     */
    @Test
    void aSecondClassForTheSameSuiteStandsNextToTheFirst() {
        String args = FQCN.replace("IgniteFunctionParameterTest", "IgniteFunctionArgsTest");
        String result = FQCN.replace("IgniteFunctionParameterTest", "IgniteFunctionResultTest");
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, args, result));
        when(github.prJavaFiles(PR)).thenReturn(List.of(ROOT + "/" + args.replace('.', '/') + ".java",
            ROOT + "/" + result.replace('.', '/') + ".java"));

        service.sweep();

        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.captor();
        verify(github).commitFiles(any(), any(), any(), any(), files.capture(), any());
        assertThat(files.getValue().get(UTIL)).contains("    IgniteSqlFunctionsTest.class,\n"
            + "    IgniteFunctionArgsTest.class,\n    IgniteFunctionResultTest.class,\n");
        assertThat(comment()).contains("- `IgniteFunctionResultTest` → `UtilTestSuite`, next to"
            + " `IgniteFunctionArgsTest`: it lists 2 classes of the same package.");
    }

    /** The rule picks the suite in the checker itself: the sweep needs no key, only the author's GitHub token. */
    @Test
    void theSweepNeedsNoApiKey() {
        service.sweepSoon();

        verify(github).commitFiles(eq("gh-pat"), eq("alice/ignite"), eq("IGNITE-29000"), eq(HEAD), anyMap(),
            anyString());
        assertThat(service.stats()).doesNotContainKey("available").containsEntry("pushed", 1);
    }

    @Test
    void anotherAuthorsPrIsLeftAlone() {
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("bob", "bob/ignite", "IGNITE-29000", HEAD));

        service.sweep();

        verify(prTests, never()).suiteCheck(any());
        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, never()).appComment(anyInt(), any(), any());
    }

    @Test
    void aCheckStillRunningIsWaitedForAndAPassedOneIsNotAskedAgain() {
        when(prTests.suiteCheck(HEAD)).thenReturn(new AbandonedTestsCheck.Outcome(State.RUNNING, HEAD, JOB, null,
            List.of(), false));
        sweepEvery5MinutesFor(30 * MINUTE);

        when(prTests.suiteCheck(HEAD)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, HEAD, JOB, null,
            List.of(), true));
        service.sweep();
        service.sweep();

        verify(prTests, times(7)).suiteCheck(HEAD);
        verify(github, never()).prJavaFiles(anyInt());
    }

    /**
     * Ignite's workflow does not run on a PR that conflicts with master, and GitHub keeps a job's log for a while
     * only: the check of such a head says nothing for good. Right after a push GitHub may just not have started it.
     */
    @Test
    void aCheckThatSaysNothingIsAskedAtEachSweepFor15MinutesThenHourly() {
        when(prTests.suiteCheck(HEAD)).thenReturn(new AbandonedTestsCheck.Outcome(State.UNKNOWN, HEAD, null, NO_JOB,
            List.of(), false));

        sweepEvery5MinutesFor(3 * HOUR);

        verify(prTests, times(6)).suiteCheck(HEAD);
        verify(github, never()).prJavaFiles(anyInt());
    }

    /** Classes Ignite's check names outside the PR's files are not the author's to fix in this PR. */
    @Test
    void classesOutsideThePrsFilesAreLeftAlone() {
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, "org.apache.ignite.SomeoneElsesTest"));

        service.sweep();

        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, never()).appComment(anyInt(), any(), any());
    }

    /** Ignite's test classes are not all in files named *Test.java: IgniteCacheQueryNodeRestartSelfTest2 is one. */
    @Test
    void aClassWhoseFileIsNotNamedTestIsAddedToo() {
        String fqcn = "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterSelfTest2";
        String path = ROOT + "/" + fqcn.replace('.', '/') + ".java";
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, fqcn));
        when(github.prJavaFiles(PR)).thenReturn(List.of(path));

        service.sweep();

        verify(github).commitFiles(eq("gh-pat"), eq("alice/ignite"), eq("IGNITE-29000"), eq(HEAD), anyMap(),
            anyString());
        assertThat(comment()).contains("- `IgniteFunctionParameterSelfTest2` → `UtilTestSuite`");
    }

    @Test
    void theCommentGetsWhatIgnitesCheckSaysOfTheCommit() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));

        service.sweep();
        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github, times(1)).appEdit(anyInt(), any(), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("\n\n✅ Ignite's check on `def5678` finds every test class in a suite.")
            .doesNotContain("its answer will be added here");
    }

    @Test
    void aClassTheCheckStillFindsInNoSuiteIsNamed() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(failed(PUSHED, FQCN));

        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).appEdit(anyInt(), any(), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("⚠️ Ignite's check on `def5678` still finds `IgniteFunctionParameterTest`"
            + " in no test suite: the suite picked may not run it. Move it by hand.");
    }

    /** The commit conflicts with master, so Ignite's workflow never runs on it: the comment stops promising. */
    @Test
    void aCheckWithNoAnswerInADayIsSaidSoInTheComment() {
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.UNKNOWN, PUSHED, null,
            NO_JOB, List.of(), false));
        service.sweep();

        now.addAndGet(25 * HOUR);
        service.sweep();
        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github, times(1)).appEdit(anyInt(), any(), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("\n\nIgnite's check gave no answer on `def5678` within a day: " + NO_JOB
            + ".").doesNotContain("its answer will be added here");
    }

    /**
     * The author is outside the project, so Ignite's workflow waits for a maintainer to approve its run on the commit
     * pushed: a day on, the comment said the commit conflicts with master. Ignite's check is read off GitHub here.
     */
    @Test
    void aCheckWaitingForApprovalForADayIsSaidSo() {
        long job = 51234567890L;
        when(github.checkRuns(HEAD)).thenReturn(List.of(new GithubClient.CheckRun(job, "Check java code on JDK 17",
            "completed", "failure", JOB)));
        when(github.job(job)).thenReturn(new GithubClient.Job("completed", List.of(
            new GithubClient.Job.Step("Run abandoned tests checks.", "completed", "failure"))));
        when(github.jobLog(eq(job), any())).thenReturn(Optional.of(Optional.of(List.of(FQCN))));
        when(github.checkSuites(PUSHED)).thenReturn(List.of(new GithubClient.CheckSuite(104534382801L, "completed",
            "action_required", new GithubClient.CheckSuite.App("github-actions"))));
        service = new SuiteFixService(github, new PrTestRuns(github, mock(TcClient.class), mock(AnalysisCache.class)),
            standing, mapper, Runnable::run, now::get);
        service.sweep();

        now.addAndGet(25 * HOUR);
        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).appEdit(anyInt(), any(), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("\n\nIgnite's check gave no answer on `def5678` within a day: it waits for"
            + " a maintainer to approve its workflow run.");
    }

    @Test
    void aTokenGithubRefusesIsDroppedAndTheCommentSaysSo() {
        when(github.commitFiles(any(), any(), any(), any(), anyMap(), any()))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        service.sweep();

        verify(standing).dropGhToken("alice");
        assertThat(comment()).contains("Suites were picked, but the commit failed: GitHub refused your token."
            + " Nothing was pushed:");
    }

    /** GitHub made the checker's account wait: the answer goes in at the next sweep. */
    @Test
    void anEditGithubRateLimitedIsTriedAgain() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));
        doThrow(new GithubClient.WriteDeferred("GitHub asked to wait")).doReturn(true).when(github)
            .appEdit(anyInt(), any(), eq(77L), anyString());

        service.sweep();
        service.sweep();
        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).appEdit(anyInt(), any(), eq(77L), body.capture());
        assertThat(body.getAllValues()).allSatisfy(b -> assertThat(b)
            .endsWith("\n\n✅ Ignite's check on `def5678` finds every test class in a suite."));
        verify(standing, never()).dropGhToken(anyString());
    }

    /** The report is the checker's own comment: GitHub refusing the checker's account says nothing of the author's. */
    @Test
    void aRefusedReportKeepsTheAuthorsToken() {
        when(github.appComment(eq(PR), any(), anyString())).thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Bad credentials", null, null, null));

        service.sweep();

        verify(github).commitFiles(eq("gh-pat"), any(), any(), any(), anyMap(), any());
        verify(github).appComment(eq(PR), eq("alice"), startsWith("@alice 🧩"));
        verify(standing, never()).dropGhToken(anyString());
    }

    /** A fine-grained token given only the author's own fork cannot push to a PR of another repo's branch. */
    @Test
    void aTokenThatMayNotPushThereIsSaidSo() {
        when(github.commitFiles(any(), any(), any(), any(), anyMap(), any()))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Resource not accessible by personal"
                + " access token", null, null, null));

        service.sweep();

        verify(standing, never()).dropGhToken(anyString());
        assertThat(comment()).contains("Suites were picked, but the commit failed: your GitHub token may not push to"
            + " `alice/ignite` (403): a fine-grained token reaches only the repositories it was given. Nothing was"
            + " pushed:");
    }

    @Test
    void anEditThatKeepsFailingStopsADayOn() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));
        doThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)).when(github)
            .appEdit(anyInt(), any(), eq(77L), anyString());
        sweepEvery5MinutesFor(DAY + HOUR);
        clearInvocations(github);

        service.sweep();

        verify(github, never()).appEdit(anyInt(), any(), anyLong(), any());
    }

    @Test
    void aReportGithubRateLimitedIsPostedAtTheNextSweep() {
        when(github.appComment(eq(PR), any(), anyString())).thenThrow(rateLimited())
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));

        service.sweep();
        service.sweep();

        ArgumentCaptor<String> report = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).appComment(eq(PR), any(), report.capture());
        assertThat(report.getAllValues().get(1)).isEqualTo(report.getAllValues().get(0));
        verify(github).appEdit(anyInt(), any(), eq(77L), contains("finds every test class in a suite"));
        verify(standing, never()).dropGhToken(anyString());
    }

    /** The comment goes out from the checker's account, whose comments the command poll reads as well. */
    @Test
    void theCommentReadsAsNoCommand() throws Exception {
        service.sweep();

        String body = comment();
        assertThat(guard("readsAsCommand", body)).isFalse();
        assertThat(guard("triesCommand", body)).isFalse();
    }

    @Test
    void theTriesSurviveARestart(@TempDir Path dir) throws Exception {
        service.sweep();
        Path file = dir.resolve(service.fileName());
        service.saveTo(file);

        assertThat(service.durable()).isTrue();
        assertThat(Files.readString(file)).contains("\"head\":\"" + HEAD + "\"", "\"pushed\":\"" + PUSHED + "\"",
            "\"commentId\":77", "\"followed\":false", "\"app\":true");

        service = new SuiteFixService(github, prTests, standing, mapper, Runnable::run, () -> 2_000_000L);
        service.loadFrom(file);
        service.sweep();

        verify(github, times(1)).prJavaFiles(PR);
        assertThat(service.stats()).containsEntry("pushed", 1).containsEntry("attempts", 1);
    }

    /**
     * An earlier version posted the report with the author's token: the checker's account may not edit that comment,
     * and GitHub's refusal would read on the status page as a blocked account.
     */
    @Test
    void aReportPostedWithTheAuthorsTokenGetsNoAnswer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(service.fileName());
        Files.writeString(file, """
            {"attempts": [{"pr": %d, "head": "%s", "at": %d, "user": "alice", "pushed": "%s", "commentId": 4242,
              "report": "Added.\\n\\n%s", "added": ["%s"], "followed": false}], "pushed": 1}
            """.formatted(PR, HEAD, now.get() - HOUR, PUSHED, SuiteFixService.pending(PUSHED), FQCN));
        service.loadFrom(file);
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));

        service.sweep();

        verify(github, never()).appEdit(anyInt(), any(), eq(4242L), anyString());
        verify(github, never()).appComment(anyInt(), any(), anyString());
    }

    private void sweepEvery5MinutesFor(long ms) {
        for (long passed = 0; passed < ms; passed += 5 * MINUTE) {
            service.sweep();
            now.addAndGet(5 * MINUTE);
        }
    }

    /** Ignite's check names a class of the PR in a package of its own, under the module's test sources. */
    private void aClassOfAPackageNoSuiteComesNear() {
        String path = ROOT + "/com/example/StrayTest.java";
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, "com.example.StrayTest"));
        when(github.prJavaFiles(PR)).thenReturn(List.of(path));
    }

    private String comment() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), any(), body.capture());

        return body.getValue();
    }

    private static boolean guard(String name, String body) throws Exception {
        Method m = PrCommands.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);

        return (boolean)m.invoke(null, body);
    }

    private static HttpClientErrorException rateLimited() {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "You have exceeded a secondary rate limit", null,
            null, null);
    }

    private static AbandonedTestsCheck.Outcome failed(String sha, String... classes) {
        return new AbandonedTestsCheck.Outcome(State.FAILED, sha, JOB, null, List.of(classes), true);
    }

    private static GithubClient.PrOutcome open(String head) {
        return new GithubClient.PrOutcome(false, false, 0, null, head, "IGNITE-29000");
    }
}
