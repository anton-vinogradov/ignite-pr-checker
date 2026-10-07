package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The re-runs of RunAll 9389046 were going when its author switched Auto-visa on in ⚙. The cutoff that keeps runs
 * finished before an option was switched on from back-filled visas was one for all options, so it moved, and the run
 * was dropped without the PR comment its author had on all along.
 */
class OptionSwitchedMidSettleTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final long now = System.currentTimeMillis() / 1000;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer, jira,
        new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, TcDates.format(now - 3_600), null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(false, true, true, false, null));
        switchedOnADayAgo();
    }

    @Test
    void theCommentComesOutWhenAnotherOptionIsSwitchedOnWhileTheReRunsGo() {
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        standing.sweep();
        assertThat(standing.waveStatus(PR, RUN_ALL)).isPresent();

        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
        standing.sweep();

        verify(github).addPrComment(eq("gh-pat"), eq(PR), contains("Settled after 1 auto re-run wave(s)"));
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(standing.buildHandled(USER, PR, RUN_ALL)).isTrue();
    }

    /** The cutoff still holds for the option switched on: the run gets the comment, but no visa back-filled. */
    @Test
    void anOptionSwitchedOnAfterTheRunFinishedDoesNotActOnIt() throws Exception {
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));

        standing.sweep();

        verify(github).addPrComment(eq("gh-pat"), eq(PR), anyString());
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());

        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        assertThat(mapper.readTree(file.toFile()).get("enrollments").get(0).get("onSince").get("VISA").asLong())
            .isGreaterThan((now - 60) * 1000);
    }

    /** Restores the user from a snapshot taken a day before the run, auto re-run and the PR comment on. */
    private void switchedOnADayAgo() throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        enrollment.put("enabledAt", (now - 86_400) * 1000);
        enrollment.remove("onSince");
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }

    private static TestVerdict blocker() {
        return new TestVerdict(9389011L, "Cache1Test.test", "Cache1", 9389011L, "Cache 1", "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
