package com.github.igniteprchecker.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.github.igniteprchecker.github.IgnitePrRuns;
import com.github.igniteprchecker.mirror.GitMirror;
import com.github.igniteprchecker.mirror.GitMirrorProperties;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MirrorLaunchErrorsTest {
    @TempDir
    Path temp;

    /** A launch during bootstrap returns the page's retry message and leaves an existing run untouched. */
    @Test
    void initializingMirrorReturnsActionableJsonBeforeCancellation() throws Exception {
        TcClient tc = mock(TcClient.class);
        GithubClient github = mock(GithubClient.class);
        when(github.prHeadSha(13786)).thenReturn("a".repeat(40));
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        GitMirror mirror = new GitMirror(new GitMirrorProperties(true, temp.toString(), 60L, 600L));
        var controller = new TriggerController(tc, mock(BlockerAnalyzer.class), mock(RerunTracker.class),
            mock(PrHeads.class), mock(SuiteBaseline.class), cfg, new IgnitePrRuns(github, tc, mirror, cfg));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class))).build();

        mvc.perform(post("/api/trigger").param("pr", "13786").param("replace", "true")
                .requestAttr(AuthInterceptor.TOKEN_ATTR, "token").requestAttr(AuthInterceptor.USER_ATTR, "author"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value(ApiExceptionHandler.MIRROR_NOT_READY))
            .andExpect(jsonPath("$.mirrorNotReady").value(true));

        verify(tc, never()).cancelOwnBuilds(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), any());
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }
}
