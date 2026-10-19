package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.JiraController;
import com.github.igniteprchecker.web.UserDirectory;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The status page said "0 users with the option on" next to "3 users with an option on", while avinogradov had tests
 * added to suites on with a GitHub token stored. The card counted the users the last sweep acted for: nobody for three
 * minutes after a start, and never a user whose token TeamCity refused.
 */
class SuiteFixCountTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = AppWriting.github();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class),
        github, mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        standing, mock(MergedVerdicts.class), mock(UserDirectory.class));

    private final SuiteFixService suiteFix = new SuiteFixService(github, mock(PrTestRuns.class), standing, mapper,
        Runnable::run, System::currentTimeMillis);

    @Test
    void aUserWithTheOptionOnCountsBeforeAnySweepAndWhilePaused() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("avinogradov"), null)));
        controller.standingVisa(null, null, null, null, null, true, "tc", "avinogradov", null, "gh-pat");

        assertThat(suiteFix.stats()).containsEntry("users", 1).containsEntry("acting", 0);

        suiteFix.sweep();

        assertThat(suiteFix.stats()).containsEntry("users", 1).containsEntry("acting", 1);

        standing.markTcRejected("avinogradov");
        suiteFix.sweep();

        assertThat(suiteFix.stats()).containsEntry("users", 1).containsEntry("acting", 0);
    }
}
