package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A reaction or a reply GitHub did not take, because it asked the checker's account to wait or a cap of its writes was
 * reached, was lost: a commander saw no 🚀 and no word. It goes at the next polls now, once, and a restart keeps it.
 * Without GITHUB_TOKEN there is no account to answer from, and no command is read.
 */
class OutboxTest {
    private static final int PR = 13800;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final List<GithubClient.IssueComment> comments = new ArrayList<>();

    private PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        when(github.recentIssueComments(anyString())).thenAnswer(inv -> {
            List<GithubClient.IssueComment> batch = List.copyOf(comments);
            comments.clear();

            return batch;
        });
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(new TcModel.Build(9400000L, null, "queued",
            "pull/13800/head", null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        when(github.appReact(PR, "author", 1L, "rocket")).thenThrow(new GithubClient.WriteDeferred("wait"))
            .thenReturn(true);
    }

    @Test
    void aReactionGithubMadeWaitGoesAtTheNextPollOnce() {
        command("/run-all");

        commands.poll();
        commands.poll();

        verify(tc, times(1)).triggerRunAll("tc", PR, false);
        verify(github, times(2)).appReact(PR, "author", 1L, "rocket");
    }

    @Test
    void whatWaitsOutlivesARestart(@TempDir Path dir) throws Exception {
        command("/run-all");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        commands = commands();
        commands.loadFrom(file);

        commands.poll();

        verify(github, times(2)).appReact(PR, "author", 1L, "rocket");
    }

    /** A 😕 a day late would only confuse: it is dropped, never sent. */
    @Test
    void whatWaitedADayGoesNoMore(@TempDir Path dir) throws Exception {
        command("/run-all");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("outbox").get(0)).put("since", System.currentTimeMillis() - 25 * 3600_000L);
        mapper.writeValue(file.toFile(), snap);
        commands = commands();
        commands.loadFrom(file);

        commands.poll();
        commands.saveTo(file);

        verify(github, times(1)).appReact(PR, "author", 1L, "rocket");
        assertThat(mapper.readTree(file.toFile()).get("outbox").isEmpty()).isTrue();
    }

    @Test
    void withoutGithubTokenNoCommandIsReadNorRun() {
        when(github.writes()).thenReturn(false);
        comments.add(comment("/run-all"));

        commands.poll();

        verify(github, never()).recentIssueComments(anyString());
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), any(Boolean.class));
        verify(github, never()).appReact(anyInt(), any(), anyLong(), anyString());
    }

    private void command(String body) {
        comments.add(comment(body));
        commands.poll();
    }

    private static GithubClient.IssueComment comment(String body) {
        return new GithubClient.IssueComment(1L, body, "https://github.com/apache/ignite/pull/" + PR
            + "#issuecomment-1", Instant.now().toString(), GhUsers.of("Author"));
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }
}
