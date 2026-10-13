package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AiProperties;
import com.github.igniteprchecker.config.SessionProperties;
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
 * before it reads back with it off; without the operator's Anthropic API key it is refused and shown unavailable.
 */
class SuiteFixOptionTest {
    private static final String FIXTURE = "/snapshots/standing-visas-v1.20.11.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = standing();

    @Test
    void withoutTheKeyItIsRefusedAndShownUnavailable() {
        Optional<StandingVisas.Refusal> refused = standing.change("alice", "tc", null, "gh-pat",
            new StandingVisas.OptionChange(null, null, null, null, null, true));

        assertThat(refused).contains(new StandingVisas.Refusal(null,
            "this checker has no Anthropic API key, so it can't pick suites: ask its operator"));
        assertThat(standing.settings("alice").suitesAvailable()).isFalse();
        assertThat(standing.enabled("alice")).isFalse();
    }

    @Test
    void itTakesTheGithubTokenAndTheLoginItProvesAndGivesTheTokenUpWhenOff() {
        standing.setAi(new AiProperties("key"));
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("Alice-V"));

        assertThat(standing.change("alice", "tc", null, null, suites(true)))
            .hasValueSatisfying(r -> assertThat(r.need()).isEqualTo("github"));
        assertThat(standing.change("alice", "tc", null, "gh-pat", suites(true))).isEmpty();

        assertThat(standing.settings("alice")).satisfies(st -> {
            assertThat(st.suites()).isTrue();
            assertThat(st.suitesAvailable()).isTrue();
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
    void aTokenGithubRefusesSwitchesItOff() {
        standing.setAi(new AiProperties("key"));
        when(github.ghUser(anyString())).thenReturn(Optional.of("Alice-V"));
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
        standing.setAi(new AiProperties("key"));
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
