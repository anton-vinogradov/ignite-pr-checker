package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branch watermark against a TeamCity that answers like ci2: builds come newest-START first, and
 * the locator can ask for builds by finish date. On PR 13335 (RunAll 9389046) the old watermark —
 * the first id of that list — let a verdict outlive the suite re-run that should have replaced it.
 */
class BranchWatermarkTest {
    private static final String TOK = "tok";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private final FakeTeamCity teamcity = FakeTeamCity.start();

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, new ObjectMapper());

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(
        new TcClient(new TeamcityProperties(teamcity.url()), cfg, new Metrics(new ObjectMapper())), chains, cfg,
        Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2),
        cache, new RunDeltaStore(new ObjectMapper()));

    /** Epoch seconds of the test's start, which stands for {@link #wallClock} on the issue's timeline. */
    private final long t0 = nowSec();

    /** The issue's UTC wall-clock time the test starts at; each test sets it before placing builds. */
    private String wallClock = "23:30:00";

    BranchWatermarkTest() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(RUN_ALL));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(RUN_ALL), any())).thenAnswer(inv -> chain());
    }

    @AfterEach
    void stop() {
        teamcity.stop();
    }

    /**
     * 9389217 started at 23:19:48, before 9389215 (23:21:09), but finished at 23:36:11, after it
     * (23:26:41). A verdict computed in between must be replaced once 9389217 is in, although the
     * first build of TeamCity's list is still 9389215.
     */
    @Test
    void aSuiteThatStartedEarlierButFinishedLaterStillMovesTheBranch() {
        wallClock = "23:30:00";
        teamcity.build(RUN_ALL, at("19:00:00"), at("23:00:32")); // 150th of 152 in TeamCity's list
        teamcity.build(9389217L, at("23:19:48"), null);
        teamcity.build(9389215L, at("23:21:09"), at("23:26:41"));

        assertThat(analyzer.warm(TOK, PR)).as("the first warm computes").isTrue();
        assertThat(analyzer.warm(TOK, PR)).as("nothing finished on the branch since: the verdict stands").isFalse();

        teamcity.finish(9389217L, nowSec()); // 23:36:11

        assertThat(analyzer.warm(TOK, PR))
            .as("9389217 finished after the verdict was computed; starting before 9389215 must not hide it")
            .isTrue();
    }

    /** Queries 5 attempt 2/2 (9389219) finishes while the analysis runs: the verdict cannot have seen it. */
    @Test
    void aBuildFinishingDuringTheAnalysisIsNotClaimedByIt() {
        wallClock = "23:45:00";
        teamcity.build(RUN_ALL, at("19:00:00"), at("23:00:32"));
        teamcity.build(9389217L, at("23:19:48"), at("23:36:11"));
        teamcity.build(9389215L, at("23:21:09"), at("23:26:41"));
        teamcity.build(9389219L, at("23:40:00"), null);
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(RUN_ALL), any())).thenAnswer(inv -> {
            teamcity.finish(9389219L, nowSec()); // the branch is already read, the verdict not yet stored
            return chain();
        });

        assertThat(analyzer.warm(TOK, PR)).as("the first warm computes").isTrue();
        assertThat(analyzer.warm(TOK, PR))
            .as("9389219 finished after the analysis read the branch, so the verdict must not claim it")
            .isTrue();
    }

    /** A snapshot from an older release carries a build-id watermark: it loads, recomputes once, then settles. */
    @Test
    void aVerdictFromAnOldSnapshotLoadsAndRecomputesOnce(@TempDir Path dir) throws Exception {
        wallClock = "23:30:00";
        teamcity.build(RUN_ALL, at("19:00:00"), at("23:00:32"));
        teamcity.build(9389215L, at("23:21:09"), at("23:26:41"));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.valueToTree(new AnalysisResult(PR, RUN_ALL, "pull/13335/head",
            System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140, 12, false, 0,
            false, 0, at("18:55:00"), at("19:00:00"), at("23:00:32"), 0));
        result.remove("branchWatermarkAt");
        result.put("branchWatermark", 9389215L);
        Path file = dir.resolve("analysis.json");
        mapper.writeValue(file.toFile(), Map.of(
            "history", List.of(),
            "results", List.of(Map.of("key", RUN_ALL, "value", result, "expiresAt", Long.MAX_VALUE)),
            "rules", TestVerdict.RULES));

        cache.loadFrom(file);

        assertThat(cache.peekResult(RUN_ALL)).as("the old snapshot still loads").isPresent();
        assertThat(analyzer.warm(TOK, PR)).as("a build id says nothing about finish times: recompute once").isTrue();
        assertThat(analyzer.warm(TOK, PR)).as("then it settles").isFalse();
    }

    private ChainCollector.Chain chain() {
        return new ChainCollector.Chain(RUN_ALL, "pull/13335/head", List.of(), List.of(), List.of(), 140, 12, false,
            0, false, 0, at("18:55:00"), at("19:00:00"), at("23:00:32"));
    }

    /** Epoch seconds of a UTC wall-clock time of the issue's evening, placed relative to {@link #wallClock}. */
    private long at(String hhmmss) {
        return t0 + LocalTime.parse(hhmmss).toSecondOfDay() - LocalTime.parse(wallClock).toSecondOfDay();
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * Just enough of TeamCity's {@code /app/rest/builds} for one PR branch: {@code state:finished},
     * {@code finishDate:(date:...,condition:after)} and {@code count}, listed newest-start first as
     * ci2 does. An unparsable date gets a 400, like the real server.
     */
    private static final class FakeTeamCity {
        private static final DateTimeFormatter TC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ");

        private static final Pattern FINISHED_AFTER = Pattern.compile("finishDate:\\(date:([^,)]*),condition:after\\)");

        private static final Pattern COUNT = Pattern.compile("(?:^|,)count:(\\d+)");

        private final List<Build> builds = new CopyOnWriteArrayList<>();

        private final HttpServer server;

        private FakeTeamCity(HttpServer server) {
            this.server = server;
        }

        static FakeTeamCity start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeTeamCity tc = new FakeTeamCity(server);
                server.createContext("/app/rest/builds", tc::handle);
                server.start();

                return tc;
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        void stop() {
            server.stop(0);
        }

        void build(long id, long startSec, Long finishSec) {
            builds.add(new Build(id, startSec, finishSec));
        }

        void finish(long id, long finishSec) {
            builds.replaceAll(b -> b.id() == id ? new Build(id, b.startSec(), finishSec) : b);
        }

        private void handle(HttpExchange ex) throws IOException {
            String locator = query(ex.getRequestURI().getRawQuery()).getOrDefault("locator", "");
            long after;
            try {
                after = finishedAfter(locator);
            }
            catch (DateTimeParseException e) {
                respond(ex, 400, "Error parsing date in locator: " + locator);

                return;
            }

            boolean finishedOnly = locator.contains("state:finished");
            Matcher count = COUNT.matcher(locator);
            String ids = builds.stream()
                .filter(b -> !finishedOnly || b.finishSec() != null)
                .filter(b -> b.finishSec() == null ? after == Long.MIN_VALUE : b.finishSec() > after)
                .sorted(Comparator.comparingLong(Build::startSec).reversed())
                .limit(count.find() ? Long.parseLong(count.group(1)) : Long.MAX_VALUE)
                .map(b -> "{\"id\":" + b.id() + "}")
                .collect(Collectors.joining(","));

            respond(ex, 200, "{\"build\":[" + ids + "]}");
        }

        private static long finishedAfter(String locator) {
            Matcher m = FINISHED_AFTER.matcher(locator);

            return m.find() ? OffsetDateTime.parse(m.group(1), TC_DATE).toEpochSecond() : Long.MIN_VALUE;
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> out = new HashMap<>();
            for (String kv : raw == null ? new String[0] : raw.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0)
                    out.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }

            return out;
        }

        private static void respond(HttpExchange ex, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }

        private record Build(long id, long startSec, Long finishSec) {
        }
    }
}
