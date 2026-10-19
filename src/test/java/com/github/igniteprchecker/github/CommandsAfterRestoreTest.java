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
import com.github.igniteprchecker.persist.TokenScrub;
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

/**
 * The operator restored the checker's state from a copy older than two days, which keeps no tokens. The author's
 * /run-all got "the checker can no longer read the TeamCity token it stores for you", as if the token had gone bad,
 * and the checkstyle fix waited for the GitHub token without a word. Now that a changed server key has its own reply,
 * a restore has its own too: the reply and the run's story say that the state was restored.
 */
class CommandsAfterRestoreTest {
    private static final int PR = 13800;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private StandingVisas standing;

    private PrCommands commands;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        StandingVisas before = standing();
        before.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true, true));
        Path copy = dir.resolve("standing-visas.json.before-1.37.0-built-20261016-120000");
        before.saveTo(copy);
        TokenScrub.removeFrom(copy, System.currentTimeMillis());
        standing = standing();
        standing.loadFrom(copy);
        commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), styleFix,
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", "pull/13800/head", "IgniteTests24Java8_RunAll", "https://ci2.example/build/9400000", null, null,
            null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aCommandBeforeTheNextVisitIsToldTheStateWasRestored() {
        runAll(1L);

        verify(github).appComment(eq(PR), any(), contains("@Author nothing was queued: the checker's state was restored"
            + " from a backup older than 2 days, which keeps no tokens. Open https://checker.example while logged in,"
            + " or log in there again: the checker takes the TeamCity token of that login, and your commands work"
            + " again."));
        verify(github, never()).appComment(eq(PR), any(), contains("can no longer read"));
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void theStorySaysTheCheckstyleFixWaitsForTheTokenAgain() {
        standing.tcTokenOffered("author", "tc-2");

        runAll(1L);

        verify(tc).triggerRunAll(eq("tc-2"), eq(PR), anyBoolean());
        verify(styleFix, never()).fixForCommand(anyInt(), any(), anyString());
        verify(github).appComment(eq(PR), eq("author"), contains("🎨 _Fix checkstyle before my commanded runs waits"
            + " for your GitHub token for commits: the checker's state was restored from a backup older than 2 days,"
            + " which keeps no tokens. Save the token in ⚙ at https://checker.example again._"));
    }

    private void runAll(long id) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, "/run-all",
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc,
            github, mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }
}
