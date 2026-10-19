package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub reports merge conflicts between PR #69 of apache/ignite-python-thin-client and the client's master. As in a
 * PR of Ignite, {@code /run-all} there queues nothing and says what to do, and {@code force} queues the run all the
 * same. The run is the suite Thin client: Python, not RunAll, and the reply names it. PR #69 of apache/ignite is
 * another PR, with its own answer from GitHub.
 */
class ConflictingClientPrCommandTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int PR = 69;

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, mock(BlockerAnalyzer.class),
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private PrCommands commands = commands(null);

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author");
        when(github.prHeadSha(CLIENT_REPO, PR)).thenReturn(CLIENT);
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn("1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c");
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335",
            "GitHubApacheIgnitePythonThinClient", "314")));
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenReturn(run());
        when(github.appComment(eq(CLIENT_REPO), eq(PR), any(), anyString())).thenReturn(
            new GithubClient.PostedComment(88L, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
        when(github.reportsConflicts(CLIENT_REPO, PR)).thenReturn(true);
    }

    @Test
    void runAllQueuesNothingAndSaysWhatToDo() {
        clientComment(1L, "/run-all");

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
        verify(tc, never()).cancelOwnBuilds(anyString(), anyString(), anyString(), any());
        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "confused");
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge"
            + " conflicts between this PR and its base branch. Resolve them and post `/run-all` again, or post"
            + " `/run-all force` to queue the suite **Thin client: Python** on the PR as it is._");
        verify(github, never()).reportsConflicts(anyInt());
    }

    @Test
    void runAllAtTopSaysHowToRunItAtTop() {
        clientComment(1L, "/run-all top");

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge"
            + " conflicts between this PR and its base branch. Resolve them and post `/run-all top` again, or post"
            + " `/run-all top force` to queue the suite **Thin client: Python** on the PR as it is._");
    }

    @Test
    void aTypoOfForceIsNamedInTheReply() {
        clientComment(1L, "/run-all forse");

        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge"
            + " conflicts between this PR and its base branch. Resolve them and post `/run-all` again, or post"
            + " `/run-all force` to queue the suite **Thin client: Python** on the PR as it is. `forse` does not count as"
            + " `force`._");
    }

    /** Another suite set up in place of ci2's: the reply names it by its id, as nothing asked TeamCity for its name. */
    @Test
    void anotherSuiteIsNamedByItsId() {
        commands = commands("PythonThinClient_Tests");

        clientComment(1L, "/run-all");

        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge"
            + " conflicts between this PR and its base branch. Resolve them and post `/run-all` again, or post"
            + " `/run-all force` to queue the suite **PythonThinClient_Tests** on the PR as it is._");
    }

    @ParameterizedTest
    @CsvSource({"/run-all force, false", "/run-all --force, false", "/run-all top force, true",
        "/run-all force top, true"})
    void forceQueuesTheSuiteWithoutAskingGithub(String typed, boolean top) {
        clientComment(1L, typed);

        verify(tc).queuePinned(eq("tc"), any(), eq(top), anyString());
        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "rocket");
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"),
            startsWith("@Author 🚀 **Thin client: Python queued"));
        verify(github, never()).reportsConflicts(anyString(), anyInt());
        verify(github, never()).reportsConflicts(anyInt());
    }

    @Test
    void aPrWithoutConflictsRunsAsBefore() {
        when(github.reportsConflicts(CLIENT_REPO, PR)).thenReturn(false);

        clientComment(1L, "/run-all");

        verify(github).reportsConflicts(CLIENT_REPO, PR);
        verify(tc).queuePinned(eq("tc"), any(), eq(false), anyString());
        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "rocket");
    }

    /** GitHub's answer for the client's PR #69 says nothing of Ignite's PR #69: there RunAll goes as usual. */
    @Test
    void igniteHasItsOwnPrSixtyNine() {
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(new TcModel.Build(9424100L, null, "queued", "pull/69/head",
            "IgniteTests24Java8_RunAll", "https://ci2.example/build/9424100", null, null, null, null, null, null, null,
            null, null, null, null, null));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            "https://github.com/apache/ignite/pull/69#issuecomment-77"));

        igniteComment(1L, "/run-all");

        verify(github).reportsConflicts(PR);
        verify(github, never()).reportsConflicts(anyString(), anyInt());
        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).appReact(PR, "author", 1L, "rocket");
    }

    private PrCommands commands(String suite) {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
            new PythonClientRuns(new PythonClientProperties(null, suite, null, null), github, tc,
                mock(BlockerAnalyzer.class),
                new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
                new TeamcityProperties("https://ci2.example/")));
    }

    private void clientComment(long id, String body) {
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(
            new GithubClient.IssueComment(id, body, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-" + id,
                Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private void igniteComment(long id, String body) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            "https://github.com/apache/ignite/pull/69#issuecomment-" + id, Instant.now().toString(),
            GhUsers.of("Author"))));
        commands.poll();
    }

    private static TcModel.Build run() {
        return new TcModel.Build(9424005L, null, "queued", "pull/69", SUITE,
            "https://ci2.example/viewLog.html?buildId=9424005", null, null, null, null, null,
            new TcModel.BuildType(SUITE, "Thin client: Python"), null, null, null, null, null, null);
    }
}
