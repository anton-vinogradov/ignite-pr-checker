package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.github.GithubClient;
import org.junit.jupiter.api.Test;

/**
 * /api/runs kept the head of every PR number anyone asked about until the checker restarted, and a request may name
 * any number. The page now keeps the heads of the PRs asked about last.
 */
class PrHeadsTest {
    private final GithubClient github = mock(GithubClient.class);

    private final PrHeads heads = new PrHeads(github, () -> 1_000_000L);

    @Test
    void theHeadsOfThePrsAskedAboutLastAreKept() {
        when(github.listedHead(anyInt(), anyLong())).thenReturn(null);
        when(github.prHeadSha(anyInt())).thenAnswer(a -> "sha" + a.getArgument(0));

        for (int pr = 1; pr <= PrHeads.KEPT + 1; pr++)
            heads.of(pr);
        assertThat(heads.of(PrHeads.KEPT + 1)).isEqualTo("sha" + (PrHeads.KEPT + 1));
        assertThat(heads.of(1)).isEqualTo("sha1");

        verify(github, times(1)).prHeadSha(PrHeads.KEPT + 1);
        verify(github, times(2)).prHeadSha(1);
    }
}
