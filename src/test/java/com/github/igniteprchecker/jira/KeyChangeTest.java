package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator changed SESSION_SECRET after a leak. The checker could no longer read the tokens the standing options
 * stored, and said nothing: the next finished run of their owner dropped all the options, and ⚙ only showed them off.
 * The file now names the key its tokens were encrypted with. A start under another key drops the tokens it cannot
 * read, and the options stay on and wait. ⚙ and the journal say that the key was changed and which tokens to save
 * again, the PR comment says why no visa came, and the status page counts who waits. A token damaged under the same
 * key is not called a key change. Runs avinogradov's options on PR 13335 through such a start.
 */
class KeyChangeTest {
    private static final String AUTHOR = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private static final String DROPPED_ALL = "The server key was changed, so the checker dropped your stored"
        + " TeamCity token, JIRA token and GitHub token for commits: it could no longer read them. Your options wait"
        + " until you store new ones.";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private Path file;

    private StandingVisas standing;

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
        file = dir.resolve("standing-visas.json");
        before.saveTo(file);

        standing = standing("new-secret", journal);
        standing.loadFrom(file);
    }

    @Test
    void theOptionsStayOnAndSettingsSayWhichTokensToSaveAgain() {
        StandingVisas.Settings st = standing.settings(AUTHOR);

        assertThat(List.of(st.visa(), st.gh(), st.commands(), st.suites())).containsOnly(true);
        assertThat(st.tokensUnread()).containsExactly("teamcity", "jira", "github");
        assertThat(st.tokensUnreadWhy()).isEqualTo("key-changed");
        assertThat(st.jiraStored()).isFalse();
        assertThat(st.ghStored()).isFalse();
        assertThat(standing.waitingAfterKeyChange()).isEqualTo(1);
        assertThat(journal.views(AUTHOR, "l1", "Firefox on Linux")).singleElement().satisfies(v -> {
            assertThat(v.text()).isEqualTo(DROPPED_ALL);
            assertThat(v.from()).isEqualTo("by the checker");
        });
    }

    /** avinogradov unlinks his account after the key change: the GitHub token the checker could not read goes too. */
    @Test
    void anUnlinkSaysTheUnreadGithubTokenWentToo() {
        standing.unlinkGhLogin(AUTHOR, SettingsJournal.By.of("l1", "Firefox on Linux"));

        assertThat(standing.settings(AUTHOR).tokensUnread()).doesNotContain("github");
        assertThat(journal.views(AUTHOR, "l1", "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Unlinked GitHub account @anton-vinogradov. PR commands and Add my PR's tests that no suite"
                + " runs went off. The checker dropped your GitHub token for commits too.", "from this browser");
    }

    /** The command poll switches off an option whose token is gone; one that waits for a new token stays on. */
    @Test
    void thePollKeepsTheWaitingOptionsOn() {
        standing.ensureGhLogins();

        StandingVisas.Settings st = standing.settings(AUTHOR);
        assertThat(List.of(st.visa(), st.commands(), st.suites())).containsOnly(true);
        assertThat(st.jiraTokenRejected()).isFalse();
        assertThat(journal.of(AUTHOR)).hasSize(1);
    }

    @Test
    void aLoginBringsTheTeamCityTokenBack() {
        standing.tcTokenAccepted(AUTHOR, "tc-2", SettingsJournal.By.of("l1", "Firefox on Linux"));

        assertThat(standing.settings(AUTHOR).tokensUnread()).containsExactly("jira", "github");
        assertThat(standing.waitingAfterKeyChange()).as("the JIRA and GitHub tokens are still to come").isEqualTo(1);
        assertThat(journal.views(AUTHOR, "l2", "Chrome on macOS").get(0)).satisfies(v -> {
            assertThat(v.text()).isEqualTo("Saved a new TeamCity token for your options.");
            assertThat(v.from()).isEqualTo("from Firefox on Linux");
        });
    }

    /** A command from the linked account is answered that the key was changed, not that the token got lost. */
    @Test
    void aCommandBeforeTheNextLoginIsToldTheKeyWasChanged() {
        assertThat(standing.actorByGhUser(GhUsers.of("anton-vinogradov"))).isEmpty();
        assertThat(standing.whyNoActor(GhUsers.of("anton-vinogradov"))).isEqualTo(StandingVisas.NoActor.KEY_CHANGED);
    }

    @Test
    void theVerdictCommentSaysWhyNoVisaCameAndAFreshTokenSendsIt() {
        standing.tcTokenAccepted(AUTHOR, "tc-2");
        finished();
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(jira.addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));

        standing.sweep();

        verify(github).appComment(eq(PR), eq(AUTHOR), contains("_No JIRA visa yet: the server key was changed, so the"
            + " checker could no longer read the JIRA token it stored. The visa goes out once whoever started the run"
            + " saves a new token in ⚙._"));
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        assertThat(standing.visaLine(AUTHOR, PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 0), false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.WAITS, AUTHOR, true, null, null, false,
                "key-changed"));
        assertThat(standing.visaLine("nizhikov", PR, RUN, () -> new StandingVisas.Run(AUTHOR, false, 0), false).state())
            .as("someone else learns whose option it is and that it is on").isEqualTo(StandingVisas.VisaLine.ON);

        assertThat(standing.change(AUTHOR, "tc-2", "fresh-jira-pat", null,
            new StandingVisas.OptionChange(true, null, null, null, null))).isEmpty();

        verify(jira, timeout(5_000)).addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), contains("RunAll"));
        assertThat(standing.settings(AUTHOR).tokensUnread()).containsExactly("github");
    }

    /** avinogradov switches auto re-run on before he has a new JIRA token at hand. */
    @Test
    void aSwitchOfAnotherOptionKeepsTheVisaWaiting() {
        assertThat(standing.change(AUTHOR, "tc-2", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null))).isEmpty();

        assertThat(standing.rerunOn(AUTHOR)).isTrue();
        assertThat(standing.visaOn(AUTHOR)).isTrue();
        assertThat(standing.settings(AUTHOR).tokensUnread()).contains("jira", "github");
        assertThat(standing.change(AUTHOR, "tc-2", null, null,
            new StandingVisas.OptionChange(true, null, null, null, null)))
            .as("switching the visa on asks for its token").map(StandingVisas.Refusal::need).contains("jira");
    }

    @Test
    void theNextStartUnderTheNewKeyFindsNothingNew(@TempDir Path dir) throws IOException {
        Path saved = dir.resolve("standing-visas.json");
        standing.saveTo(saved);
        SettingsJournal next = new SettingsJournal(mapper);
        StandingVisas restarted = standing("new-secret", next);

        restarted.loadFrom(saved);

        assertThat(next.of(AUTHOR)).isEmpty();
        assertThat(restarted.settings(AUTHOR).tokensUnread()).containsExactly("teamcity", "jira", "github");
        assertThat(restarted.waitingAfterKeyChange()).isEqualTo(1);
        JsonNode enrollment = mapper.readTree(saved.toFile()).get("enrollments").get(0);
        assertThat(enrollment.get("jiraToken").isNull()).as("no ciphertext of the old key stays").isTrue();
        assertThat(enrollment.get("jiraTokenUnread").asText()).isEqualTo("key-changed");
    }

    /** One stored token got damaged on the disk, and the key is the one that wrote it. */
    @Test
    void aTokenDamagedUnderTheSameKeyIsNotCalledAKeyChange() throws IOException {
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        String jiraToken = enrollment.get("jiraToken").asText();
        enrollment.put("jiraToken", jiraToken.substring(0, 20) + (jiraToken.charAt(20) == 'A' ? 'B' : 'A')
            + jiraToken.substring(21));
        mapper.writeValue(file.toFile(), snap);
        SettingsJournal sameKeyJournal = new SettingsJournal(mapper);
        StandingVisas sameKey = standing("old-secret", sameKeyJournal);

        sameKey.loadFrom(file);

        assertThat(sameKey.settings(AUTHOR).tokensUnread()).containsExactly("jira");
        assertThat(sameKey.settings(AUTHOR).tokensUnreadWhy()).isEqualTo("damaged");
        assertThat(sameKey.waitingAfterKeyChange()).isZero();
        assertThat(sameKeyJournal.of(AUTHOR)).singleElement().extracting(SettingsJournal::text)
            .isEqualTo("The checker dropped your stored JIRA token: it could not read it. Your options wait until you"
                + " store a new one.");
    }

    /** A file of an earlier version names no key: tokens it cannot read may be damaged as well as of another key. */
    @Test
    void aFileOfAnEarlierVersionNamesNoKey() throws IOException {
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        snap.remove("keyCheck");
        mapper.writeValue(file.toFile(), snap);
        StandingVisas fromEarlier = standing("new-secret", new SettingsJournal(mapper));

        fromEarlier.loadFrom(file);

        assertThat(fromEarlier.settings(AUTHOR).tokensUnreadWhy()).isEqualTo("damaged");
        assertThat(fromEarlier.visaOn(AUTHOR)).isTrue();
    }

    @Test
    void theKeyCheckNamesTheKeyAndNothingOfTheSecret() throws IOException {
        String check = codec("old-secret").keyCheck();

        assertThat(check).matches("[0-9a-f]{16}").isEqualTo(codec("old-secret").keyCheck())
            .isNotEqualTo(codec("new-secret").keyCheck());
        assertThat(mapper.readTree(file.toFile()).get("keyCheck").asText()).isEqualTo(check);
        assertThat(Files.readString(file)).doesNotContain("old-secret");
    }

    private StandingVisas standing(String secret, SettingsJournal journal) {
        return new StandingVisas(mapper, codec(secret), tc, github, analyzer, jira,
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class), journal);
    }

    private SessionCodec codec(String secret) {
        return new SessionCodec(new SessionProperties(false, secret), mapper);
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
