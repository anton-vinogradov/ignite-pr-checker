package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 13 to 15 of the 50 PRs the sweep goes through name no IGNITE ticket in their title ("MINOR: …", "Bump …",
 * "For TC debug"), and the sweep skipped them before anything else: no auto re-run, no PR comment, though
 * neither needs JIRA. A title spelled "ignite-28867" was skipped the same way.
 */
class TicketlessPrTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13662;

    private static final long RUN_ALL = 9401000L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer, jira,
        new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"), tracker,
        mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13662#c555"));
        when(jira.addCommentWithId(eq("jira-pat"), anyString(), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/IGNITE-28867"));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13662/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9401100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
    }

    @Test
    void reRunsAndThePrCommentNeedNoTicket() {
        title("MINOR: Fix javadoc of GridCacheProcessor");
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(false, true, true, false, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));

        standing.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("tc"), eq("Cache1"), eq(PR), anyBoolean(), anyString());

        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
        standing.sweep();

        verify(github).addPrComment(eq("gh-pat"), eq(PR), contains("No blockers"));
        assertThat(standing.buildHandled(USER, PR, RUN_ALL)).isTrue();
    }

    @Test
    void withoutATicketTheRunIsSettledWithoutAVisa() {
        title("Ignite 29031 my");
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));

        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(standing.buildHandled(USER, PR, RUN_ALL)).isTrue();
    }

    @Test
    void aTicketInLowerCaseIsTheTicket() {
        title("ignite-28867 Hot reload of SSL certificates");
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));

        standing.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq("IGNITE-28867"), anyString());
    }

    @Test
    void aTicketIsAnIgniteKeyWithItsNumber() throws Exception {
        Method ticketIn = StandingVisas.class.getDeclaredMethod("ticketIn", String.class);
        ticketIn.setAccessible(true);

        assertThat(ticketIn.invoke(null, "IGNITE-28867 Hot reload")).isEqualTo(Optional.of("IGNITE-28867"));
        assertThat(ticketIn.invoke(null, "[ignite-28867] Hot reload")).isEqualTo(Optional.of("IGNITE-28867"));
        assertThat(ticketIn.invoke(null, "Port the fix from Ignite 3")).isEqualTo(Optional.empty());
        assertThat(ticketIn.invoke(null, "Bump netty from 4.1.118 to 4.1.124")).isEqualTo(Optional.empty());
        assertThat(ticketIn.invoke(null, "IGNITE-3 is not a ticket of ours")).isEqualTo(Optional.empty());
    }

    private void title(String title) {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, title, null, null, null, null)));
    }

    private static TestVerdict blocker() {
        return new TestVerdict(9401011L, "Cache1Test.test", "Cache1", 9401011L, "Cache 1", "o1", true, false,
            "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, RUN_ALL, "pull/13662/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
    }
}
