package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats.Glance;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The green tick in the PR list stood at PRs 13583, 13577 and 13389, whose pages said "No test blockers" over
 * tests that started failing, and at runs of old code: 7 of the 13 ticks on prod had commits pushed since, up to
 * 38. The list now ticks only a clean verdict of the PR's head, and says what else a "no blockers" is.
 */
class PrListBadgeTest {
    private static final String HEAD = "5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4";

    private static final String OLDER = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567";

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final AuthInterceptor auth = mock(AuthInterceptor.class);

    private final PrsController prs = new PrsController(github, analyzer, auth);

    @Test
    void onlyACleanVerdictOfTheHeadIsTicked() {
        when(auth.signedIn(any())).thenReturn(Optional.empty());
        when(github.openPrs()).thenReturn(List.of(pr(13583, HEAD), pr(13636, HEAD), pr(13593, HEAD), pr(13461, HEAD),
            pr(13575, null), pr(13600, HEAD)));
        when(analyzer.glance(13583)).thenReturn(new Glance(0, 2, true, HEAD));
        when(analyzer.glance(13636)).thenReturn(new Glance(0, 0, true, OLDER));
        when(analyzer.glance(13593)).thenReturn(new Glance(0, 0, false, HEAD));
        when(analyzer.glance(13461)).thenReturn(new Glance(0, 0, true, HEAD));
        when(analyzer.glance(13575)).thenReturn(new Glance(0, 0, true, HEAD));

        assertThat(prs.prs(new MockHttpServletRequest()))
            .extracting(PrSummary::number, PrSummary::blockers, PrSummary::proven, PrSummary::standing)
            .containsExactly(
                tuple(13583, 0, false, "WATCH"),
                tuple(13636, 0, false, "OLD_CODE"),
                tuple(13593, 0, false, "UNPROVEN"),
                tuple(13461, 0, true, "CLEAN"),
                tuple(13575, 0, false, "UNKNOWN_CODE"),
                tuple(13600, null, null, null));
    }

    @Test
    void theListBadgesEachStanding() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/prs', { body: [
                { number: 13583, title: 'a', url: 'u', blockers: 0, proven: false, standing: 'WATCH' },
                { number: 13636, title: 'b', url: 'u', blockers: 0, proven: false, standing: 'OLD_CODE' },
                { number: 13461, title: 'c', url: 'u', blockers: 0, proven: true, standing: 'CLEAN' }] });
            await page.load('');
            report({ list: page.el('prList').innerHTML });
            """);

        String list = out.get("list").asText();
        assertThat(list).contains("<span class=\"pr-badge watch\" title=\"no blockers, but tests started failing on "
            + "this code — a re-run decides\">!</span>");
        assertThat(list).contains("<span class=\"pr-badge unproven\" title=\"old code — no blockers in this run, but "
            + "commits were pushed since it\">?</span>");
        assertThat(list.split("pr-badge ok", -1)).as("one tick only").hasSize(2);
    }

    private static PrSummary pr(int number, String head) {
        return new PrSummary(number, "IGNITE-1 x", "https://github.com/apache/ignite/pull/" + number, null, null, null,
            head, null);
    }
}
