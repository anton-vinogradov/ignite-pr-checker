package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.TeamcityProperties;
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
import org.springframework.http.converter.HttpMessageConverters;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot 4 brings Jackson 3. Up to v1.41.0 Jackson 2 read and wrote the state files, the session cookie and the
 * API, and Jackson 3's own defaults differ from its. Releases add fields to the records of the state files. Under
 * Jackson 3's defaults, a file written before a number field was added fails to load, and so does a null for a number.
 * The service then sets the file aside with everyone's options in it. An enum goes out by its toString, which an older
 * build cannot read after a rollback, and text after the JSON fails a file Jackson 2 read. The service's mapper and the
 * clients of TeamCity, JIRA and GitHub keep Jackson 2's rules.
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
        HttpServer teamcity = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        teamcity.createContext("/", ex -> {
            byte[] body = "{\"id\":null,\"state\":\"finished\",\"added\":true}".getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        teamcity.start();
        try {
            TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + teamcity.getAddress().getPort()
                + "/"), new AnalysisProperties(null, null, null, null, null, null, null), new Metrics(null));

            assertThat(tc.buildEnd("tok", 5)).get().extracting(TcModel.Build::id, TcModel.Build::state)
                .containsExactly(0L, "finished");
        }
        finally {
            teamcity.stop(0);
        }
    }
}
