package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A local run on which the developer switched auto re-run on swept the open PRs and re-ran their suites on ci2 next
 * to the production instance, which sweeps the same PRs for the same user.
 */
class AutomationOffTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final Warmer warmer = mock(Warmer.class);

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        warmer, mock(PendingCommits.class));

    private final RerunTracker.SuiteFailedMidRun queries5 = new RerunTracker.SuiteFailedMidRun(13335, 9389046L,
        "IgniteTests24Java8_Queries5", 9389028L, "Queries 5");

    @BeforeEach
    void autoRerunOn() {
        standing.change("avinogradov", "tc", null, null, new StandingVisas.OptionChange(false, true, false, false,
            null));
        clearInvocations(warmer);
    }

    @Test
    void theSweepOnlyTicks() {
        ReflectionTestUtils.setField(standing, "automation", false);

        standing.sweep();

        verifyNoInteractions(github, tc, warmer);
        assertThat(standing.lastSweepAt()).isPositive();
    }

    @Test
    void aSuiteFailingMidRunIsNotReRun() {
        ReflectionTestUtils.setField(standing, "automation", false);

        standing.earlyRerun(queries5);

        verifyNoInteractions(tc);
    }

    @Test
    void productionSweepsAndSettles() {
        standing.sweep();
        standing.earlyRerun(queries5);

        verify(warmer).offerToken("tc");
        verify(github).openPrs();
        verify(tc).buildTriggeredBy(anyString(), anyLong());
    }
}
