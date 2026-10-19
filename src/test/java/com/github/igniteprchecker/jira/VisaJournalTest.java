package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A visa goes to JIRA under the JIRA token of whoever started the run, also for a RunAll someone else started in
 * TeamCity with their ci2 token, and the user learned of it only from JIRA. Each visa the checker posts or edits with
 * the user's token is now an entry of their journal: the PR, the ticket, the run, when, and a link to the comment.
 */
class VisaJournalTest {
    private static final String AUTHOR = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private static final String COMMENT_URL = "https://issues.example/browse/IGNITE-28867?focusedCommentId=18000001"
        + "#comment-18000001";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final StandingVisas standing = standing(journal);

    @BeforeEach
    void setUp() {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", COMMENT_URL));
        when(jira.commentUrl(ISSUE, "18000001")).thenReturn(COMMENT_URL);
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(RUN, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict()));
        standing.change(AUTHOR, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null),
            null, SettingsJournal.By.of("laptop", "Firefox on Linux"));
    }

    @Test
    void aPostedVisaIsAnEntryThatLinksTheComment() {
        standing.sweep();

        assertThat(journal.views(AUTHOR, "laptop", "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from, SettingsJournal.View::linkText,
                SettingsJournal.View::url)
            .containsExactly("Posted a visa to IGNITE-28867 for PR #13335, RunAll 9389046.", "by the checker", ISSUE,
                COMMENT_URL);
    }

    /** The next sweeps find the run handled and post nothing: the journal gets nothing either. */
    @Test
    void aSweepThatPostsNothingAddsNothing() {
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));
        standing.sweep();

        standing.sweep();
        standing.sweep();

        assertThat(journal.of(AUTHOR)).extracting(SettingsJournal.Entry::kind).containsExactly("visa", "on");
    }

    /** A visa of an earlier release for the same run is still in the ticket, and the settle edits it in place. */
    @Test
    void aVisaEditedInPlaceIsAnEntryToo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).putObject("jiraThreads").putObject(String.valueOf(PR))
            .put("buildId", RUN).put("commentId", "18000001").put("issue", ISSUE);
        mapper.writeValue(file.toFile(), snap);
        SettingsJournal restartedJournal = new SettingsJournal(mapper);
        StandingVisas restarted = standing(restartedJournal);
        restarted.loadFrom(file);

        restarted.sweep();

        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq("18000001"), anyString());
        assertThat(restartedJournal.views(AUTHOR, "laptop", "Firefox on Linux")).singleElement()
            .extracting(SettingsJournal.View::text, SettingsJournal.View::url)
            .containsExactly("Edited the visa in IGNITE-28867 for PR #13335, RunAll 9389046.", COMMENT_URL);
    }

    private StandingVisas standing(SettingsJournal journal) {
        return new StandingVisas(mapper, codec, tc, github, analyzer, jira,
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);
    }

    private static AnalysisResult verdict() {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        return new AnalysisResult(PR, RUN, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
