package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The autofix report now lists the violations it left in a folded block. GitHub reads everything up to the next
 * blank line after such a block as HTML, so the RunAll line right below it came out as raw text.
 */
class AutofixNoteTest {
    private static final int PR = 13800;

    private static final String NOTE = "🎨 _Checkstyle autofix: fixed 1 of 2 violation(s)._\n\n<details><summary>1"
        + " violation(s) left</summary>\n\n- `p/C.java:6` — MethodName\n\n</details>";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        styleFix, mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));

    @Test
    void theRunAllLineStandsApartFromTheFoldedList() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Author"));
        standing.change("author", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, false, false, true, true));
        when(styleFix.fixForCommand(eq(PR), any(), eq("Author"))).thenReturn(NOTE);
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(new TcModel.Build(9400000L, null, "queued",
            "pull/13800/head", "IgniteTests24Java8_RunAll", null, null, null, null, null, null, null, null, null, null,
            null, null, null));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            new GithubClient.GhUser("Author"))));

        commands.poll();

        ArgumentCaptor<String> ack = ArgumentCaptor.forClass(String.class);
        verify(github).updatePrComment(eq("gh-pat"), eq(1L), ack.capture());
        assertThat(ack.getValue()).contains("</details>\n\n🚀 **RunAll queued**");
    }
}
