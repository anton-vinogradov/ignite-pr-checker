package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The author's /run-all was going when the operator changed the server key, or restored a copy of the state without
 * tokens. After the restart the run's story said "the options of the user who started it were switched off" and went
 * quiet for good, though the options were on and waited for the TeamCity token. The story now waits, says why, and
 * brings the verdict once the token is back.
 */
class StoryWhileTheTokenIsGoneTest {
    private static final int PR = 13800;

    private static final long RUN = 9400000L;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final String SWITCHED_OFF = "options of the user who started it were switched off";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private Path dir;

    private Path standingFile;

    private Path commandsFile;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        this.dir = dir;
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Author"),
            null)));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(build("queued", null));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            PR_URL + "#issuecomment-77"));
        when(github.appEdit(anyInt(), any(), anyLong(), anyString())).thenReturn(true);
        StandingVisas before = standing("old-secret");
        before.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true, true));
        PrCommands commandsBefore = commands(before);
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            PR_URL + "#issuecomment-1", Instant.now().toString(), GhUsers.of("Author"))));
        commandsBefore.poll();
        verify(tc).triggerRunAll(eq("tc"), eq(PR), anyBoolean());
        standingFile = dir.resolve("standing-visas.json");
        before.saveTo(standingFile);
        commandsFile = dir.resolve("pr-commands.json");
        commandsBefore.saveTo(commandsFile);
    }

    @Test
    void afterAKeyChangeTheStoryWaitsAndSaysWhy() throws IOException {
        StandingVisas after = standing("new-secret");
        after.loadFrom(standingFile);

        restarted(after).updateEtas();

        assertThat(after.settings("author").commands()).isTrue();
        verify(github).appEdit(eq(PR), eq("author"), eq(77L), contains("\n⏸ _The server key of the checker was"
            + " changed, so it could no longer read the TeamCity token it stored for you. This story waits for a new"
            + " one; the run itself goes on in TeamCity. Log in at https://checker.example again, and the story goes"
            + " on._"));
        verify(github, never()).appEdit(anyInt(), any(), anyLong(), contains(SWITCHED_OFF));
    }

    @Test
    void aLoginAfterTheKeyChangeBringsTheVerdictOfTheRun() throws IOException {
        StandingVisas after = standing("new-secret");
        after.loadFrom(standingFile);
        PrCommands commands = restarted(after);
        commands.updateEtas();

        after.tcTokenAccepted("author", "tc-2");
        when(tc.getBuildState("tc-2", RUN)).thenReturn(build("finished", "SUCCESS"));
        commands.updateEtas();

        verify(github).appComment(eq(PR), eq("author"), contains("@Author the verdict of your `/run-all` is ready"));
        verify(github).appDelete(PR, "author", 77L);
    }

    @Test
    void afterARestoreTheStoryWaitsAndSaysWhy() throws IOException {
        StandingVisas restored = restoredFromAnOldCopy();

        restarted(restored).updateEtas();

        assertThat(restored.settings("author").tokensRemoved()).contains("github");
        verify(github).appEdit(eq(PR), eq("author"), eq(77L), contains("\n⏸ _The checker's state was restored from a"
            + " backup older than 2 days, which keeps no tokens. This story waits for your TeamCity token; the run"
            + " itself goes on in TeamCity. Open https://checker.example while logged in, or log in there again, and"
            + " the story goes on._"));
        verify(github, never()).appEdit(anyInt(), any(), anyLong(), contains(SWITCHED_OFF));
    }

    @Test
    void aVisitAfterTheRestoreBringsTheVerdictOfTheRun() throws IOException {
        StandingVisas restored = restoredFromAnOldCopy();
        PrCommands commands = restarted(restored);
        commands.updateEtas();

        restored.tcTokenOffered("author", "tc-2");
        when(tc.getBuildState("tc-2", RUN)).thenReturn(build("finished", "SUCCESS"));
        commands.updateEtas();

        verify(github).appComment(eq(PR), eq("author"), contains("@Author the verdict of your `/run-all` is ready"));
    }

    /** A file of an earlier version names no key, so a token it cannot read is called damaged. */
    @Test
    void aDamagedTokenHoldsTheStoryToo() throws IOException {
        ObjectNode snap = (ObjectNode)mapper.readTree(standingFile.toFile());
        snap.remove("keyCheck");
        mapper.writeValue(standingFile.toFile(), snap);
        StandingVisas after = standing("new-secret");
        after.loadFrom(standingFile);

        restarted(after).updateEtas();

        verify(github).appEdit(eq(PR), eq("author"), eq(77L), contains("\n⏸ _The checker can no longer read the"
            + " TeamCity token it stores for you. This story waits for a new one; the run itself goes on in TeamCity."
            + " Log in at https://checker.example again, and the story goes on._"));
        verify(github, never()).appEdit(anyInt(), any(), anyLong(), contains(SWITCHED_OFF));
    }

    /** The story says it waits once, not at every look. */
    @Test
    void theStorySaysItWaitsOnce() throws IOException {
        StandingVisas after = standing("new-secret");
        after.loadFrom(standingFile);
        PrCommands commands = restarted(after);

        commands.updateEtas();
        commands.updateEtas();

        verify(github).appEdit(eq(PR), eq("author"), eq(77L), contains("This story waits for a new one"));
    }

    private StandingVisas restoredFromAnOldCopy() throws IOException {
        Path copy = Files.copy(standingFile, dir.resolve("standing-visas.json.before-1.37.0-built-20261016-120000"));
        TokenScrub.removeFrom(copy, System.currentTimeMillis());
        StandingVisas restored = standing("old-secret");
        restored.loadFrom(copy);

        return restored;
    }

    private PrCommands restarted(StandingVisas standing) throws IOException {
        PrCommands commands = commands(standing);
        commands.loadFrom(commandsFile);

        return commands;
    }

    private PrCommands commands(StandingVisas standing) {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }

    private StandingVisas standing(String secret) {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, secret), mapper), tc, github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
    }

    private static TcModel.Build build(String state, String status) {
        return new TcModel.Build(RUN, status, state, "pull/13800/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/" + RUN, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
