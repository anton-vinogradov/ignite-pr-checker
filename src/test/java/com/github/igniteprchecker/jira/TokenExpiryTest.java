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
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stored token the service refuses takes its options down with it: a switch that promises work the
 * checker can no longer do is worse than an off switch, and the panel has to know which credential
 * to ask for again.
 */
class TokenExpiryTest {
    private static final String USER = "avinogradov";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GithubClient github = mock(GithubClient.class);
    private final JiraClient jira = mock(JiraClient.class);
    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()),
        mock(TcClient.class), github, mock(BlockerAnalyzer.class), jira, mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private void enrolWithEverything() {
        when(github.ghUser(anyString())).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself(anyString())).thenReturn(Optional.of(USER));
        when(jira.myTimezone(anyString())).thenReturn(Optional.of("Europe/Moscow"));
        standing.change(USER, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, true, true, true, null));
    }

    @Test
    void aRefusedGithubTokenSwitchesOffWhatItPaidFor() {
        enrolWithEverything();

        standing.dropGhToken(USER);

        assertThat(standing.ghOn(USER)).isFalse();
        assertThat(standing.styleFixOn(USER)).isFalse();
        assertThat(standing.ghTokenRejected(USER)).isTrue();
        assertThat(standing.ghLoginOf(USER)).isEqualTo("anton-vinogradov"); // the login is not a credential
        assertThat(standing.visaOn(USER)).as("JIRA is a different token").isTrue();
        assertThat(standing.rerunOn(USER)).as("auto re-run needs no GitHub token").isTrue();
    }

    @Test
    void aRefusedJiraTokenSwitchesOffTheVisa() {
        enrolWithEverything();

        standing.dropJiraToken(USER);

        assertThat(standing.visaOn(USER)).isFalse();
        assertThat(standing.jiraTokenRejected(USER)).isTrue();
        assertThat(standing.ghOn(USER)).as("GitHub is a different token").isTrue();
    }

    @Test
    void savingAWorkingTokenClearsTheAlarm() {
        enrolWithEverything();
        standing.dropGhToken(USER);

        standing.change(USER, "tc", null, "fresh-gh-pat", new StandingVisas.OptionChange(null, null, true, null, null));

        assertThat(standing.ghTokenRejected(USER)).isFalse();
        assertThat(standing.ghOn(USER)).isTrue();
    }

    /** A token can also vanish without a 401 — made undecryptable, or lost by an older build. */
    @Test
    void anOptionWithoutItsTokenIsSwitchedOffOnTheNextPoll(@TempDir Path dir) throws Exception {
        enrolWithEverything();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).putNull("ghToken");
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);

        standing.ensureGhLogins(); // the command poll's own housekeeping

        assertThat(standing.ghOn(USER)).isFalse();
        assertThat(standing.styleFixOn(USER)).isFalse();
        assertThat(standing.ghTokenRejected(USER)).isTrue();
        assertThat(standing.rerunOn(USER)).as("auto re-run runs on the TeamCity token").isTrue();
    }

    @Test
    void aTokenGithubCannotNameIsNotStoredAndCostsNoLogin() {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        standing.linkGhLogin(USER, "tc", "anton-vinogradov");
        when(github.ghUser(anyString())).thenReturn(Optional.empty()); // expired PAT from the session

        Optional<StandingVisas.Refusal> refused =
            standing.change(USER, "tc", null, "dead-gh-pat",
                new StandingVisas.OptionChange(null, null, true, null, null));

        assertThat(refused).hasValueSatisfying(r -> assertThat(r.need()).isEqualTo("github"));
        assertThat(standing.ghOn(USER)).isFalse();
        assertThat(standing.ghLoginOf(USER)).isEqualTo("anton-vinogradov");
    }
}
