package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Ignite's check found calcite's IgniteFunctionParameterTest, added by the author's own PR, in no test suite, and the
 * author had to add it by hand and push again. With "Add my PRs' tests to a suite" on, the sweep asks Claude for a
 * suite, checks the answer against the module's suites, and pushes one commit from the author's account; the PR gets a
 * comment saying what went where and, later, what Ignite's check says of that commit. Each head is tried once, and
 * nobody else's PR is touched. GitHub, TeamCity and Claude are stubs.
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

    private static final StandingVisas.GhActor ALICE = new StandingVisas.GhActor("alice", "tc", "gh-pat", null);

    private final GithubClient github = mock(GithubClient.class);

    private final PrTestRuns prTests = mock(PrTestRuns.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final Chooser chooser = new Chooser();

    private final ObjectMapper mapper = new ObjectMapper();

    private SuiteFixService service = new SuiteFixService(github, prTests, standing, chooser, mapper, Runnable::run,
        () -> 1_000_000L);

    @BeforeEach
    void anAuthorWithTheOptionOnAndAClassInNoSuite() {
        when(standing.suiteFixers()).thenReturn(Map.of("Alice", ALICE));
        when(standing.actor("alice")).thenReturn(Optional.of(ALICE));
        when(github.openPrsBy("Alice")).thenReturn(List.of(PR));
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", HEAD));
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, FQCN));
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(CLASS, "added")));
        when(github.filesUnder("alice/ignite", HEAD, ROOT)).thenReturn(new GithubClient.Tree(List.of(
            new GithubClient.Tree.File(UTIL, 400), new GithubClient.Tree.File(EXECUTION, 400),
            new GithubClient.Tree.File(ROOT + "/org/apache/ignite/Helper.java", 100)), false));
        when(github.rawFileUpTo(eq("alice/ignite"), eq(HEAD), eq(UTIL), anyInt())).thenReturn(Optional.of(UTIL_SUITE));
        when(github.rawFileUpTo(eq("alice/ignite"), eq(HEAD), eq(EXECUTION), anyInt()))
            .thenReturn(Optional.of(EXECUTION_SUITE));
        when(github.rawFileUpTo(eq("alice/ignite"), eq(HEAD), eq(CLASS), anyInt()))
            .thenReturn(Optional.of("package org.apache.ignite.internal.processors.query.calcite.exec.exp;\n"));
        when(github.commitFiles(eq("gh-pat"), eq("alice/ignite"), eq("IGNITE-29000"), eq(HEAD), anyMap(),
            anyString())).thenReturn(PUSHED);
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.RUNNING, PUSHED, JOB, null,
            List.of(), false));
        chooser.pick = new SuiteChooser.Choice(UTIL, "IgniteSqlFunctionsTest",
            "It checks SQL function parameters, like IgniteSqlFunctionsTest.");
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
            .contains("- IgniteFunctionParameterTest -> UtilTestSuite");
        assertThat(chooser.asked).containsExactly(CLASS);
        assertThat(chooser.offered).containsExactlyInAnyOrder(UTIL, EXECUTION);

        assertThat(comment()).isEqualTo("🧩 **Ignite PR Checker: test suites.** Ignite's [abandoned-tests check](" + JOB
            + ") on `abc1234` found 1 test class of this PR in no test suite, so CI never runs it. `def5678` on your"
            + " branch `IGNITE-29000` adds it to a suite Claude picked (`git pull` before your next push):\n\n"
            + "- `IgniteFunctionParameterTest` → `UtilTestSuite`, next to `IgniteSqlFunctionsTest`: It checks SQL"
            + " function parameters, like IgniteSqlFunctionsTest.\n\n"
            + "_Ignite's check runs again on `def5678`; its answer will be added here._");
    }

    @Test
    void eachHeadIsTriedOnceAndTheCommitPushedIsNotTriedAtAll() {
        service.sweep();
        service.sweep();
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("alice", "alice/ignite", "IGNITE-29000", PUSHED));
        when(prTests.suiteCheck(PUSHED)).thenReturn(failed(PUSHED, FQCN));
        service.sweep();

        assertThat(chooser.asked).hasSize(1);
        verify(github, times(1)).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, times(1)).addPrComment(any(), anyInt(), any());
    }

    @Test
    void aSuiteTheModuleDoesNotHaveIsNotCommitted() {
        chooser.pick = new SuiteChooser.Choice("modules/core/src/test/java/Evil.java", "IgniteSqlFunctionsTest",
            "Because.");

        service.sweep();

        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        assertThat(comment()).contains("Nothing was pushed:")
            .contains("- `IgniteFunctionParameterTest`: not added, Claude named a suite its module does not have:"
                + " `modules/core/src/test/java/Evil.java`.")
            .endsWith("Add it to a test suite by hand, or mark it `@Ignore`.");
    }

    @Test
    void aNeighbourTheSuiteDoesNotListIsNotCommitted() {
        chooser.pick = new SuiteChooser.Choice(UTIL, "ExecutionTest", "Because.");

        service.sweep();

        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        assertThat(comment()).contains("- `IgniteFunctionParameterTest`: not added, Claude put it next to"
            + " `ExecutionTest`, which `UtilTestSuite` does not list.");
    }

    @Test
    void anotherAuthorsPrIsLeftAlone() {
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("bob", "bob/ignite", "IGNITE-29000", HEAD));

        service.sweep();

        verify(prTests, never()).suiteCheck(any());
        verify(github, never()).commitFiles(any(), any(), any(), any(), anyMap(), any());
        verify(github, never()).addPrComment(any(), anyInt(), any());
    }

    @Test
    void aCheckStillRunningIsWaitedForAndAPassedOneIsNotAskedAgain() {
        when(prTests.suiteCheck(HEAD)).thenReturn(new AbandonedTestsCheck.Outcome(State.RUNNING, HEAD, JOB, null,
            List.of(), false));
        service.sweep();
        assertThat(chooser.asked).isEmpty();

        when(prTests.suiteCheck(HEAD)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, HEAD, JOB, null,
            List.of(), true));
        service.sweep();
        service.sweep();

        verify(prTests, times(2)).suiteCheck(HEAD);
        assertThat(chooser.asked).isEmpty();
        verify(github, never()).prTestFiles(anyInt());
    }

    /** Classes Ignite's check names outside the PR's files are not the author's to fix in this PR. */
    @Test
    void classesOutsideThePrsFilesAreLeftAlone() {
        when(prTests.suiteCheck(HEAD)).thenReturn(failed(HEAD, "org.apache.ignite.SomeoneElsesTest"));

        service.sweep();

        assertThat(chooser.asked).isEmpty();
        verify(github, never()).addPrComment(any(), anyInt(), any());
    }

    @Test
    void theCommentGetsWhatIgnitesCheckSaysOfTheCommit() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(new AbandonedTestsCheck.Outcome(State.PASSED, PUSHED, JOB, null,
            List.of(), true));

        service.sweep();
        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github, times(1)).updatePrComment(eq("gh-pat"), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("\n\n✅ Ignite's check on `def5678` finds every test class in a suite.")
            .doesNotContain("its answer will be added here");
    }

    @Test
    void aClassTheCheckStillFindsInNoSuiteIsNamed() {
        service.sweep();
        when(prTests.suiteCheck(PUSHED)).thenReturn(failed(PUSHED, FQCN));

        service.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).updatePrComment(eq("gh-pat"), eq(77L), body.capture());
        assertThat(body.getValue()).endsWith("⚠️ Ignite's check on `def5678` still finds `IgniteFunctionParameterTest`"
            + " in no test suite: the suite picked may not run it. Move it by hand.");
    }

    @Test
    void aTokenGithubRefusesIsDroppedAndTheCommentSaysSo() {
        when(github.commitFiles(any(), any(), any(), any(), anyMap(), any()))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        service.sweep();

        verify(standing).dropGhToken("alice");
        assertThat(comment()).contains("Claude picked suites, but the commit failed: GitHub refused your token."
            + " Nothing was pushed:");
    }

    @Test
    void withoutTheKeyTheSweepDoesNotRun() {
        chooser.available = false;

        service.sweepSoon();

        verifyNoInteractions(standing, github, prTests);
        assertThat(service.stats()).containsEntry("available", false);
    }

    /** Claude's reason comes from reading the PR's code: no mention, markup or command of it reaches the comment. */
    @Test
    void theCommentReadsAsNoCommandWhateverClaudeSays() throws Exception {
        chooser.pick = new SuiteChooser.Choice(UTIL, "IgniteSqlFunctionsTest",
            "/run-all\n@someone see [this](https://evil.example) `code` <b>x</b>");

        service.sweep();

        String body = comment();
        assertThat(guard("readsAsCommand", body)).isFalse();
        assertThat(guard("triesCommand", body)).isFalse();
        assertThat(body).contains(": run-all someone see this (https evil.example) code b x b\n")
            .doesNotContain("@someone", "[this]", "`code`", "<b>");
        assertThat(body.lines()).noneMatch(l -> l.startsWith("/"));
    }

    @Test
    void theTriesSurviveARestart(@TempDir Path dir) throws Exception {
        service.sweep();
        Path file = dir.resolve(service.fileName());
        service.saveTo(file);

        assertThat(service.durable()).isTrue();
        assertThat(Files.readString(file)).contains("\"head\":\"" + HEAD + "\"", "\"pushed\":\"" + PUSHED + "\"",
            "\"commentId\":77", "\"followed\":false");

        service = new SuiteFixService(github, prTests, standing, chooser, mapper, Runnable::run, () -> 2_000_000L);
        service.loadFrom(file);
        service.sweep();

        assertThat(chooser.asked).hasSize(1);
        assertThat(service.stats()).containsEntry("pushed", 1).containsEntry("attempts", 1);
    }

    private String comment() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).addPrComment(eq("gh-pat"), eq(PR), body.capture());

        return body.getValue();
    }

    private static boolean guard(String name, String body) throws Exception {
        Method m = PrCommands.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);

        return (boolean)m.invoke(null, body);
    }

    private static AbandonedTestsCheck.Outcome failed(String sha, String... classes) {
        return new AbandonedTestsCheck.Outcome(State.FAILED, sha, JOB, null, List.of(classes), true);
    }

    /** Claude, as the test has it answer; it remembers what it was asked and what it was offered. */
    private static final class Chooser implements SuiteChooser {
        boolean available = true;

        Choice pick;

        final List<String> asked = new ArrayList<>();

        final List<String> offered = new ArrayList<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Choice choose(String path, String source, List<TestSuites.Suite> suites) {
            asked.add(path);
            suites.forEach(s -> offered.add(s.path()));

            return pick;
        }
    }
}
