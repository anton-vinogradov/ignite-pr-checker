package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ci2 queued build 9424005 of Thin client: Python for PR #69 of apache/ignite-python-thin-client from a request that
 * names the branch {@code pull/69}, Ignite's commit on VCS root instance 335, the client's on 314, and
 * {@code OVERRIDDEN_BRANCH}. The instance ids differ between TeamCity servers: they come from the suite's newest build,
 * else from the suite's own VCS roots.
 */
class PinnedRunQueryTest {
    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    /** "METHOD /path?query" of every call TeamCity received, in order. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private volatile int buildsStatus = 200;

    /** Whether TeamCity queues a build, then refuses to put it at the top, as ci2 does for an account that may not. */
    private volatile boolean refusesTop;

    /** The builds waiting in the queue for pull/69, as TeamCity lists them. */
    private final List<String> queued = new CopyOnWriteArrayList<>();

    private volatile String builds = "{\"build\":[{\"revisions\":{\"revision\":["
        + "{\"version\":\"" + IGNITE + "\",\"vcs-root-instance\":"
        + "{\"id\":\"335\",\"vcs-root-id\":\"GitHubApacheIgnite\"}},"
        + "{\"version\":\"x\",\"vcs-root-instance\":"
        + "{\"id\":\"314\",\"vcs-root-id\":\"GitHubApacheIgnitePythonThinClient\"}}"
        + "]}}]}";

    private HttpServer server;

    private TcClient tc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        tc = new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(mapper));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void aPinnedRunIsQueuedAsCi2AcceptedIt() {
        tc.queuePinned("tok", new PinnedRun(SUITE, "pull/69", List.of(
            new PinnedRun.Pin("335", "refs/heads/master", IGNITE),
            new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)), Map.of("OVERRIDDEN_BRANCH", CLIENT)), false,
            "Ignite PR Checker: /run-all by @Author in https://github.com/apache/ignite-python-thin-client/pull/69");

        assertThat(calls).singleElement().asString().startsWith("POST /app/rest/buildQueue");
        JsonNode sent = mapper.readTree(bodies.get(0));
        assertThat(sent.get("buildType").get("id").asString()).isEqualTo(SUITE);
        assertThat(sent.get("branchName").asString()).isEqualTo("pull/69");
        JsonNode revisions = sent.get("revisions").get("revision");
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).get("version").asString()).isEqualTo(IGNITE);
        assertThat(revisions.get(0).get("vcsBranchName").asString()).isEqualTo("refs/heads/master");
        assertThat(revisions.get(0).get("vcs-root-instance").get("id").asString()).isEqualTo("335");
        assertThat(revisions.get(1).get("version").asString()).isEqualTo(CLIENT);
        assertThat(revisions.get(1).get("vcsBranchName").asString()).isEqualTo("refs/pull/69/head");
        assertThat(revisions.get(1).get("vcs-root-instance").get("id").asString()).isEqualTo("314");
        JsonNode property = sent.get("properties").get("property").get(0);
        assertThat(property.get("name").asString()).isEqualTo("OVERRIDDEN_BRANCH");
        assertThat(property.get("value").asString()).isEqualTo(CLIENT);
        assertThat(sent.get("comment").get("text").asString()).contains("apache/ignite-python-thin-client/pull/69");
        assertThat(sent.get("triggeringOptions").get("queueAtTop").asBoolean()).isFalse();
    }

    @Test
    void aRunAllTopAsksForTheTopOfTheQueue() {
        tc.queuePinned("tok", pinned(), true, "Ignite PR Checker: /run-all by @Author");

        JsonNode sent = mapper.readTree(bodies.get(bodies.size() - 1));
        assertThat(sent.get("triggeringOptions").get("queueAtTop").asBoolean()).isTrue();
    }

    /**
     * alice's ci2 account may not reorder the queue: TeamCity queues her run, then answers 403 to putting it at the
     * top. The run is hers and waits in the queue, so it comes back as queued below the top, not as a failure.
     */
    @Test
    void aRunTeamCityQueuedButNotAtTheTopIsFound() {
        refusesTop = true;

        assertThatThrownBy(() -> tc.queuePinned("tok", pinned(), true, "Ignite PR Checker: /run-all by @alice"))
            .isInstanceOfSatisfying(QueuedBelowTopException.class, e -> {
                assertThat(e.build().id()).isEqualTo(9424005L);
                assertThat(e.status()).isEqualTo(403);
            });
        assertThat(calls).filteredOn(c -> c.startsWith("POST")).hasSize(1);
    }

    /**
     * What each finished run on pull/69 checked the client out at: one request for the suite's builds there, with
     * OVERRIDDEN_BRANCH by name. A build TeamCity names no value of has none.
     */
    @Test
    void theRunsOnTheClientsBranchComeWithWhatTheyCheckedOut() {
        Map<Long, String> checkouts = tc.finishedRunsParameter("tok", SUITE, "pull/69", "OVERRIDDEN_BRANCH");

        assertThat(checkouts).containsExactly(entry(9424005L, CLIENT), entry(9423500L, "master"),
            entry(9423000L, null));
        assertThat(calls).singleElement().asString().isEqualTo("GET /app/rest/builds?locator=buildType:(id:" + SUITE
            + "),branch:(name:pull/69),state:finished,count:100&fields=build(id,resultingProperties($locator(name:"
            + "OVERRIDDEN_BRANCH),property(name,value)))");
    }

    @Test
    void theRootInstancesComeFromTheSuitesNewestBuild() {
        Optional<Map<String, String>> roots = tc.vcsRootInstances("tok", SUITE);

        assertThat(roots).contains(Map.of("GitHubApacheIgnite", "335", "GitHubApacheIgnitePythonThinClient", "314"));
        assertThat(calls).singleElement().asString().startsWith("GET /app/rest/builds?locator=buildType:(id:" + SUITE
            + "),branch:(default:any),count:1");
    }

    @Test
    void aSuiteWithNoBuildYetGivesItsOwnRootInstances() {
        builds = "{\"build\":[]}";

        Optional<Map<String, String>> roots = tc.vcsRootInstances("tok", SUITE);

        assertThat(roots).contains(Map.of("GitHubApacheIgnite", "335", "GitHubApacheIgnitePythonThinClient", "314"));
        assertThat(calls.get(1)).startsWith("GET /app/rest/vcs-root-instances?locator=buildType:(id:" + SUITE + ")");
    }

    @Test
    void aSuiteTeamCityDoesNotHaveGivesNothing() {
        buildsStatus = 404;

        assertThat(tc.vcsRootInstances("tok", SUITE)).isEmpty();
    }

    /** A test's runs on the client's branch are not those of Ignite's PR #69, which runs on pull/69/head. */
    @Test
    void theRunsOfATestAreReadOnTheClientsBranch() {
        tc.branchRuns("tok", "pull/69", 502L, SUITE);

        assertThat(calls).singleElement().asString().contains("branch:(name:pull/69),buildType:(id:" + SUITE + ")");
    }

    private static boolean asked(String rawQuery, String part) {
        return rawQuery != null && URLDecoder.decode(rawQuery, UTF_8).contains(part);
    }

    private static PinnedRun pinned() {
        return new PinnedRun(SUITE, "pull/69", List.of(new PinnedRun.Pin("335", "refs/heads/master", IGNITE),
            new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)), Map.of("OVERRIDDEN_BRANCH", CLIENT));
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        calls.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + URLDecoder.decode(query, UTF_8)));
        bodies.add(new String(ex.getRequestBody().readAllBytes(), UTF_8));
        int code = 200;
        String body = "{}";

        if ("POST".equals(ex.getRequestMethod()) && path.equals("/app/rest/buildQueue")) {
            body = "{\"id\":9424005,\"state\":\"queued\",\"buildTypeId\":\"" + SUITE + "\"}";
            queued.add("{\"id\":9424005,\"state\":\"queued\",\"buildTypeId\":\"" + SUITE + "\","
                + "\"triggered\":{\"type\":\"user\",\"user\":{\"username\":\"alice\"}}}");
            if (refusesTop && bodies.get(bodies.size() - 1).contains("\"queueAtTop\":true")) {
                code = 403;
                body = "Access denied. Check the user has enough permissions to perform the operation.";
            }
        }
        else if (path.equals("/app/rest/builds") && asked(query, "state:queued"))
            body = "{\"build\":[" + String.join(",", queued) + "]}";
        else if (path.equals("/app/rest/builds") && asked(query, "state:finished"))
            body = "{\"build\":[{\"id\":9424005,\"resultingProperties\":{\"property\":[{\"name\":"
                + "\"OVERRIDDEN_BRANCH\",\"value\":\"" + CLIENT + "\"}]}},{\"id\":9423500,\"resultingProperties\":"
                + "{\"property\":[{\"name\":\"OVERRIDDEN_BRANCH\",\"value\":\"master\"}]}},{\"id\":9423000}]}";
        else if (path.equals("/app/rest/users/current"))
            body = "{\"username\":\"alice\"}";
        else if (path.equals("/app/rest/builds")) {
            code = buildsStatus;
            body = code == 200 ? builds : "{}";
        }
        else if (path.equals("/app/rest/vcs-root-instances"))
            body = "{\"vcs-root-instance\":[{\"id\":\"335\",\"vcs-root-id\":\"GitHubApacheIgnite\"},"
                + "{\"id\":\"314\",\"vcs-root-id\":\"GitHubApacheIgnitePythonThinClient\"}]}";
        else if (path.equals("/app/rest/testOccurrences"))
            body = "{\"testOccurrence\":[]}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", code == 403 ? "text/plain" : "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
