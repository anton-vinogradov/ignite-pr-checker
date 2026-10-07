package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A /run-all whose author's stored TeamCity token had expired got a 😕 and nothing else: the author
 * could not tell an expired token from a broken checker, and retried.
 */
class CommandTokenRefusedTest {
    private static final int PR = 13335;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @BeforeEach
    void setUp() {
        standing.change("nsamelchev", "expired-tc", null, null,
            new StandingVisas.OptionChange(false, true, false, false, null));
        standing.linkGhLogin("nsamelchev", "expired-tc", "NSAmelchev");
        when(tc.cancelOwnRunAllChains(eq("expired-tc"), eq(PR), anyString())).thenThrow(
            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0],
                null));
    }

    @Test
    void theAuthorIsToldInWordsOncePerRefusal() {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(runAll(1L)), List.of(runAll(2L)));

        commands.poll();
        commands.poll();

        verify(github, times(1)).addPrCommentAsApp(eq(PR), contains("TeamCity no longer accepts the token"));
        verify(github, times(2)).reactToCommentAsApp(anyLong(), eq("confused"));
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    private static GithubClient.IssueComment runAll(long id) {
        return new GithubClient.IssueComment(id, "/run-all", "https://github.com/apache/ignite/pull/13335#issuecomment-"
            + id, "2026-10-07T10:00:00Z", new GithubClient.GhUser("NSAmelchev"));
    }
}
