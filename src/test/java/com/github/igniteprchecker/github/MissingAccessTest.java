package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.github.igniteprchecker.tc.TcResponseException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A /run-all from an account whose login an earlier version kept without a proof, or whose stored TeamCity token the
 * checker could no longer read, got the reply meant for strangers, which told them to do what they had done long ago;
 * a /top without access was answered as if it queued; and the reply on an expired TeamCity token came once for all
 * PRs, so the next PR got a bare 😕. Each case now says what is missing and what to press in ⚙, once per person and
 * PR, and quotes nothing of the comment.
 */
class MissingAccessTest {
    private static final int PR = 13800;

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final StyleFixService styleFix = mock(StyleFixService.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        styleFix, mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"));

    private long nextComment = 1;

    @TempDir
    Path dir;

    @BeforeEach
    void enrolled() throws Exception {
        ObjectNode snapshot = mapper.createObjectNode();
        ArrayNode enrollments = snapshot.putArray("enrollments");
        enrollments.add(enrollment("avinogradov", "anton-vinogradov", true).put("tcToken",
            codec.encryptString("tc-avinogradov")));
        enrollments.add(enrollment("typist", "Vladsz83", false).put("tcToken", codec.encryptString("tc-typist")));
        enrollments.add(enrollment("nsamelchev", "NSAmelchev", true).put("tcToken",
            new SessionCodec(new SessionProperties(false, "old-secret"), mapper).encryptString("tc-nsamelchev")));
        enrollments.add(enrollment("kirill", "kirill-gh", true).put("tcToken", codec.encryptString("tc-kirill"))
            .put("tcRejectedAt", 1760000000000L));
        enrollments.add(enrollment("alice", "alice-v", true).put("tcToken", codec.encryptString("tc-alice"))
            .put("ghToken", codec.encryptString("github_pat_alice")).put("styleFix", true));
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), snapshot);
        standing.loadFrom(file);
        when(github.linksAccounts()).thenReturn(true);
    }

    /** Vladsz83 typed in by hand in an earlier version: whoever did it, the owner is told to link, and nobody named. */
    @Test
    void aLoginKeptWithoutProofIsToldToLinkGithub() {
        command("Vladsz83", "/run-all");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        assertThat(reply()).isEqualTo("@Vladsz83 nothing was queued: the checker keeps this GitHub login from an"
            + " earlier version, with no proof that the account is yours. Log in at https://checker.example, open ⚙ and"
            + " press **Link GitHub** under **GitHub account**: that also switches on **PR commands**.")
            .doesNotContain("typist");
    }

    /** SESSION_SECRET changed: the stored TeamCity token of NSAmelchev cannot be read, and a login brings a new one. */
    @Test
    void aTokenTheCheckerCannotReadIsToldToLogInAgain() {
        command("NSAmelchev", "/run-all");

        assertThat(reply()).isEqualTo("@NSAmelchev nothing was queued: the checker can no longer read the TeamCity"
            + " token it stores for you. Log in at https://checker.example again, and it keeps the new one.");
    }

    @Test
    void aRefusedTeamCityTokenIsToldOncePerPr() {
        command("kirill-gh", "/run-all");
        command("kirill-gh", "/run-all");
        commandOn(PR + 1, "kirill-gh", "/run-all");

        verify(github, times(1)).appComment(eq(PR), eq("kirill"), eq("@kirill-gh nothing was queued: TeamCity no longer"
            + " accepts the token the checker stores for you (expired or revoked). Log in at https://checker.example"
            + " with a fresh TeamCity token: it replaces the stored one, and your commands and options resume."));
        verify(github, times(1)).appComment(eq(PR + 1), eq("kirill"), anyString());
        verify(github, times(3)).appReact(anyInt(), any(), anyLong(), eq("confused"));
    }

    @Test
    void aTopWithoutAccessSaysNothingMoved() {
        command("stranger", "/top");

        assertThat(reply()).startsWith("@stranger nothing moved: this GitHub account is not linked to a ci2 account");
    }

    @Test
    void aTopWithNothingToMoveIsExplainedOncePerPr() {
        command("anton-vinogradov", "/top");
        command("anton-vinogradov", "/top");

        verify(github, times(1)).appComment(eq(PR), eq("avinogradov"), anyString());
        verify(github, times(2)).appReact(anyInt(), any(), anyLong(), eq("confused"));
    }

    /** ci2 answered 403 to queuing her RunAll: its firewall does that to valid calls, and so does a missing right. */
    @Test
    void aRunAllCi2RefusesSaysTheAccountMayNotRunBuilds() {
        when(tc.triggerRunAll("tc-avinogradov", PR, false)).thenThrow(new TcResponseException(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null)));

        command("anton-vinogradov", "/run-all");

        assertThat(reply()).isEqualTo("@anton-vinogradov 🚀 _Nothing was queued: ci2 answered HTTP 403. Your ci2 account"
            + " may not run builds here, or ci2's firewall blocked the request: try again in a few minutes._");
    }

    /**
     * GitHub, at its rate limit, answered 403 when the checkstyle fix read alice-v's PR before queuing: ci2 and her
     * ci2 account had no part in it.
     */
    @Test
    void aGithub403BeforeQueuingBlamesNoCi2Account() {
        when(styleFix.fixForCommand(eq(PR), any(), eq("alice-v"))).thenThrow(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        command("alice-v", "/run-all");

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        assertThat(reply()).isEqualTo("@alice-v 🚀 _Nothing was queued: the request failed with HTTP 403. Try again in"
            + " a few minutes._");
    }

    /** The reply goes into a public thread under the checker's name: whatever the comment says stays out of it. */
    @Test
    void theReplyQuotesNothingOfTheComment() {
        command("stranger", "/run-all top <img src=x onerror=alert(1)> please @everyone");

        assertThat(reply()).doesNotContain("img", "alert", "please", "everyone");
    }

    private String reply() {
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(PR), any(), reply.capture());
        assertThat(PrCommands.readsAsCommand(reply.getValue())).isFalse();

        return reply.getValue();
    }

    private void command(String login, String body) {
        commandOn(PR, login, body);
    }

    private void commandOn(int pr, String login, String body) {
        long id = nextComment++;
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            "https://github.com/apache/ignite/pull/" + pr + "#issuecomment-" + id, Instant.now().toString(),
            GhUsers.of(login))));
        commands.poll();
    }

    /** An enrollment with PR commands on and nothing else; {@code proven}: a link proved the login, with its id. */
    private ObjectNode enrollment(String username, String login, boolean proven) {
        ObjectNode e = mapper.createObjectNode();
        e.put("username", username);
        e.putNull("jiraToken");
        e.putNull("ghToken");
        e.put("ghLogin", login);
        e.put("enabledAt", 1760000000000L);
        e.put("autoRerun", false);
        e.put("commands", true);
        e.put("ghLoginProven", proven);
        if (proven)
            e.put("ghId", GhUsers.idOf(login));

        return e;
    }
}
