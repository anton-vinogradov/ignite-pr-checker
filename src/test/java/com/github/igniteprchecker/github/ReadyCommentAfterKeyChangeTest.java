package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The author of PR 13440 has Visa my runs in JIRA and PR commands on, and Post my runs' verdicts in the PR off. The
 * operator changed SESSION_SECRET, and the author logged in again: the TeamCity token is back, the JIRA token is not.
 * Their /run-all settled, the visa waited for a new JIRA token, and the one comment that brought the verdict said
 * nothing of it. That comment now says why no visa came.
 */
class ReadyCommentAfterKeyChangeTest {
    private static final int PR = 13440;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theReadyCommentSaysWhyNoVisaCame(@TempDir Path dir) throws Exception {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Author"));
        StandingVisas before = standing("old-secret");
        before.linkGhLogin("author", "tc-a", GhUsers.of("Author"));
        before.change("author", "tc-a", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null, null));
        Path file = dir.resolve("standing-visas.json");
        before.saveTo(file);
        StandingVisas standing = standing("new-secret");
        standing.loadFrom(file);
        standing.tcTokenAccepted("author", "tc-a");
        PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
            mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
            new TeamcityProperties("https://ci2.example/"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28999 Fix it", null, null, null, null)));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L, PR_URL
            + "#c"));
        when(tc.triggerRunAll("tc-a", PR, false)).thenReturn(chain("queued", null));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            PR_URL + "#issuecomment-1", Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
        when(tc.findRunAllBuildForPr("tc-a", PR)).thenReturn(Optional.of(new TcModel.Build(CHAIN, "SUCCESS",
            "finished", "pull/13440/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User("author")), null, null, null, null, null)));
        when(analyzer.analyzeForAction("tc-a", PR)).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13440/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140, 1,
            false, 0, false, 0, 0, 0, 1, 0)));
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain("finished", "SUCCESS"));
        standing.settleRequested(PR);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!standing.buildHandled("author", PR, CHAIN)) {
            assertThat(System.currentTimeMillis()).as("the run is settled").isLessThan(deadline);
            Thread.onSpinWait();
        }

        commands.updateEtas();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(PR, "author", "@Author the verdict of your `/run-all` is ready: RunAll [" + CHAIN
            + "](https://ci2.example/build/" + CHAIN + ") — [see the verdict](https://checker.example/?pr=" + PR
            + "). No JIRA visa yet: the server key was changed, so the checker could no longer read the JIRA token it"
            + " stored. The visa goes out once whoever started the run saves a new token in ⚙.");
    }

    private StandingVisas standing(String secret) {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, secret), mapper), tc, github,
            analyzer, jira, new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }

    private static TcModel.Build chain(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13440/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
