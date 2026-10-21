package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * On python#69 the author's Visa my runs in JIRA put the visa of build 9424005 into IGNITE-29100, and a reviewer posted
 * their own visa of the run with Post to JIRA. The checker kept one record of the run's visa, and the reviewer's press
 * took it. So the author's next press added a second comment instead of editing theirs, and their option's next run of
 * the same commits posted the same visa again. A press of the author while the sweep posted the visa their option owed
 * added a second comment too. Each user now has their own visa of a run, which a press of anyone else leaves alone.
 */
class OnePageVisaPerUserTest {
    private static final String AUTHOR = "author";

    private static final String REVIEWER = "reviewer";

    private static final String CLIENT = "apache/ignite-python-thin-client";

    private static final int CLIENT_PR = 69;

    private static final long BUILD = 9424005L;

    private static final int PR = 13709;

    private static final long RUN = 9425992L;

    private static final String ISSUE = "IGNITE-29100";

    private static final String AUTHORS = "18000001";

    private static final String REVIEWERS = "18000002";

    private static final SettingsJournal.By LAPTOP = SettingsJournal.By.of("laptop", "Firefox on Linux");

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(jira.myself(anyString())).thenReturn(Optional.of("Someone"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment(AUTHORS, url(AUTHORS)));
        when(jira.addCommentWithId(eq("jira-pat-2"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment(REVIEWERS, url(REVIEWERS)));
        when(jira.commentUrl(eq(ISSUE), anyString())).thenAnswer(inv -> url(inv.getArgument(1)));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Fix the control utility", null, null,
            null, null)));
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(RUN, "FAILURE",
            "finished", "pull/13709/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null)));
        standing.change(AUTHOR, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null),
            null, LAPTOP);
        standing.change(REVIEWER, "tc2", "jira-pat-2", null, new StandingVisas.OptionChange(true, false, false, false,
            null), null, LAPTOP);
    }

    /** The author presses, the reviewer presses, the author presses again: the author's comment is edited. */
    @Test
    void anotherUsersPressLeavesYourVisaOfTheRunYours() {
        press(AUTHOR);
        press(REVIEWER);
        StandingVisas.PagePost again = press(AUTHOR);

        assertThat(again).isEqualTo(new StandingVisas.PagePost(StandingVisas.PagePost.EDITED, ISSUE, url(AUTHORS), 0));
        verify(jira, times(1)).addCommentWithId(eq("jira-pat"), anyString(), anyString());
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(AUTHORS), anyString());
        verify(jira, never()).updateComment(eq("jira-pat-2"), anyString(), anyString(), anyString());
    }

    /**
     * The author's option posted the visa of the run, then the reviewer pressed. The run's visa on the page is still the
     * author's for everyone but the reviewer, who sees theirs, and the author's press edits the option's comment.
     */
    @Test
    void theOptionsVisaStaysTheRunsVisa() {
        assertThat(option(BUILD).state()).isEqualTo(ClientVisa.POSTED);
        press(REVIEWER);

        assertThat(standing.visaButton(AUTHOR, CLIENT, CLIENT_PR, BUILD).yoursIn()).isEqualTo(ISSUE);
        assertThat(standing.visaButton(REVIEWER, CLIENT, CLIENT_PR, BUILD).yoursIn()).isEqualTo(ISSUE);
        assertThat(standing.clientVisaLine("someone", CLIENT, CLIENT_PR, BUILD, null)).isEqualTo(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, AUTHOR, false, ISSUE, url(AUTHORS), false));
        assertThat(standing.clientVisaLine(REVIEWER, CLIENT, CLIENT_PR, BUILD, null)).isEqualTo(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, REVIEWER, true, ISSUE, url(REVIEWERS), false));

        assertThat(press(AUTHOR).outcome()).isEqualTo(StandingVisas.PagePost.EDITED);
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(AUTHORS), anyString());
    }

    /** The reviewer's press leaves the author's last visa the one their option compares the next run of the commits to. */
    @Test
    void anotherUsersPressDoesNotMakeYourOptionRepeatItself() {
        option(BUILD);
        press(REVIEWER);

        assertThat(option(BUILD + 300).state()).isEqualTo(ClientVisa.SAME);
        verify(jira, times(1)).addCommentWithId(eq("jira-pat"), anyString(), anyString());
    }

    /**
     * JIRA failed the author's option, which tries again. The reviewer's press does not tell the author's line that the
     * visa is in: theirs is still to come. The reviewer sees their own.
     */
    @Test
    void aPressDoesNotHideThatTheStartersVisaIsStillToCome() {
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(PR, RUN)));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        standing.settle(new PrSummary(PR, ISSUE + " Fix the control utility", null, null, null, null));
        Supplier<StandingVisas.Run> run = () -> new StandingVisas.Run(AUTHOR, false, 1);

        standing.postVisaFromPage(REVIEWER, LAPTOP, "tc2", PR, RUN, ISSUE, verdict(PR, RUN));

        assertThat(standing.visaLine(AUTHOR, PR, RUN, run, false).state()).isEqualTo(StandingVisas.VisaLine.RETRYING);
        assertThat(standing.visaLine(REVIEWER, PR, RUN, run, false)).isEqualTo(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, REVIEWER, true, ISSUE, url(REVIEWERS), false));
    }

    /**
     * JIRA failed the author's option, and the sweep posts the visa again. The author presses while JIRA has not
     * answered the sweep yet: the press waits for it, then edits the visa the sweep put in.
     */
    @Test
    void aPressWhileTheSweepPostsTheOwedVisaEditsIt() throws InterruptedException {
        PrSummary summary = new PrSummary(PR, ISSUE + " Fix the control utility", null, null, null, null);
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(PR, RUN)));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        standing.settle(summary);
        Thread press = new Thread(() -> standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE,
            verdict(PR, RUN)));
        doAnswer(inv -> {
            if (Thread.currentThread() == press)
                return new JiraClient.PostedComment("18000003", url("18000003"));

            press.start();
            long until = System.currentTimeMillis() + 5_000;
            while (press.getState() != Thread.State.BLOCKED && press.isAlive() && System.currentTimeMillis() < until)
                Thread.onSpinWait();

            return new JiraClient.PostedComment(AUTHORS, url(AUTHORS));
        }).when(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());

        standing.settle(summary);
        press.join(5_000);

        verify(jira, times(2)).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(AUTHORS), anyString());
    }

    /** Each user's visa of the run outlives a restart: the presses after it edit the author's and the reviewer's own. */
    @Test
    void theVisasOfEachUserAreSaved(@TempDir Path dir) throws Exception {
        option(BUILD);
        press(REVIEWER);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        standing = standing();
        standing.loadFrom(file);

        press(AUTHOR);
        press(REVIEWER);

        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(AUTHORS), anyString());
        verify(jira).updateComment(eq("jira-pat-2"), eq(ISSUE), eq(REVIEWERS), anyString());
        verify(jira, times(1)).addCommentWithId(eq("jira-pat"), anyString(), anyString());
        verify(jira, times(1)).addCommentWithId(eq("jira-pat-2"), anyString(), anyString());
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, jira,
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);
    }

    private StandingVisas.PagePost press(String user) {
        return standing.postClientVisaFromPage(user, LAPTOP, CLIENT, CLIENT_PR, BUILD, ISSUE,
            verdict(CLIENT_PR, BUILD), "the verdict");
    }

    /** What the author's option does with the visa of their run {@code build} of the same commits as {@link #BUILD}. */
    private ClientVisa option(long build) {
        return standing.clientVisa(AUTHOR, CLIENT, CLIENT_PR, build, System.currentTimeMillis() + 1000,
            verdict(CLIENT_PR, build), "the option's visa",
            () -> Optional.of(new GithubClient.PullState(ISSUE + " Fix the test", true, false)));
    }

    private static String url(String comment) {
        return "https://issues.example/browse/IGNITE-29100?focusedCommentId=" + comment + "#comment-" + comment;
    }

    private static AnalysisResult verdict(int pr, long build) {
        return new AnalysisResult(pr, build, "pull/" + pr, 1, List.of(), List.of(), List.of(), List.of(), List.of(),
            1, 0, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0, "1f2e3d4+a1b2c3d", 0);
    }
}
