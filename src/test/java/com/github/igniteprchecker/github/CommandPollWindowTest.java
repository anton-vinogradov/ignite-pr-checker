package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

/**
 * The command poll moved its window before asking GitHub, so a minute in which GitHub failed was
 * never read again (six such minutes in a month). And a /run-all edited a day after it was written
 * ran again: the poll forgot handled comments after 24 hours and never looked at when a comment was
 * written, while the checker edits command comments itself.
 */
class CommandPollWindowTest {
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

    /** Comments as GitHub lists them, each with the moment it was last updated. */
    private final List<Listed> repo = new ArrayList<>();

    private final List<String> asked = new ArrayList<>();

    private boolean githubDown;

    private final long now = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("nsamelchev", "tc", "NSAmelchev");
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        when(github.recentIssueComments(anyString())).thenAnswer(inv -> {
            String since = inv.getArgument(0);
            asked.add(since);
            if (githubDown)
                throw new ResourceAccessException("I/O error on GET request for \"https://api.github.com/...\"");

            long from = Instant.parse(since).toEpochMilli();

            return repo.stream().filter(l -> l.updatedAt() >= from).map(Listed::comment).toList();
        });
    }

    @Test
    void aCommandWrittenWhileGithubFailedRunsOnTheNextPoll(@TempDir Path dir) throws Exception {
        lastGoodPollAt(now - 10 * 60_000L, Map.of(), dir);
        repo.add(runAll(1L, now - 5 * 60_000L, now - 5 * 60_000L));
        githubDown = true;
        commands.poll();
        githubDown = false;

        commands.poll();

        verify(tc).triggerRunAll("tc", PR, false);
    }

    /** Down for 40 minutes: the /run-all written 30 minutes ago is still in the window. */
    @Test
    void aCommandWrittenDuringALongerOutageRuns(@TempDir Path dir) throws Exception {
        lastGoodPollAt(now - 40 * 60_000L, Map.of(), dir);
        repo.add(runAll(1L, now - 30 * 60_000L, now - 30 * 60_000L));

        commands.poll();

        verify(tc).triggerRunAll("tc", PR, false);
    }

    @Test
    void eachPollReadsAFewMinutesBeforeTheLastOneThatWorked(@TempDir Path dir) throws Exception {
        lastGoodPollAt(now - 40 * 60_000L, Map.of(), dir);

        commands.poll();
        commands.poll();

        assertThat(asked).hasSize(2);
        assertThat(Instant.parse(asked.get(1)).toEpochMilli()).isLessThanOrEqualTo(now - 2 * 60_000L)
            .isGreaterThanOrEqualTo(now - 5 * 60_000L);
    }

    /** One comment the poll trips over (its author could not be looked up) leaves the rest of the window. */
    @Test
    void aCommentThatFailsDoesNotStopTheOthers() {
        StandingVisas tripping = spy(standing);
        doThrow(new IllegalStateException("lookup failed")).when(tripping).actorByGhLogin("Broken");
        PrCommands polled = new PrCommands(mapper, github, tripping, tc, mock(RerunTracker.class),
            mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
            new TeamcityProperties("https://ci2.example/"));
        repo.add(new Listed(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.ofEpochMilli(now).toString(),
            new GithubClient.GhUser("Broken")), now));
        repo.add(runAll(2L, now, now));

        polled.poll();

        verify(tc).triggerRunAll("tc", PR, false);
    }

    @Test
    void aCommandReadTwiceInOverlappingWindowsRunsOnce() {
        repo.add(runAll(1L, now, now));

        commands.poll();
        commands.poll();

        verify(tc, times(1)).triggerRunAll("tc", PR, false);
    }

    /** The checker edits the comment of a /run-all it ran yesterday; GitHub lists it as updated now. */
    @Test
    void aCommandWrittenYesterdayAndEditedNowDoesNotRunAgain(@TempDir Path dir) throws Exception {
        long yesterday = now - 25 * 3600_000L;
        lastGoodPollAt(now - 60_000L, Map.of(1L, yesterday), dir);
        commands.poll();
        repo.add(runAll(1L, yesterday, now));

        commands.poll();

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(tc, never()).cancelOwnRunAllChains(anyString(), anyInt(), anyString());
    }

    @Test
    void anOldCommentEditedIntoACommandDoesNotRun() {
        repo.add(runAll(1L, now - 3 * 3600_000L, now));

        commands.poll();

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void handledCommandsAreRememberedForWeeks(@TempDir Path dir) throws Exception {
        lastGoodPollAt(now - 60_000L, Map.of(1L, now - 2 * 24 * 3600_000L), dir);

        commands.poll();
        Path saved = dir.resolve("saved.json");
        commands.saveTo(saved);

        assertThat(mapper.readTree(saved.toFile()).get("handled").has("1")).isTrue();
    }

    /** Restores the poll's state as a restart would: the end of the last good window and what it handled. */
    private void lastGoodPollAt(long sinceMs, Map<Long, Long> handled, Path dir) throws Exception {
        Path file = dir.resolve("pr-commands.json");
        Files.writeString(file, mapper.writeValueAsString(Map.of("sinceMs", sinceMs, "handled", handled,
            "handledTotal", handled.size())));
        commands.loadFrom(file);
    }

    private static Listed runAll(long id, long createdAt, long updatedAt) {
        return new Listed(new GithubClient.IssueComment(id, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-" + id,
            Instant.ofEpochMilli(createdAt).toString(), new GithubClient.GhUser("NSAmelchev")), updatedAt);
    }

    /** A comment and its {@code updated_at}, which is what GitHub's {@code since} filters on. */
    private record Listed(GithubClient.IssueComment comment, long updatedAt) {
    }
}
