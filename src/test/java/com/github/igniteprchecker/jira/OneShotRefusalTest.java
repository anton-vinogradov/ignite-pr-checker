package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A one-shot Auto visa whose JIRA token JIRA refused stayed armed for good, the token with it in visa-subs.json, and
 * nothing told its user. It goes now with its token, and the PR page marks it until its user arms one again.
 */
class OneShotRefusalTest {
    private static final int PR = 13654;

    private static final long RUN_ALL = 9391879L;

    private static final String ISSUE = "IGNITE-28890";

    private static final String USER = "avinogradov";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final JiraClient jira = mock(JiraClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final long now = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(new AnalysisResult(PR, RUN_ALL,
            "pull/13654/head", now, List.of(), List.of(), List.of(), List.of(), List.of(), 140, 0, false, 0, false, 0,
            0, 0, now / 1000 - 3600, now / 1000 - 60)));
    }

    @Test
    void aSubscriptionWhoseTokenJiraRefusesGoesWithItAndIsMarked(@TempDir Path dir) throws Exception {
        when(jira.addComment(eq("dead-pat"), eq(ISSUE), any()))
            .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        when(jira.tokenRejected("dead-pat")).thenReturn(true);
        VisaSubscriptions subs = subscriptions();
        subs.arm(PR, ISSUE, "dead-pat", USER);

        subs.settle(PR, RUN_ALL, System.currentTimeMillis());

        assertThat(subs.armedCount()).isZero();
        assertThat(subs.armed(PR, USER)).isEqualTo(new VisaSubscriptions.Armed(null, List.of(), ISSUE));

        Path file = dir.resolve("visa-subs.json");
        subs.saveTo(file);
        VisaSubscriptions restarted = subscriptions();
        restarted.loadFrom(file);

        assertThat(Files.readString(file)).doesNotContain("\"token\"");
        assertThat(restarted.armed(PR, USER).refusedFor()).isEqualTo(ISSUE);

        restarted.arm(PR, ISSUE, "fresh-pat", USER);

        assertThat(restarted.armed(PR, USER)).isEqualTo(new VisaSubscriptions.Armed(ISSUE, List.of(), null));
    }

    /** JIRA answers 403 to a ticket the user may not comment on: the token is alive, and the visa is tried again. */
    @Test
    void aSubscriptionJiraFailsForAnotherReasonStaysArmed() {
        when(jira.addComment(eq("jira-pat"), eq(ISSUE), any()))
            .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        VisaSubscriptions subs = subscriptions();
        subs.arm(PR, ISSUE, "jira-pat", USER);

        subs.settle(PR, RUN_ALL, System.currentTimeMillis());

        assertThat(subs.armed(PR, USER)).isEqualTo(new VisaSubscriptions.Armed(ISSUE, List.of(), null));
    }

    private VisaSubscriptions subscriptions() {
        Warmer warmer = mock(Warmer.class);
        when(warmer.borrowToken()).thenReturn("tc");
        VisaService visas = mock(VisaService.class);
        when(visas.compose(eq(PR), any(), any(), any())).thenReturn("verdict of " + RUN_ALL);
        StandingVisas standing = mock(StandingVisas.class);
        when(standing.visaCover(any(), anyInt(), anyLong(), any())).thenReturn(StandingVisas.VisaCover.NONE);

        return new VisaSubscriptions(mapper, codec, jira, visas, analyzer, warmer, mock(PendingCommits.class),
            mock(TcClient.class), standing);
    }
}
