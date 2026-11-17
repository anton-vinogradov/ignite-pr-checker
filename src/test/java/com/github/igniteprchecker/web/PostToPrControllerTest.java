package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import com.github.igniteprchecker.github.ClientRuns;
import com.github.igniteprchecker.github.ClientSpec;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.ThinClients;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

/**
 * Post to PR on the page of apache/ignite#13709: after the author re-ran the suite with the blocker on the PR's head,
 * a reviewer puts the verdict of RunAll 9425992 as it is now into the PR. It goes only while nothing can change the
 * verdict, as for Post to JIRA, and only while the checker's account can write to GitHub; the comment mentions bob,
 * who started the run, as the run's own verdict comment does. The answer says what GitHub said. The page of PR #69 of
 * the Python thin client puts in the verdict of its run of the client's suite, in the words of its verdict comment.
 */
class PostToPrControllerTest {
    private static final ThinClients PROJECTS = new ThinClients(
        new PythonClientProperties(null, null, null, null), new ThinClientProperties(null, null));

    private static final int PR = 13709;

    private static final long RUN = 9425992L;

    private static final String URL = "https://github.com/apache/ignite/pull/13709#issuecomment-4100";

    private static final int CLIENT_PR = 69;

    private static final long CLIENT_RUN = 9424200L;

    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private final StandingVisas standing = mock(StandingVisas.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final MergedVerdicts merged = mock(MergedVerdicts.class);

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final ClientVerdicts clients = mock(ClientVerdicts.class);

    private final PrCommands commands = mock(PrCommands.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(github.repo()).thenReturn("apache/ignite");
        when(github.writes()).thenReturn(true);
        when(github.canWrite()).thenReturn(true);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29100 Fix the control utility", null, null,
            null, null)));

        when(standing.phase(eq(PR), eq(RUN), any())).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(commands.clientPhase(PROJECTS.require("python"), CLIENT_PR, CLIENT_RUN)).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(analyzer.analyzeForAction("tok", PR)).thenReturn(Optional.of(result(PR, RUN, false)));
        when(tc.buildEnd("tok", RUN)).thenReturn(Optional.of(started("bob")));
        when(standing.postCommentFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), any(), any(), any()))
            .thenReturn(new StandingVisas.CommentPost(StandingVisas.CommentPost.POSTED, URL, 0));

        ClientRuns python = new ClientRuns(github, tc,
            analyzer, new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
            new TeamcityProperties("https://ci2.example/"));
        ClientPrsController clientPrs = new ClientPrsController(clients, python, tc, github,
            new PrHeads(github, () -> 1_800_000_000_000L), new YourPrs(github, standing), standing, commands,
            mock(AuthInterceptor.class), PROJECTS);
        PostVisaController visas = new PostVisaController(standing, analyzer, merged, tc,
            new PrTitleController(github, merged), clients, python, PROJECTS, commands, clientPrs);
        JiraController runs = new JiraController(mock(JiraClient.class), tc, standing, merged,
            mock(UserDirectory.class));
        mvc = MockMvcBuilders.standaloneSetup(new PostToPrController(standing, github, visas, runs, python, clients, PROJECTS,
                "https://checker.example"))
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)))
            .build();
    }

    @Test
    void aFinalVerdictGoesToThePrAndMentionsWhoStartedTheRun() throws Exception {
        asAlice(post("/api/pr-comment"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bot").value("ready"))
            .andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.edited").value(false));

        verify(standing).postCommentFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN),
            eq(result(PR, RUN, false)), eq("bob"), any());
    }

    /** The press updated the run's verdict comment: the answer says so, and the page says it in its words. */
    @Test
    void anUpdateOfTheRunsCommentIsSaid() throws Exception {
        when(standing.postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any()))
            .thenReturn(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, URL, 0));

        asAlice(post("/api/pr-comment")).andExpect(status().isOk()).andExpect(jsonPath("$.edited").value(true));
    }

    /** TeamCity did not say who started the run: the verdict still goes, and mentions nobody. */
    @Test
    void aStarterTeamCityDoesNotNameCostsOnlyTheMention() throws Exception {
        when(tc.buildEnd("tok", RUN)).thenThrow(new IllegalStateException("TeamCity answered 500"));

        asAlice(post("/api/pr-comment")).andExpect(status().isOk());

        verify(standing).postCommentFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), any(), isNull(), any());
    }

    /** A checker without GITHUB_TOKEN has no account to write with: nothing is looked up or posted. */
    @Test
    void withoutAnAccountToWriteWithNothingIsLookedUp() throws Exception {
        when(github.writes()).thenReturn(false);

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.bot").value("none"))
            .andExpect(jsonPath("$.error").value("This checker has no GitHub account to write with (GITHUB_TOKEN), so"
                + " it cannot post to the PR. Its operator can add one."));

        verifyNoInteractions(analyzer);
        verify(standing, never()).postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /** GitHub refuses the checker's token, or asked it to wait: each is said apart, and nothing is posted. */
    @Test
    void aRefusedOrPausedAccountPostsNothing() throws Exception {
        when(github.canWrite()).thenReturn(false);
        when(github.writeState()).thenReturn(new GithubClient.WriteState(0, null, 0, List.of()));

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.bot").value("refused"))
            .andExpect(jsonPath("$.error").value("GitHub refuses the token the checker writes with (GITHUB_TOKEN), so"
                + " the checker cannot post to the PR now."));

        when(github.writeState()).thenReturn(new GithubClient.WriteState(1_800_000_060_000L, null, 0, List.of()));

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.bot").value("paused"))
            .andExpect(jsonPath("$.error").value("GitHub asked the checker to wait before it writes again. Try again"
                + " later."));
        verify(standing, never()).postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /** An auto re-run settles the run, then a suite re-run of the PR goes: either may still change the verdict. */
    @Test
    void aVerdictRunsCanStillChangeWaits() throws Exception {
        when(standing.phase(eq(PR), eq(RUN), any())).thenReturn(phase(StandingVisas.Phase.SETTLING));

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.going").value(true))
            .andExpect(jsonPath("$.error").value("Post to PR waits for the final verdict: runs of this PR can still"
                + " change it."));

        when(standing.phase(eq(PR), eq(RUN), any())).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(tc.currentUserBuilds("tok", PR)).thenReturn(List.of(new TcModel.Build(9426316L, null, "running", null,
            "IgniteTests24Java8_ControlUtility5", null, null, null, null, null, null, null, null, null, null, null,
            null, null)));

        asAlice(post("/api/pr-comment")).andExpect(status().isConflict()).andExpect(jsonPath("$.going").value(true));
        verify(standing, never()).postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void aVerdictTeamCityErrorsLeftUncheckedWaits() throws Exception {
        when(analyzer.stillRetrying(any())).thenReturn(true);

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("TeamCity errors left part of this verdict unchecked. The checker"
                + " checks that part again, and Post to PR waits up to 20 minutes for that."));
    }

    @Test
    void aRunOlderThanTheCheckersIsNotPosted() throws Exception {
        when(analyzer.analyzeForAction("tok", PR)).thenReturn(Optional.of(result(PR, RUN + 400, false)));

        asAlice(post("/api/pr-comment"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.stale").value(true))
            .andExpect(jsonPath("$.error").value("The checker has a newer verdict of this PR than the page shows."
                + " Reload the page, then post the verdict."));
    }

    /** #13709 is merged: the verdict kept at the merge goes, once the runs that went at the merge are counted. */
    @Test
    void aMergedPrsVerdictIsTheOneKeptAtTheMerge() throws Exception {
        AnalysisResult atMerge = result(PR, RUN, false);
        when(merged.verdict(PR)).thenReturn(Optional.of(atMerge));
        when(analyzer.runsAtMergeDue(PR)).thenReturn(true);

        asAlice(post("/api/pr-comment")).andExpect(status().isConflict()).andExpect(jsonPath("$.going").value(true));

        when(analyzer.runsAtMergeDue(PR)).thenReturn(false);

        asAlice(post("/api/pr-comment")).andExpect(status().isOk());
        verify(analyzer, never()).analyzeForAction(anyString(), anyInt());
    }

    @Test
    void theAnswerSaysWhatGithubSaid() throws Exception {
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.DEFERRED, null, 0))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("The checker's GitHub account cannot write to this PR now: it reached"
                + " an hourly limit of writes, or GitHub asked it to wait. Try again later."));
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.FAILED, null, 403))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("GitHub did not let the checker's account comment on PR #13709 (HTTP"
                + " 403)."));
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.FAILED, null, 401))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.bot").value("refused"))
            .andExpect(jsonPath("$.error").value("GitHub refused the token the checker writes with (GITHUB_TOKEN), so"
                + " the comment did not go in."));
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.FAILED, null, 0))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("GitHub did not answer — try again in a moment"));
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.FAILED, null, 500))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("GitHub did not take the comment (HTTP 500). Try again later."));
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.NO_BOT, null, 0))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.bot").value("none"));
    }

    /** The PR had 5 presses in the last hour: the answer says that the checker takes no more for now. */
    @Test
    void aPressPastTheCapOfThePrIsRefused() throws Exception {
        answers(new StandingVisas.CommentPost(StandingVisas.CommentPost.CAPPED, null, 0))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("The checker took 5 presses of Post to PR on this PR in the last hour,"
                + " the most it allows. Try again later."));
    }

    /**
     * PR #69's verdict counts the auto re-run 9424210 of its run, which bob started. The comment has the words of the
     * client's verdict comment: it mentions bob, names both runs and links the checker's page. Its first words say how
     * the run was queued only when the checker knows it, and no line speaks of a visa.
     */
    @Test
    void aClientRunsVerdictIsInTheWordsOfItsVerdictComment() throws Exception {
        when(clients.verdict("tok", PROJECTS.require("python"), CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(
            result(CLIENT_PR, CLIENT_RUN, false), new ClientVerdicts.Run(CLIENT_RUN, 9424210L, "Thin client: Python",
                "bob", "a1b2c3d4e5f6", "1f2e3d4c5b6a"))));
        when(standing.provenGhLogin("bob")).thenReturn("bob-gh");
        when(standing.postClientCommentFromPage(eq("alice"), any(), eq(CLIENT_REPO), eq(CLIENT_PR), eq(CLIENT_RUN),
            eq(9424210L), any(), any(), any())).thenReturn(new StandingVisas.CommentPost(
                StandingVisas.CommentPost.POSTED, URL, 0));

        asAlice(post("/api/client/pr-comment").param("repo", "python"), CLIENT_PR, CLIENT_RUN).andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value(URL));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<Boolean, String>> body = ArgumentCaptor.forClass(Function.class);
        verify(standing).postClientCommentFromPage(eq("alice"), any(), eq(CLIENT_REPO), eq(CLIENT_PR), eq(CLIENT_RUN),
            eq(9424210L), any(), body.capture(), any());
        assertThat(body.getValue().apply(null))
            .startsWith("@bob-gh the verdict of your run: **Thin client: Python** [build 9424200]"
                + "(https://ci2.example/build/9424200) ran this PR's head [`a1b2c3d`](https://github.com/" + CLIENT_REPO
                + "/pull/69/commits/a1b2c3d4e5f6) against Ignite master at [`1f2e3d4`](https://github.com/apache/ignite"
                + "/commit/1f2e3d4c5b6a). The checker's page shows this verdict and the PR's runs: [Ignite PR Checker]"
                + "(https://checker.example/python?pr=69).")
            .contains("♻️ This verdict counts [build 9424210](https://ci2.example/build/9424210) too: a later run of"
                + " the suite on the same commits.")
            .doesNotContain("📝");
        assertThat(body.getValue().apply(true))
            .startsWith("@bob-gh the verdict of the run you queued on the checker's page: **Thin client: Python**");
        assertThat(body.getValue().apply(false)).startsWith("@bob-gh the verdict of your `/run-all`: ");
    }

    /** Given a final client run, when its comment is requested, then it uses that repository and project link. */
    @ParameterizedTest
    @ValueSource(strings = {"python", "php", "nodejs"})
    void postsToTheSelectedClientProject(String project) throws Exception {
        ClientSpec spec = PROJECTS.require(project);
        AnalysisResult res = new AnalysisResult(CLIENT_PR, CLIENT_RUN, spec.branch(CLIENT_PR), 1, List.of(),
            List.of(), List.of(), List.of(), List.of(), 1, 1, false, 0, false, 0, 0, 0, 1, 0);
        when(clients.verdict("tok", spec, CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(res,
            new ClientVerdicts.Run(CLIENT_RUN, 0, spec.suiteLabel(), "bob", "a".repeat(40), "b".repeat(40)))));
        when(commands.clientPhase(spec, CLIENT_PR, CLIENT_RUN)).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(standing.postClientCommentFromPage(eq("alice"), any(), eq(spec.repo()), eq(CLIENT_PR), eq(CLIENT_RUN),
            eq(0L), any(), any(), any())).thenReturn(new StandingVisas.CommentPost(StandingVisas.CommentPost.POSTED, URL, 0));

        asAlice(post("/api/client/pr-comment").param("repo", project), CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<Boolean, String>> body = ArgumentCaptor.forClass(Function.class);
        verify(standing).postClientCommentFromPage(eq("alice"), any(), eq(spec.repo()), eq(CLIENT_PR),
            eq(CLIENT_RUN), eq(0L), any(), body.capture(), any());
        assertThat(body.getValue().apply(true)).contains("https://github.com/" + spec.repo() + "/pull/69",
            "https://checker.example/" + project + "?pr=69", spec.suiteLabel());
    }

    /** Given an invalid project, when a client comment is requested, then no other project's PR is used. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown"})
    void refusesClientCommentWithoutAKnownProject(String project) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/client/pr-comment");
        if (project != null)
            request.param("repo", project);

        asAlice(request, CLIENT_PR, CLIENT_RUN).andExpect(status().isBadRequest());

        verify(standing, never()).postClientCommentFromPage(any(), any(), any(), anyInt(), anyLong(), anyLong(), any(), any(),
            any());
    }

    /** A run of the client's suite on the PR's branch is going: it may change the verdict, so nothing goes. */
    @Test
    void aClientRunOfTheSuiteGoingHoldsTheVerdict() throws Exception {
        when(clients.verdict("tok", PROJECTS.require("python"), CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(
            result(CLIENT_PR, CLIENT_RUN, false), new ClientVerdicts.Run(CLIENT_RUN, 0, "Thin client: Python", "bob",
                null, null))));
        when(tc.currentUserBuilds("tok", "pull/69")).thenReturn(List.of(new TcModel.Build(9424300L, null, "queued",
            null, "IgniteTests24Java8_WiPThinClientPython", null, null, null, null, null, null, null, null, null, null,
            null, null, null)));

        asAlice(post("/api/client/pr-comment").param("repo", "python"), CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.going").value(true));
        verify(standing, never()).postClientCommentFromPage(any(), any(), any(), anyInt(), anyLong(), anyLong(), any(), any(),
            any());
    }

    /** The row the page polls says whether the checker can write, and where the run's comment is. */
    @Test
    void theRowTellsWhatPostToPrCanDo() throws Exception {
        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.of(URL));
        when(clients.kept(PROJECTS.require("python"), CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(
            result(CLIENT_PR, CLIENT_RUN, false), new ClientVerdicts.Run(CLIENT_RUN, 9424210L, "Thin client: Python",
                "bob", null, null))));
        when(standing.runCommentUrl(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 9424210L)).thenReturn(Optional.empty());

        asAlice(get("/api/pr-comment"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bot").value("ready"))
            .andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.edited").doesNotExist());
        asAlice(get("/api/client/pr-comment").param("repo", "python"), CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").doesNotExist());

        verify(standing).runCommentUrl(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 9424210L);
        verifyNoInteractions(analyzer);
    }

    /**
     * The run has no verdict comment yet: the row says whose Post my runs' verdicts in the PR still posts it. With a
     * comment in the PR, or no account to post from, nobody's option is looked up.
     */
    @Test
    void theRowTellsWhoseOptionStillPostsTheComment() throws Exception {
        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.empty());
        when(standing.commentLine(eq("alice"), eq(PR), eq(RUN), any(), eq(false)))
            .thenReturn(new StandingVisas.CommentLine("bob", false, true));

        asAlice(get("/api/pr-comment"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bot").value("ready"))
            .andExpect(jsonPath("$.url").doesNotExist())
            .andExpect(jsonPath("$.starter").value("bob"))
            .andExpect(jsonPath("$.mine").value(false))
            .andExpect(jsonPath("$.auto").value(true));

        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.of(URL));
        asAlice(get("/api/pr-comment")).andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.auto").doesNotExist());

        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.empty());
        when(github.writes()).thenReturn(false);
        asAlice(get("/api/pr-comment")).andExpect(jsonPath("$.bot").value("none"))
            .andExpect(jsonPath("$.auto").doesNotExist());

        verify(standing, times(1)).commentLine(any(), anyInt(), anyLong(), any(), anyBoolean());
    }

    /**
     * The run has a verdict comment: the row says whether it still says what the verdict says, so that the page offers
     * Update only when it does not, and says nothing where the checker cannot tell. The verdict kept of a merged PR is
     * the one compared with. A press just posted the verdict as it is now: its answer says the comment is current.
     */
    @Test
    void theRowTellsWhetherTheRunsCommentIsStillCurrent() throws Exception {
        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.of(URL));
        when(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).thenReturn(null);
        asAlice(get("/api/pr-comment")).andExpect(jsonPath("$.current").doesNotExist());

        when(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).thenReturn(false);
        asAlice(get("/api/pr-comment")).andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.current").value(false));

        AnalysisResult atMerge = result(PR, RUN, false);
        when(merged.verdict(PR)).thenReturn(Optional.of(atMerge));
        when(standing.commentCurrent(null, PR, RUN, 0, Optional.of(atMerge))).thenReturn(true);
        asAlice(get("/api/pr-comment")).andExpect(jsonPath("$.current").value(true));

        when(merged.verdict(PR)).thenReturn(Optional.empty());
        asAlice(post("/api/pr-comment")).andExpect(status().isOk()).andExpect(jsonPath("$.current").value(true));
    }

    /**
     * TeamCity does not answer who started the run. The row still says that the checker can post and that the run has
     * no comment yet, so Post to PR works; it says nothing of the starter's option.
     */
    @Test
    void aStarterTeamCityDoesNotNameCostsTheRowOnlyTheOption() throws Exception {
        when(standing.runCommentUrl(null, PR, RUN, 0)).thenReturn(Optional.empty());
        when(tc.buildEnd("tok", RUN)).thenThrow(new ResourceAccessException("Connection reset"));
        when(standing.commentLine(eq("alice"), eq(PR), eq(RUN), any(), eq(false))).thenAnswer(call -> {
            StandingVisas.Run run = call.<Supplier<StandingVisas.Run>>getArgument(3).get();

            return new StandingVisas.CommentLine(run.starter(), false, true);
        });

        asAlice(get("/api/pr-comment"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bot").value("ready"))
            .andExpect(jsonPath("$.url").doesNotExist())
            .andExpect(jsonPath("$.starter").doesNotExist())
            .andExpect(jsonPath("$.auto").doesNotExist());
    }

    /**
     * Before the press the page shows the comment as it would go: the preview composes the text the post sends, for
     * the verdict as it is now, mentioning bob, who started the run. Nothing is posted, and the press is not counted.
     * It gives the key of that text, which the press sends back.
     */
    @Test
    void thePreviewShowsTheCommentAndPostsNothing() throws Exception {
        String comment = "@bob-gh the verdict\n\n_Posted by ci2 user alice from the checker's page._";
        when(standing.commentPreview("alice", "tok", PR, RUN, result(PR, RUN, false), "bob")).thenReturn(comment);

        asAlice(get("/api/pr-comment/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value(comment))
            .andExpect(jsonPath("$.key").value(StandingVisas.textKey(comment)));

        verify(standing, never()).postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /**
     * The press carries the key of the text the dialog showed, and the comment goes only as that text. When the
     * comment is another text by then, nothing is posted, and the answer says to press again; the same for a client's
     * run.
     */
    @Test
    void aCommentThatIsNotTheTextThePageShowedIsRefused() throws Exception {
        StandingVisas.CommentPost changed = new StandingVisas.CommentPost(StandingVisas.CommentPost.CHANGED, null, 0);
        when(standing.postCommentFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), any(), eq("bob"),
            eq("key-of-the-old-text"))).thenReturn(changed);

        asAlice(post("/api/pr-comment").param("key", "key-of-the-old-text"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.error").value("The text changed after the page showed it to you. Press the button"
                + " again to read it as it is now."));

        when(clients.verdict("tok", PROJECTS.require("python"), CLIENT_PR)).thenReturn(Optional.of(
            new ClientVerdicts.Verdict(result(CLIENT_PR, CLIENT_RUN, false), new ClientVerdicts.Run(CLIENT_RUN, 0,
                "Thin client: Python", "bob", "a1b2c3d4e5f6", "1f2e3d4c5b6a"))));
        when(standing.postClientCommentFromPage(eq("alice"), any(), eq(CLIENT_REPO), eq(CLIENT_PR), eq(CLIENT_RUN),
            eq(0L), any(), any(), eq("key-of-the-old-text"))).thenReturn(changed);

        asAlice(post("/api/client/pr-comment").param("repo", "python").param("key", "key-of-the-old-text"),
            CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.changed").value(true));
    }

    /** What keeps a verdict from being posted keeps it from being shown, with the same answer. */
    @Test
    void thePreviewIsRefusedAsThePostIs() throws Exception {
        when(standing.phase(eq(PR), eq(RUN), any())).thenReturn(phase(StandingVisas.Phase.SETTLING));
        asAlice(get("/api/pr-comment/preview")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.going").value(true));

        when(standing.phase(eq(PR), eq(RUN), any())).thenReturn(phase(StandingVisas.Phase.FINAL));
        asAlice(get("/api/pr-comment/preview"), PR, 9000000L).andExpect(status().isConflict())
            .andExpect(jsonPath("$.stale").value(true));

        when(github.canWrite()).thenReturn(false);
        when(github.writeState()).thenReturn(new GithubClient.WriteState(0, null, 0, List.of()));
        asAlice(get("/api/pr-comment/preview")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.bot").value("refused"));
        asAlice(get("/api/client/pr-comment/preview").param("repo", "python"), CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.bot").value("refused"));

        verify(standing, never()).commentPreview(any(), any(), anyInt(), anyLong(), any(), any());
        verify(standing, never()).clientCommentPreview(any(), any(), anyInt(), anyLong(), anyLong(), any());
    }

    /** The preview of a client's run is the body of its verdict comment, as the post composes it. */
    @Test
    void theClientPreviewShowsTheVerdictOfItsSuiteRun() throws Exception {
        when(clients.verdict("tok", PROJECTS.require("python"), CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(
            result(CLIENT_PR, CLIENT_RUN, false), new ClientVerdicts.Run(CLIENT_RUN, 9424210L, "Thin client: Python",
                "bob", "a1b2c3d4e5f6", "1f2e3d4c5b6a"))));
        when(standing.provenGhLogin("bob")).thenReturn("bob-gh");
        when(standing.clientCommentPreview(eq("alice"), eq(CLIENT_REPO), eq(CLIENT_PR), eq(CLIENT_RUN), eq(9424210L),
            any())).thenAnswer(call -> call.<Function<Boolean, String>>getArgument(5).apply(null) + "\n\n_Posted_");

        asAlice(get("/api/client/pr-comment/preview").param("repo", "python"), CLIENT_PR, CLIENT_RUN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("@bob-gh"),
                org.hamcrest.Matchers.containsString("https://checker.example/python?pr=69"),
                org.hamcrest.Matchers.endsWith("_Posted_"))));

        verify(standing, never()).postClientCommentFromPage(any(), any(), any(), anyInt(), anyLong(), anyLong(), any(), any(),
            any());
    }

    private ResultActions answers(StandingVisas.CommentPost posted) throws Exception {
        when(standing.postCommentFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any()))
            .thenReturn(posted);

        return asAlice(post("/api/pr-comment"));
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req) throws Exception {
        return asAlice(req, PR, RUN);
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req, int pr, long build) throws Exception {
        return mvc.perform(req.param("pr", String.valueOf(pr)).param("build", String.valueOf(build))
            .requestAttr(AuthInterceptor.TOKEN_ATTR, "tok")
            .requestAttr(AuthInterceptor.USER_ATTR, "alice"));
    }

    private static StandingVisas.Phase phase(String phase) {
        return new StandingVisas.Phase(phase, 0, 2, null, null);
    }

    private static TcModel.Build started(String user) {
        return new TcModel.Build(RUN, "SUCCESS", "finished", "pull/13709/head", null, null, null, null, null, null,
            null, null, new TcModel.Triggered("user", new TcModel.User(user)), null, null, null, null, null);
    }

    private static AnalysisResult result(int pr, long buildId, boolean live) {
        return new AnalysisResult(pr, buildId, "pull/" + pr + "/head", 1, List.of(), List.of(), List.of(), List.of(),
            List.of(), 148, 1, false, 0, live, live ? buildId : 0, 0, 0, live ? 0 : 1, 0);
    }
}
