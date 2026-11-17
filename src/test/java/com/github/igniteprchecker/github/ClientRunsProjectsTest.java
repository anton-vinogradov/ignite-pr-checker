package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ClientRunsProjectsTest {
    private static final String IGNITE = "d".repeat(40);
    private final ThinClients projects = ThinClients.defaults();
    private final GithubClient github = mock(GithubClient.class);
    private final ObjectMapper mapper = Jackson2Defaults.mapper();
    private final List<JsonNode> queued = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong(1000);
    private final AtomicLong instanceOffset = new AtomicLong();
    private HttpServer server;
    private ClientRuns runs;

    /** Supplies a real TeamCity client backed by a local HTTP endpoint and controlled GitHub commit replies. */
    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        TeamcityProperties tcConfig = new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/");
        AnalysisProperties analysis = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        runs = new ClientRuns(github, new TcClient(tcConfig, analysis, new Metrics(mapper)),
            mock(BlockerAnalyzer.class), analysis, tcConfig);
        when(github.repo()).thenReturn("apache/ignite");
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(IGNITE);
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    /**
     * Given new current root instances and an older build's instance IDs, when a selected client run is queued,
     * then both exact revisions target stable root IDs rather than the older build's instances.
     */
    @ParameterizedTest
    @ValueSource(strings = {"python", "php", "nodejs", "extensions"})
    void queuesSelectedProjectWithBothPins(String key) {
        ClientSpec spec = projects.require(key);
        String head = head(spec);
        instanceOffset.set(1000);
        when(github.prHeadSha(spec.repo(), 69)).thenReturn(head);

        runs.queue("token", spec, 69, false, "Test launch");

        assertThat(queued).singleElement().satisfies(body -> {
            assertThat(body.path("buildType").path("id").asText()).isEqualTo(spec.suite());
            assertThat(body.path("branchName").asText()).isEqualTo(spec.branch(69));
            JsonNode revisions = body.path("revisions").path("revision");
            assertThat(revisions.size()).isEqualTo(2);
            assertThat(revisions.get(0).path("version").asText()).isEqualTo(IGNITE);
            assertThat(revisions.get(0).path("vcs-root-instance").path("vcs-root-id").asText())
                .isEqualTo(spec.igniteVcsRoot());
            assertThat(revisions.get(1).path("version").asText()).isEqualTo(head);
            assertThat(revisions.get(1).path("vcsBranchName").asText()).isEqualTo("refs/pull/69/head");
            assertThat(revisions.get(1).path("vcs-root-instance").path("vcs-root-id").asText())
                .isEqualTo(spec.clientVcsRoot());
            if (spec.composite())
                assertThat(body.path("properties").path("property")).isEmpty();
            else
                assertThat(body.path("properties").path("property").get(0).path("value").asText()).isEqualTo(head);
        });
    }

    /**
     * Given a symbolic ref returned instead of either commit, when a client launch is prepared,
     * then it fails before any request can queue code that may move later.
     */
    @ParameterizedTest
    @ValueSource(strings = {"client", "ignite"})
    void rejectsNonShaRevisionBeforeQueue(String repository) {
        ClientSpec spec = projects.require("python");
        when(github.prHeadSha(spec.repo(), 69)).thenReturn(repository.equals("client") ? "master" : head(spec));
        if (repository.equals("ignite"))
            when(github.branchHeadSha("apache/ignite", "master")).thenReturn("master");

        assertThatThrownBy(() -> runs.queue("token", spec, 69, false, "Test launch"))
            .isInstanceOf(ClientRuns.NotQueued.class).hasMessageContaining("GitHub returned no head commit");

        assertThat(queued).isEmpty();
    }

    /**
     * Given a legacy snapshot and changed TeamCity instance IDs, when its run is restored and rerun,
     * then stable root selectors are saved and submitted with the original commits.
     */
    @ParameterizedTest
    @ValueSource(strings = {"python", "php", "nodejs"})
    void restoresLegacyPinsAfterInstanceChanges(String key) {
        ClientSpec spec = projects.require(key);
        String head = head(spec);
        String snapshot = """
            {"suite":"%s","branch":"%s","revisions":[
              {"vcsRootInstance":"335","vcsBranch":"refs/heads/master","version":"%s"},
              {"vcsRootInstance":"%s","vcsBranch":"refs/pull/69/head","version":"%s"}],
             "properties":{"OVERRIDDEN_BRANCH":"%s"}}
            """.formatted(spec.suite(), spec.branch(69), IGNITE, instance(spec), head, head);
        PinnedRun restored = mapper.readValue(snapshot, PinnedRun.class);
        instanceOffset.set(1000);

        ClientRuns.Queued rerun = runs.rerun("token", spec, 69, 900, restored);
        PinnedRun savedAgain = mapper.readValue(mapper.writeValueAsBytes(rerun.pinned()), PinnedRun.class);

        assertThat(savedAgain.suite()).isEqualTo(restored.suite());
        assertThat(savedAgain.branch()).isEqualTo(restored.branch());
        assertThat(savedAgain.properties()).isEqualTo(restored.properties());
        assertThat(savedAgain.revisions()).extracting(PinnedRun.Pin::version).containsExactly(IGNITE, head);
        assertThat(savedAgain.revisions()).extracting(PinnedRun.Pin::vcsBranch)
            .containsExactly("refs/heads/master", "refs/pull/69/head");
        assertThat(savedAgain.revisions()).extracting(PinnedRun.Pin::vcsRootId)
            .containsExactly(spec.igniteVcsRoot(), spec.clientVcsRoot());
        assertThat(savedAgain.revisions()).allSatisfy(pin -> assertThat(pin.vcsRootInstance()).isNull());
        JsonNode revisions = queued.get(0).path("revisions").path("revision");
        assertThat(revisions.get(0).path("vcs-root-instance").path("vcs-root-id").asText())
            .isEqualTo(spec.igniteVcsRoot());
        assertThat(revisions.get(1).path("vcs-root-instance").path("vcs-root-id").asText())
            .isEqualTo(spec.clientVcsRoot());
        verify(github, never()).prHeadSha(spec.repo(), 69);
        verify(github, never()).branchHeadSha("apache/ignite", "master");
    }

    /**
     * Given an earlier pinned run and moved GitHub heads, when it is rerun,
     * then the TeamCity request repeats its original revisions and checkout property at the top of the queue.
     */
    @ParameterizedTest
    @ValueSource(strings = {"python", "php", "nodejs", "extensions"})
    void rerunsOriginalCommitsAfterHeadsMove(String key) {
        ClientSpec spec = projects.require(key);
        String head = head(spec);
        when(github.prHeadSha(spec.repo(), 69)).thenReturn(head);
        ClientRuns.Queued first = runs.queue("token", spec, 69, false, "Test launch");
        when(github.prHeadSha(spec.repo(), 69)).thenReturn("e".repeat(40));
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn("f".repeat(40));

        runs.rerun("token", spec, 69, first.build().id(), first.pinned());

        assertThat(queued).hasSize(2);
        assertThat(queued.get(0).path("triggeringOptions").path("queueAtTop").asBoolean()).isFalse();
        assertThat(queued.get(1).path("triggeringOptions").path("queueAtTop").asBoolean()).isTrue();
        assertThat(queued.get(1).path("revisions")).isEqualTo(queued.get(0).path("revisions"));
        assertThat(queued.get(1).path("properties")).isEqualTo(queued.get(0).path("properties"));
        assertThat(queued.get(1).path("branchName")).isEqualTo(queued.get(0).path("branchName"));
        verify(github, times(1)).prHeadSha(spec.repo(), 69);
        verify(github, times(1)).branchHeadSha("apache/ignite", "master");
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body;
        int status = 200;
        if (exchange.getRequestMethod().equals("POST")) {
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String suite = request.path("buildType").path("id").asText();
            ClientSpec spec = projects.all().stream().filter(c -> c.suite().equals(suite)).findFirst().orElseThrow();
            if (!matchesCurrentRoots(request, spec)) {
                status = 400;
                body = "{\"error\":\"Revision root does not match a current build configuration instance\"}";
            }
            else {
                queued.add(request);
                body = mapper.writeValueAsString(Map.of("id", ids.incrementAndGet(), "state", "queued",
                    "branchName", request.path("branchName").asText(), "buildTypeId", suite,
                    "buildType", Map.of("id", suite, "name", spec.suiteLabel())));
            }
        }
        else {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (query.contains("state:queued"))
                body = "{\"build\":[]}";
            else {
                ClientSpec spec = projects.all().stream().filter(c -> query.contains("id:" + c.suite())
                        || c.composite() && query.contains("id:" + c.igniteSuite()))
                    .findFirst().orElseThrow();
                Map<String, Object> ignite = Map.of("vcs-root-instance",
                    Map.of("id", "335", "vcs-root-id", spec.igniteVcsRoot()));
                Map<String, Object> project = Map.of("vcs-root-instance",
                    Map.of("id", instance(spec), "vcs-root-id", spec.clientVcsRoot()));
                List<Map<String, Object>> roots = !spec.composite() ? List.of(ignite, project)
                    : query.contains("id:" + spec.igniteSuite()) ? List.of(ignite) : List.of(project);
                body = mapper.writeValueAsString(Map.of("build", List.of(Map.of("revisions",
                    Map.of("revision", roots)))));
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Accepts root IDs or current instance IDs, matching TeamCity's revision selector contract. */
    private boolean matchesCurrentRoots(JsonNode request, ClientSpec spec) {
        for (JsonNode pin : request.path("revisions").path("revision")) {
            JsonNode root = pin.path("vcs-root-instance");
            if (root.has("vcs-root-id")) {
                if (!List.of(spec.igniteVcsRoot(), spec.clientVcsRoot()).contains(root.path("vcs-root-id").asText()))
                    return false;
            }
            else if (!List.of(String.valueOf(335 + instanceOffset.get()),
                String.valueOf(Long.parseLong(instance(spec)) + instanceOffset.get())).contains(root.path("id").asText()))
                return false;
        }
        return true;
    }

    private String instance(ClientSpec spec) {
        return String.valueOf(314 + projects.all().indexOf(spec));
    }

    private String head(ClientSpec spec) {
        return spec.composite() ? "9".repeat(40)
            : String.valueOf((char) ('a' + projects.all().indexOf(spec))).repeat(40);
    }
}
