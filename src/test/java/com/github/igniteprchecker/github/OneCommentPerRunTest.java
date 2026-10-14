package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A /run-all of someone with "Comment my runs' verdicts" on left two comments of the checker in the PR when the
 * checkstyle autofix had pushed: the verdict, and the story cut down to the autofix's note. While GitHub had not taken
 * the verdict comment yet, only one condition kept the story from posting "the verdict is ready" ahead of the verdict
 * itself, a second mention for the same run, and no test held it.
 */
class OneCommentPerRunTest {
    private static final int PR = 13440;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final String VERDICT = "@Author **[Ignite PR Checker]";

    private static final String NOTE = "🎨 _Checkstyle autofix: fixed 2 of 2 violation(s) in 1 file(s) and pushed"
        + " `abc1234` to your branch `IGNITE-28999` — `git pull` before your next push._";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), new VisaService(new TeamcityProperties("https://ci2.example/"),
            "https://checker.example"), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        styleFix, mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void aFinishedRunAllWithTheVerdictCommentOn() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        standing.linkGhLogin("author", "tc-a", GhUsers.of("Author"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28999 Fix it", null, null, null, null)));
        when(tc.triggerRunAll("tc-a", PR, false)).thenReturn(chain("queued", null));
        when(tc.getBuildState("tc-a", CHAIN)).thenReturn(chain("finished", "FAILURE"));
        when(tc.findRunAllBuildForPr("tc-a", PR)).thenReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE",
            "finished", "pull/13440/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User("author")), null, null, null, null, null)));
        when(analyzer.analyzeForAction("tc-a", PR)).thenReturn(Optional.of(new AnalysisResult(PR, CHAIN,
            "pull/13440/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140,
            1, false, 0, false, 0, 0, 0, 1, 0)));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, PR_URL + "#c77"));
        when(github.appComment(eq(PR), eq("author"), startsWith(VERDICT)))
            .thenReturn(new GithubClient.PostedComment(79L, PR_URL + "#c79"));
    }

    @Test
    void theStoryWaitsForTheVerdictCommentGithubHasNotTakenAndThenGoes() {
        standing.change("author", "tc-a", null, null, new StandingVisas.OptionChange(null, null, true, null, null));
        when(github.appComment(eq(PR), eq("author"), startsWith(VERDICT)))
            .thenThrow(new GithubClient.WriteDeferred("PR 13440 had 40 writes of the app account in the last hour"))
            .thenReturn(new GithubClient.PostedComment(79L, PR_URL + "#c79"));
        command();
        settled(() -> standing.buildHandled("author", PR, CHAIN));

        commands.updateEtas();

        verify(github, never()).appComment(eq(PR), any(), contains("the verdict of your `/run-all` is ready"));
        verify(github, never()).appDelete(anyInt(), any(), anyLong());

        settled(() -> standing.verdictCommentId("author", PR, CHAIN).isPresent());
        commands.updateEtas();

        verify(github).appDelete(PR, "author", 77L);
        verify(github, never()).appComment(eq(PR), any(), contains("the verdict of your `/run-all` is ready"));
    }

    @Test
    void theAutofixNoteGoesIntoTheVerdictCommentAndTheStoryGoes() {
        standing.change("author", "tc-a", null, "gh-pat", new StandingVisas.OptionChange(null, null, true, true,
            null));
        when(styleFix.fixForCommand(eq(PR), any(), eq("Author"))).thenReturn(NOTE);
        when(github.commentBody(79L)).thenReturn(Optional.of(VERDICT + "(https://checker.example/?pr=13440)** verdict"
            + " · RunAll build [9400000]\n"));
        command();
        settled(() -> standing.verdictCommentId("author", PR, CHAIN).isPresent());

        commands.updateEtas();

        verify(github).appEdit(PR, "author", 79L, VERDICT + "(https://checker.example/?pr=13440)** verdict · RunAll"
            + " build [9400000]\n\n" + NOTE);
        verify(github).appDelete(PR, "author", 77L);
        verify(github, never()).appComment(eq(PR), any(), contains("the verdict of your `/run-all` is ready"));
    }

    private void command() {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            PR_URL + "#issuecomment-1", Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    /** Settles the PR on the settle thread, and waits for {@code done}. */
    private void settled(BooleanSupplier done) {
        standing.settleSoon(PR);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("the run is settled").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static TcModel.Build chain(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13440/head", "IgniteTests24Java8_RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
