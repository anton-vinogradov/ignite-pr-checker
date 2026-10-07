package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.github.igniteprchecker.web.JiraController;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Ten of the fourteen comments of 13335 were verdicts. "No blockers" of ec2c458 stood while the head was f195e0c,
 * no verdict named the commit it tested, and nothing set the old red ones apart from the newest. The visas in JIRA
 * named no commit either.
 */
class VerdictRevisionTest {
    private static final String AUTHOR = "avinogradov";

    private static final String REVIEWER = "nizhikov";

    private static final int PR = 13335;

    private static final long FIRST = 9389046L;

    private static final long SECOND = 9391200L;

    private static final String ISSUE = "IGNITE-28867";

    private static final String TESTED = "ec2c458b1d6a4e0f9c3b2a1d0e9f8c7b6a5d4e3f";

    private static final String MARK = "\n\n🔁 _Superseded by the newer RunAll [" + SECOND + "](https://ci2.example/build/"
        + SECOND + ") — its verdict is on [the checker's page](https://checker.example/?pr=" + PR + ")._";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, jira, visas, tracker,
        mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.ghUser("reviewer-gh-pat")).thenReturn(Optional.of("nizhikov"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.addPrComment(anyString(), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"),
                new GithubClient.PostedComment(556L, "https://github.com/apache/ignite/pull/13335#c556"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));
        when(tc.buildRevision(anyString(), anyLong())).thenReturn(Optional.of(TESTED));
        standing.change(AUTHOR, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, false, true, false,
            null));
    }

    @Test
    void theVerdictNamesTheCommitItTested() {
        finished(FIRST, AUTHOR);

        standing.sweep();

        verify(github).addPrComment(eq("gh-pat"), eq(PR), contains("· tested [ec2c458](https://github.com/apache/ignite/"
            + "pull/13335/commits/" + TESTED + ") ·"));
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), contains("· tested [ec2c458|https://github.com/apache/"
            + "ignite/pull/13335/commits/" + TESTED + "] ·"));
    }

    @Test
    void theOneShotAndTheManualVisaNameTheCommitToo() {
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(verdict(FIRST)));
        when(analyzer.analyze("tc", PR)).thenReturn(Optional.of(verdict(FIRST)));
        when(jira.addComment(anyString(), eq(ISSUE), anyString())).thenReturn("https://issues.example/" + ISSUE);
        Warmer warmer = mock(Warmer.class);
        when(warmer.borrowToken()).thenReturn("tc");
        StandingVisas noStanding = mock(StandingVisas.class);
        when(noStanding.visaCover(any(), anyInt(), anyLong(), any())).thenReturn(StandingVisas.VisaCover.NONE);
        VisaSubscriptions subs = new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer,
            mock(PendingCommits.class), tc, noStanding);
        subs.arm(PR, ISSUE, "reviewer-jira-pat", REVIEWER);

        subs.settle(PR, FIRST, System.currentTimeMillis());
        new JiraController(jira, tc, analyzer, codec, visas, subs, standing, github, mock(PendingCommits.class), false)
            .visa(PR, ISSUE, "tc", "jira-pat");

        String tested = "· tested [ec2c458|https://github.com/apache/ignite/pull/13335/commits/" + TESTED + "] ·";
        verify(jira).addComment(eq("reviewer-jira-pat"), eq(ISSUE), contains(tested));
        verify(jira).addComment(eq("jira-pat"), eq(ISSUE), contains(tested));
    }

    @Test
    void theOlderVerdictIsMarkedOnceANewerRunFinishes() {
        finished(FIRST, AUTHOR);
        standing.sweep();
        when(github.commentBody(555L)).thenReturn(Optional.of("verdict of " + FIRST + "\n"));

        finished(SECOND, AUTHOR);
        standing.sweep();
        standing.sweep();

        verify(github, times(1)).updatePrComment("gh-pat", 555L, "verdict of " + FIRST + MARK);
        verify(github, never()).updatePrComment(anyString(), eq(556L), anyString());
    }

    @Test
    void anotherUsersVerdictIsMarkedWithTheirOwnToken() {
        standing.change(REVIEWER, "reviewer-tc", null, "reviewer-gh-pat",
            new StandingVisas.OptionChange(false, false, true, false, null));
        finished(FIRST, REVIEWER);
        standing.sweep();
        verify(github).addPrComment(eq("reviewer-gh-pat"), eq(PR), anyString());
        when(github.commentBody(555L)).thenReturn(Optional.of("verdict of " + FIRST));

        finished(SECOND, AUTHOR);
        standing.sweep();
        standing.sweep();

        verify(github).updatePrComment("reviewer-gh-pat", 555L, "verdict of " + FIRST + MARK);
        verify(github, times(1)).commentBody(555L);
    }

    /**
     * GitHub failed the first read of the old comment, and the verdict of the newer run took its place in the same
     * settle: the old one was never marked.
     */
    @Test
    void aFailedFirstTryIsTriedAgain() {
        finished(FIRST, AUTHOR);
        standing.sweep();
        when(github.commentBody(555L)).thenThrow(new IllegalStateException("GitHub 502"))
            .thenReturn(Optional.of("verdict of " + FIRST));

        finished(SECOND, AUTHOR);
        standing.sweep();
        standing.sweep();
        standing.sweep();

        verify(github, times(1)).updatePrComment("gh-pat", 555L, "verdict of " + FIRST + MARK);
        verify(github, times(2)).commentBody(555L);
    }

    @Test
    void aCommentStillToMarkOutlivesARestart(@TempDir Path dir) throws Exception {
        finished(FIRST, AUTHOR);
        standing.sweep();
        when(github.commentBody(555L)).thenThrow(new IllegalStateException("GitHub 502"));
        finished(SECOND, AUTHOR);
        standing.sweep();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = new StandingVisas(mapper, codec, tc, github, analyzer, jira, visas, tracker,
            mock(Warmer.class), mock(PendingCommits.class));
        restarted.loadFrom(file);
        doReturn(Optional.of("verdict of " + FIRST)).when(github).commentBody(555L);

        restarted.sweep();

        verify(github).updatePrComment("gh-pat", 555L, "verdict of " + FIRST + MARK);
        assertThat(mapper.readTree(file.toFile()).get("enrollments").get(0).get("ghThreads").get(String.valueOf(PR))
            .get("unmarked").toString()).isEqualTo("[555]");
    }

    /** A living comment from before this release is still redone while its re-runs go: it names the commit too. */
    @Test
    void anInterimVerdictNamesTheCommit(@TempDir Path dir) throws Exception {
        standing.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).putObject("ghThreads").putObject(String.valueOf(PR))
            .put("buildId", FIRST).put("commentId", 501L);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
        when(tracker.hasActive(PR)).thenReturn(true);
        finished(FIRST, AUTHOR);

        standing.sweep();

        verify(github).updatePrComment(eq("gh-pat"), eq(501L), contains("· tested [ec2c458](https://github.com/apache/"
            + "ignite/pull/13335/commits/" + TESTED + ") ·"));
    }

    @Test
    void aCommentThatSaysANewerRunReplacedItIsLeftAsItIs() {
        finished(FIRST, AUTHOR);
        standing.sweep();
        when(github.commentBody(555L)).thenReturn(Optional.of("verdict of " + FIRST + "\n\n🛑 _Re-runs stopped: RunAll "
            + SECOND + " replaced this run before they settled._"));

        finished(SECOND, AUTHOR);
        standing.sweep();
        standing.sweep();

        verify(github, never()).updatePrComment(anyString(), eq(555L), anyString());
        verify(github, times(1)).commentBody(555L);
    }

    /** standing-visas.json of v1.20.11 knows neither whether a comment is final nor whether it was marked. */
    @Test
    void aVerdictCommentFromAnOlderReleaseIsMarkedToo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        enrollment.putObject("posted").put(String.valueOf(PR), FIRST);
        enrollment.putObject("ghThreads").putObject(String.valueOf(PR)).put("buildId", FIRST).put("commentId", 501L);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
        when(github.commentBody(501L)).thenReturn(Optional.of("verdict of " + FIRST));

        finished(SECOND, AUTHOR);
        standing.sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).updatePrComment(eq("gh-pat"), eq(501L), body.capture());
        assertThat(body.getValue()).isEqualTo("verdict of " + FIRST + MARK);
    }

    private void finished(long buildId, String by) {
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(buildId, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(by)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(buildId)));
    }

    private static AnalysisResult verdict(long buildId) {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master run(s)", "FF", 2);

        return new AnalysisResult(PR, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
