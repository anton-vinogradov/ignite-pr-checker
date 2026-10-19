package com.github.igniteprchecker.persist;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.LogCapture;
import com.github.igniteprchecker.config.PersistProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The daily backups and the copies kept from before each build held every stored token for a week, or for five
 * builds: on prod, classic GitHub tokens their owners had already replaced were still in seven zips. A copy now keeps
 * its tokens two days, then the rest of the state without them.
 */
class CopyTokensTest {
    private static final long NOW = 1_800_000_000_000L;

    private static final String STANDING = "{\"enrollments\":[{\"username\":\"avinogradov\",\"tcToken\":\"tc-cipher\","
        + "\"jiraToken\":\"jira-cipher\",\"ghToken\":null,\"tz\":\"Europe/Moscow\",\"autoVisa\":true,"
        + "\"ghRejectedAt\":null,\"jiraRejectedAt\":0,\"tcRejectedAt\":0}],\"retries\":{}}";

    private static final String SUBS = "[{\"pr\":13335,\"issue\":\"IGNITE-28867\",\"token\":\"sub-cipher\","
        + "\"username\":\"avinogradov\",\"armedAt\":1}]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path copy(Path dir, String name, String content, Duration age) throws IOException {
        Path f = Files.writeString(dir.resolve(name), content);
        Files.setLastModifiedTime(f, FileTime.fromMillis(NOW - age.toMillis()));

        return f;
    }

    private static CacheStore store(Path dir) {
        return new CacheStore(List.of(), new PersistProperties(true, dir.toString(), 5), "1.36.0");
    }

    @Test
    void aCopyOlderThanTwoDaysKeepsTheStateButNoToken(@TempDir Path dir) throws IOException {
        Path before = copy(dir, "standing-visas.json.before-1.35.0-built-20261010-120000", STANDING,
            Duration.ofDays(3));
        Path bad = copy(dir, "visa-subs.json.bad-20261012-080000", SUBS, Duration.ofDays(5));

        store(dir).removeTokensFromCopies(NOW);

        JsonNode alice = JSON.readTree(before.toFile()).get("enrollments").get(0);
        assertThat(alice.get("tcToken").isNull()).isTrue();
        assertThat(alice.get("jiraToken").isNull()).isTrue();
        assertThat(alice.get("tcTokenRemoved").asBoolean()).isTrue();
        assertThat(alice.get("jiraTokenRemoved").asBoolean()).isTrue();
        assertThat(alice.has("ghTokenRemoved")).as("there was no GitHub token").isFalse();
        assertThat(alice.get("username").asText()).isEqualTo("avinogradov");
        assertThat(alice.get("tz").asText()).isEqualTo("Europe/Moscow");
        assertThat(alice.get("autoVisa").asBoolean()).isTrue();
        assertThat(Files.getLastModifiedTime(before).toMillis()).as("the copy's age stays")
            .isEqualTo(NOW - Duration.ofDays(3).toMillis());

        JsonNode sub = JSON.readTree(bad.toFile()).get(0);
        assertThat(sub.get("token").isNull()).isTrue();
        assertThat(sub.get("tokenRemoved").asBoolean()).isTrue();
        assertThat(sub.get("issue").asText()).isEqualTo("IGNITE-28867");
    }

    /**
     * Builds before the marks take a TeamCity token they cannot read for a rotated secret and drop the options at the
     * next finished run; one TeamCity refused only pauses them.
     */
    @Test
    void aRemovedTokenReadsAsRefusedToBuildsThatDoNotKnowTheMark(@TempDir Path dir) throws IOException {
        Path before = copy(dir, "standing-visas.json.before-1.35.0-built-20261010-120000", STANDING,
            Duration.ofDays(3));

        store(dir).removeTokensFromCopies(NOW);

        JsonNode alice = JSON.readTree(before.toFile()).get("enrollments").get(0);
        assertThat(alice.get("tcRejectedAt").asLong()).isEqualTo(NOW);
        assertThat(alice.get("jiraRejectedAt").asLong()).isEqualTo(NOW);
        assertThat(alice.get("ghRejectedAt").isNull()).as("no GitHub token was taken out").isTrue();
    }

    @Test
    void aYoungerCopyAndTheLiveStateKeepTheirTokens(@TempDir Path dir) throws IOException {
        Path young = copy(dir, "standing-visas.json.before-1.36.0-built-20261017-120000", STANDING,
            Duration.ofHours(47));
        Path live = copy(dir, "standing-visas.json", STANDING, Duration.ofDays(10));

        store(dir).removeTokensFromCopies(NOW);

        assertThat(young).hasContent(STANDING);
        assertThat(live).hasContent(STANDING);
    }

    @Test
    void aBackupLosesItsTokensAndKeepsItsOtherFilesAsTheyWere(@TempDir Path dir) throws IOException {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        String analysis = "{\"13335\":{\"suite\":\"Cache 1\",\"rate\":1.50}}";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("analysis.json", analysis);
        entries.put("standing-visas.json", STANDING);
        entries.put("visa-subs.json", SUBS);
        Path old = zip(backups.resolve("cache-2026-10-10.zip"), entries, Duration.ofDays(3));
        Path young = zip(backups.resolve("cache-2026-10-17.zip"), entries, Duration.ofDays(1));
        byte[] youngBytes = Files.readAllBytes(young);

        store(dir).removeTokensFromCopies(NOW);

        Map<String, String> left = unzip(old);
        assertThat(left.keySet()).containsExactly("analysis.json", "standing-visas.json", "visa-subs.json");
        assertThat(left.get("analysis.json")).isEqualTo(analysis);
        assertThat(String.join("\n", left.values())).doesNotContain("tc-cipher", "jira-cipher", "sub-cipher")
            .contains("\"username\":\"avinogradov\"", "\"tcTokenRemoved\":true", "\"tokenRemoved\":true");
        assertThat(Files.getLastModifiedTime(old).toMillis()).isEqualTo(NOW - Duration.ofDays(3).toMillis());
        assertThat(Files.readAllBytes(young)).isEqualTo(youngBytes);
    }

    /**
     * A release that could not read standing-visas.json set it aside with the time of its last save, three quiet days
     * back, and its tokens went within ten minutes: the rollback to the release before then brought a state with every
     * user's options paused. The two days of a file set aside start when it is set aside.
     */
    @Test
    void aFileSetAsideKeepsItsTokensTwoDaysFromThenHoweverOldItsLastSave(@TempDir Path dir) throws IOException {
        long lastSave = System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        Files.setLastModifiedTime(Files.writeString(dir.resolve("standing-visas.json"), STANDING),
            FileTime.fromMillis(lastSave));
        SnapshotCache unreadable = new SnapshotCache() {
            @Override public String fileName() {
                return "standing-visas.json";
            }

            @Override public boolean durable() {
                return true;
            }

            @Override public void saveTo(Path file) {
            }

            @Override public void loadFrom(Path file) throws IOException {
                throw new IOException("Unrecognized field \"ghThreadsV2\"");
            }
        };
        CacheStore store = new CacheStore(List.of(unreadable), new PersistProperties(true, dir.toString(), 5),
            "1.37.0");
        store.init();
        store.onShutdown();
        Path bad = dir.resolve(store.status().unreadable().get(0).keptAs());
        long setAside = System.currentTimeMillis();

        store.removeTokensFromCopies(setAside + Duration.ofMinutes(10).toMillis());

        assertThat(bad).hasContent(STANDING);

        store.removeTokensFromCopies(setAside + Duration.ofDays(2).plusMinutes(1).toMillis());

        assertThat(bad).content().doesNotContain("tc-cipher", "jira-cipher");
    }

    /** A file set aside because it could not be read may be cut short: then no field in it can be told. */
    @Test
    void aCopyThatIsNotJsonIsNamedInTheLogOnce(@TempDir Path dir) throws IOException {
        String cut = STANDING.substring(0, STANDING.indexOf("\"jiraToken\""));
        Path bad = copy(dir, "standing-visas.json.bad-20261012-080000", cut, Duration.ofDays(3));
        CacheStore store = store(dir);

        String log;
        try (LogCapture capture = new LogCapture(Map.of())) {
            store.removeTokensFromCopies(NOW);
            store.removeTokensFromCopies(NOW + Duration.ofMinutes(10).toMillis());
            log = capture.text();
        }

        assertThat(bad).hasContent(cut);
        assertThat(log.split("could not take the tokens out of")).hasSize(2);
        assertThat(log).contains("standing-visas.json.bad-20261012-080000: it is not JSON (Unexpected end-of-input",
            "Delete it once you no longer need it");
    }

    private static Path zip(Path zip, Map<String, String> entries, Duration age) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(UTF_8));
                z.closeEntry();
            }
        }
        Files.setLastModifiedTime(zip, FileTime.fromMillis(NOW - age.toMillis()));

        return zip;
    }

    private static Map<String, String> unzip(Path zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry())
                out.put(e.getName(), new String(in.readAllBytes(), UTF_8));
        }

        return out;
    }
}
