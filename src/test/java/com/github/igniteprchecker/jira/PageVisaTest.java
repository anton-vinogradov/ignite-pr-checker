package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * On apache/ignite#13709 RunAll 9425992 found a blocker in Control Utility 5, and its author's Visa my runs in JIRA put
 * it into the ticket. The author fixed the code and re-ran that suite on the PR's head: it passed, and the verdict
 * became a mixed run with no blockers. Nothing could put that verdict into the ticket: S5 had taken the manual visa
 * away. Post to JIRA on the PR page posts the visa of the run now, with the JIRA token the user stored for the option,
 * and a run keeps one visa of theirs: a second press, and a press after their option's visa, edit it in place.
 */
class PageVisaTest {
    private static final String AUTHOR = "author";

    private static final String REVIEWER = "reviewer";

    private static final int PR = 13709;

    private static final long RUN = 9425992L;

    private static final String ISSUE = "IGNITE-29100";

    private static final String COMMIT = "c577b18e2f0d4a6b8c1d3e5f7a9b0c2d4e6f8a1b";

    private static final String HEAD = "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a";

    private static final String COMMENT = "18000001";

    private static final String URL = "https://issues.example/browse/IGNITE-29100?focusedCommentId=18000001"
        + "#comment-18000001";

    private static final String POSTED_BY = "\n\nPosted by ci2 user author from the checker's page.";

    private static final SettingsJournal.By LAPTOP = SettingsJournal.By.of("laptop", "Firefox on Linux");

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, jira, visas,
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);

    @BeforeEach
    void setUp() {
        when(jira.myself(anyString())).thenReturn(Optional.of("Someone"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment(COMMENT, URL));
        when(jira.commentUrl(ISSUE, COMMENT)).thenReturn(URL);
        when(github.openPrs()).thenReturn(List.of(summary()));
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(RUN, "FAILURE",
            "finished", "pull/13709/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null)));
        standing.change(AUTHOR, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null),
            null, LAPTOP);
    }

    /**
     * The settle posted the visa with the blocker; after the re-run the author posts the mixed run's verdict. The same
     * comment gets the text the automatic visa has for that verdict, and a last line that names who posted it.
     */
    @Test
    void thePageEditsTheOptionsVisaOfTheRunWithTheVerdictAsItIsNow() {
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(withBlocker()));
        standing.settle(summary());
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());

        StandingVisas.PagePost posted = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(COMMENT), body.capture());
        assertThat(body.getValue()).isEqualTo(visas.compose(PR, mixed(), null, null) + POSTED_BY)
            .startsWith("(!) *No blockers found in a mixed run* — ")
            .contains("\n\nMixed run — Control Utility 5 re-ran on 9b02561, the rest of the RunAll ran on c577b18; only"
                + " a RunAll of the PR's head can prove it clean.\n\n");
        assertThat(posted).isEqualTo(new StandingVisas.PagePost(StandingVisas.PagePost.EDITED, ISSUE, URL, 0));
        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from, SettingsJournal.View::url)
            .containsExactly("Updated your visa in IGNITE-29100 for PR #13709, RunAll 9425992, with Post to JIRA on"
                + " the PR page.", "from this browser", URL);
    }

    /**
     * Before a press the page shows the visa as it would go. The preview is the text the press then sends, and it
     * sends nothing itself. A user with no JIRA token stored gets none.
     */
    @Test
    void thePreviewIsTheTextThePressSends() {
        Optional<String> preview = standing.visaPreview(AUTHOR, "tc", PR, RUN, mixed());

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux")).extracting(SettingsJournal.View::text)
            .noneMatch(text -> text.contains("Post to JIRA"));

        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        assertThat(preview).hasValue(visas.compose(PR, mixed(), null, null) + POSTED_BY);
        verify(jira).addCommentWithId("jira-pat", ISSUE, preview.get());
        assertThat(standing.visaPreview(REVIEWER, "tc", PR, RUN, mixed())).as("no JIRA token stored").isEmpty();
        assertThat(standing.clientVisaPreview(AUTHOR, "the client's visa")).hasValue("the client's visa" + POSTED_BY);
    }

    /**
     * The author read the visa with the blocker in the dialog. Before they pressed, the suite passed on a re-run, and
     * the verdict became the mixed run's. The press carries the key of the text they read, and the visa is another
     * text now: nothing is posted. The next press shows the new text, and its key posts it.
     */
    @Test
    void aVisaThatIsNotTheTextThePageShowedDoesNotGo() {
        String shown = StandingVisas.textKey(standing.visaPreview(AUTHOR, "tc", PR, RUN, withBlocker()).orElseThrow());

        StandingVisas.PagePost changed = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(),
            shown);

        assertThat(changed).isEqualTo(StandingVisas.PagePost.of(StandingVisas.PagePost.CHANGED));
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux")).extracting(SettingsJournal.View::text)
            .noneMatch(text -> text.contains("Post to JIRA"));

        String now = standing.visaPreview(AUTHOR, "tc", PR, RUN, mixed()).orElseThrow();
        StandingVisas.PagePost posted = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(),
            StandingVisas.textKey(now));

        assertThat(posted.outcome()).isEqualTo(StandingVisas.PagePost.POSTED);
        verify(jira).addCommentWithId("jira-pat", ISSUE, now);
    }

    /**
     * The PR page offers Update only for a visa the verdict left behind. The author's option posted the visa of the
     * run with its blocker. While the checker holds that verdict their visa is current; once the suite passed on a
     * re-run it is not, until their press updates it. The checker cannot tell while it holds no verdict of the run,
     * and tells nothing to a reviewer, who has no visa of the run. A merged PR's kept verdict is the one to compare.
     */
    @Test
    void theVisaIsCurrentUntilTheVerdictChanges() {
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(withBlocker()));
        standing.settle(summary());
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());

        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).as("no verdict held").isNull();
        when(analyzer.held(RUN)).thenReturn(Optional.of(withBlocker()));
        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).isTrue();
        assertThat(standing.visaCurrent(REVIEWER, null, PR, RUN, Optional.empty())).as("no visa of theirs").isNull();
        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.of(mixed()))).as("merged as a mixed run")
            .isFalse();
        when(analyzer.held(RUN)).thenReturn(Optional.of(mixed()));
        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).isFalse();

        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).isTrue();
    }

    /**
     * A file of v1.52.0 keeps the author's visa of the run without what its verdict says. It loads, and the checker
     * cannot tell whether the visa is current until a press updates it.
     */
    @Test
    void aVisaKeptWithoutItsVerdictLoadsAndIsNotToldCurrent(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, """
            {"enrollments": [], "retries": {}, "earlyReruns": {}, "waves": {}, "keyCheck": null,
             "visas": {"%d": {"pr": %d, "by": "author", "issue": "%s", "commentId": "%s"}},
             "pageVisas": {"%d": [{"pr": %d, "by": "author", "issue": "%s", "commentId": "%s"}]}}
            """.formatted(RUN, PR, ISSUE, COMMENT, RUN, PR, ISSUE, COMMENT));
        when(analyzer.held(RUN)).thenReturn(Optional.of(mixed()));

        standing.loadFrom(old);
        standing.change(AUTHOR, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null),
            null, LAPTOP);

        assertThat(standing.visaButton(AUTHOR, null, PR, RUN)).isEqualTo(new StandingVisas.VisaButton(true, ISSUE));
        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).isNull();
        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(COMMENT), anyString());
        assertThat(standing.visaCurrent(AUTHOR, null, PR, RUN, Optional.empty())).isTrue();
    }

    /** The first press posts a comment, the second edits it: a run has one visa of the user's. */
    @Test
    void aSecondPressEditsTheVisaTheFirstPosted() {
        StandingVisas.PagePost first = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, withBlocker(),
            null);
        StandingVisas.PagePost second = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        verify(jira).addCommentWithId("jira-pat", ISSUE, visas.compose(PR, withBlocker(), null, null) + POSTED_BY);
        verify(jira).updateComment("jira-pat", ISSUE, COMMENT, visas.compose(PR, mixed(), null, null) + POSTED_BY);
        assertThat(first.outcome()).isEqualTo(StandingVisas.PagePost.POSTED);
        assertThat(second.outcome()).isEqualTo(StandingVisas.PagePost.EDITED);
        assertThat(standing.visaLine(REVIEWER, PR, RUN, () -> StandingVisas.Run.UNKNOWN, false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, AUTHOR, false, ISSUE, URL, false));
        assertThat(standing.visaButton(AUTHOR, null, PR, RUN)).isEqualTo(new StandingVisas.VisaButton(true, ISSUE));
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux")).extracting(SettingsJournal.View::text)
            .startsWith("Updated your visa in IGNITE-29100 for PR #13709, RunAll 9425992, with Post to JIRA on the PR"
                + " page.", "Posted a visa to IGNITE-29100 for PR #13709, RunAll 9425992, with Post to JIRA on the PR"
                + " page.");
    }

    /**
     * JIRA failed the option's visa at the settle, so the option still owed it. The visa from the page is that visa:
     * the next settle neither posts another nor puts the option's text over it.
     */
    @Test
    void theVisaFromThePageIsTheOneTheOptionOwed() {
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(mixed()));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null))
            .thenReturn(new JiraClient.PostedComment(COMMENT, URL));
        standing.settle(summary());

        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);
        standing.settle(summary());

        verify(jira, times(2)).addCommentWithId(anyString(), anyString(), anyString());
        verify(jira, never()).updateComment(anyString(), anyString(), anyString(), anyString());
    }

    /** JIRA refuses the token itself: the checker drops it and switches the option off, as after an automatic visa. */
    @Test
    void aTokenJiraRefusesIsDroppedAsAfterTheOptionsVisa() {
        when(jira.addCommentWithId(anyString(), anyString(), anyString())).thenThrow(refused(401));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);

        StandingVisas.PagePost posted = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        assertThat(posted.outcome()).isEqualTo(StandingVisas.PagePost.REFUSED);
        assertThat(standing.jiraTokenRejected(AUTHOR)).isTrue();
        assertThat(standing.settings(AUTHOR).visa()).isFalse();
        assertThat(standing.visaButton(AUTHOR, null, PR, RUN).jiraReady()).isFalse();
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux").get(0).text())
            .startsWith("JIRA refused your JIRA token, so the checker dropped it.");
    }

    /** A ticket the user may not comment on: JIRA answers 403, the token stays, and the answer says what JIRA said. */
    @Test
    void aVisaJiraDidNotTakeKeepsTheToken() {
        when(jira.addCommentWithId(anyString(), anyString(), anyString())).thenThrow(refused(403));

        StandingVisas.PagePost posted = standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        assertThat(posted).isEqualTo(new StandingVisas.PagePost(StandingVisas.PagePost.FAILED, null, null, 403));
        assertThat(standing.visaButton(AUTHOR, null, PR, RUN)).isEqualTo(new StandingVisas.VisaButton(true, null));
        assertThat(standing.visaLine(AUTHOR, PR, RUN, () -> StandingVisas.Run.UNKNOWN, false).state())
            .isNotEqualTo(StandingVisas.VisaLine.POSTED);
    }

    /** The JIRA token lives only in the option: a user without it gets nothing posted, and JIRA is not asked. */
    @Test
    void noStoredJiraTokenNoVisa() {
        StandingVisas.PagePost posted = standing.postVisaFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, ISSUE, mixed(),
            null);

        assertThat(posted.outcome()).isEqualTo(StandingVisas.PagePost.NO_TOKEN);
        assertThat(standing.visaButton(REVIEWER, null, PR, RUN)).isEqualTo(new StandingVisas.VisaButton(false, null));
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
    }

    /**
     * A reviewer posts the visa of the author's run, whose option posted one: the reviewer's token cannot edit the
     * author's comment, so theirs is a comment of its own. The author's next press still edits their own comment.
     */
    @Test
    void eachUserHasTheirOwnVisaOfTheRun() {
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(mixed()));
        standing.settle(summary());
        standing.change(REVIEWER, "tc2", "jira-pat-2", null, new StandingVisas.OptionChange(true, false, false, false,
            null), null, LAPTOP);
        when(jira.addCommentWithId(eq("jira-pat-2"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000002", URL + "2"));

        standing.postVisaFromPage(REVIEWER, LAPTOP, "tc2", PR, RUN, ISSUE, mixed(), null);
        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        verify(jira).addCommentWithId(eq("jira-pat-2"), eq(ISSUE), anyString());
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(COMMENT), anyString());
        verify(jira, never()).updateComment(eq("jira-pat-2"), anyString(), anyString(), anyString());
    }

    /** The PR's title names another ticket now: the visa there is a new comment, and the old one stays as it is. */
    @Test
    void aVisaInAnotherTicketIsANewComment() {
        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);
        when(jira.addCommentWithId(eq("jira-pat"), eq("IGNITE-29101"), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000009", URL));

        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, "IGNITE-29101", mixed(), null);

        verify(jira).addCommentWithId(eq("jira-pat"), eq("IGNITE-29101"), anyString());
        verify(jira, never()).updateComment(anyString(), anyString(), anyString(), anyString());
    }

    /** A GitHub account a token proved is named too: the PR's people know each other by it. */
    @Test
    void aProvenGithubLoginIsNamedNextToTheCi2Name() {
        standing.linkGhLogin(AUTHOR, "tc", GhUsers.of("author-gh"));

        standing.postVisaFromPage(AUTHOR, LAPTOP, "tc", PR, RUN, ISSUE, mixed(), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), body.capture());
        assertThat(body.getValue()).as("no mention of a JIRA user of that name")
            .endsWith("\n\nPosted by @\u2060author-gh (ci2 user author) from the checker's page.");
    }

    /**
     * The visa of a run of the Python thin client's suite from the page; the run's option, still trying its own visa,
     * finds it in and posts no second one.
     */
    @Test
    void aClientRunsVisaFromThePageIsTheOneTheOptionPosts() {
        String repo = "apache/ignite-python-thin-client";
        long build = 9424005L;
        AnalysisResult res = new AnalysisResult(69, build, "pull/69", System.currentTimeMillis(), List.of(), List.of(),
            List.of(), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, System.currentTimeMillis() / 1000, 0,
            List.of(), List.of(), List.of(), 0, "1f2e3d4+a1b2c3d", 0);

        StandingVisas.PagePost posted = standing.postClientVisaFromPage(AUTHOR, LAPTOP, repo, 69, build, ISSUE, res,
            "the verdict", null);
        ClientVisa option = standing.clientVisa(AUTHOR, repo, 69, build, System.currentTimeMillis() + 1000, res,
            "the option's visa", () -> Optional.of(new GithubClient.PullState(ISSUE + " Fix the test", true, false)));

        verify(jira).addCommentWithId("jira-pat", ISSUE, "the verdict" + POSTED_BY);
        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(posted.outcome()).isEqualTo(StandingVisas.PagePost.POSTED);
        assertThat(option.state()).isEqualTo(ClientVisa.POSTED);
        assertThat(option.url()).isEqualTo(URL);
        assertThat(standing.clientVisaLine(REVIEWER, repo, 69, build, null).state())
            .isEqualTo(StandingVisas.VisaLine.POSTED);
        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux").get(0).text()).isEqualTo("Posted a visa to"
            + " IGNITE-29100 for PR #69 of apache/ignite-python-thin-client, build 9424005, with Post to JIRA on the PR"
            + " page.");
    }

    /** A client's visa goes only as the text the page showed too. */
    @Test
    void aClientRunsVisaThatIsNotTheTextThePageShowedDoesNotGo() {
        String repo = "apache/ignite-python-thin-client";
        long build = 9424005L;
        AnalysisResult res = new AnalysisResult(69, build, "pull/69", System.currentTimeMillis(), List.of(), List.of(),
            List.of(), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, System.currentTimeMillis() / 1000, 0,
            List.of(), List.of(), List.of(), 0, "1f2e3d4+a1b2c3d", 0);
        String shown = StandingVisas.textKey(standing.clientVisaPreview(AUTHOR, "the verdict").orElseThrow());

        StandingVisas.PagePost changed = standing.postClientVisaFromPage(AUTHOR, LAPTOP, repo, 69, build, ISSUE, res,
            "the verdict after a re-run", shown);
        StandingVisas.PagePost posted = standing.postClientVisaFromPage(AUTHOR, LAPTOP, repo, 69, build, ISSUE, res,
            "the verdict", shown);

        assertThat(changed).isEqualTo(StandingVisas.PagePost.of(StandingVisas.PagePost.CHANGED));
        assertThat(posted.outcome()).isEqualTo(StandingVisas.PagePost.POSTED);
        verify(jira).addCommentWithId("jira-pat", ISSUE, "the verdict" + POSTED_BY);
        verify(jira, times(1)).addCommentWithId(anyString(), anyString(), anyString());
    }

    private static PrSummary summary() {
        return new PrSummary(PR, ISSUE + " Fix the control utility", null, null, null, null);
    }

    private static HttpClientErrorException refused(int status) {
        return HttpClientErrorException.create(HttpStatus.valueOf(status), HttpStatus.valueOf(status).getReasonPhrase(),
            null, null, null);
    }

    private static AnalysisResult withBlocker() {
        TestVerdict blocker = new TestVerdict(9425993L, "org.apache.ignite.util.GridCommandHandlerTest.testCache",
            "ControlUtility5", 9425993L, "Control Utility 5", "o1", true, false, "not seen failing in 100 master runs",
            "FF", 2);

        return new AnalysisResult(PR, RUN, "pull/13709/head", 1, List.of(blocker), List.of(), List.of(), List.of(),
            List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0, COMMIT, 0);
    }

    private static AnalysisResult mixed() {
        return new AnalysisResult(PR, RUN, "pull/13709/head", 1, List.of(), List.of(), List.of(), List.of(),
            List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0, COMMIT, 0,
            List.of(new RunRef("IgniteTests24Java8_ControlUtility5", "Control Utility 5", 9426316L, HEAD)), List.of());
    }
}
