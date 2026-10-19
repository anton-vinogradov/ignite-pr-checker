package com.github.igniteprchecker.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Run, Run at top and Run RunAll queued a new RunAll on a PR with merge conflicts, whose code has to change before it
 * can be merged. Now such a PR gets an answer that says so, and Run anyway queues the chain all the same. Merge
 * conflicts do not stop re-runs of suites of a run the PR already has. Here GitHub reports conflicts between PR 13636
 * and master.
 */
class ConflictingPrTriggerTest {
    private static final int PR = 13636;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private long now = 1_800_000_000_000L;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(github.reportsConflicts(PR)).thenReturn(true);
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build("IgniteTests24Java8_RunAll"));
        when(tc.triggerOwnBuildReplacingQueued(anyString(), anyString(), anyString(), anyInt(), anyBoolean()))
            .thenReturn(build("IgniteTests24Java8_Cache"));
        when(tc.cancelOwnBuilds(anyString(), anyInt(), anyString(), any())).thenReturn(1);

        AnalysisProperties cfg = new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null,
            null);
        mvc = MockMvcBuilders.standaloneSetup(new TriggerController(tc, mock(BlockerAnalyzer.class),
                mock(RerunTracker.class), new PrHeads(github, () -> now), mock(SuiteBaseline.class), cfg))
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)))
            .build();
    }

    @Test
    void runAllIsNotQueuedAndYourRunAllIsNotCancelled() throws Exception {
        asAlice(post("/api/trigger").param("replace", "true"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conflicts").value(true))
            .andExpect(jsonPath("$.error").value("GitHub reports merge conflicts between this PR and its base branch."
                + " Resolve them and run RunAll again, or press Run anyway to queue RunAll on the PR as it is"));

        verify(tc, never()).cancelOwnBuilds(anyString(), anyInt(), anyString(), any());
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void runAnywayQueuesRunAllWithoutAskingGithub() throws Exception {
        asAlice(post("/api/trigger").param("top", "true").param("replace", "true").param("force", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replaced").value(1));

        verify(tc).triggerRunAll("tok", PR, true);
        verify(github, never()).reportsConflicts(anyInt());
    }

    @Test
    void aPrWithoutConflictsIsQueued() throws Exception {
        when(github.reportsConflicts(PR)).thenReturn(false);

        asAlice(post("/api/trigger")).andExpect(status().isOk());

        verify(tc).triggerRunAll("tok", PR, false);
    }

    @Test
    void aSuiteOfTheRunThePrHasIsReRun() throws Exception {
        asAlice(post("/api/rerun-suite").param("suite", "IgniteTests24Java8_Cache")).andExpect(status().isOk());
        asAlice(post("/api/rerun-suites").param("suites", "IgniteTests24Java8_Cache")).andExpect(status().isOk());

        verify(github, never()).reportsConflicts(anyInt());
    }

    /** The page keeps the token's last calls for PR commands, comments and visas: RunAll is queued unasked. */
    @Test
    void withNoCallsToSpareGithubIsNotAsked() throws Exception {
        when(github.appQuota()).thenReturn(new GithubClient.Quota(100, 5000, now / 1000 + 600));

        asAlice(post("/api/trigger")).andExpect(status().isOk());

        verify(github, never()).reportsConflicts(anyInt());
        verify(tc).triggerRunAll("tok", PR, false);
    }

    private ResultActions asAlice(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.param("pr", String.valueOf(PR))
            .requestAttr(AuthInterceptor.TOKEN_ATTR, "tok")
            .requestAttr(AuthInterceptor.USER_ATTR, "alice"));
    }

    private static TcModel.Build build(String type) {
        return new TcModel.Build(501L, null, "queued", "pull/13636/head", type, "https://ci2.example/build/501", null,
            null, null, null, null, null, null, null, null, null, null, null);
    }
}
