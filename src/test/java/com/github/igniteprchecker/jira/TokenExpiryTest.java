package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A stored token the service refuses takes its options down with it: a switch that promises work the
 * checker can no longer do is worse than an off switch, and the panel has to know which credential
 * to ask for again.
 */
class TokenExpiryTest {
    private static final String USER = "avinogradov";

    private final GithubClient github = mock(GithubClient.class);
    private final JiraClient jira = mock(JiraClient.class);
    private final StandingVisas standing = new StandingVisas(new ObjectMapper(),
        new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()),
        mock(TcClient.class), github, mock(BlockerAnalyzer.class), jira, mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private void enrolWithEverything() {
        when(github.ghUser(anyString())).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myTimezone(anyString())).thenReturn(Optional.of("Europe/Moscow"));
        standing.enable(USER, "tc", "jira-pat", "gh-pat", true, true, true, true);
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

        standing.enable(USER, "tc", "jira-pat", "fresh-gh-pat", true, true, true, true);

        assertThat(standing.ghTokenRejected(USER)).isFalse();
        assertThat(standing.ghOn(USER)).isTrue();
    }

    /** A token can also vanish without a 401 — refused at save time, or lost by an older build. */
    @Test
    void anOptionWithoutItsTokenIsSwitchedOffOnTheNextPoll() {
        enrolWithEverything();
        when(github.ghUser(anyString())).thenReturn(Optional.empty());
        standing.enable(USER, "tc", "jira-pat", "dead-gh-pat", true, true, true, true); // token refused, not stored

        standing.ensureGhLogins(); // the command poll's own housekeeping

        assertThat(standing.ghOn(USER)).isFalse();
        assertThat(standing.styleFixOn(USER)).isFalse();
        assertThat(standing.ghTokenRejected(USER)).isTrue();
        assertThat(standing.rerunOn(USER)).as("auto re-run runs on the TeamCity token").isTrue();
    }

    @Test
    void aTokenGithubCannotNameIsNotStoredAndCostsNoLogin() {
        enrolWithEverything();
        when(github.ghUser(anyString())).thenReturn(Optional.empty()); // expired PAT from the session

        boolean accepted = standing.enable(USER, "tc", "jira-pat", "dead-gh-pat", true, true, true, true);

        assertThat(accepted).isFalse();
        assertThat(standing.ghLoginOf(USER)).isEqualTo("anton-vinogradov");
    }
}
