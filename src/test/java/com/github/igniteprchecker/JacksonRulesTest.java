package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot 4 brings Jackson 3. Up to v1.41.0 Jackson 2 read and wrote the state files, the session cookie and the
 * API, and Jackson 3's own defaults differ from its. Releases add fields to the records of the state files. Under
 * Jackson 3's defaults, a file written before a number field was added fails to load, and so does a null for a number.
 * The service then sets the file aside with everyone's options in it. An enum goes out by its toString, which an older
 * build cannot read after a rollback, and text after the JSON fails a file Jackson 2 read. The service's mapper and the
 * clients of TeamCity, JIRA and GitHub keep Jackson 2's rules and limits.
 */
class JacksonRulesTest {
    private static final ObjectMapper MAPPER = ServiceMapper.get();

    /** A record of the state, with a number and an enum whose toString is not its name. */
    record Counted(long count, Kind kind) {
    }

    enum Kind {
        FIRST,
        SECOND {
            @Override
            public String toString() {
                return "second";
            }
        }
    }

    @Test
    void aFileWithoutANumberFieldAddedSinceReadsItAsZero() {
        assertThat(MAPPER.readValue("{\"kind\":\"FIRST\"}", Counted.class)).isEqualTo(new Counted(0, Kind.FIRST));
    }

    @Test
    void aNullNumberReadsAsZero() {
        assertThat(MAPPER.readValue("{\"count\":null,\"kind\":\"FIRST\"}", Counted.class))
            .isEqualTo(new Counted(0, Kind.FIRST));
    }

    @Test
    void anEnumGoesByItsName() {
        String json = MAPPER.writeValueAsString(new Counted(1, Kind.SECOND));

        assertThat(json).isEqualTo("{\"count\":1,\"kind\":\"SECOND\"}");
        assertThat(MAPPER.readValue(json, Counted.class)).isEqualTo(new Counted(1, Kind.SECOND));
    }

    @Test
    void textAfterTheJsonIsLeftUnread() {
        assertThat(MAPPER.readValue("{\"count\":1,\"kind\":\"FIRST\"} left", Counted.class))
            .isEqualTo(new Counted(1, Kind.FIRST));
    }

    @Test
    void fieldsTheRecordLacksAreIgnored() {
        assertThat(MAPPER.readValue("{\"count\":1,\"kind\":\"FIRST\",\"added\":true}", Counted.class))
            .isEqualTo(new Counted(1, Kind.FIRST));
    }

    /** A raw file of GitHub comes as text, the answers as JSON: the clients read both, as before. */
    @Test
    void theClientsKeepTheOtherConvertersOfSpring() {
        HttpMessageConverters.ClientBuilder ours = HttpMessageConverters.forClient();
        OutboundHttp.json(ours);

        assertThat(classes(ours.build()))
            .isEqualTo(classes(HttpMessageConverters.forClient().registerDefaults().build()));
    }

    private static List<Class<?>> classes(HttpMessageConverters converters) {
        List<Class<?>> out = new ArrayList<>();
        converters.forEach(c -> out.add(c.getClass()));

        return out;
    }

    @Test
    void anAnswerOfTeamCityReadsByTheSameRules() throws IOException {
        HttpServer teamcity = serve("{\"id\":null,\"state\":\"finished\",\"added\":true}");
        try {
            TcClient tc = new TcClient(new TeamcityProperties(base(teamcity) + "/"),
                new AnalysisProperties(null, null, null, null, null, null, null), new Metrics(null));

            assertThat(tc.buildEnd("tok", 5)).get().extracting(TcModel.Build::id, TcModel.Build::state)
                .containsExactly(0L, "finished");
        }
        finally {
            teamcity.stop(0);
        }
    }

    @Test
    void anAnswerOfGithubReadsByTheSameRules() throws IOException {
        HttpServer github = serve("{\"login\":\"alice\",\"id\":null,\"added\":true}");
        try {
            GithubClient gh = new GithubClient(new GithubProperties("apache/ignite", null, 300, null, base(github)),
                MAPPER, new Metrics(null), "");

            assertThat(gh.ghIdentity("tok")).contains(new GithubClient.GhUser("alice", 0));
        }
        finally {
            github.stop(0);
        }
    }

    /** Jackson 3's own rules fail an answer with text after its JSON. */
    @Test
    void anAnswerOfJiraReadsByTheSameRules() throws IOException {
        HttpServer jira = serve("{\"name\":\"alice\",\"displayName\":\"Alice\"} left");
        try {
            JiraClient client = new JiraClient(base(jira), OutboundHttp.READ_TIMEOUT, new Metrics(null));

            assertThat(client.myself("tok")).contains("Alice");
        }
        finally {
            jira.stop(0);
        }
    }

    /**
     * Jackson 3 reads a string of up to 100 million characters and JSON nested up to 500 deep; Jackson 2.21 read up to
     * 20 million and 1000 deep. Anyone can post a body to /api/login, and one string of 100 million characters takes
     * hundreds of megabytes to read.
     */
    @Test
    void theLimitsOfReadsAndWritesAreJackson2s() {
        HttpMessageConverters.ClientBuilder clients = HttpMessageConverters.forClient();
        OutboundHttp.json(clients);
        JsonMapper outbound = null;
        for (HttpMessageConverter<?> c : clients.build()) {
            if (c instanceof JacksonJsonHttpMessageConverter json)
                outbound = json.getMapper();
        }

        for (ObjectMapper m : List.of(MAPPER, outbound)) {
            TokenStreamFactory f = m.tokenStreamFactory();

            assertThat(f.streamReadConstraints().getMaxStringLength()).isEqualTo(20_000_000);
            assertThat(f.streamReadConstraints().getMaxNestingDepth()).isEqualTo(1000);
            assertThat(f.streamWriteConstraints().getMaxNestingDepth()).isEqualTo(1000);
        }
    }

    private static HttpServer serve(String answer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            byte[] body = answer.getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
