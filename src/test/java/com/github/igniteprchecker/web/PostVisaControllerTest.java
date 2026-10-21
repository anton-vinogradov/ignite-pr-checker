package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Post to JIRA on the page of apache/ignite#13709: its author re-ran Control Utility 5 on the PR's head after RunAll
 * 9425992 found a blocker there, and the verdict became a mixed run with no blockers. The visa goes to the ticket the
 * author confirmed, and only while nothing can change the verdict: a run of the PR going, an auto re-run settling it,
 * a TeamCity error to check again. The answer says what JIRA said. The page of PR #69 of the Python thin client posts
 * the visa of its run of the client's suite.
 */
class PostVisaControllerTest {
    private static final int PR = 13709;

    private static final long RUN = 9425992L;

    private static final String ISSUE = "IGNITE-29100";

    private static final String URL = "https://issues.example/browse/IGNITE-29100?focusedCommentId=1#comment-1";

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
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Fix the control utility", null, null,
            null, null)));
        when(clients.repo()).thenReturn(CLIENT_REPO);
        when(clients.openPrs()).thenReturn(List.of(new PrSummary(CLIENT_PR, "IGNITE-29117 Fix test to forcefully use"
            + " H2 engine", null, null, null, null)));
        when(standing.visaButton(eq("alice"), any(), anyInt(), anyLong())).thenReturn(
            new StandingVisas.VisaButton(true, null));
        when(standing.phase(PR, RUN)).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(commands.clientPhase(CLIENT_PR, CLIENT_RUN)).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(analyzer.analyzeForAction("tok", PR)).thenReturn(Optional.of(result(PR, RUN, false)));
        when(standing.postVisaFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), eq(ISSUE), any()))
            .thenReturn(new StandingVisas.PagePost(StandingVisas.PagePost.POSTED, ISSUE, URL, 0));

        PythonClientRuns python = new PythonClientRuns(new PythonClientProperties(null, null, null, null), github, tc,
            analyzer, new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
            new TeamcityProperties("https://ci2.example/"));
        ClientPrsController clientPrs = new ClientPrsController(clients, python, tc, github,
            new PrHeads(github, () -> 1_800_000_000_000L), new YourPrs(github, standing), standing, commands,
            mock(AuthInterceptor.class));
        mvc = MockMvcBuilders.standaloneSetup(new PostVisaController(standing, analyzer, merged, tc,
                new PrTitleController(github, merged), clients, python, commands, clientPrs),
                new JiraController(mock(JiraClient.class), tc, standing, merged, mock(UserDirectory.class)))
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)))
            .build();
    }

    @Test
    void aFinalVerdictGoesToTheTicketTheAuthorConfirmed() throws Exception {
        asAlice(post("/api/visa"), ISSUE)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("posted"))
            .andExpect(jsonPath("$.issue").value(ISSUE))
            .andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.mine").value(true))
            .andExpect(jsonPath("$.edited").value(false))
            .andExpect(jsonPath("$.jiraReady").value(true));

        verify(standing).postVisaFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), eq(ISSUE),
            eq(result(PR, RUN, false)));
    }

    /** The second press edited the visa of the first: the answer says so, and the page says it in its words. */
    @Test
    void aSecondPressSaysItEditedTheVisa() throws Exception {
        when(standing.postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any()))
            .thenReturn(new StandingVisas.PagePost(StandingVisas.PagePost.EDITED, ISSUE, URL, 0));

        asAlice(post("/api/visa"), ISSUE).andExpect(status().isOk()).andExpect(jsonPath("$.edited").value(true));
    }

    /** The JIRA token lives only in the option: without it, nothing is looked up or posted. */
    @Test
    void noStoredJiraTokenAsksForItAndDoesNothing() throws Exception {
        when(standing.visaButton(eq("alice"), any(), anyInt(), anyLong())).thenReturn(
            new StandingVisas.VisaButton(false, null));

        asAlice(post("/api/visa"), ISSUE)
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.need").value("jira"))
            .andExpect(jsonPath("$.error").value("Post to JIRA uses the JIRA token you save in ⚙, and you have none"
                + " there. Saving one also switches on Visa my runs in JIRA."));
        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117").andExpect(status().isPreconditionFailed());

        verify(analyzer, never()).analyzeForAction(anyString(), anyInt());
        verify(clients, never()).verdict(anyString(), anyInt());
        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    /** An auto re-run settles the run, a run of the PR goes, or the RunAll still goes: the verdict can change. */
    @Test
    void aVerdictRunsCanStillChangeWaits() throws Exception {
        when(standing.phase(PR, RUN)).thenReturn(phase(StandingVisas.Phase.SETTLING));
        refusedAs("going");

        when(standing.phase(PR, RUN)).thenReturn(phase(StandingVisas.Phase.FINAL));
        when(tc.currentUserBuilds("tok", PR)).thenReturn(List.of(build("IgniteTests24Java8_ControlUtility5")));
        refusedAs("going");

        when(tc.currentUserBuilds("tok", PR)).thenReturn(List.of());
        when(analyzer.analyzeForAction("tok", PR)).thenReturn(Optional.of(result(PR, RUN, true)));
        refusedAs("going");

        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    /** TeamCity errors left tests unchecked, and the checker tries them again within 20 minutes. */
    @Test
    void aVerdictTeamCityErrorsLeftUncheckedWaits() throws Exception {
        when(analyzer.stillRetrying(any())).thenReturn(true);

        refusedAs("going").andExpect(jsonPath("$.error").value("TeamCity errors left part of this verdict unchecked."
            + " The checker checks that part again, and Post to JIRA waits up to 20 minutes for that."));
    }

    /** The page shows RunAll 9425992, and the checker has a newer RunAll of the PR. */
    @Test
    void aRunOlderThanTheCheckersIsNotPosted() throws Exception {
        when(analyzer.analyzeForAction("tok", PR)).thenReturn(Optional.of(result(PR, RUN + 100, false)));

        refusedAs("stale");
    }

    /** The visa goes only to the ticket the author confirmed, which the PR title names. */
    @Test
    void theTicketIsTheOneTheTitleNamesAndTheAuthorConfirmed() throws Exception {
        asAlice(post("/api/visa"), "IGNITE-29101")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.ticket").value(true))
            .andExpect(jsonPath("$.error").value("The PR title now names IGNITE-29100, not IGNITE-29101. Reload the"
                + " page, then post the visa."));

        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    /**
     * The author renamed the PR to name IGNITE-29101 after a press for IGNITE-29100, and the page shows the new ticket:
     * the next press goes there, as the open list has it, not as the checker read the title at the first press.
     */
    @Test
    void aTicketRenamedAfterAPressIsTheTicketOfTheNext() throws Exception {
        asAlice(post("/api/visa"), ISSUE).andExpect(status().isOk());
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29101 Fix the control utility", null,
            null, null, null)));
        when(standing.postVisaFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), eq("IGNITE-29101"), any()))
            .thenReturn(new StandingVisas.PagePost(StandingVisas.PagePost.POSTED, "IGNITE-29101", URL, 0));

        asAlice(post("/api/visa"), "IGNITE-29101").andExpect(status().isOk());

        clientVerdict(CLIENT_RUN);
        when(standing.postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any()))
            .thenReturn(new StandingVisas.PagePost(StandingVisas.PagePost.POSTED, "IGNITE-29117", URL, 0));
        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117").andExpect(status().isOk());
        when(clients.openPrs()).thenReturn(List.of(new PrSummary(CLIENT_PR, "IGNITE-29118 Fix test to forcefully use"
            + " H2 engine", null, null, null, null)));
        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29118").andExpect(status().isOk());
    }

    /** GitHub did not give the title of a PR the open list does not hold: the checker does not guess its ticket. */
    @Test
    void anUnknownTitleIsNotATitleWithoutATicket() throws Exception {
        when(github.openPrs()).thenReturn(List.of());
        when(github.pullState(PR)).thenThrow(new IllegalStateException("GitHub did not answer"));

        asAlice(post("/api/visa"), ISSUE)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("The checker could not get the PR's title from GitHub, so it does not"
                + " know the ticket. Try again in a minute."));
        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void aTitleWithoutATicketGetsNoVisa() throws Exception {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "Fix the control utility", null, null, null,
            null)));

        refusedAs("ticket").andExpect(jsonPath("$.error").value("The PR title names no IGNITE ticket, so there is no"
            + " ticket to post the visa to. Add the ticket key to the title."));
        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    /**
     * #13709 was merged while the re-run went: its page shows the verdict kept at the merge, and the visa is of that.
     * Runs queued after the merge do not change it, so TeamCity is not asked about them; while the checker still looks
     * for the runs that went at the merge, the visa waits.
     */
    @Test
    void aMergedPrsVisaIsOfTheVerdictKeptAtTheMerge() throws Exception {
        AnalysisResult atMerge = result(PR, RUN, false);
        when(merged.verdict(PR)).thenReturn(Optional.of(atMerge));
        when(analyzer.runsAtMergeDue(PR)).thenReturn(true);
        refusedAs("going");

        when(analyzer.runsAtMergeDue(PR)).thenReturn(false);
        asAlice(post("/api/visa"), ISSUE).andExpect(status().isOk());

        verify(standing).postVisaFromPage(eq("alice"), any(), eq("tok"), eq(PR), eq(RUN), eq(ISSUE), eq(atMerge));
        verify(analyzer, never()).analyzeForAction(anyString(), anyInt());
        verify(tc, never()).currentUserBuilds(anyString(), anyInt());
    }

    @Test
    void theAnswerSaysWhatJiraSaid() throws Exception {
        posting(StandingVisas.PagePost.REFUSED, 401).andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.need").value("jira"))
            .andExpect(jsonPath("$.error").value("JIRA rejected your JIRA token, so the checker dropped it and switched"
                + " off Visa my runs in JIRA. Save a new token in ⚙."));
        posting(StandingVisas.PagePost.FAILED, 403).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("JIRA refused the comment (HTTP 403). Check that your JIRA account can"
                + " comment on IGNITE-29100."));
        posting(StandingVisas.PagePost.FAILED, 404).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("JIRA has no ticket IGNITE-29100, or your JIRA account cannot see it"
                + " (HTTP 404)."));
        posting(StandingVisas.PagePost.FAILED, 500).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("JIRA did not take the visa (HTTP 500). Try again later."));
        posting(StandingVisas.PagePost.FAILED, 0).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("JIRA did not answer — try again in a moment"));
    }

    /**
     * The client's PR #69: its newest run, 9424200, and a later run of the same commits that the verdict counts. The
     * visa says it is of one suite and names both runs; a build of another suite on the PR's branch does not hold it.
     */
    @Test
    void aClientRunsVisaIsOfItsSuiteRun() throws Exception {
        AnalysisResult res = result(CLIENT_PR, CLIENT_RUN, false);
        when(clients.verdict("tok", CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(res,
            new ClientVerdicts.Run(CLIENT_RUN, 9424210L, "Thin client: Python", "alice", "a1b2c3d4e5f6",
                "1f2e3d4c5b6a"))));
        when(tc.currentUserBuilds("tok", "pull/69")).thenReturn(List.of(build("IgniteTests24Java8_Cache1")));
        when(standing.postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any()))
            .thenReturn(new StandingVisas.PagePost(StandingVisas.PagePost.POSTED, "IGNITE-29117", URL, 0));

        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issue").value("IGNITE-29117"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(standing).postClientVisaFromPage(eq("alice"), any(), eq(CLIENT_REPO), eq(CLIENT_PR), eq(CLIENT_RUN),
            eq("IGNITE-29117"), eq(res), body.capture());
        assertThat(body.getValue()).contains("*one suite only, not a RunAll*")
            .contains("[build 9424200|https://ci2.example/build/9424200] tested the PR's head")
            .contains("This verdict counts [build 9424210|https://ci2.example/build/9424210] too: a later run of the"
                + " suite on the same commits.");
    }

    /** The client's run is still settling: its auto re-run can still change the verdict. */
    @Test
    void aClientRunStillSettlingWaits() throws Exception {
        clientVerdict(CLIENT_RUN);
        when(commands.clientPhase(CLIENT_PR, CLIENT_RUN)).thenReturn(phase(StandingVisas.Phase.SETTLING));

        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.going").value(true));
        verify(standing, never()).postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /** The page shows the client's run 9424200, and the checker's verdict is of a newer run of the suite. */
    @Test
    void aClientRunOlderThanTheCheckersIsNotPosted() throws Exception {
        clientVerdict(CLIENT_RUN + 100);

        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.stale").value(true));
        verify(standing, never()).postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void aClientRunOfTheSuiteGoingHoldsTheVisa() throws Exception {
        AnalysisResult res = result(CLIENT_PR, CLIENT_RUN, false);
        when(clients.verdict("tok", CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(res,
            new ClientVerdicts.Run(CLIENT_RUN, 0, "Thin client: Python", "alice", null, null))));
        when(tc.currentUserBuilds("tok", "pull/69"))
            .thenReturn(List.of(build("IgniteTests24Java8_WiPThinClientPython")));

        asAlice("/api/client/visa", CLIENT_PR, CLIENT_RUN, "IGNITE-29117")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.going").value(true));
        verify(standing, never()).postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /** The visa line the page polls tells it whether the reader has a JIRA token stored, and where their visa is. */
    @Test
    void theVisaRowTellsWhatPostVisaCanDo() throws Exception {
        when(standing.visaLine(eq("alice"), eq(PR), eq(RUN), any(), eq(false))).thenReturn(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, "alice", true, ISSUE, URL, false));
        when(standing.visaButton("alice", null, PR, RUN)).thenReturn(new StandingVisas.VisaButton(true, ISSUE));

        asAlice(get("/api/visa"), ISSUE)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("posted"))
            .andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.jiraReady").value(true))
            .andExpect(jsonPath("$.yoursIn").value(ISSUE))
            .andExpect(jsonPath("$.edited").doesNotExist());
    }

    private void clientVerdict(long build) {
        when(clients.verdict("tok", CLIENT_PR)).thenReturn(Optional.of(new ClientVerdicts.Verdict(
            result(CLIENT_PR, build, false), new ClientVerdicts.Run(build, 0, "Thin client: Python", "alice", null,
                null))));
    }

    private ResultActions posting(String outcome, int status) throws Exception {
        when(standing.postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any()))
            .thenReturn(new StandingVisas.PagePost(outcome, null, null, status));

        return asAlice(post("/api/visa"), ISSUE);
    }

    private ResultActions refusedAs(String flag) throws Exception {
        return asAlice(post("/api/visa"), ISSUE)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$." + flag).value(true));
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req, String issue) throws Exception {
        return asAlice(req, PR, RUN, issue);
    }

    private ResultActions asAlice(String path, int pr, long build, String issue) throws Exception {
        return asAlice(post(path), pr, build, issue);
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req, int pr, long build, String issue)
        throws Exception {
        return mvc.perform(req.param("pr", String.valueOf(pr)).param("build", String.valueOf(build))
            .param("issue", issue)
            .requestAttr(AuthInterceptor.TOKEN_ATTR, "tok")
            .requestAttr(AuthInterceptor.USER_ATTR, "alice"));
    }

    private static StandingVisas.Phase phase(String phase) {
        return new StandingVisas.Phase(phase, 0, 2, null, null);
    }

    private static TcModel.Build build(String suite) {
        return new TcModel.Build(9426316L, null, "running", null, suite, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

    private static AnalysisResult result(int pr, long buildId, boolean live) {
        return new AnalysisResult(pr, buildId, "pull/" + pr + "/head", 1, List.of(), List.of(), List.of(), List.of(),
            List.of(), 148, 1, false, 0, live, live ? buildId : 0, 0, 0, live ? 0 : 1, 0);
    }
}
