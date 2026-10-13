package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Claude picks the suite of calcite's IgniteFunctionParameterTest from the module's suites. The request asks for the
 * answer in a fixed form at medium effort, sends the suites as a cached prefix, and has a refused request run again
 * on the model Anthropic recommends; a refusal or an error is no pick, said in words. A stub stands in for
 * Anthropic's API.
 */
class ClaudeSuiteChooserTest {
    private static final String UTIL = "modules/calcite/src/test/java/org/apache/ignite/testsuites/UtilTestSuite.java";

    private static final String CLASS = "modules/calcite/src/test/java/org/apache/ignite/internal/processors/query/"
        + "calcite/exec/exp/IgniteFunctionParameterTest.java";

    private static final String SOURCE = """
        /*
         * Licensed to the Apache Software Foundation (ASF) under one or more
         */

        package org.apache.ignite.internal.processors.query.calcite.exec.exp;

        /** Checks the parameters of SQL functions. */
        public class IgniteFunctionParameterTest {
        }
        """;

    private static final List<TestSuites.Suite> SUITES = List.of(
        new TestSuites.Suite(UTIL, "org.apache.ignite.testsuites", List.of(
            new TestSuites.Member("QueryCheckerTest", "org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest"),
            new TestSuites.Member("IgniteSqlFunctionsTest",
                "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest"))),
        new TestSuites.Suite("modules/calcite/src/test/java/org/apache/ignite/testsuites/ExecutionTestSuite.java",
            "org.apache.ignite.testsuites", List.of(new TestSuites.Member("ExecutionTest",
                "org.apache.ignite.internal.processors.query.calcite.exec.rel.ExecutionTest"))));

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> bodies = new ArrayList<>();

    private final List<String> betas = new ArrayList<>();

    private HttpServer server;

    private ClaudeSuiteChooser chooser;

    @AfterEach
    void stop() {
        if (chooser != null)
            chooser.close();
        if (server != null)
            server.stop(0);
    }

    @Test
    void theRequestAsksForTheFormTheEffortAndTheFallback() throws Exception {
        answer(200, message("end_turn", "[{\"type\":\"text\",\"text\":\"{\\\"suite\\\":\\\"" + UTIL
            + "\\\",\\\"neighbour\\\":\\\"IgniteSqlFunctionsTest\\\",\\\"reason\\\":\\\"It tests SQL functions, as "
            + "IgniteSqlFunctionsTest does.\\\"}\"}]"));

        SuiteChooser.Choice choice = chooser.choose(CLASS, SOURCE, SUITES);

        assertThat(choice).isEqualTo(new SuiteChooser.Choice(UTIL, "IgniteSqlFunctionsTest",
            "It tests SQL functions, as IgniteSqlFunctionsTest does."));
        JsonNode sent = mapper.readTree(bodies.get(0));
        assertThat(sent.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(sent.get("fallbacks").asText()).isEqualTo("default");
        assertThat(betas.get(0)).contains(ClaudeSuiteChooser.FALLBACK_BETA);
        assertThat(sent.at("/output_config/effort").asText()).isEqualTo("medium");
        assertThat(sent.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(sent.at("/output_config/format/schema/properties").has("neighbour")).isTrue();
        assertThat(sent.at("/system/0/text").asText()).isEqualTo(ClaudeSuiteChooser.INSTRUCTIONS);
        assertThat(sent.at("/system/1/text").asText()).contains("Suite: " + UTIL,
            "  org.apache.ignite.internal.processors.query.calcite.exec.exp: IgniteSqlFunctionsTest");
        assertThat(sent.at("/system/1/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(sent.at("/messages/0/content").asText()).startsWith("The test class to add: " + CLASS)
            .contains("package org.apache.ignite.internal.processors.query.calcite.exec.exp;")
            .doesNotContain("Licensed to the Apache Software Foundation");
        assertThat(sent.has("thinking")).as("always on for this model; effort is the control").isFalse();
    }

    @Test
    void aRefusalIsNoPick() throws Exception {
        answer(200, message("refusal", "[]"));

        assertThatThrownBy(() -> chooser.choose(CLASS, SOURCE, SUITES)).isInstanceOf(SuiteChooser.NoChoice.class)
            .hasMessageStartingWith("Claude declined to pick one");
    }

    @Test
    void aCutAnswerIsNoPick() throws Exception {
        answer(200, message("max_tokens", "[{\"type\":\"text\",\"text\":\"{\\\"suite\\\":\"}]"));

        assertThatThrownBy(() -> chooser.choose(CLASS, SOURCE, SUITES)).isInstanceOf(SuiteChooser.NoChoice.class)
            .hasMessage("Claude's answer was cut short");
    }

    @Test
    void anErrorOfTheApiIsNoPick() throws Exception {
        answer(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");

        assertThatThrownBy(() -> chooser.choose(CLASS, SOURCE, SUITES)).isInstanceOf(SuiteChooser.NoChoice.class)
            .hasMessageStartingWith("Anthropic's API answered 400");
    }

    @Test
    void withoutAKeyNothingIsAsked() {
        ClaudeSuiteChooser keyless = new ClaudeSuiteChooser(new AiProperties(" "));

        assertThat(keyless.available()).isFalse();
        assertThatThrownBy(() -> keyless.choose(CLASS, SOURCE, SUITES)).isInstanceOf(SuiteChooser.NoChoice.class)
            .hasMessage("the checker has no Anthropic API key");
    }

    @Test
    void aLongClassIsCutAndTheModelComesFromTheSettings() {
        String longSource = "class A {\n" + "    // filler line of the class body\n".repeat(1000) + "}\n";
        String sent = ClaudeSuiteChooser.theClass(CLASS, longSource);

        assertThat(sent).endsWith("// ... the rest of the class is left out\n</source>");
        assertThat(sent.length()).isLessThan(ClaudeSuiteChooser.SOURCE_CHARS + 300);
        assertThat(new AiProperties("k", "claude-sonnet-5-5", null).model()).isEqualTo("claude-sonnet-5-5");
        assertThat(new AiProperties("k", "", null).model()).isEqualTo("claude-opus-5-5");
    }

    /** Starts the stub, which answers every request with {@code status} and {@code body}, and a chooser on it. */
    private void answer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            betas.add(String.valueOf(exchange.getRequestHeaders().get("anthropic-beta")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        chooser = new ClaudeSuiteChooser(new AiProperties("test-key"),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String message(String stopReason, String content) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
            + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }
}
