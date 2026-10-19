package com.github.igniteprchecker.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The checker's page of PR #69 of apache/ignite-python-thin-client: apache/ignite has a PR #69 too, so the page asks
 * /api/client/... about it. Run there queues what {@code /run-all} in the PR queues: Thin client: Python with the
 * PR's head and Ignite's master. GitHub reports merge conflicts in the PR, as in apache/ignite#13636, so Run queues
 * nothing until Run anyway.
 */
class ClientPrPageApiTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final int PR = 69;

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private final ClientVerdicts clients = mock(ClientVerdicts.class);

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final PrCommands commands = mock(PrCommands.class);

    private final AuthInterceptor auth = mock(AuthInterceptor.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(clients.repo()).thenReturn(CLIENT_REPO);
        when(github.repo()).thenReturn("apache/ignite");
        when(github.reportsConflicts(CLIENT_REPO, PR)).thenReturn(true);
        when(github.prHeadSha(CLIENT_REPO, PR)).thenReturn(CLIENT);
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(IGNITE);
        when(tc.vcsRootInstances("tok", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335",
            "GitHubApacheIgnitePythonThinClient", "314")));
        when(tc.queuePinned(eq("tok"), any(), anyBoolean(), anyString())).thenReturn(build(9424794L, "queued",
            "alice"));

        PythonClientRuns python = new PythonClientRuns(new PythonClientProperties(null, null, null, null), github, tc,
            mock(BlockerAnalyzer.class), new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null,
                null, null), new TeamcityProperties("https://ci2.example/"));
        mvc = MockMvcBuilders.standaloneSetup(new ClientPrsController(clients, python, tc, github,
                new PrHeads(github, () -> 1_800_000_000_000L), new YourPrs(github, standing), standing, commands,
                auth))
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)))
            .build();
    }

    @Test
    void theVerdictNamesItsRepoAndTheCommitsItTested() throws Exception {
        when(clients.verdict("tok", PR)).thenReturn(Optional.of(verdict(9424200L, 9424210L)));
        when(clients.head(PR)).thenReturn(CLIENT);

        asAlice(get("/api/client/analyze"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.prNumber").value(PR))
            .andExpect(jsonPath("$.buildId").value(9424200L))
            .andExpect(jsonPath("$.repo").value("python"))
            .andExpect(jsonPath("$.rerunId").value(9424210L))
            .andExpect(jsonPath("$.suiteName").value("Thin client: Python"))
            .andExpect(jsonPath("$.clientSha").value(CLIENT))
            .andExpect(jsonPath("$.igniteSha").value(IGNITE))
            .andExpect(jsonPath("$.clientRepo").value(CLIENT_REPO))
            .andExpect(jsonPath("$.igniteRepo").value("apache/ignite"))
            .andExpect(jsonPath("$.standing").value("CLEAN"))
            .andExpect(jsonPath("$.pushed.pending").value(false))
            .andExpect(jsonPath("$.pushed.checked").value(true));
    }

    @Test
    void aHeadPastTheTestedCommitMakesTheVerdictOneOfOlderCode() throws Exception {
        when(clients.verdict("tok", PR)).thenReturn(Optional.of(verdict(9424200L, 0)));
        when(clients.head(PR)).thenReturn("b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4e5");

        asAlice(get("/api/client/analyze"))
            .andExpect(jsonPath("$.pushed.pending").value(true))
            .andExpect(jsonPath("$.pushed.builtSha").value("a1b2c3d"))
            .andExpect(jsonPath("$.pushed.headSha").value("b2c3d4e"))
            .andExpect(jsonPath("$.pushed.caveat").value(Caveats.headMoved()));
    }

    @Test
    void aClosedPrsHeadIsNotKnown() throws Exception {
        when(clients.verdict("tok", PR)).thenReturn(Optional.of(verdict(9424200L, 0)));

        asAlice(get("/api/client/analyze"))
            .andExpect(jsonPath("$.pushed.pending").value(false))
            .andExpect(jsonPath("$.pushed.checked").value(false));
    }

    @Test
    void aPrWithoutARunSaysSo() throws Exception {
        asAlice(get("/api/client/analyze"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.noRun").value(true));
        asAlice(post("/api/client/refresh"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.noRun").value(true));
    }

    @Test
    void runOnAPrWithConflictsQueuesNothingAndCancelsNothing() throws Exception {
        asAlice(post("/api/client/trigger").param("replace", "true"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conflicts").value(true))
            .andExpect(jsonPath("$.error").value(ClientPrsController.CONFLICTS));

        verify(tc, never()).cancelOwnBuilds(anyString(), anyString(), anyString(), any());
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void runAnywayQueuesTheRunOfRunAllWithBothCommits() throws Exception {
        asAlice(post("/api/client/trigger").param("force", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.triggered[0].buildId").value(9424794L))
            .andExpect(jsonPath("$.replaced").value(0));

        verify(tc).queuePinned("tok", new PinnedRun(SUITE, "pull/69", List.of(
            new PinnedRun.Pin("335", "refs/heads/master", IGNITE),
            new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)), Map.of("OVERRIDDEN_BRANCH", CLIENT)), false,
            "Ignite PR Checker: Run on the checker's page for https://github.com/" + CLIENT_REPO + "/pull/69");
        verify(github, never()).reportsConflicts(CLIENT_REPO, PR);
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void runReplacesYourRunsOfTheSuiteFirst() throws Exception {
        when(github.reportsConflicts(CLIENT_REPO, PR)).thenReturn(false);
        when(tc.cancelOwnBuilds(eq("tok"), eq("pull/69"), eq("alice"), any())).thenReturn(1);

        asAlice(post("/api/client/trigger").param("top", "true").param("replace", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replaced").value(1));

        InOrder order = inOrder(tc);
        order.verify(tc).cancelOwnBuilds(eq("tok"), eq("pull/69"), eq("alice"), any());
        order.verify(tc).queuePinned(eq("tok"), any(), eq(true), anyString());
    }

    @Test
    void aRunThatCannotBePutTogetherSaysWhy() throws Exception {
        when(github.reportsConflicts(CLIENT_REPO, PR)).thenReturn(false);
        when(tc.vcsRootInstances("tok", SUITE)).thenReturn(Optional.empty());

        asAlice(post("/api/client/trigger"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("TeamCity has no suite " + SUITE));
    }

    @Test
    void theRunsAreTheSuitesOnThePrsBranch() throws Exception {
        when(tc.currentUserBuilds("tok", "pull/69")).thenReturn(List.of(build(9424794L, "running", "alice"),
            new TcModel.Build(9424800L, null, "queued", "pull/69", "IgniteTests24Java8_Other", "", null, null, null,
                null, null, null, null, null, null, null, null, null)));

        asAlice(get("/api/client/runs"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].buildId").value(9424794L))
            .andExpect(jsonPath("$[0].mine").value(true))
            .andExpect(jsonPath("$[0].runAll").value(true))
            .andExpect(jsonPath("$[0].pinned").value(true));

        asAlice(post("/api/client/cancel-all").param("ids", "9424794"))
            .andExpect(jsonPath("$.cancelled").value(0));
        verify(tc).cancelOwnBuilds(eq("tok"), eq("pull/69"), eq("alice"), any());
    }

    @Test
    void theListBadgesTheClientsPrsAndTellsTheStarterToASignedInViewer() throws Exception {
        when(clients.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29117 Fix test",
            "https://github.com/" + CLIENT_REPO + "/pull/69", null, null, null, CLIENT, null)));
        when(clients.glance(PR)).thenReturn(new Caveats.Glance(0, 0, true, CLIENT));
        when(clients.starter(PR)).thenReturn("alice");
        when(auth.signedIn(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/client/prs"))
            .andExpect(jsonPath("$[0].number").value(PR))
            .andExpect(jsonPath("$[0].blockers").value(0))
            .andExpect(jsonPath("$[0].standing").value("CLEAN"))
            .andExpect(jsonPath("$[0].triggeredBy").doesNotExist());
    }

    @Test
    void mineSearchesTheClientsRepo() throws Exception {
        when(standing.ghLoginOf("alice")).thenReturn("Alice");
        when(github.writes()).thenReturn(true);
        when(github.prsOf(CLIENT_REPO, "Alice")).thenReturn(new GithubClient.PrsOf(List.of(new PrSummary(PR,
            "IGNITE-29117 Fix test", "https://github.com/" + CLIENT_REPO + "/pull/69", null, null, null, CLIENT, null)),
            List.of()));

        asAlice(get("/api/client/your-prs"))
            .andExpect(jsonPath("$.login").value("Alice"))
            .andExpect(jsonPath("$.mine[0].number").value(PR));

        verify(github, never()).prsOf("Alice");
    }

    @Test
    void theStoryOfARunAllTellsWhetherTheVerdictIsFinal() throws Exception {
        when(commands.clientPhase(PR, 9424200L)).thenReturn(new StandingVisas.Phase(StandingVisas.Phase.SETTLING, 1, 1,
            "Thin client: Python", null));

        asAlice(get("/api/client/settling").param("build", "9424200"))
            .andExpect(jsonPath("$.phase").value("settling"))
            .andExpect(jsonPath("$.wave").value(1));
    }

    /**
     * The visa line asks for the visa of the run on screen, with the commits its kept verdict tested: an auto re-run
     * that stood in for a cancelled run has the visa of those commits.
     */
    @Test
    void theVisaLineLooksForTheVisaOfTheRunsCommits() throws Exception {
        ClientVerdicts.Verdict kept = verdict(9424200L, 0, IGNITE + "+" + CLIENT);
        when(clients.kept(PR)).thenReturn(Optional.of(kept));
        when(standing.clientVisaLine("alice", CLIENT_REPO, PR, 9424200L, IGNITE + "+" + CLIENT)).thenReturn(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, "alice", true, "IGNITE-29117",
                "https://jira.example/c/1", false));

        asAlice(get("/api/client/visa").param("build", "9424200"))
            .andExpect(jsonPath("$.state").value("posted"))
            .andExpect(jsonPath("$.issue").value("IGNITE-29117"));
        asAlice(get("/api/client/visa").param("build", "9424300"));

        verify(standing).clientVisaLine("alice", CLIENT_REPO, PR, 9424300L, null);
    }

    @Test
    void theTitleOfAListedPrComesFromTheListAndOfAnotherFromGithubOnce() throws Exception {
        when(clients.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-29117 Fix test",
            "https://github.com/" + CLIENT_REPO + "/pull/69", null, null, null, CLIENT, null)));
        when(github.pullState(CLIENT_REPO, 68)).thenReturn(Optional.of(new GithubClient.PullState(
            "IGNITE-29001 Support Python 3.13", false, true)));

        asAlice(get("/api/client/pr-title")).andExpect(jsonPath("$.title").value("IGNITE-29117 Fix test"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/client/pr-title").param("pr", "68"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("IGNITE-29001 Support Python 3.13"));
        }

        verify(github, never()).pullState(CLIENT_REPO, PR);
        verify(github).pullState(CLIENT_REPO, 68);
    }

    /** A reader trying PR numbers one after another: GitHub is asked about 60 of them an hour, as for Ignite's PRs. */
    @Test
    void gitHubIsAskedAboutSixtyTitlesAnHour() throws Exception {
        when(github.pullState(eq(CLIENT_REPO), anyInt())).thenReturn(Optional.of(new GithubClient.PullState("Fix",
            true, false)));

        for (int pr = 1; pr <= 60; pr++)
            mvc.perform(get("/api/client/pr-title").param("pr", String.valueOf(pr))).andExpect(status().isOk());
        mvc.perform(get("/api/client/pr-title").param("pr", "61")).andExpect(status().isNotFound());

        verify(github, never()).pullState(CLIENT_REPO, 61);
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.param("pr", String.valueOf(PR))
            .requestAttr(AuthInterceptor.TOKEN_ATTR, "tok")
            .requestAttr(AuthInterceptor.USER_ATTR, "alice"));
    }

    private static ClientVerdicts.Verdict verdict(long buildId, long rerunId) {
        return verdict(buildId, rerunId, null);
    }

    /** {@code revision}: the commits the verdict names, null when TeamCity named none. */
    private static ClientVerdicts.Verdict verdict(long buildId, long rerunId, String revision) {
        return new ClientVerdicts.Verdict(new AnalysisResult(PR, buildId, "pull/69", System.currentTimeMillis(),
            List.of(), List.of(), List.of(), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, 0, 0, List.of(),
            List.of(), List.of(), 0, revision, 0),
            new ClientVerdicts.Run(buildId, rerunId, "Thin client: Python", "alice", CLIENT, IGNITE));
    }

    private static TcModel.Build build(long id, String state, String starter) {
        return new TcModel.Build(id, null, state, "pull/69", SUITE, "https://ci2.example/build/" + id, null, null, null,
            null, null, new TcModel.BuildType(SUITE, "Thin client: Python"),
            new TcModel.Triggered("user", new TcModel.User(starter)), null, null, null, null, null);
    }
}
