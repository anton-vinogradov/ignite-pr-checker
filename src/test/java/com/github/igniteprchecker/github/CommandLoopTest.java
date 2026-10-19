package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The checker's own comments are read back by its command poll, so nothing it posts may look like a
 * command. Taking a narration over from a dead PAT re-posted the author's comment verbatim — first
 * line "/runall" included — and the poll obligingly triggered another RunAll on two PRs. Its account now tells each
 * story in a comment of its own, in its own words: what a commander wrote, after a "---" or not, is never posted.
 */
class CommandLoopTest {
    private static final int PR = 13800;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    @Test
    void theStoryCarriesNothingTheCommanderWrote() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author"); // told already: this test follows the run, not the line on a new link
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(new TcModel.Build(9400000L, null, "queued",
            "pull/13800/head", null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L,
            "/runall\n\n---\n/run-all top, and ping @someone", "https://github.com/apache/ignite/pull/" + PR
            + "#issuecomment-1", Instant.now().toString(), GhUsers.of("Author"))));

        new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"))
            .poll();

        ArgumentCaptor<String> story = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), eq("author"), story.capture());
        assertThat(story.getValue()).startsWith("@Author 🚀 **RunAll queued**").doesNotContain("/runall")
            .doesNotContain("ping").doesNotContain("@someone");
        assertThat(PrCommands.readsAsCommand(story.getValue())).isFalse();
    }

    @Test
    void theCommandItselfStillParses() {
        assertThat(PrCommands.readsAsCommand("/runall")).isTrue();
        assertThat(PrCommands.readsAsCommand("/run-all top")).isTrue();
        assertThat(PrCommands.readsAsCommand("/top")).isTrue();
        assertThat(PrCommands.readsAsCommand("please /runall")).isFalse();
    }
}
