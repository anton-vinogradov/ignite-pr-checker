package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.PrTests;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * PR 13335's SslRenewalTest and SslContextReloadTest had no runs in RunAll 9389046, and while a newer RunAll went the
 * page put that down to "an abstract base, or a class no suite runs": SecurityTestSuite held them, and commits pushed
 * after that run had brought them in. Whether a class is in a suite now comes from Ignite's own abandoned-tests check
 * of the PR's head, and whether it changed since the run from GitHub's comparison of the run's revision with the head.
 */
class NoRunsExplainedTest {
    private static final String TOK = "tok";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String BUILT = "5be1c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8";

    private static final String HEAD = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    private static final long JOB = 51234567890L;

    private static final String RENEWAL =
        "modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java";

    private static final String RELOAD = "modules/core/src/test/java/org/apache/ignite/ssl/SslContextReloadTest.java";

    private static final String BASE = "modules/core/src/test/java/org/apache/ignite/ssl/AbstractSslReloadTest.java";

    private static final String BASIC = "modules/core/src/test/java/org/apache/ignite/ssl/GridSslBasicTest.java";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final PrTestRuns prTests = new PrTestRuns(github, tc,
        new AnalysisCache(new AnalysisProperties(null, "RunAll", null, null, null, null, null), new ObjectMapper()));

    @Test
    void classesAddedAfterTheRunAreSaidToBe() {
        files();
        check("success");
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(BUILT));
        when(github.changesBetween(BUILT, HEAD)).thenReturn(new GithubClient.Changes(Set.of(RENEWAL, RELOAD,
            "modules/core/src/test/java/org/apache/ignite/testsuites/SecurityTestSuite.java"), false, true));

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.suiteCheck().state()).isEqualTo(State.PASSED);
        assertThat(answer.suiteCheck().sha()).isEqualTo(HEAD);
        assertThat(answer.classes()).extracting(c -> c.name().substring(c.name().lastIndexOf('.') + 1),
                PrTests.TestClass::notInSuite, PrTests.TestClass::changedSinceRun)
            .containsExactly(tuple("SslRenewalTest", false, true), tuple("SslContextReloadTest", false, true),
                tuple("AbstractSslReloadTest", false, false), tuple("GridSslBasicTest", false, null));
    }

    /** A class nested in a class of the PR is named apart: the tag on the outer class would blame the wrong one. */
    @Test
    void aClassInNoSuiteIsMarkedAndTheOthersAreListed() {
        files();
        check("failure", "org.apache.ignite.ssl.SslContextReloadTest",
            "org.apache.ignite.internal.ssl.SslRenewalTest$ClientRenewal", "org.apache.ignite.ssl.SslSessionTest");
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(HEAD));

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.suiteCheck().state()).isEqualTo(State.FAILED);
        assertThat(answer.suiteCheck().elsewhere()).containsExactly(
            "org.apache.ignite.internal.ssl.SslRenewalTest$ClientRenewal", "org.apache.ignite.ssl.SslSessionTest");
        assertThat(answer.classes()).extracting(PrTests.TestClass::notInSuite, PrTests.TestClass::changedSinceRun)
            .containsExactly(tuple(false, false), tuple(true, null), tuple(false, false), tuple(false, null));
        verify(github, never()).changesBetween(anyString(), anyString());
    }

    /** Ignite's check still going says nothing of any class, and nothing more is asked to explain them. */
    @Test
    void whileTheCheckRunsNothingIsConcluded() {
        files();
        when(github.checkRuns(HEAD)).thenReturn(List.of(new GithubClient.CheckRun(JOB, "Check java code on JDK 17",
            "in_progress", null, null)));
        when(github.job(JOB)).thenReturn(new GithubClient.Job("in_progress", List.of(
            new GithubClient.Job.Step("Run abandoned tests checks.", "in_progress", null))));

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.suiteCheck().state()).isEqualTo(State.RUNNING);
        assertThat(answer.classes()).allSatisfy(c -> assertThat(c.changedSinceRun()).isNull());
        verify(tc, never()).buildRevision(anyString(), anyLong());
        verify(github, never()).changesBetween(anyString(), anyString());
    }

    /** After a rebase GitHub's comparison holds master's changes too, so a file in it may not have changed. */
    @Test
    void aRewrittenBranchTellsNothingByItsFiles() {
        files();
        check("success");
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(BUILT));
        when(github.changesBetween(BUILT, HEAD)).thenReturn(new GithubClient.Changes(Set.of(RENEWAL), true, true));

        assertThat(prTests.of(TOK, PR, CHAIN, false).classes())
            .allSatisfy(c -> assertThat(c.changedSinceRun()).isNull());
    }

    /** GitHub lists 300 files at most: a file not among them may still have changed. */
    @Test
    void aComparisonCutShortTellsOnlyOfTheFilesInIt() {
        files();
        check("success");
        when(tc.buildRevision(TOK, CHAIN)).thenReturn(Optional.of(BUILT));
        when(github.changesBetween(BUILT, HEAD)).thenReturn(new GithubClient.Changes(Set.of(RENEWAL), false, false));

        assertThat(prTests.of(TOK, PR, CHAIN, false).classes()).extracting(PrTests.TestClass::changedSinceRun)
            .containsExactly(true, null, null, null);
    }

    @Test
    void aPrThatChangesNoTestAsksNothingOfIgnitesCheck() {
        when(github.prTestFiles(PR)).thenReturn(List.of());

        assertThat(prTests.of(TOK, PR, CHAIN, false).suiteCheck()).isNull();
        assertThat(prTests.notInAnySuite(PR)).isEmpty();
        verify(github, never()).prHead(PR);
        verify(github, never()).checkRuns(anyString());
    }

    @Test
    void theVerdictIsToldOfTheClassesInNoSuite() {
        files();
        check("failure", "org.apache.ignite.ssl.SslContextReloadTest");

        assertThat(prTests.notInAnySuite(PR)).hasValueSatisfying(c -> assertThat(c.classes())
            .containsExactly("org.apache.ignite.ssl.SslContextReloadTest"));
    }

    @Test
    void theVerdictIsToldNothingOfACheckThatPassed() {
        files();
        check("success");

        assertThat(prTests.notInAnySuite(PR)).isEmpty();
    }

    /** RunAll 9389046 ran GridSslBasicTest only: the new classes came after it. */
    private void files() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(RENEWAL, "added"),
            new GithubClient.PrFile(RELOAD, "added"), new GithubClient.PrFile(BASE, "modified"),
            new GithubClient.PrFile(BASIC, "modified")));
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("someone", "someone/ignite", "ignite-28867", HEAD));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of(
            new TcModel.TestOccurrence("build:(id:9389100),id:1", "SecurityTestSuite: org.apache.ignite.ssl."
                + "GridSslBasicTest.testBasic", "SUCCESS", new TcModel.TestRef(1L), new TcModel.BuildRef(9389100L,
                null, null, null, "IgniteTests24Java8_Security", new TcModel.BuildType("IgniteTests24Java8_Security",
                "Security"), null, null), null, 900L))));
    }

    /** Ignite's job on the head, its abandoned-tests step ended as {@code conclusion}, its log naming the classes. */
    private void check(String conclusion, String... notInSuite) {
        when(github.checkRuns(HEAD)).thenReturn(List.of(new GithubClient.CheckRun(JOB, "Check java code on JDK 17",
            "completed", conclusion, "https://github.com/apache/ignite/actions/runs/18001/job/" + JOB)));
        when(github.job(JOB)).thenReturn(new GithubClient.Job("completed", List.of(
            new GithubClient.Job.Step("Run abandoned tests checks.", "completed", conclusion))));
        String[] log = Stream.concat(Stream.of("[ERROR] List of non-suited classes (" + notInSuite.length + " items):"),
            Stream.of(notInSuite).map(c -> "[ERROR] \t" + c)).toArray(String[]::new);
        when(github.jobLog(eq(JOB), any())).thenAnswer(inv -> {
            Function<Stream<String>, ?> reader = inv.getArgument(1);

            return Optional.ofNullable(reader.apply(Stream.of(log)));
        });
    }
}
