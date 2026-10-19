package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two batches made options wait for tokens instead of dropping them: a state restored from a copy older than two days,
 * which keeps no tokens, and a start under a changed server key, which cannot read them. They are not the same news:
 * after a restore nobody has to replace a token, and after a key change everyone should. A restore may not read as a
 * key change, nor a key change as a restore. But when the operator restored a copy made before the key change, ⚙ asked
 * avinogradov to save his JIRA token again, the one the leaked key could decrypt: such a copy reads as a key change.
 */
class RestoredOrKeyChangedTest {
    private static final String AUTHOR = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private Path live;

    private Path copy;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        StandingVisas before = standing("old-secret", new SettingsJournal(mapper));
        before.change(AUTHOR, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, false, true, null, true,
            true));
        live = dir.resolve("standing-visas.json");
        before.saveTo(live);
        copy = Files.copy(live, dir.resolve("standing-visas.json.before-1.37.0-built-20261016-120000"));
        TokenScrub.removeFrom(copy, System.currentTimeMillis());
    }

    @Test
    void aCopyRestoredUnderTheSameKeyIsARestoreNotAKeyChange() throws IOException {
        StandingVisas restored = standing("old-secret", journal);

        restored.loadFrom(copy);

        StandingVisas.Settings st = restored.settings(AUTHOR);
        assertThat(st.tokensRemoved()).containsExactly("jira", "github");
        assertThat(st.tokensUnread()).isEmpty();
        assertThat(st.tokensUnreadWhy()).isNull();
        assertThat(restored.waitingAfterKeyChange()).as("the operator's count of a key change").isZero();
        assertThat(journal.of(AUTHOR)).as("no entry says the server key was changed").isEmpty();
        assertThat(restored.whyNoActor(GhUsers.of("anton-vinogradov")))
            .isEqualTo(StandingVisas.NoActor.TOKEN_REMOVED);
        assertThat(restored.waitingToken(AUTHOR, SettingsJournal.Entry.GITHUB)).contains(StandingVisas.TOKEN_REMOVED);
        assertThat(restored.visaLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 0), false).state())
            .isEqualTo(StandingVisas.VisaLine.JIRA_TOKEN_REMOVED);
    }

    /** The key changed after the copy was made, so its tokens may have leaked: the user stores new ones. */
    @Test
    void aCopyRestoredUnderTheNewKeyAsksForNewTokens() throws IOException {
        StandingVisas restored = standing("new-secret", journal);

        restored.loadFrom(copy);

        StandingVisas.Settings st = restored.settings(AUTHOR);
        assertThat(st.tokensRemoved()).isEmpty();
        assertThat(st.tokensUnread()).containsExactly("teamcity", "jira", "github");
        assertThat(st.tokensUnreadWhy()).isEqualTo(SettingsJournal.Entry.KEY_CHANGED);
        assertThat(restored.waitingAfterKeyChange()).as("the operator's count of a key change").isEqualTo(1);
        assertThat(journal.of(AUTHOR)).singleElement().extracting(SettingsJournal::text)
            .isEqualTo("The server key was changed, so the checker dropped your stored TeamCity token, JIRA token and"
                + " GitHub token for commits: it could no longer read them. Your options wait until you store new"
                + " ones.");
        assertThat(restored.whyNoActor(GhUsers.of("anton-vinogradov"))).isEqualTo(StandingVisas.NoActor.KEY_CHANGED);
        assertThat(restored.waitingToken(AUTHOR, SettingsJournal.Entry.GITHUB))
            .contains(SettingsJournal.Entry.KEY_CHANGED);
        assertThat(restored.visaLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 0), false).state())
            .isEqualTo(StandingVisas.VisaLine.WAITS);
    }

    /** The next start under the same new key finds the marks of the key change and writes no second entry. */
    @Test
    void aRestartAfterTheRestoreUnderTheNewKeyKeepsTheKeyChange(@TempDir Path dir) throws IOException {
        StandingVisas restored = standing("new-secret", journal);
        restored.loadFrom(copy);
        Path saved = dir.resolve("standing-visas.json");
        restored.saveTo(saved);
        SettingsJournal next = new SettingsJournal(mapper);
        StandingVisas restarted = standing("new-secret", next);

        restarted.loadFrom(saved);

        assertThat(restarted.settings(AUTHOR).tokensUnreadWhy()).isEqualTo(SettingsJournal.Entry.KEY_CHANGED);
        assertThat(restarted.waitingAfterKeyChange()).isEqualTo(1);
        assertThat(next.of(AUTHOR)).isEmpty();
    }

    @Test
    void aKeyChangeIsNotARestore() throws IOException {
        StandingVisas changed = standing("new-secret", journal);

        changed.loadFrom(live);

        StandingVisas.Settings st = changed.settings(AUTHOR);
        assertThat(st.tokensRemoved()).isEmpty();
        assertThat(st.tokensUnread()).containsExactly("teamcity", "jira", "github");
        assertThat(st.tokensUnreadWhy()).isEqualTo(SettingsJournal.Entry.KEY_CHANGED);
        assertThat(changed.waitingAfterKeyChange()).isEqualTo(1);
        assertThat(changed.whyNoActor(GhUsers.of("anton-vinogradov"))).isEqualTo(StandingVisas.NoActor.KEY_CHANGED);
        assertThat(changed.waitingToken(AUTHOR, SettingsJournal.Entry.GITHUB))
            .contains(SettingsJournal.Entry.KEY_CHANGED);
        assertThat(changed.visaLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 0), false).state())
            .isEqualTo(StandingVisas.VisaLine.WAITS);
    }

    /** The comment that brings the verdict says why no visa came, by the cause it has. */
    @Test
    void theVerdictCommentSaysTheStateWasRestored() throws IOException {
        StandingVisas restored = standing("old-secret", journal);
        restored.loadFrom(copy);
        restored.tcTokenOffered(AUTHOR, "tc-2");
        finished();
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));

        restored.sweep();

        String line = "No JIRA visa yet: the checker's state was restored from a backup older than 2 days, which keeps"
            + " no tokens. The visa goes out once whoever started the run saves their JIRA token in ⚙ again.";
        verify(github).appComment(eq(PR), eq(AUTHOR), contains("_" + line + "_"));
        verify(github, never()).appComment(eq(PR), eq(AUTHOR), contains("server key"));
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(restored.visaWaiting(AUTHOR, PR, RUN)).contains(line);
    }

    private StandingVisas standing(String secret, SettingsJournal journal) {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, secret), mapper), tc, github,
            analyzer, jira, new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);
    }

    private void finished() {
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(RUN, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null)));
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(new AnalysisResult(PR, RUN,
            "pull/13335/head", System.currentTimeMillis(), List.of(blocker), List.of(), List.of(), List.of(), List.of(),
            140, 1, false, 0, false, 0, 0, 0, 1, 0)));
    }
}
