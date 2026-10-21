package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The owner looked at the verdict of a run on the PR page and asked for a button that puts it into the PR, next to the
 * one that puts it into JIRA. Post to PR has the checker's account post the verdict comment of the run, with the text
 * of the run's automatic verdict comment and a last line that names who pressed. A run keeps one verdict comment: a
 * press updates the one the option of its starter posted, or an earlier press, and the option later updates the one a
 * press posted. A run of the Python thin client's suite and its story share one comment the same way.
 */
class PostToPrTest {
    private static final String AUTHOR = "avinogradov";

    private static final String REVIEWER = "reviewer";

    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int CLIENT_PR = 69;

    private static final long CLIENT_RUN = 9424794L;

    private static final String OPTION_URL = "https://github.com/apache/ignite/pull/13335#issuecomment-555";

    private static final String PRESS_URL = "https://github.com/apache/ignite/pull/13335#issuecomment-777";

    private static final String TESTED = "c577b18d9a1e2f3b4c5d6e7f8091a2b3c4d5e6f7";

    private static final SettingsJournal.By LAPTOP = SettingsJournal.By.of("laptop", "Firefox on Linux");

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final PendingCommits pending = mock(PendingCommits.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    private final StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.commentUrl(PR, 555L)).thenReturn(OPTION_URL);
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(build(RUN)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(withBlocker()));
    }

    /**
     * The author's option posted the verdict of the run with its blocker; the suite then passed on a re-run, and a
     * reviewer puts the verdict as it is now into the PR. The option's comment gets it, with the mention of the author
     * it had, and a last line that says the reviewer updated it. No second comment goes in.
     */
    @Test
    void aPressAfterTheOptionsCommentUpdatesItWithTheVerdictAsItIsNow() {
        standing.linkGhLogin(AUTHOR, "tc", GhUsers.of("anton-vinogradov"));
        commentOn();
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, OPTION_URL));
        standing.sweep();

        StandingVisas.CommentPost posted = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR);

        verify(github).appEdit(PR, REVIEWER, 555L, "@anton-vinogradov " + visas.composeMarkdown(PR, clean(), null,
            null) + "\n\n_Updated by ci2 user reviewer from the checker's page._");
        verify(github, times(1)).appComment(eq(PR), anyString(), anyString());
        assertThat(posted).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, OPTION_URL, 0));
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::linkText, SettingsJournal.View::url)
            .containsExactly("Updated the verdict comment of RunAll 9389046 in PR #13335 with Post to PR on the PR"
                + " page.", "PR #13335", OPTION_URL);
    }

    /**
     * The run tested c577b18, and the PR's head moved on to 9b02561 since. The press says what the author's option
     * said in the verdict comment of the run: the commit the run tested and where the PR's head is now.
     */
    @Test
    void aPressSaysWhatTheOptionsCommentSaysOfTheTestedCommitAndThePrsHead() {
        commentOn();
        when(tc.buildRevision(anyString(), eq(RUN))).thenReturn(Optional.of(TESTED));
        when(pending.since(anyString(), eq(PR), eq(RUN)))
            .thenReturn(new PendingCommits.Ahead(3, "c577b18", "9b02561", TESTED, false));
        when(github.appComment(eq(PR), eq(AUTHOR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, OPTION_URL));
        standing.sweep();
        ArgumentCaptor<String> option = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), eq(AUTHOR), option.capture());

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR);

        assertThat(option.getValue()).contains(TESTED)
            .contains("_Tested commit `c577b18`; the PR is now at `9b02561`._");
        verify(github).appEdit(PR, REVIEWER, 555L, option.getValue() + "\n\n_Updated by ci2 user reviewer from the"
            + " checker's page._");
    }

    /**
     * The author's option posted the verdict of the run, then the author switched it off, and with it their last
     * option: the checker forgot their options. A reviewer's press still updates that comment.
     */
    @Test
    void aPressUpdatesTheOptionsCommentAfterTheAuthorSwitchedTheOptionOff() {
        commentOn();
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, OPTION_URL));
        standing.sweep();
        standing.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(null, null, false, null, null));

        StandingVisas.CommentPost posted = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR);

        assertThat(posted).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, OPTION_URL, 0));
        verify(github).appEdit(eq(PR), eq(REVIEWER), eq(555L), anyString());
        verify(github, times(1)).appComment(eq(PR), anyString(), anyString());
    }

    /**
     * A maintainer deleted the comment the author's option posted, and TeamCity did not say who started the run when
     * a reviewer pressed. The press posts the verdict anew, and that comment becomes the author's verdict comment of
     * the run, the one their option updates.
     */
    @Test
    void aPressInPlaceOfADeletedOptionCommentBecomesTheAuthorsComment() {
        commentOn();
        when(github.appComment(eq(PR), eq(AUTHOR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, OPTION_URL));
        standing.sweep();
        when(github.appEdit(eq(PR), anyString(), eq(555L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        StandingVisas.CommentPost posted = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null);

        assertThat(posted).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.POSTED, PRESS_URL, 0));
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(777L);
    }

    /**
     * A version before Post to PR kept the verdict comment 555 of the author's run only among the author's options.
     * After a restart, the option puts the run's verdict into comment 555 once more, and the author then switches
     * every option off. A reviewer's press still updates comment 555.
     */
    @Test
    void aPressUpdatesTheCommentOfAnEarlierVersionOnceTheOptionUpdatedIt(@TempDir Path dir) throws Exception {
        commentOn();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).putObject("ghThreads").putObject(String.valueOf(PR))
            .put("buildId", RUN).put("commentId", 555L).put("done", false).put("app", true);
        snap.remove("runComments");
        mapper.writeValue(file.toFile(), snap);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);
        restarted.sweep();
        restarted.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(null, null, false, null, null));

        StandingVisas.CommentPost posted = restarted.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR);

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(555L), anyString());
        assertThat(posted).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, OPTION_URL, 0));
        verify(github, never()).appComment(eq(PR), anyString(), anyString());
    }

    /**
     * A press posted the verdict while GitHub made the author's option wait, and a maintainer deleted that comment.
     * The option then posts the verdict anew, and the next press updates the option's comment: the PR has one verdict
     * comment of the run, the one a newer run's verdict marks superseded.
     */
    @Test
    void aPressUpdatesTheCommentTheOptionPostedInPlaceOfADeletedOne() {
        commentOn();
        when(github.canWrite()).thenReturn(false);
        when(github.appComment(eq(PR), eq(AUTHOR), anyString())).thenThrow(new GithubClient.WriteDeferred("wait"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));
        standing.sweep();
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR);
        when(github.appEdit(eq(PR), anyString(), eq(777L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        doReturn(new GithubClient.PostedComment(555L, OPTION_URL)).when(github).appComment(eq(PR), eq(AUTHOR),
            anyString());
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        StandingVisas.CommentPost again = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR);

        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(555L);
        assertThat(again).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, OPTION_URL, 0));
        verify(github).appEdit(eq(PR), eq(REVIEWER), eq(555L), anyString());
        verify(github, times(1)).appComment(eq(PR), eq(REVIEWER), anyString());
    }

    /**
     * Anyone logged in may press Post to PR, and each press writes to the PR with the checker's account. After 5
     * presses on PR #13335 in an hour, the checker takes no more there, and mallory's press writes nothing. The
     * writes the account has left for the PR stay for its stories and verdict comments. Another PR still takes a
     * press.
     */
    @Test
    void aPrTakesFivePressesAnHour() {
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));
        when(github.appComment(eq(PR + 1), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(888L, PRESS_URL + "8"));
        for (int i = 0; i < StandingVisas.PRESSES_PER_PR_HOUR; i++)
            standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null);

        StandingVisas.CommentPost capped = standing.postCommentFromPage("mallory", LAPTOP, "tc", PR, RUN, clean(),
            null);
        StandingVisas.CommentPost other = standing.postCommentFromPage("mallory", LAPTOP, "tc", PR + 1, RUN + 1,
            clean(), null);

        assertThat(capped).isEqualTo(StandingVisas.CommentPost.of(StandingVisas.CommentPost.CAPPED));
        assertThat(other.outcome()).isEqualTo(StandingVisas.CommentPost.POSTED);
        verify(github, times(1)).appComment(eq(PR), anyString(), anyString());
        verify(github, times(StandingVisas.PRESSES_PER_PR_HOUR - 1)).appEdit(eq(PR), anyString(), anyLong(),
            anyString());
        assertThat(journal.views("mallory", "laptop", "Firefox on Linux")).hasSize(1);
    }

    /**
     * The story of the client's run posts its verdict while a reviewer presses Post to PR on the page: the press waits
     * for the story's comment, then updates it, and the run has one verdict comment.
     */
    @Test
    void aPressWhileTheClientsStoryPostsTheVerdictWaitsAndUpdatesIt() throws Exception {
        String url = "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88";
        CountDownLatch posting = new CountDownLatch(1);
        CountDownLatch posted = new CountDownLatch(1);
        when(github.appComment(eq(CLIENT_REPO), eq(CLIENT_PR), eq(AUTHOR), anyString())).thenAnswer(inv -> {
            posting.countDown();
            assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();

            return new GithubClient.PostedComment(88L, url);
        });
        AtomicReference<GithubClient.PostedComment> story = new AtomicReference<>();
        Thread storyThread = new Thread(() -> story.set(standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR,
            CLIENT_RUN, AUTHOR, "the story's verdict", false)));
        storyThread.start();
        assertThat(posting.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<StandingVisas.CommentPost> press = CompletableFuture.supplyAsync(() ->
            standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0,
                fromPage -> "the verdict from the page"));
        Thread.sleep(300);
        posted.countDown();
        storyThread.join(10_000);

        assertThat(story.get()).isEqualTo(new GithubClient.PostedComment(88L, url));
        assertThat(press.get(10, TimeUnit.SECONDS))
            .isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, url, 0));
        verify(github).appEdit(eq(CLIENT_REPO), eq(CLIENT_PR), eq(REVIEWER), eq(88L), anyString());
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(CLIENT_PR), eq(REVIEWER), anyString());
    }

    /** Nobody's option posted the verdict: the first press posts it, the second updates that comment. */
    @Test
    void aSecondPressUpdatesTheCommentTheFirstPosted() {
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        StandingVisas.CommentPost first = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(),
            AUTHOR);
        StandingVisas.CommentPost second = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR);

        verify(github).appComment(PR, REVIEWER, visas.composeMarkdown(PR, withBlocker(), null, null)
            + "\n\n➡️ **Next:** fix the blockers, push, and run RunAll again — a `/run-all` comment here does it when PR"
            + " commands are on in the checker's ⚙.\n\n_Posted by ci2 user reviewer from the checker's page._");
        verify(github).appEdit(PR, REVIEWER, 777L, visas.composeMarkdown(PR, clean(), null, null)
            + "\n\n_Updated by ci2 user reviewer from the checker's page._");
        assertThat(first).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.POSTED, PRESS_URL, 0));
        assertThat(second).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, PRESS_URL, 0));
        assertThat(standing.runCommentUrl(null, PR, RUN, 0)).hasValue(PRESS_URL);
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux")).extracting(SettingsJournal.View::text)
            .containsExactly("Updated the verdict comment of RunAll 9389046 in PR #13335 with Post to PR on the PR"
                + " page.", "Posted the verdict of RunAll 9389046 to PR #13335 with Post to PR on the PR page.");
    }

    /**
     * GitHub made the checker's account wait when the author's option was to post the verdict, so the option still
     * owed it. A reviewer's press posted it meanwhile. Once the account can write, the option puts its text into that
     * comment rather than posting a second one, and it is the author's verdict comment of the run.
     */
    @Test
    void theOptionUpdatesTheCommentAPressPosted() {
        commentOn();
        when(github.canWrite()).thenReturn(false);
        when(github.appComment(eq(PR), eq(AUTHOR), anyString())).thenThrow(new GithubClient.WriteDeferred("wait"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));
        standing.sweep();
        assertThat(standing.verdictCommentDue(AUTHOR, PR, RUN)).isTrue();
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR);
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).as("the story of a /run-all links it").hasValue(777L);
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(777L), startsWith("**[Ignite PR Checker]"));
        verify(github, times(1)).appComment(eq(PR), eq(REVIEWER), anyString());
        verify(github, times(1)).appComment(eq(PR), eq(AUTHOR), anyString());
        assertThat(standing.verdictCommentDue(AUTHOR, PR, RUN)).isFalse();
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(777L);
    }

    /**
     * The same, when TeamCity did not say who started the run at the press: the option still finds the comment of the
     * run and updates it.
     */
    @Test
    void theOptionFindsTheCommentOfThePressWithoutItsStarter() {
        commentOn();
        when(github.canWrite()).thenReturn(false);
        when(github.appComment(eq(PR), eq(AUTHOR), anyString())).thenThrow(new GithubClient.WriteDeferred("wait"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));
        standing.sweep();
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), null);
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(777L), startsWith("**[Ignite PR Checker]"));
        verify(github, times(1)).appComment(eq(PR), eq(AUTHOR), anyString());
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(777L);
    }

    /**
     * A reviewer presses while the author's option is posting the verdict of the run: the press waits for that comment,
     * then updates it, and the run has one verdict comment.
     */
    @Test
    void aPressWhileTheOptionPostsTheVerdictWaitsAndUpdatesIt() throws Exception {
        commentOn();
        CountDownLatch posting = new CountDownLatch(1);
        CountDownLatch posted = new CountDownLatch(1);
        when(github.appComment(eq(PR), eq(AUTHOR), anyString())).thenAnswer(inv -> {
            posting.countDown();
            assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();

            return new GithubClient.PostedComment(555L, OPTION_URL);
        });
        Thread sweep = new Thread(standing::sweep);
        sweep.start();
        assertThat(posting.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<StandingVisas.CommentPost> press = CompletableFuture.supplyAsync(() ->
            standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), AUTHOR));
        Thread.sleep(300);
        posted.countDown();
        sweep.join(10_000);

        assertThat(press.get(10, TimeUnit.SECONDS))
            .isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, OPTION_URL, 0));
        verify(github).appEdit(eq(PR), eq(REVIEWER), eq(555L), anyString());
        verify(github, never()).appComment(eq(PR), eq(REVIEWER), anyString());
    }

    /** The reviewer's GitHub login is proven: the last line names it, written so that GitHub tells them nothing. */
    @Test
    void thePressersLoginIsNamedWithoutAMention() {
        standing.linkGhLogin(REVIEWER, "tc-rev", GhUsers.of("rev-gh"));
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null);

        verify(github).appComment(PR, REVIEWER, visas.composeMarkdown(PR, clean(), null, null)
            + "\n\n_Posted by @⁠rev-gh (ci2 user reviewer) from the checker's page._");
    }

    /**
     * A comment GitHub no longer has gives way to a new one; a write GitHub asks to wait with, or a cap of writes,
     * posts nothing and says so; a refusal says what GitHub answered. Neither of the last two is in the journal.
     */
    @Test
    void whatGithubDidNotTakeIsSaid() {
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL))
            .thenReturn(new GithubClient.PostedComment(778L, PRESS_URL + "8"))
            .thenThrow(new GithubClient.WriteDeferred("PR:13335 had 40 writes of the app account in the last hour"))
            .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        when(github.appEdit(eq(PR), eq(REVIEWER), eq(777L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null);

        StandingVisas.CommentPost again = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null);
        when(github.appEdit(eq(PR), eq(REVIEWER), eq(778L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        StandingVisas.CommentPost deferred = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null);
        StandingVisas.CommentPost refused = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null);

        assertThat(again).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.POSTED, PRESS_URL + "8",
            0));
        assertThat(deferred.outcome()).isEqualTo(StandingVisas.CommentPost.DEFERRED);
        assertThat(refused).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.FAILED, null, 403));
        assertThat(standing.runCommentUrl(null, PR, RUN, 0)).hasValue(PRESS_URL + "8");
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux")).hasSize(2);
    }

    /**
     * A reviewer posts the verdict of the client's run from the page before its story does. The story then puts its
     * verdict into that comment, and the next press keeps the story's first words: the run was queued on the page.
     */
    @Test
    void aClientRunsStoryAndPressShareOneComment() {
        String url = "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88";
        when(github.appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(88L, url));

        StandingVisas.CommentPost pressed = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, 0, fromPage -> "verdict, queued on the page: " + fromPage);
        GithubClient.PostedComment story = standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, AUTHOR,
            "the story's verdict", true);
        StandingVisas.CommentPost again = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, 0, fromPage -> "verdict, queued on the page: " + fromPage);

        verify(github).appComment(CLIENT_REPO, CLIENT_PR, REVIEWER, "verdict, queued on the page: null\n\n_Posted by"
            + " ci2 user reviewer from the checker's page._");
        verify(github).appEdit(CLIENT_REPO, CLIENT_PR, AUTHOR, 88L, "the story's verdict");
        verify(github).appEdit(CLIENT_REPO, CLIENT_PR, REVIEWER, 88L, "verdict, queued on the page: true\n\n_Updated"
            + " by ci2 user reviewer from the checker's page._");
        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString());
        assertThat(pressed.outcome()).isEqualTo(StandingVisas.CommentPost.POSTED);
        assertThat(story).isEqualTo(new GithubClient.PostedComment(88L, url));
        assertThat(again).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, url, 0));
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux").get(1).text()).isEqualTo("Posted the verdict"
            + " of build 9424794 to PR #69 of apache/ignite-python-thin-client with Post to PR on the PR page.");
    }

    /**
     * The story of a {@code /run-all} posted the verdict of the client's run, which the page pairs with its auto
     * re-run: a press updates that comment, and keeps its first words.
     */
    @Test
    void aPressUpdatesTheVerdictTheStoryOfACommandPosted() {
        String url = "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-89";
        when(github.appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(89L, url));
        standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, AUTHOR, "the story's verdict", false);

        StandingVisas.CommentPost pressed = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, CLIENT_RUN + 16, fromPage -> "queued on the page: " + fromPage);

        verify(github).appEdit(CLIENT_REPO, CLIENT_PR, REVIEWER, 89L, "queued on the page: false\n\n_Updated by ci2"
            + " user reviewer from the checker's page._");
        assertThat(pressed).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, url, 0));
        assertThat(standing.runCommentUrl(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0)).hasValue(url);
        assertThat(standing.runCommentUrl(null, CLIENT_PR, CLIENT_RUN, 0)).as("Ignite's PR #69 is another PR")
            .isEmpty();
    }

    /**
     * The comment a press posted outlives a restart: the next press after it updates it. A file of a version without
     * Post to PR reads as no such comments.
     */
    @Test
    void theCommentOfAPressOutlivesARestart(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, """
            {"enrollments": [], "retries": {}, "earlyReruns": {}, "waves": {}, "visas": {}, "pageVisas": {}}
            """);
        standing.loadFrom(old);
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), null);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);

        StandingVisas restarted = standing();
        restarted.loadFrom(file);
        StandingVisas.CommentPost again = restarted.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null);

        assertThat(again).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, PRESS_URL, 0));
        verify(github, times(1)).appComment(eq(PR), anyString(), anyString());
        verify(github, never()).appComment(anyString(), eq(PR), any(), anyString());
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, mock(JiraClient.class), visas,
            mock(RerunTracker.class), mock(Warmer.class), pending, journal);
    }

    private void commentOn() {
        standing.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(null, null, true, null, null));
    }

    private static TcModel.Build build(long id) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/13335/head", null, null, null, null, null, null, null,
            null, new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null);
    }

    private static AnalysisResult withBlocker() {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        return new AnalysisResult(PR, RUN, "pull/13335/head", 1_800_000_000_000L, List.of(blocker), List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }

    private static AnalysisResult clean() {
        return new AnalysisResult(PR, RUN, "pull/13335/head", 1_800_000_000_000L, List.of(), List.of(), List.of(),
            List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
