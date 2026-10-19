package com.github.igniteprchecker.github;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * After his /run-all on apache/ignite#13636, Nikolay Izhikov asked whether a PR with merge conflicts should get a
 * RunAll at all: its code has to change before it can be merged. On such a PR /run-all queues nothing and says what to
 * do, and {@code force} queues RunAll all the same. Here GitHub reports conflicts between PR 13636 and master.
 */
class ConflictingPrCommandTest {
    private static final int PR = 13636;

    private static final long CHAIN = 9500000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(chain());
        when(github.appComment(anyInt(), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, PR_URL + "#issuecomment-77"));
        when(github.reportsConflicts(PR)).thenReturn(true);
    }

    @Test
    void runAllQueuesNothingAndSaysWhatToDo() {
        comment(1L, "/run-all");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(tc, never()).cancelOwnRunAllChains(anyString(), anyInt(), anyString());
        verify(github).appReact(PR, "author", 1L, "confused");
        verify(github).appComment(PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge conflicts"
            + " between this PR and its base branch. Resolve them and post `/run-all` again, or post `/run-all force`"
            + " to queue RunAll on the PR as it is._");
    }

    @Test
    void runAllAtTopSaysHowToRunItAtTop() {
        comment(1L, "/run-all top");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).appComment(PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge conflicts"
            + " between this PR and its base branch. Resolve them and post `/run-all top` again, or post"
            + " `/run-all top force` to queue RunAll on the PR as it is._");
    }

    @ParameterizedTest
    @CsvSource({"/run-all force, false", "/run-all --force, false", "/runall FORCE, false", "/run-all top force, true",
        "/run-all force top, true", "/run-all --top --force, true"})
    void forceQueuesRunAllWithoutAskingGithub(String typed, boolean top) {
        comment(1L, typed);

        verify(tc).triggerRunAll("tc", PR, top);
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github).appComment(eq(PR), eq("author"), startsWith("@Author 🚀 **RunAll queued"));
        verify(github, never()).reportsConflicts(anyInt());
    }

    @Test
    void aPrWithoutConflictsRunsAsBefore() {
        when(github.reportsConflicts(PR)).thenReturn(false);

        comment(1L, "/run-all");

        verify(github).reportsConflicts(PR);
        verify(tc).triggerRunAll("tc", PR, false);
        verify(github).appReact(PR, "author", 1L, "rocket");
    }

    /** A typo of {@code force} does not force the run, and the reply names it next to the right spelling. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/run-all forse | forse | /run-all",
        "/run-all top froce | froce | /run-all top", "/run-all foce | foce | /run-all",
        "/run-all -force | -force | /run-all", "/run-all —force | —force | /run-all", "/run-all Force. | Force. | /run-all",
        "/run-all please top and then forse it, thanks | forse | /run-all top"})
    void aTypoOfForceIsNamedInTheReply(String typed, String typo, String again) {
        comment(1L, typed);

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).appReact(PR, "author", 1L, "confused");
        verify(github).appComment(PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge conflicts"
            + " between this PR and its base branch. Resolve them and post `" + again + "` again, or post `" + again
            + " force` to queue RunAll on the PR as it is. `" + typo + "` does not count as `force`._");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run-all for the new commit", "/run-all source", "/run-all on my fork"})
    void aWordFartherFromForceIsNoTypo(String typed) {
        comment(1L, typed);

        verify(github).appComment(PR, "author", "@Author 🚀 _Nothing was queued: GitHub reports merge conflicts"
            + " between this PR and its base branch. Resolve them and post `/run-all` again, or post `/run-all force`"
            + " to queue RunAll on the PR as it is._");
    }

    /** On a PR without conflicts {@code force} changes nothing, so a word close to it does not hold the run back. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/run-all after the forced push | false",
        "/run-all as it forces a rebuild of the cache | false", "/run-all top before the forge sync | true",
        "/run-all forse | false", "/run-all -force | false"})
    void aWordCloseToForceRunsAsBeforeWithoutConflicts(String typed, boolean top) {
        when(github.reportsConflicts(PR)).thenReturn(false);

        comment(1L, typed);

        verify(tc).triggerRunAll("tc", PR, top);
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github, never()).appReact(PR, "author", 1L, "confused");
    }

    /** /top queues nothing new: it moves the RunAll the commander's forced /run-all queued. */
    @Test
    void topMovesAForcedRun() {
        comment(1L, "/run-all force");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain());

        comment(2L, "/top");

        verify(tc).moveToQueueTop("tc", CHAIN);
        verify(github).appReact(PR, "author", 2L, "rocket");
    }

    private void comment(long id, String body) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private static TcModel.Build chain() {
        return new TcModel.Build(CHAIN, null, "queued", "pull/13636/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/9500000", null, null, null, null, null, null, null, null, null, null, null,
            null);
    }
}
