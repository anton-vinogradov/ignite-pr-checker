package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.mirror.GitMirror;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

class MirroredPrCommandTest {
    private static final int PR = 13800;

    private static final String HEAD = "a".repeat(40);

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final GitMirror mirror = mock(GitMirror.class);

    private final StyleFixService style = mock(StyleFixService.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        style, mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
        null, new IgnitePrRuns(github, tc, mirror,
            new AnalysisProperties(null, "RunAll", null, null, null, null, null)));

    @BeforeEach
    void aCommandWithStyleFixEnabled() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true,
            true));
        standing.firstCommand("author");
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L,
            "/run-all", "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of("Author"))));
        when(github.prHeadSha(PR)).thenReturn(HEAD);
        TcModel.Build build = new TcModel.Build(9400000L, null, "queued", "pull/13800/head", "RunAll", null, null,
            null, null, null, null, null, null, null, null, null, null, null);
        when(tc.queuePinned(eq("tc"), any(), eq(false), anyString())).thenReturn(build);
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(build);
    }

    /** A style fix may push a commit: read and mirror the resulting head before replacing the previous chain. */
    @Test
    void styleFixAndMirrorFinishBeforeReplacement() {
        when(mirror.enabled()).thenReturn(true);

        commands.poll();

        InOrder order = inOrder(style, github, mirror, tc);
        order.verify(style).fixForCommand(eq(PR), any(), eq("Author"));
        order.verify(github).prHeadSha(PR);
        order.verify(mirror).ensurePr("ignite", PR, HEAD);
        order.verify(tc).cancelOwnRunAllChains("tc", PR, "author");
        order.verify(tc).queuePinned("tc", new PinnedRun("RunAll", "pull/13800/head",
            List.of(PinnedRun.Pin.forRoot("GitHubApacheIgnite", "refs/pull/13800/head", HEAD)), Map.of()), false,
            "Triggered by Ignite PR Checker");
    }

    /** If fetching the fixed head fails, the command leaves the previous chain and its reruns running. */
    @Test
    void failedMirrorPreparationDoesNotReplaceThePreviousRun() {
        when(mirror.enabled()).thenReturn(true);
        doThrow(new GitMirror.NotReadyException("PR head changed while fetching"))
            .when(mirror).ensurePr("ignite", PR, HEAD);

        commands.poll();

        verify(style).fixForCommand(eq(PR), any(), eq("Author"));
        verify(tc, never()).cancelOwnRunAllChains(anyString(), anyInt(), anyString());
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    /** Disabled mirrors keep the established replacement, style-fix and branch-based RunAll order. */
    @Test
    void disabledMirrorPreservesTheCommandOrder() {
        commands.poll();

        InOrder order = inOrder(style, tc);
        order.verify(tc).cancelOwnRunAllChains("tc", PR, "author");
        order.verify(style).fixForCommand(eq(PR), any(), eq("Author"));
        order.verify(tc).triggerRunAll("tc", PR, false);
        verify(github, never()).prHeadSha(anyInt());
    }
}
