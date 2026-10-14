package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Add my PRs' tests to a suite" is a standing option like the checkstyle autofix: it commits with the author's
 * GitHub token, finds the author's PRs by the GitHub login, and lives in standing-visas.json. A snapshot written
 * before it reads back with it off. The suite is picked by a rule in the checker, so the token is all it takes.
 */
class SuiteFixOptionTest {
    private static final String FIXTURE = "/snapshots/standing-visas-v1.20.11.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = AppWriting.github();

    private final StandingVisas standing = standing();

    @Test
    void itTakesTheGithubTokenAndTheLoginItProvesAndGivesTheTokenUpWhenOff() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Alice-V"),
            null)));

        assertThat(standing.change("alice", "tc", null, null, suites(true)))
            .hasValueSatisfying(r -> assertThat(r.need()).isEqualTo("github"));
        assertThat(standing.change("alice", "tc", null, "gh-pat", suites(true))).isEmpty();

        assertThat(standing.settings("alice")).satisfies(st -> {
            assertThat(st.suites()).isTrue();
            assertThat(st.ghStored()).isTrue();
            assertThat(st.login()).isEqualTo("Alice-V");
        });
        assertThat(standing.suiteFixers()).containsOnlyKeys("Alice-V")
            .hasEntrySatisfying("Alice-V", a -> assertThat(a.ghToken()).isEqualTo("gh-pat"));

        standing.markTcRejected("alice");
        assertThat(standing.suiteFixers()).as("paused while TeamCity refuses the user's token").isEmpty();
        standing.tcTokenAccepted("alice", "tc");

        assertThat(standing.change("alice", "tc", null, null, suites(false))).isEmpty();
        assertThat(standing.enabled("alice")).isFalse();
        assertThat(standing.suiteFixers()).isEmpty();
    }

    @Test
    void switchingItOffGivesTheGithubTokenUpWhileAutoRerunStaysOn() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Alice-V"),
            null)));
        standing.change("alice", "tc", null, "gh-pat", new StandingVisas.OptionChange(null, true, null, null, null,
            true));

        assertThat(standing.change("alice", "tc", null, null, suites(false))).isEmpty();

        assertThat(standing.rerunOn("alice")).isTrue();
        assertThat(standing.settings("alice").ghStored()).isFalse();
    }

    /** Auto visa and auto re-run act on runs that finish after they are switched on; this one does not move that. */
    @Test
    void switchingItOnLeavesTheCutoffOfTheOtherOptions(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            Files.copy(in, file);
        }
        standing.loadFrom(file);

        assertThat(standing.change("avinogradov", "tc-avinogradov", null, null, suites(true))).isEmpty();
        Path saved = dir.resolve("saved.json");
        standing.saveTo(saved);

        assertThat(mapper.readTree(saved.toFile()).at("/enrollments")).filteredOn(n -> n.at("/username").asText()
            .equals("avinogradov")).singleElement().satisfies(n -> {
                assertThat(n.at("/suiteFix").asBoolean()).isTrue();
                assertThat(n.at("/enabledAt").asLong()).isEqualTo(1759312800000L);
            });
    }

    /**
     * The checkstyle autofix, with PR commands, stored the GitHub token without a login; the PRs this option fixes are
     * found by it.
     */
    @Test
    void aLoginTheTokenWasStoredWithoutIsFilledIn(@TempDir Path dir) throws Exception {
        ObjectNode snapshot;
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            snapshot = (ObjectNode)mapper.readTree(in);
        }
        ObjectNode avinogradov = (ObjectNode)snapshot.at("/enrollments/0");
        avinogradov.put("ghComment", false).put("commands", true).remove("ghLogin");
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), snapshot);
        standing.loadFrom(file);
        when(github.ghIdentity(anyString())).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));

        assertThat(standing.change("avinogradov", "tc-avinogradov", null, null, suites(true))).isEmpty();
        assertThat(standing.suiteFixers()).isEmpty();

        standing.ensureGhLogins();

        assertThat(standing.suiteFixers()).containsOnlyKeys("anton-vinogradov");
    }

    @Test
    void aTokenGithubRefusesSwitchesItOff() {
        when(github.tokenOwner(anyString())).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("Alice-V"),
            null)));
        standing.change("alice", "tc", null, "gh-pat", new StandingVisas.OptionChange(null, true, null, null, null,
            true));

        standing.dropGhToken("alice");

        assertThat(standing.settings("alice").suites()).isFalse();
        assertThat(standing.rerunOn("alice")).isTrue();
    }

    @Test
    void aSnapshotWrittenBeforeTheOptionReadsWithItOffAndANewOneKeepsIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            Files.copy(in, file);
        }
        standing.loadFrom(file);

        assertThat(standing.settings("avinogradov").suites()).isFalse();
        assertThat(standing.suiteFixers()).isEmpty();

        assertThat(standing.change("avinogradov", "tc-avinogradov", null, null, suites(true))).isEmpty();
        Path saved = dir.resolve("saved.json");
        standing.saveTo(saved);
        StandingVisas restarted = standing();
        restarted.loadFrom(saved);

        assertThat(mapper.readTree(saved.toFile()).at("/enrollments").findValues("suiteFix"))
            .extracting(n -> n.isNull() ? null : n.asBoolean()).contains(true);
        assertThat(restarted.suiteFixers()).containsOnlyKeys("anton-vinogradov");
        assertThat(restarted.styleFixOn("avinogradov")).isTrue();
        assertThat(restarted.visaOn("avinogradov")).isTrue();
    }

    private static StandingVisas.OptionChange suites(boolean on) {
        return new StandingVisas.OptionChange(null, null, null, null, null, on);
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper),
            mock(TcClient.class), github, mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }
}
