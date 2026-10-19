package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.AnalyzeController;
import com.github.igniteprchecker.web.PrHeads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PR 13335's page said "No blockers 🎉 … good to go" from the cached analysis of build 9398719 and, once /api/pending
 * had asked GitHub 1.6 s later, that 33 commits were pushed since. The PR list beside it already showed "?": its last
 * look at GitHub had the newer head. The analysis now brings what the list knows, without a call, and /api/pending
 * tells a run of the PR's head from a check that failed.
 */
class ListedHeadTest {
    private static final String BUILT = "1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e";

    private static final String HEAD = "6573622e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a";

    private static final long STARTED = Instant.parse("2026-10-14T09:00:00Z").getEpochSecond();

    private static final long BUILD = 9398719L;

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final AnalyzeController controller = new AnalyzeController(analyzer, mock(RunDeltaStore.class),
        new PendingCommits(tc, github), new PrHeads(github));

    @Test
    void theAnalysisSaysTheHeadTheListSawMove(@TempDir Path dir) throws IOException {
        listed(dir, HEAD, STARTED + 7200);

        JsonNode json = analyze();

        assertThat(json.get("standing").asText()).isEqualTo("CLEAN");
        JsonNode pushed = json.get("pushed");
        assertThat(pushed.get("pending").asBoolean()).isTrue();
        assertThat(pushed.get("ahead").asInt()).as("not counted without a call").isEqualTo(-1);
        assertThat(pushed.get("builtSha").asText()).isEqualTo("1f93640");
        assertThat(pushed.get("headSha").asText()).isEqualTo("6573622");
        assertThat(pushed.get("caveat").asText()).isEqualTo("new commits pushed since this run — it tested older code");
        verifyNoInteractions(tc);
        server.verify();
    }

    /** A list fetched before the run may name a head older than the run's own. */
    @Test
    void aListOlderThanTheRunTellsNothing(@TempDir Path dir) throws IOException {
        listed(dir, HEAD, STARTED - 60);

        assertThat(analyze().get("pushed").isNull()).isTrue();
    }

    /** The head may have moved since the list was fetched: only GitHub's answer now proves the run tested it. */
    @Test
    void aListNamingTheRunsRevisionLeavesTheCheckToPending(@TempDir Path dir) throws IOException {
        listed(dir, BUILT, STARTED + 7200);

        assertThat(analyze().get("pushed").isNull()).isTrue();
    }

    @Test
    void pendingSaysTheRunTestedTheHead() {
        when(tc.prBuildRevision("t", 13335, BUILD)).thenReturn(Optional.of(BUILT));
        expectHead(BUILT);

        assertThat(controller.pending(13335, BUILD, "t")).containsEntry("pending", false)
            .containsEntry("checked", true);
    }

    /** The author deleted the fork: GitHub names no head repository, but still the head's sha. */
    @Test
    void pendingSaysTheRunTestedTheHeadOfADeletedFork() {
        when(tc.prBuildRevision("t", 13335, BUILD)).thenReturn(Optional.of(BUILT));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13335")).andRespond(withSuccess(
            "{\"user\":{\"login\":\"alice\"},\"head\":{\"ref\":\"ignite-28867\",\"sha\":\"" + BUILT + "\","
                + "\"repo\":null}}", MediaType.APPLICATION_JSON));

        assertThat(controller.pending(13335, BUILD, "t")).containsEntry("pending", false)
            .containsEntry("checked", true);
    }

    @Test
    void pendingSaysWhenGithubCouldNotTell() {
        when(tc.prBuildRevision("t", 13335, BUILD)).thenReturn(Optional.of(BUILT));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13335"))
            .andRespond(withServerError());

        assertThat(controller.pending(13335, BUILD, "t")).containsEntry("pending", false)
            .containsEntry("checked", false);
    }

    @Test
    void pendingSaysWhenTeamCityCouldNotTell() {
        when(tc.prBuildRevision("t", 13335, BUILD)).thenReturn(Optional.empty());

        assertThat(controller.pending(13335, BUILD, "t")).containsEntry("pending", false)
            .containsEntry("checked", false);
    }

    private JsonNode analyze() {
        when(analyzer.analyze("t", 13335)).thenReturn(Optional.of(new AnalysisResult(13335, BUILD,
            "pull/13335/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140,
            1, false, 0, false, 0, STARTED - 600, STARTED, STARTED + 3600, 0, List.of(), List.of(), List.of(), 0,
            BUILT, 0)));

        return mapper.valueToTree(controller.analyze(13335, "t").getBody());
    }

    /** The open-PR list as the checker last fetched it, at {@code fetchedAt} epoch seconds. */
    private void listed(Path dir, String head, long fetchedAt) throws IOException {
        Path file = dir.resolve("github.json");
        Files.writeString(file, "{\"prs\":[{\"number\":13335,\"title\":\"IGNITE-28867 Reload SSL certificates\","
            + "\"url\":\"https://github.com/apache/ignite/pull/13335\",\"headSha\":\"" + head + "\"}],"
            + "\"cacheTs\":" + fetchedAt * 1000 + "}");
        github.loadFrom(file);
    }

    private void expectHead(String sha) {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13335")).andRespond(withSuccess(
            "{\"user\":{\"login\":\"alice\"},\"head\":{\"ref\":\"ignite-28867\",\"sha\":\"" + sha + "\","
                + "\"repo\":{\"full_name\":\"alice/ignite\"}}}", MediaType.APPLICATION_JSON));
    }
}
