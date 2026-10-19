package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * PR 13335 had Post now and a one-shot Auto visa beside the verdict, which kept the JIRA token of whoever pressed
 * them. A visa comes only from Visa my runs in JIRA of the run's starter now, and a line under the verdict says
 * where that visa is: from what the settle did with the run's visa, the run itself and the starter's option. Of
 * another user's run it tells what became of the run and its visa, whose option it is and whether it is on, and
 * nothing more.
 */
class VisaLineTest {
    private static final String USER = "avinogradov";

    private static final String REVIEWER = "nizhikov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private static final String COMMENT_URL = "https://issues.example/browse/" + ISSUE
        + "?focusedCommentId=18000001#comment-18000001";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(jira.commentUrl(ISSUE, "18000001")).thenReturn(COMMENT_URL);
        titled(ISSUE + " Hot reload of SSL certificates");
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.buildRevision(eq("tc"), anyLong())).thenReturn(Optional.of("9f1c2e7a"));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(new AnalysisResult(PR, RUN_ALL,
            "pull/13335/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140,
            1, false, 0, false, 0, 0, 0, 1, 0)));
    }

    @Test
    void aPostedVisaLinksItsComment() {
        visaOn();
        jiraTakesTheVisa();
        standing.sweep();

        assertThat(line(USER, 0, false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, USER, true, ISSUE, COMMENT_URL, false));
        assertThat(line(REVIEWER, 0, true))
            .as("the visa is in, though the PR was merged since")
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, USER, false, ISSUE, COMMENT_URL,
                false));
    }

    /** avinogradov switched his only option off: his visa of the run is still in the ticket, and the line says so. */
    @Test
    void aPostedVisaIsToldAfterItsOwnerSwitchedEveryOptionOff(@TempDir Path dir) throws IOException {
        visaOn();
        jiraTakesTheVisa();
        standing.sweep();
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, null, null, null, null));

        StandingVisas.VisaLine posted = new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, USER, false, ISSUE,
            COMMENT_URL, false);
        assertThat(standing.enabled(USER)).isFalse();
        assertThat(line(REVIEWER, 0, false)).isEqualTo(posted);

        Path file = dir.resolve(standing.fileName());
        standing.saveTo(file);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);
        assertThat(restarted.visaLine(REVIEWER, PR, RUN_ALL, () -> run(USER, false, 0), false))
            .as("after a restart").isEqualTo(posted);
    }

    /**
     * alice's visa of PR 13655 was posted by v1.20.11, which kept the comment, not the ticket. The ticket is the one
     * the PR title names while the PR is open; after that the line names none and links nothing.
     */
    @Test
    void aVisaOfV12011NamesTheTicketOfThePrTitle(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("standing-visas.json");
        try (InputStream in = getClass().getResourceAsStream("/snapshots/v1.20.11/standing-visas.json")) {
            Files.copy(in, file);
        }
        standing.loadFrom(file);
        String url = "https://issues.example/browse/IGNITE-26512?focusedCommentId=18012345#comment-18012345";
        when(jira.commentUrl("IGNITE-26512", "18012345")).thenReturn(url);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13655, "IGNITE-26512 Thin client: retry on topology "
            + "change", null, null, null, null)));

        assertThat(standing.visaLine("alice", 13655, 8743210, () -> run("alice", false, 0), false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, "alice", true, "IGNITE-26512", url,
                false));

        when(github.openPrs()).thenReturn(List.of());
        assertThat(standing.visaLine("alice", 13655, 8743210, () -> run("alice", false, 0), false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, "alice", true, null, null, false));
    }

    @Test
    void theStartersOptionOnPostsItOnceTheRunSettles() {
        visaOn();

        assertThat(line(USER, 0, false).state())
            .as("the run still goes").isEqualTo(StandingVisas.VisaLine.DUE);
        assertThat(line(USER, System.currentTimeMillis() + 5, false).state())
            .isEqualTo(StandingVisas.VisaLine.DUE);
    }

    @Test
    void aVisaJiraFailedIsTriedAgain() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        standing.sweep();

        assertThat(line(USER, 0, false).state()).isEqualTo(StandingVisas.VisaLine.RETRYING);
    }

    @Test
    void aRunThatFinishedBeforeTheOptionWentOnGetsNone() {
        long finished = System.currentTimeMillis() - 3_600_000;
        visaOn();

        assertThat(line(USER, finished, false).state()).isEqualTo(StandingVisas.VisaLine.LATE);
    }

    /**
     * nizhikov opens avinogradov's run, whose visa is late, held back by a refused TeamCity token, or failed by JIRA:
     * the line tells him only that avinogradov's option is on.
     */
    @Test
    void ofSomeoneElsesRunTheLineTellsOnlyThatTheirOptionIsOn() {
        long finished = System.currentTimeMillis() - 3_600_000;
        visaOn();
        StandingVisas.VisaLine on = StandingVisas.VisaLine.of(StandingVisas.VisaLine.ON, USER, false);

        assertThat(line(REVIEWER, finished, false)).as("late for its starter").isEqualTo(on);
        assertThat(line(REVIEWER, System.currentTimeMillis() + 5, false)).as("due").isEqualTo(on);

        standing.markTcRejected(USER);
        assertThat(line(USER, 0, false).state()).isEqualTo(StandingVisas.VisaLine.PAUSED);
        assertThat(line(REVIEWER, 0, false)).as("paused for its starter").isEqualTo(on);
    }

    @Test
    void aVisaJiraFailedIsNotToldToSomeoneElse() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        standing.sweep();

        assertThat(line(REVIEWER, 0, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.ON, USER, false));
    }

    @Test
    void anOptionOffIsSaidWithWhoseItIs() {
        assertThat(line(USER, 0, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.OFF, USER, true));
        assertThat(standing.visaLine(USER, PR, RUN_ALL, () -> run(REVIEWER, false, 0), false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.OFF, REVIEWER, false));
    }

    /** JIRA refused the token, which took the option off: its owner learns why, a reviewer only that it is off. */
    @Test
    void aRefusedJiraTokenIsToldToItsOwnerAlone() {
        visaOn();
        standing.dropJiraToken(USER);

        assertThat(line(USER, 0, false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.OFF, USER, true, null, null, true));
        assertThat(line(REVIEWER, 0, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.OFF, USER, false));
    }

    @Test
    void optionsOnARefusedTeamCityTokenWait() {
        visaOn();
        standing.markTcRejected(USER);

        assertThat(line(USER, 0, false).state()).isEqualTo(StandingVisas.VisaLine.PAUSED);
    }

    /**
     * avinogradov's only RunAll of PR 13335 was cancelled. The page shows it, as no clean run is there, and the sweep
     * never settles a cancelled run: no visa comes, and the line says why.
     */
    @Test
    void aCancelledRunGetsNone() {
        visaOn();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.empty());
        standing.sweep();
        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(standing.visaLine(REVIEWER, PR, RUN_ALL, () -> run(USER, true, System.currentTimeMillis()), false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.CANCELLED, USER, false));
    }

    /**
     * The run settled while the title of PR 13335 named no ticket, and the author put IGNITE-28867 in it after. No
     * visa of that run is coming, and the line names the reason, not a late option.
     */
    @Test
    void aRunSettledWithoutATicketSaysSoAfterTheTitleGotOne() {
        visaOn();
        titled("Hot reload of SSL certificates");
        standing.sweep();
        titled(ISSUE + " Hot reload of SSL certificates");
        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(line(USER, 0, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.NO_TICKET, USER, true));
        assertThat(line(REVIEWER, 0, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.NO_TICKET, USER, false));
    }

    /** What became of the PR or of a newer run tells it before TeamCity is asked who started this one. */
    @Test
    void aNewerRunAClosedPrOrAnUnknownStarterGetNone() {
        visaOn();

        assertThat(standing.visaLine(USER, PR, RUN_ALL, VisaLineTest::notAsked, true))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.CLOSED, null, false));
        assertThat(standing.visaLine(USER, PR, RUN_ALL, () -> StandingVisas.Run.UNKNOWN, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.UNKNOWN, null, false));
        when(tracker.newestChainUnderWay(PR)).thenReturn(RUN_ALL + 500);
        assertThat(standing.visaLine(USER, PR, RUN_ALL, VisaLineTest::notAsked, false))
            .isEqualTo(StandingVisas.VisaLine.of(StandingVisas.VisaLine.REPLACED, null, false));
    }

    /** The line of avinogradov's run, which finished at {@code finishedMs}, 0 while it goes. */
    private StandingVisas.VisaLine line(String viewer, long finishedMs, boolean merged) {
        return standing.visaLine(viewer, PR, RUN_ALL, () -> run(USER, false, finishedMs), merged);
    }

    private static StandingVisas.Run run(String starter, boolean cancelled, long finishedMs) {
        return new StandingVisas.Run(starter, cancelled, finishedMs);
    }

    private static StandingVisas.Run notAsked() {
        throw new AssertionError("TeamCity is asked about the run");
    }

    private void titled(String title) {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, title, null, null, null, null)));
    }

    private void jiraTakesTheVisa() {
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", COMMENT_URL));
    }

    private void visaOn() {
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null));
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc,
            github, analyzer, jira, new VisaService(new TeamcityProperties("https://ci2.example/"),
            "https://checker.example"), tracker, mock(Warmer.class), mock(PendingCommits.class));
    }
}
