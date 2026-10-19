package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * The PR page asks where the visa of the run on screen stands at every poll of the runs, every 15 seconds while one
 * goes. TeamCity is asked who started the run, whether it was cancelled and when it finished, and only when the
 * checker's own records do not tell: a finished run once, and a run TeamCity no longer has once too. The merged PR
 * 13335, whose RunAll 9389046 TeamCity had dropped, cost a failed TeamCity call and an error at every poll.
 */
class VisaRunLookupTest {
    private static final String USER = "avinogradov";

    private static final String REVIEWER = "nizhikov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final DateTimeFormatter TC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ")
        .withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final TcClient tc = mock(TcClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final MergedVerdicts merged = mock(MergedVerdicts.class);

    private final AtomicLong now = new AtomicLong(1_760_000_000_000L);

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, mock(GithubClient.class),
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final JiraController controller = new JiraController(jira, tc, standing, merged,
        new UserDirectory(mapper), now::get);

    @BeforeEach
    void setUp() {
        when(merged.verdict(PR)).thenReturn(Optional.empty());
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null));
    }

    @Test
    void aFinishedRunIsAskedAboutOnce() {
        when(tc.buildEnd("reviewer-tc", RUN_ALL)).thenReturn(Optional.of(build("finished", inAMinute(), false)));

        controller.visaLine(PR, RUN_ALL, "reviewer-tc", REVIEWER);
        now.addAndGet(3_600_000);

        assertThat(controller.visaLine(PR, RUN_ALL, "reviewer-tc", REVIEWER))
            .isEqualTo(line(StandingVisas.VisaLine.ON, USER, false));
        verify(tc, times(1)).buildEnd(anyString(), anyLong());
    }

    /** The page tells the server nothing of the run: when it finished is TeamCity's word. */
    @Test
    void whenTheRunFinishedComesFromTeamCity() {
        when(tc.buildEnd("tc", RUN_ALL)).thenReturn(Optional.of(build("finished", "20250101T000000+0000", false)));

        assertThat(controller.visaLine(PR, RUN_ALL, "tc", USER).state()).isEqualTo(StandingVisas.VisaLine.LATE);
    }

    /** The run was going when the page first asked, and someone cancelled it a minute later. */
    @Test
    void aRunStillGoingIsAskedAboutAgainAMinuteLater() {
        when(tc.buildEnd("tc", RUN_ALL)).thenReturn(Optional.of(build("running", null, false)));
        assertThat(controller.visaLine(PR, RUN_ALL, "tc", USER).state()).isEqualTo(StandingVisas.VisaLine.DUE);

        when(tc.buildEnd("tc", RUN_ALL)).thenReturn(Optional.of(build("finished", inAMinute(), true)));
        now.addAndGet(JiraController.RUN_GOING_ASKED_EVERY_MS - 1);
        assertThat(controller.visaLine(PR, RUN_ALL, "tc", USER).state()).isEqualTo(StandingVisas.VisaLine.DUE);
        now.addAndGet(1);
        assertThat(controller.visaLine(PR, RUN_ALL, "tc", USER))
            .isEqualTo(line(StandingVisas.VisaLine.CANCELLED, USER, true));
        verify(tc, times(2)).buildEnd(anyString(), anyLong());
    }

    @Test
    void aMergedPrAsksTeamCityNothing() {
        when(merged.verdict(PR)).thenReturn(Optional.of(mock(AnalysisResult.class)));

        assertThat(controller.visaLine(PR, RUN_ALL, "reviewer-tc", REVIEWER))
            .isEqualTo(line(StandingVisas.VisaLine.CLOSED, null, false));
        verify(tc, never()).buildEnd(anyString(), anyLong());
    }

    @Test
    void aRunTeamCityNoLongerHasIsAskedAboutOnce() {
        when(tc.buildEnd("reviewer-tc", RUN_ALL)).thenThrow(new TcResponseException(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null)));

        for (int poll = 0; poll < 3; poll++) {
            assertThat(controller.visaLine(PR, RUN_ALL, "reviewer-tc", REVIEWER))
                .isEqualTo(line(StandingVisas.VisaLine.UNKNOWN, null, false));
            now.addAndGet(90_000);
        }
        verify(tc, times(1)).buildEnd(anyString(), anyLong());
    }

    @Test
    void aTeamCityFailureIsNotKept() {
        when(tc.buildEnd("tc", RUN_ALL)).thenThrow(new TcResponseException(
            HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null)))
            .thenReturn(Optional.of(build("finished", inAMinute(), false)));

        assertThatThrownBy(() -> controller.visaLine(PR, RUN_ALL, "tc", USER)).isInstanceOf(TcResponseException.class);
        assertThat(controller.visaLine(PR, RUN_ALL, "tc", USER).state()).isEqualTo(StandingVisas.VisaLine.DUE);
    }

    private static StandingVisas.VisaLine line(String state, String starter, boolean mine) {
        return new StandingVisas.VisaLine(state, starter, mine, null, null, false);
    }

    private TcModel.Build build(String state, String finishDate, boolean cancelled) {
        return new TcModel.Build(RUN_ALL, null, state, null, null, null, null, null, finishDate, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null,
            cancelled ? new TcModel.CanceledInfo("Canceled", new TcModel.User(USER)) : null);
    }

    /** A finish date in TeamCity's spelling, a minute after the option went on. */
    private static String inAMinute() {
        return TC_DATE.format(Instant.now().plusSeconds(60));
    }
}
