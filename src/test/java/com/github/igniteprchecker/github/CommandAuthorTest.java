package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A PR command was the command of whoever had linked the author's login, a string: after a rename, another account
 * could take the login and command under the old owner's TeamCity token, and a login typed in by hand was anyone's.
 * The author is now matched by the id of a proven account. Production's standing-visas.json of v1.27.0 holds two
 * logins its users' GitHub tokens proved, and no id: the id comes from GitHub at the first poll, and a login typed in
 * by hand, which an earlier version could hold, runs nothing until a token proves it.
 */
class CommandAuthorTest {
    private static final int PR = 13800;

    private static final String PR_URL = "https://github.com/apache/ignite/pull/" + PR;

    private static final long ANTON = 1729371L;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final PrCommands commands = new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class),
        mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    private long nextComment = 1;

    @TempDir
    Path dir;

    @BeforeEach
    void production() throws Exception {
        ObjectNode snapshot = mapper.createObjectNode();
        ArrayNode enrollments = snapshot.putArray("enrollments");
        enrollments.add(enrollment("avinogradov", "anton-vinogradov", codec.encryptString("gh-avinogradov")));
        enrollments.add(enrollment("olegvlsk", "oleg-vlsk", codec.encryptString("gh-olegvlsk")));
        enrollments.add(enrollment("typist", "Vladsz83", null));
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), snapshot);
        standing.loadFrom(file);
        when(github.userByLogin("anton-vinogradov"))
            .thenReturn(Optional.of(new GithubClient.GhUser("anton-vinogradov", ANTON)));
        when(github.userByLogin("oleg-vlsk")).thenReturn(Optional.of(new GithubClient.GhUser("oleg-vlsk", 5550101L)));
        when(tc.triggerRunAll(anyString(), anyInt(), anyBoolean())).thenReturn(new TcModel.Build(9400000L, null,
            "queued", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aLoginATokenProvedGetsItsIdAndCommands() throws Exception {
        command(new GithubClient.GhUser("anton-vinogradov", ANTON));

        verify(tc).triggerRunAll("tc-avinogradov", PR, false);
        assertThat(standing.settings("avinogradov").loginUnproven()).isFalse();
        standing.saveTo(dir.resolve("saved.json"));
        JsonNode saved = null;
        for (JsonNode e : mapper.readTree(dir.resolve("saved.json").toFile()).get("enrollments")) {
            if (e.get("username").asText().equals("avinogradov"))
                saved = e;
        }
        assertThat(saved.get("ghId").asLong()).isEqualTo(ANTON);
        assertThat(saved.get("ghLoginProven").asBoolean()).isTrue();
    }

    /** anton-vinogradov renamed his account, and someone else took the old login. */
    @Test
    void anotherAccountUnderTheSameLoginCommandsNothing() {
        command(new GithubClient.GhUser("anton-vinogradov", 98765432L));

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github).reactToCommentAsApp(1L, "confused");
    }

    @Test
    void theAccountUnderItsNewLoginStillCommands() {
        command(new GithubClient.GhUser("anton-vinogradov", ANTON));

        command(new GithubClient.GhUser("avinogradov-gh", ANTON));

        verify(tc, times(2)).triggerRunAll("tc-avinogradov", PR, false);
        assertThat(standing.ghLoginOf("avinogradov")).isEqualTo("avinogradov-gh");
    }

    /** No token ever proved the login, so it may be anyone's: ⚙ says so, and the user's commands wait. */
    @Test
    void aLoginTypedInByHandCommandsNothingUntilProven() {
        command(new GithubClient.GhUser("Vladsz83", 4242L));

        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        assertThat(standing.commandsOn("typist")).as("the switch stays as it was").isTrue();
        assertThat(standing.settings("typist")).satisfies(st -> {
            assertThat(st.login()).isEqualTo("Vladsz83");
            assertThat(st.loginUnproven()).isTrue();
        });

        standing.linkGhLogin("typist", "tc-typist", new GithubClient.GhUser("Vladsz83", 4242L));
        command(new GithubClient.GhUser("Vladsz83", 4242L));

        verify(tc).triggerRunAll("tc-typist", PR, false);
        assertThat(standing.settings("typist").loginUnproven()).isFalse();
    }

    /** The account was renamed before GitHub named its id: the login it was proven under names nobody now. */
    @Test
    void aProvenLoginThatNamesNobodyNowIsNoLongerProven() {
        when(github.userByLogin("oleg-vlsk")).thenReturn(Optional.empty());

        command(new GithubClient.GhUser("oleg-vlsk", 5550101L));

        verify(tc, never()).triggerRunAll("tc-olegvlsk", PR, false);
        assertThat(standing.settings("olegvlsk").loginUnproven()).isTrue();
    }

    /** One comment of a fresh poll, by {@code author}. */
    private void command(GithubClient.GhUser author) {
        long id = nextComment++;
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, "/run-all",
            PR_URL + "#issuecomment-" + id, Instant.now().toString(), author)));
        commands.poll();
    }

    /** An enrollment as v1.27.0 wrote it: PR comment and PR commands on, the login without an id. */
    private ObjectNode enrollment(String username, String login, String ghToken) {
        ObjectNode e = mapper.createObjectNode();
        e.put("username", username);
        e.put("tcToken", codec.encryptString("tc-" + username));
        e.putNull("jiraToken");
        e.put("ghToken", ghToken);
        e.put("ghLogin", login);
        e.put("tz", "Europe/Moscow");
        e.put("enabledAt", 1760000000000L);
        e.put("autoVisa", false);
        e.put("autoRerun", false);
        e.put("ghComment", ghToken != null);
        e.put("styleFix", false);
        e.put("ghRejectedAt", 0);
        e.put("jiraRejectedAt", 0);
        e.put("tcRejectedAt", 0);
        e.put("commands", true);
        e.put("suiteFix", false);

        return e;
    }
}
