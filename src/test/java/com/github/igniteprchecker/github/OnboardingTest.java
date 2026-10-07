package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * PR commands used to need a standing option, and the reply to a stranger's /run-all was 250 words
 * advising to switch on auto re-run, which loads ci2, just to use commands. Every stranger in a PR
 * got such a reply, and every repeat of the same command another 😕. Commands now have their own
 * switch, and the reply is short and rationed.
 */
class OnboardingTest {
    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private PrCommands commands = commands();

    private final List<GithubClient.IssueComment> comments = new ArrayList<>();

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("avinogradov", "tc", "anton-vinogradov"); // someone has to have commands on
        when(github.recentIssueComments(anyString())).thenAnswer(inv -> {
            List<GithubClient.IssueComment> batch = List.copyOf(comments);
            comments.clear();

            return batch;
        });
        when(github.addPrCommentAsApp(anyInt(), anyString())).thenReturn(true);
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void theOnlySwitchCommandsNeedIsTheirOwn() {
        command(13700, "anton-vinogradov");

        verify(tc).triggerRunAll("tc", 13700, false);
        verify(github, never()).addPrCommentAsApp(anyInt(), anyString());
    }

    @Test
    void someoneWithOtherOptionsButCommandsOffIsToldHowToSwitchThemOn() {
        standing.change("nsamelchev", "tc-2", null, null, new StandingVisas.OptionChange(null, true, null, null, null));

        command(13701, "NSAmelchev");

        verify(tc, never()).triggerRunAll(eq("tc-2"), anyInt(), anyBoolean());
        verify(github).addPrCommentAsApp(eq(13701), anyString());
    }

    @Test
    void theReplyIsShortAndPointsAtTheCommandsSwitch() {
        command(13702, "stranger");

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(github).addPrCommentAsApp(eq(13702), reply.capture());
        assertThat(reply.getValue()).contains("**PR commands**").doesNotContainIgnoringCase("re-run");
        assertThat(reply.getValue().split("\\s+")).hasSizeLessThan(100);
        assertThat(PrCommands.readsAsCommand(reply.getValue())).isFalse();
    }

    @Test
    void onePrGetsOneReplyAndARepeatNoSecondConfusedReaction() {
        command(13703, "stranger1");
        command(13703, "stranger1");
        command(13703, "stranger2");

        verify(github, times(1)).addPrCommentAsApp(eq(13703), anyString());
        verify(github, times(2)).reactToCommentAsApp(anyLong(), eq("confused"));
    }

    @Test
    void atMostFiveRepliesADayEvenAcrossARestart(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 3; i++)
            command(13710 + i, "stranger" + i);
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);
        commands = commands();
        commands.loadFrom(file);

        for (int i = 3; i < 7; i++)
            command(13710 + i, "stranger" + i);

        verify(github, times(5)).addPrCommentAsApp(anyInt(), anyString());
    }

    private void command(int pr, String login) {
        long id = nextId++;
        comments.add(new GithubClient.IssueComment(id, "/run-all",
            "https://github.com/apache/ignite/pull/" + pr + "#issuecomment-" + id, "2026-10-07T10:00:00Z",
            new GithubClient.GhUser(login)));
        commands.poll();
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }
}
