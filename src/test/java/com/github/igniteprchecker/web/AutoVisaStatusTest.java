package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The Auto visa button showed one state per PR: a reviewer saw the author's "Armed — click to cancel"
 * and could cancel it, and it offered a one-shot visa where the author's standing auto-visa already
 * posts the verdict. Whose standing visa that is was then read from the PR's last finished RunAll, so
 * a run under way started by someone else could not be armed.
 */
class AutoVisaStatusTest {
    private static final int PR = 13335;

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final JiraClient jira = mock(JiraClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final RerunTracker tracker = new RerunTracker(tc, mock(Warmer.class),
        new AnalysisProperties(null, RUN_ALL, null, null, null, null, null), mapper,
        mock(ApplicationEventPublisher.class));

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, jira,
        mock(VisaService.class), tracker, mock(Warmer.class), mock(PendingCommits.class));

    private final VisaSubscriptions subs = new VisaSubscriptions(mapper, codec, jira, mock(VisaService.class),
        analyzer, mock(Warmer.class), mock(PendingCommits.class), tc, standing);

    private final JiraController controller = new JiraController(jira, analyzer, codec, mock(VisaService.class),
        subs, standing, github, mock(PendingCommits.class), false);

    @BeforeEach
    void setUp() {
        when(jira.myself("author-pat")).thenReturn(Optional.of("author"));
        when(github.openPrs()).thenReturn(List.of(
            new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates", null, null, null, null)));
        controller.armAutoVisa(PR, "IGNITE-28867", "author", "author-pat");
    }

    @Test
    void eachUserSeesTheirOwnSubscriptionAndWhoElseArmedOne() {
        assertThat(controller.autoVisaStatus(PR, "author")).containsEntry("armed", true)
            .containsEntry("issue", "IGNITE-28867").containsEntry("others", List.of());
        assertThat(controller.autoVisaStatus(PR, "reviewer")).containsEntry("armed", false)
            .containsEntry("others", List.of("author")).doesNotContainKey("issue");
    }

    @Test
    void aReviewersCancelLeavesTheAuthorsArmed() {
        controller.cancelAutoVisa(PR, "reviewer");

        assertThat(controller.autoVisaStatus(PR, "author")).containsEntry("armed", true);
    }

    @Test
    void aRunStartedBySomeoneWithTheStandingVisaOnSaysSo() {
        standingVisaOn("author");
        runUnderWay(9400000L, "author");

        assertThat(controller.autoVisaStatus(PR, "reviewer")).containsEntry("standingBy", "author");
    }

    @Test
    void withoutTheStandingVisaTheButtonStays() {
        runUnderWay(9400000L, "author");

        assertThat(controller.autoVisaStatus(PR, "reviewer")).doesNotContainKey("standingBy");
    }

    /** Alice's run finished yesterday; the run under way now is Bob's, and his has no standing visa. */
    @Test
    void theRunUnderWayDecidesNotTheLastFinishedOne() {
        standingVisaOn("alice");
        when(analyzer.triggeredBy(PR)).thenReturn("alice");
        runUnderWay(9400200L, "bob");

        assertThat(controller.autoVisaStatus(PR, "bob")).doesNotContainKey("standingBy");
    }

    @Test
    void noRunUnderWayNoStandingVisaToPointAt() {
        standingVisaOn("alice");
        when(analyzer.triggeredBy(PR)).thenReturn("alice");

        assertThat(controller.autoVisaStatus(PR, "bob")).doesNotContainKey("standingBy");
    }

    /** TeamCity refused the token the author's options run on: their standing visa posts nothing. */
    @Test
    void aPausedStandingVisaIsNotPointedAt() {
        standingVisaOn("author");
        runUnderWay(9400000L, "author");
        standing.markTcRejected("author");

        assertThat(controller.autoVisaStatus(PR, "reviewer")).doesNotContainKey("standingBy");
    }

    private void standingVisaOn(String user) {
        when(jira.myself(user + "-pat")).thenReturn(Optional.of(user));
        standing.change(user, user + "-tc", user + "-pat", null,
            new StandingVisas.OptionChange(true, false, false, false, null));
    }

    /** The PR's runs as the page lists them: the chain under way, with who started it. */
    private void runUnderWay(long chainId, String starter) {
        tracker.record(PR, new TcModel.Build(chainId, null, "running", "pull/13335/head", RUN_ALL, null, null, null,
            null, null, null, null, new TcModel.Triggered("user", new TcModel.User(starter)), null, null, null, null,
            null));
    }
}
