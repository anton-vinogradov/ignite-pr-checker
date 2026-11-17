package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import tools.jackson.databind.ObjectMapper;

/** The authenticated comparison reads raw TeamCity metadata without changing a PR verdict or scheduling builds. */
class MasterSuiteControllerTest {
    private static final String SUITE = "IgniteTests24Java8_ThinClientNodeJs";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "master-suite-test"), mapper);

    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private MockMvc mvc;

    private volatile String answer = "{\"build\":[]}";

    private volatile int answerStatus = 200;

    /** Serves TeamCity JSON over loopback and installs the application's real cookie and path guards. */
    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/builds", ex -> {
            try {
                requests.add(Map.of("locator", param(ex.getRequestURI().getRawQuery(), "locator"),
                    "fields", param(ex.getRequestURI().getRawQuery(), "fields"),
                    "authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))));
                byte[] body = answer.getBytes(UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(answerStatus, body.length);
                ex.getResponseBody().write(body);
            }
            finally {
                ex.close();
            }
        });
        server.start();
        TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, "RunAll", null, null, null, null, null), new Metrics(mapper));
        Warmer warmer = mock(Warmer.class);
        UserDirectory users = new UserDirectory(mapper);
        AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, new Logins(mapper, users),
            mock(StandingVisas.class));
        Registry registry = new Registry();
        new WebConfig(auth, new LiveTokenGuard(tc, warmer)).addInterceptors(registry);
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(new MasterSuiteController(tc))
            .setControllerAdvice(new ApiExceptionHandler(tc, warmer));
        for (Object entry : registry.interceptors()) {
            MappedInterceptor mapped = (MappedInterceptor)entry;
            builder.addMappedInterceptors(mapped.getIncludePathPatterns(), mapped.getInterceptor());
        }
        mvc = builder.build();
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    /** Given failed master metadata, opening the comparison returns its dated build and distinct problem types. */
    @Test
    void aFailedMasterBuildHasItsTimeAndReportedProblemTypes() throws Exception {
        answer = build("FAILURE", "{\"type\":\"TC_EXIT_CODE\"},{\"type\":\"TC_EXECUTION_TIMEOUT\"},"
            + "{\"type\":\"TC_EXIT_CODE\"}");

        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.buildId").value(9504154))
            .andExpect(jsonPath("$.finishedAt").value(java.time.Instant.parse("2026-11-17T12:00:00Z").getEpochSecond()))
            .andExpect(jsonPath("$.status").value("FAILURE"))
            .andExpect(jsonPath("$.problemTypes[0]").value("TC_EXECUTION_TIMEOUT"))
            .andExpect(jsonPath("$.problemTypes[1]").value("TC_EXIT_CODE"));

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.get("locator")).isEqualTo("buildType:(id:" + SUITE + "),branch:(default:true),"
                + "state:finished,canceled:false,failedToStart:any,count:1");
            assertThat(request.get("fields")).isEqualTo("build(id,status,finishDate,problemOccurrences(problemOccurrence(type)))");
            assertThat(request.get("authorization")).isEqualTo("Bearer tc-read");
        });
    }

    /** Given a passing master run, the comparison identifies that run without inventing build problems. */
    @Test
    void aPassingBuildStaysDistinctFromNoHistory() throws Exception {
        answer = build("SUCCESS", "");

        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.buildId").value(9504154))
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.problemTypes").isEmpty());
    }

    /** Given no finished default-branch run, the comparison says no history rather than success or failure. */
    @Test
    void noHistoryHasNoBuildOrStatus() throws Exception {
        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.buildId").value(0))
            .andExpect(jsonPath("$.finishedAt").value(0)).andExpect(jsonPath("$.status").isEmpty())
            .andExpect(jsonPath("$.problemTypes").isEmpty());
    }

    /** Given some unnamed problems, the response keeps an unknown marker so known types cannot imply a full match. */
    @Test
    void unknownProblemTypesAreNotSilentlyDropped() throws Exception {
        answer = build("FAILURE", "{\"type\":\"TC_EXIT_CODE\"},{},{\"type\":null},{\"type\":\" \"},null");

        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.problemTypes[0]").value(""))
            .andExpect(jsonPath("$.problemTypes[1]").value("TC_EXIT_CODE"));
    }

    /** Given TeamCity unavailable, the normal API handler reports an error instead of a successful empty baseline. */
    @Test
    void aTeamCityErrorCannotLookLikeNoHistory() throws Exception {
        answerStatus = 503;
        answer = "{}";

        read(SUITE).andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.buildId").doesNotExist());
    }

    /** Given a new master build after one comparison, the next explicit read asks TeamCity again. */
    @Test
    void anExplicitReadUsesFreshMetadata() throws Exception {
        answer = build("FAILURE", "{\"type\":\"TC_EXIT_CODE\"}");
        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILURE"));
        answer = build("SUCCESS", "").replace("9504154", "9504155");

        read(SUITE).andExpect(status().isOk()).andExpect(jsonPath("$.buildId").value(9504155))
            .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(requests).hasSize(2);
    }

    /** Given a malformed suite ID, the request is rejected before it can enter a TeamCity locator. */
    @ParameterizedTest
    @ValueSource(strings = {"", "Suite),branch:(default:false)", "../Suite", "Suite,count:5000", " "})
    void invalidSuiteIdsNeverReachTeamCity(String suite) throws Exception {
        read(suite).andExpect(status().isBadRequest());

        assertThat(requests).isEmpty();
    }

    /** Given no session, the registered application guard refuses the comparison before any TeamCity request. */
    @Test
    void theComparisonRequiresLogin() throws Exception {
        mvc.perform(get("/api/master-suite").param("suite", SUITE)).andExpect(status().isUnauthorized());

        assertThat(requests).isEmpty();
    }

    private ResultActions read(String suite) throws Exception {
        Cookie cookie = new Cookie(AuthInterceptor.COOKIE, codec.encode(new SessionCodec.Session("alice", "tc-read")));
        return mvc.perform(get("/api/master-suite").param("suite", suite).cookie(cookie));
    }

    /** A raw finished build in TeamCity's build-list envelope; problem JSON is exactly what that server reports. */
    private static String build(String status, String problems) {
        return "{\"build\":[{\"id\":9504154,\"buildTypeId\":\"" + SUITE + "\",\"state\":\"finished\",\"status\":\""
            + status + "\",\"finishDate\":\"20261117T120000+0000\",\"problemOccurrences\":{\"problemOccurrence\":["
            + problems + "]}}]}";
    }

    private static String param(String query, String name) {
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair[1], UTF_8);
        }
        return "";
    }

    /** Exposes only the interceptors WebConfig registers, so the test uses production path matching. */
    private static final class Registry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
