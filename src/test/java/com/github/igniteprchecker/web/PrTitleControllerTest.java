package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Merged PR 13692 and open PRs past the list of 50 had no title on their page, so the page told the reader that the
 * title named no IGNITE ticket. The title now comes from the open list, else from the verdict kept at the merge, else
 * from GitHub, and a page left open asks GitHub at most once in five minutes.
 */
class PrTitleControllerTest {
    private final GithubClient github = mock(GithubClient.class);

    private final MergedVerdicts merged = mock(MergedVerdicts.class);

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private final PrTitleController titles = new PrTitleController(github, merged, now::get);

    @BeforeEach
    void listed() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13575, "IGNITE-29049 Move MDC to JUnit",
            "https://github.com/apache/ignite/pull/13575", null, null, null)));
        when(merged.title(anyInt())).thenReturn(Optional.empty());
        when(github.pullState(anyInt())).thenReturn(Optional.empty());
    }

    @Test
    void aListedPrsTitleComesFromTheList() {
        assertThat(body(titles.title(13575))).containsEntry("title", "IGNITE-29049 Move MDC to JUnit");
        verify(github, never()).pullState(anyInt());
    }

    @Test
    void aMergedPrsTitleIsTheOneAtItsMerge() {
        when(merged.title(13692)).thenReturn(Optional.of("IGNITE-29117 Python thin client: fix examples"));

        assertThat(body(titles.title(13692))).containsEntry("title", "IGNITE-29117 Python thin client: fix examples");
        verify(github, never()).pullState(anyInt());
    }

    @Test
    void anOpenPrPastTheListAsksGithubOnceInFiveMinutes() {
        when(github.pullState(13100)).thenReturn(Optional.of(new GithubClient.PullState("IGNITE-28800 Old work",
            true, false)));

        assertThat(body(titles.title(13100))).containsEntry("title", "IGNITE-28800 Old work");
        now.addAndGet(PrTitleController.KEPT_MS - 1);
        titles.title(13100);
        verify(github, times(1)).pullState(13100);

        now.addAndGet(1);
        titles.title(13100);
        verify(github, times(2)).pullState(13100);
    }

    @Test
    void aPrGithubDoesNotKnowOrCannotTellIsNotFound() {
        when(github.pullState(13999)).thenThrow(new IllegalStateException("GitHub timed out"));

        assertThat(titles.title(13998).getStatusCode().value()).isEqualTo(404);
        assertThat(titles.title(13999).getStatusCode().value()).isEqualTo(404);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        return (Map<String, Object>)res.getBody();
    }
}
