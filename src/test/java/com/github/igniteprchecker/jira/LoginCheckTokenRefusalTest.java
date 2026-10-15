package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * At the first poll of v1.28.0 the checker asked avinogradov's stored GitHub token whose it was, to prove the login an
 * earlier version kept next to it. GitHub refused the token, and the login went unproven, but the token stayed: the
 * checkstyle autofix stayed on, on a token that could not commit, and ⚙ said nothing of it. A token GitHub refuses
 * when asked goes now as after a refused commit; one GitHub could not ask about stays.
 */
class LoginCheckTokenRefusalTest {
    private static final String USER = "avinogradov";

    private static final String PAT = "ghp_expired0123456789";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final GithubClient github = AppWriting.github();

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    @TempDir
    Path dir;

    /** An enrollment of v1.27.0: no word of whether the login is proven. */
    @Test
    void aTokenRefusedWhenAskedGoesWithTheOptionsThatCommit() throws Exception {
        load(enrollment(true, true).putNull("ghLoginProven"));
        when(github.ghIdentity(PAT)).thenReturn(Optional.empty());

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.ghStored()).isFalse();
            assertThat(st.ghTokenRejected()).as("⚙ says GitHub rejected the token").isTrue();
            assertThat(st.style()).isFalse();
            assertThat(st.suites()).isFalse();
            assertThat(st.commands()).as("PR commands need no token").isTrue();
            assertThat(st.loginUnproven()).isTrue();
        });
    }

    /** Production's avinogradov as v1.28.0 left him: the login unproven, the refused token kept, the autofix on. */
    @Test
    void aTokenKeptNextToAnUnprovenLoginIsAskedAgain() throws Exception {
        load(enrollment(true, false).put("ghLoginProven", false));
        when(github.ghIdentity(PAT)).thenReturn(Optional.empty());

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.ghStored()).isFalse();
            assertThat(st.ghTokenRejected()).isTrue();
            assertThat(st.style()).isFalse();
            assertThat(st.loginUnproven()).isTrue();
        });
    }

    /** Asked again, the token names the login: it is proven now, as at any save of a token. */
    @Test
    void aTokenAskedAgainThatNamesTheLoginProvesIt() throws Exception {
        load(enrollment(true, false).put("ghLoginProven", false));
        when(github.ghIdentity(PAT)).thenReturn(Optional.of(new GithubClient.GhUser("anton-vinogradov", 1729371L)));

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.loginUnproven()).isFalse();
            assertThat(st.ghStored()).isTrue();
            assertThat(st.style()).isTrue();
        });
    }

    /** An unproven login with no option that commits: its token went at the load, as before, and nothing is asked. */
    @Test
    void anUnprovenLoginWithNothingToCommitIsNotAskedAgain() throws Exception {
        load(enrollment(false, false).put("ghLoginProven", false));

        standing.ensureGhLogins();

        verify(github, never()).ghIdentity(PAT);
        assertThat(standing.settings(USER).ghStored()).isFalse();
    }

    @Test
    void aTokenGithubCouldNotAskAboutStays() throws Exception {
        load(enrollment(true, false).putNull("ghLoginProven"));
        when(github.ghIdentity(PAT)).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.ghStored()).isTrue();
            assertThat(st.ghTokenRejected()).isFalse();
            assertThat(st.style()).isTrue();
        });
    }

    /**
     * A token kept only to be asked about, for the mention in the PR comment: refused, it goes without a note of
     * options switched off, as none was on.
     */
    @Test
    void aRefusedTokenNoOptionNeededGoesWithoutANote() throws Exception {
        load(enrollment(false, false).putNull("ghLoginProven"));
        when(github.ghIdentity(PAT)).thenReturn(Optional.empty());

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.ghStored()).isFalse();
            assertThat(st.ghTokenRejected()).isFalse();
        });
    }

    /** An enrollment older still, with no login at all: the token is asked for it, and a refused one goes the same. */
    @Test
    void aTokenRefusedWhenItsLoginIsBackfilledGoesToo() throws Exception {
        ObjectNode old = enrollment(false, true);
        old.remove("ghLogin");
        old.put("commands", false);
        load(old);
        when(github.ghIdentity(PAT)).thenReturn(Optional.empty());

        standing.ensureGhLogins();

        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.ghStored()).isFalse();
            assertThat(st.ghTokenRejected()).isTrue();
            assertThat(st.suites()).isFalse();
            assertThat(st.gh()).isTrue();
        });
    }

    private void load(ObjectNode enrollment) throws Exception {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.putArray("enrollments").add(enrollment);
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), snapshot);
        standing.loadFrom(file);
    }

    /** avinogradov's enrollment: the PR comment and PR commands on, the GitHub token kept next to his login. */
    private ObjectNode enrollment(boolean styleFix, boolean suiteFix) {
        ObjectNode e = mapper.createObjectNode();
        e.put("username", USER);
        e.put("tcToken", codec.encryptString("tc-" + USER));
        e.putNull("jiraToken");
        e.put("ghToken", codec.encryptString(PAT));
        e.put("ghLogin", "anton-vinogradov");
        e.put("tz", "Europe/Moscow");
        e.put("enabledAt", 1760000000000L);
        e.put("autoVisa", false);
        e.put("autoRerun", false);
        e.put("ghComment", true);
        e.put("styleFix", styleFix);
        e.put("ghRejectedAt", 0);
        e.put("jiraRejectedAt", 0);
        e.put("tcRejectedAt", 0);
        e.put("commands", true);
        e.put("suiteFix", suiteFix);

        return e;
    }
}
