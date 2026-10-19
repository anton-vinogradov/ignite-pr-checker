package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator changed SESSION_SECRET, and the author's /run-all got "the checker can no longer read the TeamCity
 * token it stores for you", as if the checker had lost it. With a fresh login the run went, and the checkstyle fix the
 * author had on did nothing, without a word. The reply now says that the server key was changed, and the run's story
 * says what the fix waits for.
 */
class CommandsAfterKeyChangeTest {
    private static final int PR = 13800;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private StandingVisas standing;

    private PrCommands commands;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        StandingVisas before = standing("old-secret");
        before.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true, true));
        Path file = dir.resolve("standing-visas.json");
        before.saveTo(file);
        standing = standing("new-secret");
        standing.loadFrom(file);
        commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), styleFix,
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", "pull/13800/head", "IgniteTests24Java8_RunAll", "https://ci2.example/build/9400000", null, null,
            null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aCommandBeforeTheNextLoginIsToldTheKeyWasChanged() {
        runAll(1L);

        verify(github).appComment(eq(PR), any(), contains("@Author nothing was queued: the server key of the checker"
            + " was changed, so it could no longer read the TeamCity token it stored for you. Log in at"
            + " https://checker.example again: the checker stores the token of that login, and your commands work"
            + " again."));
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void theStorySaysWhatTheCheckstyleFixWaitsFor() {
        standing.tcTokenAccepted("author", "tc-2");

        runAll(1L);

        verify(tc).triggerRunAll(eq("tc-2"), eq(PR), anyBoolean());
        verify(styleFix, never()).fixForCommand(anyInt(), any(), anyString());
        verify(github).appComment(eq(PR), eq("author"), contains("🎨 _Fix checkstyle before my commanded runs waits"
            + " for a new GitHub token for commits: the server key was changed, so the checker could no longer read"
            + " the stored one. Save a new token in ⚙ at https://checker.example._"));
    }

    private void runAll(long id) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, "/run-all",
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private StandingVisas standing(String secret) {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, secret), mapper), tc, github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
    }
}
