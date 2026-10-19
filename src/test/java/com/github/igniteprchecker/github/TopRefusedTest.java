package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.github.igniteprchecker.tc.QueuedBelowTopException;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * ci2 lets only some accounts reorder the build queue. A `/run-all top` from anyone else got its chain queued at the
 * end of the queue, since TeamCity queues a build before it moves it, yet the checker answered 😕 "Nothing was queued:
 * the request failed with HTTP 403. Try again in a few minutes", and a try again queued a second chain. A refused
 * `/top` was told to try again too, which never helps.
 */
class TopRefusedTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final HttpClientErrorException REFUSED = HttpClientErrorException.create(
        HttpStatusCode.valueOf(403), "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, tracker,
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
    }

    @Test
    void runAllAtTopTeamCityQueuedAtTheEndIsAcceptedAndSaysWhy() {
        when(tc.triggerRunAll("tc", PR, true)).thenThrow(new QueuedBelowTopException(chain(), REFUSED));

        comment(1L, "/run-all top");

        verify(tc, times(1)).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(tracker).record(PR, chain());
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github, never()).appReact(anyInt(), any(), eq(1L), eq("confused"));
        verify(github).appComment(eq(PR), eq("author"), contains("🚀 **RunAll queued, but not at the top** —"
            + " [build 9400000](https://ci2.example/build/9400000) · live progress & verdict: [Ignite PR Checker]"
            + "(https://checker.example/?pr=13800). TeamCity refused to move it up (HTTP 403): your ci2 account may"
            + " not have the permission to reorder the build queue."));
    }

    @Test
    void topTeamCityRefusesSaysWhyWithoutTryAgain() {
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(chain());
        comment(1L, "/run-all");
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain());
        doThrow(REFUSED).when(tc).moveToQueueTop("tc", CHAIN);

        comment(2L, "/top");

        verify(github).appReact(PR, "author", 2L, "confused");
        verify(github).appComment(PR, "author", "@Author ⬆️ _Nothing moved: TeamCity refused to move build 9400000 up"
            + " (HTTP 403): your ci2 account may not have the permission to reorder the build queue._");
    }

    private void comment(long id, String body) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private static TcModel.Build chain() {
        return new TcModel.Build(CHAIN, null, "queued", "pull/13800/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/9400000", null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
