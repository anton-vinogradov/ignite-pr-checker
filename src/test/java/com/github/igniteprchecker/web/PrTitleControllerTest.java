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
 * from GitHub. GitHub is asked under the bot's token, whose hourly quota also serves PR commands and visas. So an
 * answer, a title or "no such PR", is kept for five minutes, and GitHub is asked about 60 PRs an hour at most.
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
        when(merged.atMerge(anyInt())).thenReturn(Optional.empty());
        when(github.pullState(anyInt())).thenReturn(Optional.empty());
    }

    @Test
    void aListedPrsTitleComesFromTheList() {
        assertThat(body(titles.title(13575))).containsEntry("title", "IGNITE-29049 Move MDC to JUnit");
        verify(github, never()).pullState(anyInt());
    }

    @Test
    void aMergedPrsTitleIsTheOneAtItsMerge() {
        when(merged.atMerge(13692)).thenReturn(Optional.of(new GithubClient.PullState(
            "IGNITE-29117 Python thin client: fix examples", false, true, 1_791_208_920L, "5be1c0d")));

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
        titles.title(13999);
        verify(github, times(2)).pullState(13999);
    }

    @Test
    void aPrGithubDoesNotKnowIsAskedAboutOnceInFiveMinutes() {
        for (int i = 0; i < 100; i++)
            assertThat(titles.title(99999).getStatusCode().value()).isEqualTo(404);
        verify(github, times(1)).pullState(99999);

        now.addAndGet(PrTitleController.KEPT_MS);
        titles.title(99999);
        verify(github, times(2)).pullState(99999);
    }

    @Test
    void githubIsAskedAboutSixtyPrsAnHourAtMost() {
        for (int pr = 1; pr <= 200; pr++)
            assertThat(titles.title(pr).getStatusCode().value()).isEqualTo(404);
        verify(github, times(PrTitleController.ASKED_PER_HOUR)).pullState(anyInt());
        assertThat(body(titles.title(13575))).as("the open list needs no call").containsEntry("title",
            "IGNITE-29049 Move MDC to JUnit");

        now.addAndGet(60 * 60_000L);
        titles.title(201);
        verify(github).pullState(201);
    }

    /**
     * apache/ignite#13709 was merged on 20 Oct at 10:12 UTC at its commit 9b02561, and the page's head tags it so. The
     * verdict kept at the merge says when and at which commit, with no call to GitHub.
     */
    @Test
    void aMergedPrSaysWhenAndAtWhichCommitItWasMerged() {
        when(merged.atMerge(13709)).thenReturn(Optional.of(new GithubClient.PullState("IGNITE-29120 Fix control.sh",
            false, true, 1_792_491_159L, "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a")));

        assertThat(body(titles.title(13709))).containsEntry("title", "IGNITE-29120 Fix control.sh")
            .containsEntry("state", "merged").containsEntry("closedAt", 1_792_491_159L)
            .containsEntry("head", "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a");
        verify(github, never()).pullState(anyInt());
    }

    @Test
    void aPrClosedWithoutAMergeSaysWhenFromGithub() {
        when(github.pullState(13100)).thenReturn(Optional.of(new GithubClient.PullState("IGNITE-28800 Old work",
            false, false, 1_792_400_000L, "def4567")));

        assertThat(body(titles.title(13100))).containsEntry("state", "closed").containsEntry("closedAt", 1_792_400_000L)
            .containsEntry("head", "def4567");
    }

    @Test
    void anOpenPrIsNeitherMergedNorClosed() {
        when(github.pullState(13100)).thenReturn(Optional.of(new GithubClient.PullState("IGNITE-28800 Old work",
            true, false)));

        assertThat(body(titles.title(13100))).containsOnlyKeys("title");
        assertThat(body(titles.title(13575))).containsOnlyKeys("title");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        return (Map<String, Object>)res.getBody();
    }
}
