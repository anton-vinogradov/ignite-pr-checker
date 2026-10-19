package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Someone with a copy of nsamelchev's cookie could link their own GitHub account to his ci2 account. Their /run-all
 * then queued RunAll under his token, and nothing in the PR said whose account it ran under. The first command from a
 * GitHub account since its link now gets one line from the checker in the PR: which GitHub account commands for which
 * ci2 user. One line per link, also across a restart.
 */
class FirstCommandTest {
    private static final int PR = 13800;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final String FIRST = "ℹ️ First command from @NSAmelchev for ci2 user nsamelchev: [Ignite PR"
        + " Checker](https://checker.example) runs the commands of @NSAmelchev under that ci2 account.";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private StandingVisas standing = standing();

    private PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev"));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", "pull/13800/head", "IgniteTests24Java8_RunAll", "https://ci2.example/build/9400000", null, null,
            null, null, null, null, null, null, null, null, null, null));
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            PR_URL + "#issuecomment-77"));
    }

    @Test
    void theFirstCommandSinceTheLinkGetsOneLine() {
        comment(1L, "/run-all", "NSAmelchev");
        comment(2L, "/run-all", "NSAmelchev");

        verify(github, times(1)).appComment(PR, "nsamelchev", FIRST);
        verify(tc, times(2)).triggerRunAll(eq("tc"), eq(PR), anyBoolean());
    }

    /** The checker was redeployed between the first command and the second. */
    @Test
    void aRestartDoesNotTellItAgain(@TempDir Path dir) throws Exception {
        comment(1L, "/run-all", "NSAmelchev");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        standing = standing();
        standing.loadFrom(file);
        commands = commands();

        comment(2L, "/run-all", "NSAmelchev");

        verify(github, times(1)).appComment(PR, "nsamelchev", FIRST);
    }

    /** The checker was redeployed between the link and the first command: the link keeps its date and its line. */
    @Test
    void aRestartBeforeTheFirstCommandKeepsItDue(@TempDir Path dir) throws Exception {
        Long linkedAt = standing.settings("nsamelchev").linkedAt();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        standing = standing();
        standing.loadFrom(file);
        commands = commands();

        comment(1L, "/run-all", "NSAmelchev");

        verify(github).appComment(PR, "nsamelchev", FIRST);
        assertThat(standing.settings("nsamelchev").linkedAt()).isNotNull().isEqualTo(linkedAt);
    }

    /** nsamelchev links another GitHub account of his: its first command is news again. */
    @Test
    void aLinkOfAnotherAccountGetsItsOwnLine() {
        comment(1L, "/run-all", "NSAmelchev");
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev-work"));

        comment(2L, "/run-all", "NSAmelchev-work");

        verify(github).appComment(PR, "nsamelchev", FIRST.replace("@NSAmelchev", "@NSAmelchev-work"));
    }

    /** nsamelchev renamed his GitHub account before his first command: the link is the same, and so is the line. */
    @Test
    void aRenamedAccountKeepsItsLink() {
        comment(1L, "/run-all", "NSAmelchev-renamed", GhUsers.idOf("NSAmelchev"));

        verify(github).appComment(PR, "nsamelchev", FIRST.replace("@NSAmelchev", "@NSAmelchev-renamed"));
    }

    /** Pressing Link GitHub again for the account already linked changes nobody's commands: nothing to tell. */
    @Test
    void linkingTheSameAccountAgainTellsNothingNew() {
        comment(1L, "/run-all", "NSAmelchev");
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev"));

        comment(2L, "/run-all", "NSAmelchev");

        verify(github, times(1)).appComment(eq(PR), any(), startsWith("ℹ️ First command"));
    }

    /** nsamelchev pressed Link GitHub twice before his first command: the line is still due. */
    @Test
    void linkingTheSameAccountTwiceBeforeTheFirstCommandKeepsItDue() {
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev"));

        comment(1L, "/run-all", "NSAmelchev");

        verify(github, times(1)).appComment(PR, "nsamelchev", FIRST);
    }

    /** nsamelchev unlinked his account and linked it again: that is a new link, and its first command is told. */
    @Test
    void anUnlinkAndALinkAgainTellTheNextCommand() {
        comment(1L, "/run-all", "NSAmelchev");
        standing.unlinkGhLogin("nsamelchev");
        standing.linkGhLogin("nsamelchev", "tc", GhUsers.of("NSAmelchev"));

        comment(2L, "/run-all", "NSAmelchev");

        verify(github, times(2)).appComment(PR, "nsamelchev", FIRST);
    }

    /** /top runs under the ci2 account too: as the first command, it gets the line. */
    @Test
    void topAsTheFirstCommandGetsTheLine() {
        comment(1L, "/top", "NSAmelchev");

        verify(github).appComment(PR, "nsamelchev", FIRST);
    }

    /**
     * Whoever had the old server key could make a cookie of nsamelchev and link their own account to him. After the
     * operator changed the key, the next command of the linked account is told again, and only that one.
     */
    @Test
    void aKeyChangeTellsTheNextCommandAgain(@TempDir Path dir) throws Exception {
        comment(1L, "/run-all", "NSAmelchev");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        standing = standing("new-secret");
        standing.loadFrom(file);
        standing.tcTokenAccepted("nsamelchev", "tc-new");
        commands = commands();

        comment(2L, "/run-all", "NSAmelchev");
        standing.saveTo(file);
        standing = standing("new-secret");
        standing.loadFrom(file);
        commands = commands();
        comment(3L, "/run-all", "NSAmelchev");

        verify(github, times(2)).appComment(PR, "nsamelchev", FIRST);
        verify(tc, times(2)).triggerRunAll(eq("tc-new"), eq(PR), anyBoolean());
    }

    /**
     * Anyone may register on ci2 under a name of their choice. The line names the ci2 user as text: a name such as a
     * markdown link makes no link in a comment of the checker's account.
     */
    @Test
    void theCi2NameIsTextInTheLine() {
        String name = "[Ignite PMC approved](https://evil.example) @apache/ignite-pmc";
        standing.linkGhLogin(name, "tc-eve", GhUsers.of("eve-gh"));

        comment(1L, "/run-all", "eve-gh");

        verify(github).appComment(PR, name, "ℹ️ First command from @eve-gh for ci2 user \\[Ignite PMC approved\\]"
            + "(https:\u2060//evil.example) @\u2060apache/ignite-pmc: [Ignite PR Checker](https://checker.example)"
            + " runs the commands of @eve-gh under that ci2 account.");
    }

    /** A link made before the checker kept when it was made has no first command to tell. */
    @Test
    void aLinkOfAnEarlierVersionGetsNoLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).remove(List.of("ghLinkedAt", "ghLinkedFrom", "ghFirstCommandDue"));
        mapper.writeValue(file.toFile(), snap);
        standing = standing();
        standing.loadFrom(file);
        commands = commands();

        comment(1L, "/run-all", "NSAmelchev");

        verify(github, never()).appComment(eq(PR), any(), startsWith("ℹ️ First command"));
        verify(tc).triggerRunAll(eq("tc"), eq(PR), anyBoolean());
    }

    /** An edited comment runs nothing, so it is no first command; the next command that runs is. */
    @Test
    void aCommandThatRunsNothingIsNoFirstCommand() {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            PR_URL + "#issuecomment-1", Instant.now().minusSeconds(60).toString(), GhUsers.of("NSAmelchev"),
            Instant.now().toString())));
        commands.poll();

        verify(github, never()).appComment(eq(PR), any(), startsWith("ℹ️ First command"));

        comment(2L, "/run-all", "NSAmelchev");

        verify(github, times(1)).appComment(PR, "nsamelchev", FIRST);
    }

    private void comment(long id, String body, String login) {
        comment(id, body, login, GhUsers.idOf(login));
    }

    private void comment(long id, String body, String login, long accountId) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), new GithubClient.GhUser(login, accountId))));
        commands.poll();
    }

    private StandingVisas standing() {
        return standing("test-secret");
    }

    private StandingVisas standing(String secret) {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, secret), mapper), tc, github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));
    }
}
