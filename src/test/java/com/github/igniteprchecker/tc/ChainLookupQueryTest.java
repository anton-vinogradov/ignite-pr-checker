package com.github.igniteprchecker.tc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which RunAll of a PR to analyse: the newest clean one, else the newest cancelled one, else the one still
 * running. Asked as three lookups in a row, a PR without a finished RunAll cost three calls on every warm
 * cycle, and the warmer checks 50 PRs every 10 minutes. One lookup of the PR's newest chains answers all
 * three, against a TeamCity that filters chains the way ci2 does.
 */
class ChainLookupQueryTest {
    private static final String TOK = "t";

    private static final int PR = 13654;

    private static final Pattern COUNT = Pattern.compile("(?:^|,)count:(\\d+)");

    private final List<String> locators = new CopyOnWriteArrayList<>();

    private final List<String> fields = new CopyOnWriteArrayList<>();

    /** The PR's RunAll chains, newest first. */
    private volatile List<Chain> chains = List.of();

    private volatile boolean rejectsDefaultFilter;

    private HttpServer server;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            String locator = param(ex.getRequestURI().getRawQuery(), "locator");
            locators.add(locator);
            fields.add(param(ex.getRequestURI().getRawQuery(), "fields"));
            boolean reject = rejectsDefaultFilter && locator.contains("defaultFilter");
            byte[] body = (reject ? "Error has occurred during request processing, Status: BAD_REQUEST"
                : answer(locator)).getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", reject ? "text/plain" : "application/json");
            ex.sendResponseHeaders(reject ? 400 : 200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    /** PR 13654's first RunAll, hours before it ends: the warmer found it only with the third call. */
    @Test
    void aPrWhoseOnlyChainIsRunningIsFoundWithOneCall() {
        chains = List.of(running(9391879L));

        assertThat(find()).map(TcModel.Build::id).contains(9391879L);
        assertThat(locators).singleElement().asString().contains("buildType:RunAll",
            "branch:(name:pull/13654/head)", "defaultFilter:false", "personal:false", "count:5");
        assertThat(fields).singleElement().asString().contains("canceledInfo(", "triggered(type,user(username))");
    }

    @Test
    void aPrWithoutAnyChainCostsOneCall() {
        assertThat(find()).isEmpty();
        assertThat(locators).hasSize(1);
    }

    /** A fresh cancel or a fresh start must not shadow a good verdict. */
    @Test
    void theCleanChainWinsOverNewerCancelledAndRunningOnes() {
        chains = List.of(running(9392300L), cancelled(9392200L), clean(9392100L), clean(9391879L));

        assertThat(find()).map(TcModel.Build::id).contains(9392100L);
        assertThat(locators).hasSize(1);
    }

    /** A chain cancelled after most of its suites ran still shows their failures while nothing cleaner exists. */
    @Test
    void theNewestCancelledChainIsShownWhenNoneFinishedClean() {
        chains = List.of(running(9392300L), cancelled(9392200L), cancelled(9392100L));

        assertThat(find()).map(TcModel.Build::id).contains(9392200L);
        assertThat(locators).hasSize(1);
    }

    /** Five cancelled chains in a row hide nothing: the clean one before them is looked up as before. */
    @Test
    void aCleanChainOlderThanTheNewestFiveIsStillFound() {
        chains = List.of(cancelled(9392500L), cancelled(9392400L), cancelled(9392300L), cancelled(9392200L),
            cancelled(9392100L), clean(9391879L));

        assertThat(find()).map(TcModel.Build::id).contains(9391879L);
        assertThat(locators).hasSize(2);
        assertThat(locators.get(1)).contains("state:finished", "canceled:false");
    }

    @Test
    void aRejectedLookupFallsBackToTheThreeLookupsForGood() {
        rejectsDefaultFilter = true;
        chains = List.of(running(9391879L));
        TcClient client = client();

        Optional<TcModel.Build> found = client.findRunAllBuildForAnalysis(TOK, PR);
        client.findRunAllBuildForAnalysis(TOK, PR);

        assertThat(found).map(TcModel.Build::id).contains(9391879L);
        assertThat(locators).hasSize(7);
        assertThat(locators.subList(1, 7)).noneMatch(l -> l.contains("defaultFilter"));
    }

    private Optional<TcModel.Build> find() {
        return client().findRunAllBuildForAnalysis(TOK, PR);
    }

    private TcClient client() {
        return new TcClient(new TeamcityProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(new ObjectMapper()));
    }

    /**
     * ci2's answer for the PR's chains: by default only finished, not cancelled ones; {@code defaultFilter:false}
     * lifts that, {@code state} and {@code canceled} narrow it again.
     */
    private String answer(String locator) {
        Stream<Chain> found = chains.stream();
        if (locator.contains("state:running"))
            found = found.filter(c -> c.state().equals("running"));
        else if (!locator.contains("defaultFilter:false"))
            found = found.filter(c -> c.state().equals("finished")
                && (locator.contains("canceled:any") || !c.cancelled()));
        Matcher count = COUNT.matcher(locator);
        if (count.find())
            found = found.limit(Long.parseLong(count.group(1)));

        return found.map(Chain::json).collect(Collectors.joining(",", "{\"build\":[", "]}"));
    }

    private static Chain running(long id) {
        return new Chain(id, "running", "SUCCESS", false);
    }

    private static Chain clean(long id) {
        return new Chain(id, "finished", "FAILURE", false);
    }

    private static Chain cancelled(long id) {
        return new Chain(id, "finished", "UNKNOWN", true);
    }

    private static String param(String rawQuery, String name) {
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (kv.substring(0, eq).equals(name))
                return URLDecoder.decode(kv.substring(eq + 1), UTF_8);
        }

        return null;
    }

    private record Chain(long id, String state, String status, boolean cancelled) {
        String json() {
            List<String> parts = new ArrayList<>(List.of("\"id\":" + id, "\"state\":\"" + state + "\"",
                "\"status\":\"" + status + "\"", "\"branchName\":\"pull/" + PR + "/head\""));
            if (cancelled)
                parts.add("\"canceledInfo\":{\"text\":\"Canceled\",\"user\":{\"username\":\"someone\"}}");

            return parts.stream().collect(Collectors.joining(",", "{", "}"));
        }
    }
}
