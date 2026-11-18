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
            AUTHOR, null);

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

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR, null);

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
            AUTHOR, null);

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
            null, null);

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
            AUTHOR, null);

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
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR, null);
        when(github.appEdit(eq(PR), anyString(), eq(777L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        doReturn(new GithubClient.PostedComment(555L, OPTION_URL)).when(github).appComment(eq(PR), eq(AUTHOR),
            anyString());
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        StandingVisas.CommentPost again = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR, null);

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
            standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null, null);

        StandingVisas.CommentPost capped = standing.postCommentFromPage("mallory", LAPTOP, "tc", PR, RUN, clean(),
            null, null);
        StandingVisas.CommentPost other = standing.postCommentFromPage("mallory", LAPTOP, "tc", PR + 1, RUN + 1,
            clean(), null, null);

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
            CLIENT_RUN, AUTHOR, "the story's verdict", false, clientRun())));
        storyThread.start();
        assertThat(posting.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<StandingVisas.CommentPost> press = CompletableFuture.supplyAsync(() ->
            standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, clientRun(),
                fromPage -> "the verdict from the page", null));
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
            AUTHOR, null);
        StandingVisas.CommentPost second = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR, null);

        verify(github).appComment(PR, REVIEWER, visas.composeMarkdown(PR, withBlocker(), null, null)
            + "\n\n_Posted by ci2 user reviewer from the checker's page._");
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

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), AUTHOR, null);
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).as("the story of a /run-all links it").hasValue(777L);
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(777L), startsWith("❌ **1 blocker in 1 suite** · "));
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

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), null, null);
        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(777L), startsWith("❌ **1 blocker in 1 suite** · "));
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
            standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), AUTHOR, null));
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

        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null, null);

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
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null, null);

        StandingVisas.CommentPost again = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null, null);
        when(github.appEdit(eq(PR), eq(REVIEWER), eq(778L), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        StandingVisas.CommentPost deferred = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null, null);
        StandingVisas.CommentPost refused = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null, null);

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
            CLIENT_PR, CLIENT_RUN, 0, clientRun(), fromPage -> "verdict, queued on the page: " + fromPage, null);
        GithubClient.PostedComment story = standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, AUTHOR,
            "the story's verdict", true, clientRun());
        StandingVisas.CommentPost again = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, 0, clientRun(), fromPage -> "verdict, queued on the page: " + fromPage, null);

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
        standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, AUTHOR, "the story's verdict", false,
            clientRun());

        StandingVisas.CommentPost pressed = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, CLIENT_RUN + 16, clientRun(), fromPage -> "queued on the page: " + fromPage,
            null);

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
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(), null, null);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);

        StandingVisas restarted = standing();
        restarted.loadFrom(file);
        StandingVisas.CommentPost again = restarted.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            null, null);

        assertThat(again).isEqualTo(new StandingVisas.CommentPost(StandingVisas.CommentPost.EDITED, PRESS_URL, 0));
        verify(github, times(1)).appComment(eq(PR), anyString(), anyString());
        verify(github, never()).appComment(anyString(), eq(PR), any(), anyString());
    }

    /**
     * Before a press the page shows the comment as it would go. The preview is the text the press then sends: its last
     * line says "Posted" for the run's first verdict comment and "Updated" once the run has one. It writes nothing to
     * GitHub, and it is no press: after more previews than a PR takes presses in an hour, the press still goes.
     */
    @Test
    void thePreviewIsTheTextThePressSendsAndIsNoPress() {
        standing.linkGhLogin(AUTHOR, "tc", GhUsers.of("anton-vinogradov"));
        commentOn();
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));

        String first = null;
        for (int i = 0; i <= StandingVisas.PRESSES_PER_PR_HOUR; i++)
            first = standing.commentPreview(REVIEWER, "tc", PR, RUN, withBlocker(), AUTHOR);
        verify(github, never()).appComment(eq(PR), anyString(), anyString());
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux")).isEmpty();

        StandingVisas.CommentPost posted = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, withBlocker(),
            AUTHOR, null);
        String second = standing.commentPreview(REVIEWER, "tc", PR, RUN, clean(), AUTHOR);
        standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), AUTHOR, null);

        assertThat(posted.outcome()).isEqualTo(StandingVisas.CommentPost.POSTED);
        verify(github).appComment(PR, REVIEWER, first);
        verify(github).appEdit(PR, REVIEWER, 777L, second);
        assertThat(first).startsWith("@anton-vinogradov ")
            .endsWith("\n\n_Posted by ci2 user reviewer from the checker's page._");
        assertThat(second).endsWith("\n\n_Updated by ci2 user reviewer from the checker's page._");
    }

    /**
     * The reviewer read the comment with the blocker in the dialog. Before they pressed, the suite passed on a re-run.
     * The press carries the key of the text they read, and the comment is another text now: nothing goes to GitHub,
     * and the press is not one of the five the PR takes in an hour. The same holds when the run got its verdict
     * comment meanwhile, and the press would say "Updated" where the dialog said "Posted". The next press shows the
     * new text, and its key posts it.
     */
    @Test
    void aCommentThatIsNotTheTextThePageShowedDoesNotGo() {
        when(github.appComment(eq(PR), eq(REVIEWER), anyString()))
            .thenReturn(new GithubClient.PostedComment(777L, PRESS_URL));
        String shown = StandingVisas.textKey(standing.commentPreview(REVIEWER, "tc", PR, RUN, withBlocker(), AUTHOR));

        for (int i = 0; i <= StandingVisas.PRESSES_PER_PR_HOUR; i++) {
            assertThat(standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), AUTHOR, shown))
                .isEqualTo(StandingVisas.CommentPost.of(StandingVisas.CommentPost.CHANGED));
        }
        verify(github, never()).appComment(eq(PR), anyString(), anyString());
        assertThat(journal.views(REVIEWER, "laptop", "Firefox on Linux")).isEmpty();

        String now = standing.commentPreview(REVIEWER, "tc", PR, RUN, clean(), AUTHOR);
        StandingVisas.CommentPost posted = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR, StandingVisas.textKey(now));
        StandingVisas.CommentPost again = standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(),
            AUTHOR, StandingVisas.textKey(now));

        assertThat(posted.outcome()).isEqualTo(StandingVisas.CommentPost.POSTED);
        verify(github).appComment(PR, REVIEWER, now);
        assertThat(again).as("the run has a verdict comment now, and the press would update it")
            .isEqualTo(StandingVisas.CommentPost.of(StandingVisas.CommentPost.CHANGED));
        verify(github, never()).appEdit(eq(PR), anyString(), anyLong(), anyString());
    }

    /** A client's verdict comment goes only as the text the page showed too. */
    @Test
    void aClientRunsCommentThatIsNotTheTextThePageShowedDoesNotGo() {
        when(github.appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString())).thenReturn(
            new GithubClient.PostedComment(88L, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
        String shown = StandingVisas.textKey(standing.clientCommentPreview(REVIEWER, CLIENT_REPO, CLIENT_PR,
            CLIENT_RUN, 0, fromPage -> "the verdict"));

        StandingVisas.CommentPost changed = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, 0, clientRun(), fromPage -> "the verdict after a re-run", shown);
        StandingVisas.CommentPost posted = standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO,
            CLIENT_PR, CLIENT_RUN, 0, clientRun(), fromPage -> "the verdict", shown);

        assertThat(changed).isEqualTo(StandingVisas.CommentPost.of(StandingVisas.CommentPost.CHANGED));
        assertThat(posted.outcome()).isEqualTo(StandingVisas.CommentPost.POSTED);
        verify(github).appComment(CLIENT_REPO, CLIENT_PR, REVIEWER, "the verdict\n\n_Posted by ci2 user reviewer from"
            + " the checker's page._");
        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString());
    }

    /**
     * The PR page offers Update only for a comment the verdict left behind. The author's option posted the verdict of
     * the run with its blocker. While the checker holds that verdict the comment is current; once the suite passed on
     * a re-run it is not, until a press updates it. What the comment says is kept with it over a restart. The checker
     * tells from the verdict it holds, and cannot tell while it holds none; the verdict kept of a merged PR is the one
     * to compare with.
     */
    @Test
    void theCommentIsCurrentUntilTheVerdictChanges(@TempDir Path dir) throws Exception {
        commentOn();
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, OPTION_URL));
        when(github.appEdit(eq(PR), eq(REVIEWER), eq(555L), anyString())).thenReturn(true);
        standing.sweep();

        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).as("no verdict held").isNull();
        when(analyzer.held(RUN)).thenReturn(Optional.of(withBlocker()));
        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).isTrue();
        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.of(clean()))).as("merged with another verdict")
            .isFalse();
        assertThat(standing.commentCurrent(null, PR, RUN + 1, 0, Optional.empty())).as("a run with no comment")
            .isNull();
        when(analyzer.held(RUN)).thenReturn(Optional.of(clean()));
        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).isFalse();

        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);

        assertThat(restarted.commentCurrent(null, PR, RUN, 0, Optional.empty())).isFalse();
        restarted.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), AUTHOR, null);
        assertThat(restarted.commentCurrent(null, PR, RUN, 0, Optional.empty())).isTrue();
    }

    /**
     * A client's run: its story posted the verdict comment, and the verdict kept for the PR is the one to compare with.
     * A press with the verdict after a re-run makes the comment current again.
     */
    @Test
    void aClientRunsCommentIsCurrentUntilTheVerdictChanges() {
        when(github.appComment(eq(CLIENT_REPO), eq(CLIENT_PR), any(), anyString())).thenReturn(
            new GithubClient.PostedComment(88L, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
        when(github.appEdit(eq(CLIENT_REPO), eq(CLIENT_PR), any(), eq(88L), anyString())).thenReturn(true);
        TestVerdict blocker = new TestVerdict(9424795L, "tests.test_cache.test_put", "ThinClientPython", 9424795L,
            "Thin client: Python", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);
        AnalysisResult blocked = new AnalysisResult(CLIENT_PR, CLIENT_RUN, "pull/69", 1_800_000_000_000L,
            List.of(blocker), List.of(), List.of(), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, 1, 0);
        standing.clientVerdictComment(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, AUTHOR, "the story's verdict", false,
            blocked);

        assertThat(standing.commentCurrent(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, Optional.of(blocked))).isTrue();
        assertThat(standing.commentCurrent(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, Optional.of(clientRun()))).isFalse();
        assertThat(standing.commentCurrent(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, Optional.empty()))
            .as("no verdict kept for the PR").isNull();

        standing.postClientCommentFromPage(REVIEWER, LAPTOP, CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, clientRun(),
            fromPage -> "the verdict after a re-run", null);

        assertThat(standing.commentCurrent(CLIENT_REPO, CLIENT_PR, CLIENT_RUN, 0, Optional.of(clientRun()))).isTrue();
    }

    /**
     * A file of v1.52.0 keeps the run's comment without what its verdict says. It loads, the press still updates that
     * comment, and until then the checker cannot tell whether the comment is current.
     */
    @Test
    void aCommentKeptWithoutItsVerdictLoadsAndIsNotToldCurrent(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, """
            {"enrollments": [], "retries": {}, "earlyReruns": {}, "waves": {}, "visas": {}, "pageVisas": {},
             "runComments": {"%d": {"repo": null, "pr": %d, "commentId": 777, "url": "%s", "fromPage": null}}}
            """.formatted(RUN, PR, PRESS_URL));
        when(analyzer.held(RUN)).thenReturn(Optional.of(clean()));
        when(github.appEdit(eq(PR), eq(REVIEWER), eq(777L), anyString())).thenReturn(true);

        standing.loadFrom(old);

        assertThat(standing.runCommentUrl(null, PR, RUN, 0)).hasValue(PRESS_URL);
        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).isNull();
        assertThat(standing.postCommentFromPage(REVIEWER, LAPTOP, "tc", PR, RUN, clean(), null, null).outcome())
            .isEqualTo(StandingVisas.CommentPost.EDITED);
        assertThat(standing.commentCurrent(null, PR, RUN, 0, Optional.empty())).isTrue();
    }

    /**
     * The row of the PR page says whether Post my runs' verdicts in the PR of whoever started the run still posts its
     * verdict comment: the option is on and was on when the run finished. A cancelled run gets none, a merged PR's run
     * and one TeamCity names no starter of say nothing of an option.
     */
    @Test
    void theRowSaysWhoseOptionStillPostsTheComment() {
        StandingVisas.Run going = new StandingVisas.Run(AUTHOR, false, 0);

        assertThat(standing.commentLine(REVIEWER, PR, RUN, () -> going, false)).as("the author has no options")
            .isEqualTo(new StandingVisas.CommentLine(AUTHOR, false, false));

        commentOn();

        assertThat(standing.commentLine(REVIEWER, PR, RUN, () -> going, false))
            .isEqualTo(new StandingVisas.CommentLine(AUTHOR, false, true));
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> going, false))
            .isEqualTo(new StandingVisas.CommentLine(AUTHOR, true, true));
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 1_000L), false))
            .as("the run finished before the option went on").isEqualTo(new StandingVisas.CommentLine(AUTHOR, true, false));
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, true, 0), false))
            .as("a cancelled run").isEqualTo(new StandingVisas.CommentLine(AUTHOR, true, false));
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> going, true)).as("a merged PR")
            .isEqualTo(new StandingVisas.CommentLine(null, false, false));
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> StandingVisas.Run.UNKNOWN, false))
            .isEqualTo(new StandingVisas.CommentLine(null, false, false));

        when(github.writes()).thenReturn(false);
        assertThat(standing.commentLine(AUTHOR, PR, RUN, () -> going, false)).as("no account to post from")
            .isEqualTo(new StandingVisas.CommentLine(AUTHOR, true, false));
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

    /** The verdict of the client's run, with no blockers. */
    private static AnalysisResult clientRun() {
        return new AnalysisResult(CLIENT_PR, CLIENT_RUN, "pull/69", 1_800_000_000_000L, List.of(), List.of(), List.of(),
            List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, 1, 0);
    }

    private static AnalysisResult clean() {
        return new AnalysisResult(PR, RUN, "pull/13335/head", 1_800_000_000_000L, List.of(), List.of(), List.of(),
            List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
