package com.github.igniteprchecker.persist;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.ServiceMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Up to v1.41.0 Jackson 2 wrote the state files and the session cookie, and a rollback runs v1.41.0 on the files a
 * later version wrote. The samples were written by v1.41.0 with the service's own mapper: each state file with every
 * field set, and each record those files and the cookie hold once with empty collections and strings and once with
 * nulls. Each sample must read without loss and be written back in the same form: the same fields in the same order,
 * with the same values. A field a later version adds may show up in what is written while it holds a default value,
 * as it does when read from a file without it.
 */
class Jackson2StateTest {
    private static final String SAMPLES = "/snapshots/jackson2/";

    /** Each state file and what the service reads it as. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("metrics.json", "com.github.igniteprchecker.metrics.Metrics$Persisted");
        FILES.put("suite-baseline.json", "com.github.igniteprchecker.analysis.SuiteBaseline$Persisted");
        FILES.put("flaky.json", "java.util.List<com.github.igniteprchecker.analysis.FlakyStats$Persisted>");
        FILES.put("analysis.json", "com.github.igniteprchecker.analysis.AnalysisCache$Persisted");
        FILES.put("delta.json", "java.util.List<com.github.igniteprchecker.analysis.RunDeltaStore$Persisted>");
        FILES.put("users.json", "java.util.List<com.github.igniteprchecker.web.UserDirectory$UserView>");
        FILES.put("admin-actions.json",
            "java.util.Map<java.lang.String,com.github.igniteprchecker.web.AdminActions$Use>");
        FILES.put("logins.json", "java.util.List<com.github.igniteprchecker.web.Logins$Login>");
        FILES.put("problems.json", "java.util.List<com.github.igniteprchecker.health.LogTracker$Entry>");
        FILES.put("reruns.json", "java.util.List<com.github.igniteprchecker.tc.RerunTracker$Persisted>");
        FILES.put("journal.json", "com.github.igniteprchecker.jira.SettingsJournal$Snapshot");
        FILES.put("standing-visas.json", "com.github.igniteprchecker.jira.StandingVisas$Snapshot");
        FILES.put("pr-commands.json", "com.github.igniteprchecker.github.PrCommands$Persisted");
        FILES.put("github.json", "com.github.igniteprchecker.github.GithubClient$Persisted");
        FILES.put("suite-fixes.json", "com.github.igniteprchecker.suitefix.SuiteFixService$Snapshot");
        FILES.put("merged-verdict.json", "com.github.igniteprchecker.analysis.MergedVerdicts$Merged");
        FILES.put("session-cookie.json", "com.github.igniteprchecker.session.SessionCodec$Payload");
    }

    /** The service's own mapper, as it reads and writes the state. */
    private static final ObjectMapper MAPPER = ServiceMapper.get();

    static Stream<Arguments> stateFiles() {
        return FILES.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stateFiles")
    void aStateFileReadsAndIsWrittenAsBefore(String file, String type) throws IOException {
        byte[] sample;
        try (InputStream in = Jackson2StateTest.class.getResourceAsStream(SAMPLES + file)) {
            sample = in.readAllBytes();
        }

        assertWrittenAsBefore(sample, type, file);
    }

    static Stream<Arguments> records() throws IOException {
        JsonNode all;
        try (InputStream in = Jackson2StateTest.class.getResourceAsStream(SAMPLES + "records.json")) {
            all = MAPPER.readTree(in);
        }
        List<Arguments> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> type : all.properties()) {
            for (Map.Entry<String, JsonNode> variant : type.getValue().properties())
                out.add(Arguments.of(type.getKey(), variant.getKey(), variant.getValue()));
        }

        return out.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("records")
    void aRecordReadsAndIsWrittenAsBefore(String type, String variant, JsonNode sample) throws IOException {
        assertWrittenAsBefore(MAPPER.writeValueAsBytes(sample), type, type + " " + variant);
    }

    private static void assertWrittenAsBefore(byte[] sample, String type, String what) throws IOException {
        JavaType javaType = MAPPER.getTypeFactory().constructFromCanonical(type);

        Object read = MAPPER.readValue(sample, javaType);
        JsonNode written = MAPPER.readTree(MAPPER.writeValueAsBytes(read));

        List<String> diffs = new ArrayList<>();
        compare(MAPPER.readTree(sample), written, "", diffs);
        assertThat(diffs).as(what).isEmpty();
    }

    /** Notes where {@code written} lost, changed or reordered what {@code sample} has. */
    private static void compare(JsonNode sample, JsonNode written, String path, List<String> diffs) {
        if (sample.isObject() && written.isObject()) {
            List<String> order = new ArrayList<>();
            for (String name : written.propertyNames()) {
                if (sample.has(name))
                    order.add(name);
                else if (!isDefault(written.get(name)))
                    diffs.add(path + "/" + name + ": not in the sample, written as " + written.get(name));
            }
            List<String> sampleOrder = new ArrayList<>(sample.propertyNames());
            for (String name : sampleOrder) {
                if (written.has(name))
                    compare(sample.get(name), written.get(name), path + "/" + name, diffs);
                else
                    diffs.add(path + "/" + name + ": not written");
            }
            if (!order.equals(sampleOrder))
                diffs.add(path + ": fields written in the order " + order + ", the sample has " + sampleOrder);
        }
        else if (sample.isArray() && written.isArray() && sample.size() == written.size()) {
            for (int i = 0; i < sample.size(); i++)
                compare(sample.get(i), written.get(i), path + "/" + i, diffs);
        }
        else if (sample.getNodeType() != written.getNodeType() || !sample.equals(written))
            diffs.add(path + ": the sample has " + sample + ", written as " + written);
    }

    /** What a field missing from a file reads as. */
    private static boolean isDefault(JsonNode value) {
        return value.isNull() || value.isBoolean() && !value.booleanValue()
            || value.isNumber() && value.doubleValue() == 0 || value.isContainer() && value.isEmpty();
    }
}
