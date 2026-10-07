package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.AnalyzeController;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A PR run on one commit, then rebased on master and force-pushed: GitHub compares the two as "diverged", 150
 * commits ahead, master's among them. /api/pending passed on only the count and a 7-character sha, so the "ai"
 * prompt said "150 commits pushed since" and checked that sha out of pull/N/head, which no longer has it.
 */
class RewrittenBranchTest {
    private static final String BUILT = "abc1234f5e6d7c8b9a0f1e2d3c4b5a6978695a4b";

    private static final String HEAD = "def5678a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    private final TcClient tc = mock(TcClient.class);

    private final AnalyzeController analyze = new AnalyzeController(mock(BlockerAnalyzer.class),
        mock(RunDeltaStore.class), new PendingCommits(tc, github));

    @Test
    void rebasedBranchIsToldApartFromNewCommits() {
        when(tc.buildRevision("t", 9002L)).thenReturn(Optional.of(BUILT));
        expectHead();
        server.expect(requestTo(compare())).andRespond(withSuccess("{\"status\":\"diverged\",\"ahead_by\":150,"
            + "\"behind_by\":2}", MediaType.APPLICATION_JSON));

        Map<String, Object> pending = analyze.pending(13575, 9002L, "t");

        assertThat(pending).containsEntry("pending", true).containsEntry("ahead", 150)
            .containsEntry("builtSha", "abc1234").containsEntry("builtRevision", BUILT).containsEntry("rewritten", true);
        server.verify();
    }

    @Test
    void newCommitsOnTopAreNoRewrite() {
        when(tc.buildRevision("t", 9002L)).thenReturn(Optional.of(BUILT));
        expectHead();
        server.expect(requestTo(compare())).andRespond(withSuccess("{\"status\":\"ahead\",\"ahead_by\":2,"
            + "\"behind_by\":0}", MediaType.APPLICATION_JSON));

        assertThat(analyze.pending(13575, 9002L, "t")).containsEntry("ahead", 2).containsEntry("rewritten", false);
    }

    @Test
    void failedCompareCountsNothingAndClaimsNoRewrite() {
        when(tc.buildRevision("t", 9002L)).thenReturn(Optional.of(BUILT));
        expectHead();
        server.expect(requestTo(compare())).andRespond(withServerError());

        assertThat(analyze.pending(13575, 9002L, "t")).containsEntry("ahead", -1).containsEntry("rewritten", false)
            .containsEntry("builtRevision", BUILT);
    }

    private void expectHead() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13575")).andRespond(withSuccess(
            "{\"user\":{\"login\":\"alice\"},\"head\":{\"ref\":\"ignite-29049\",\"sha\":\"" + HEAD + "\","
                + "\"repo\":{\"full_name\":\"alice/ignite\"}}}", MediaType.APPLICATION_JSON));
    }

    private static String compare() {
        return "https://api.github.com/repos/apache/ignite/compare/" + BUILT + "..." + HEAD;
    }
}
