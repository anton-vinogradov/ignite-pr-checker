package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

/**
 * The times in a /run-all story came from the JIRA profile, asked at every command poll for users without one: with
 * JIRA not answering, the poll stopped before it read a comment, and nobody's /run-all or /top ran. The JIRA token is
 * for visas only now; the time zone is the browser's.
 */
class BrowserTimeZoneTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private final AtomicBoolean jiraDown = new AtomicBoolean();

    private final JiraClient jira = mock(JiraClient.class, withSettings().defaultAnswer(inv -> {
        if (jiraDown.get())
            throw new ResourceAccessException("JIRA did not answer");

        return Answers.RETURNS_DEFAULTS.answer(inv);
    }));

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    @Test
    void aRunAllRunsWhileJiraIsDownAndItsStoryIsInTheBrowsersTimeZone() {
        visaOn("Europe/Moscow");

        assertThat(runAllWhileJiraIsDown()).containsPattern("expected to finish \\*\\*≈ \\d\\d:\\d\\d MSK\\*\\*");
    }

    /** As standing-visas.json of the release before has it: an auto visa with a JIRA token, and no zone yet. */
    @Test
    void aRunAllOfAUserWithNoZoneRunsWhileJiraIsDownAndItsStoryIsInUtc() {
        visaOn(null);

        assertThat(runAllWhileJiraIsDown()).containsPattern("expected to finish \\*\\*≈ \\d\\d:\\d\\d UTC\\*\\*");
    }

    @Test
    void aZoneThatIsNoneIsIgnoredAndOneSentLaterReplacesIt() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"), " Europe/Moscow ");

        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null),
            "Mars/Olympus_Mons");

        assertThat(standing.actor("author")).hasValueSatisfying(a -> assertThat(a.tz()).isEqualTo("Europe/Moscow"));

        standing.change("author", "tc", null, null, new StandingVisas.OptionChange(null, false, null, null, null),
            "Asia/Novosibirsk");

        assertThat(standing.actor("author")).hasValueSatisfying(a -> assertThat(a.tz()).isEqualTo("Asia/Novosibirsk"));
    }

    private void visaOn(String browserTz) {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.change("author", "tc", "jira-pat", null, new StandingVisas.OptionChange(true, null, null, null, null),
            browserTz);
    }

    /** The story of the author's /run-all, polled and timed while every JIRA call fails. */
    private String runAllWhileJiraIsDown() {
        jiraDown.set(true);
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of("Author"))));
        TcModel.Build queued = new TcModel.Build(CHAIN, null, "queued", "pull/13800/head", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(queued);
        when(tc.getBuildState("tc", CHAIN)).thenReturn(queued);
        when(tc.chainRemainingSeconds(eq("tc"), eq(CHAIN), any())).thenReturn(3600L);
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(77L, "https://github.com/apache/ignite/pull/13800#c77"));
        when(github.appEdit(anyInt(), any(), anyLong(), anyString())).thenReturn(true);
        PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
            mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
            new TeamcityProperties("https://ci2.example/"));

        commands.poll();
        commands.updateEtas();

        verify(tc).triggerRunAll("tc", PR, false);
        ArgumentCaptor<String> story = ArgumentCaptor.forClass(String.class);
        verify(github).appEdit(anyInt(), any(), eq(77L), story.capture());

        return story.getValue();
    }
}
