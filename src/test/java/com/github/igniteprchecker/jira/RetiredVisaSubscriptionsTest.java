package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.igniteprchecker.config.PersistProperties;
import com.github.igniteprchecker.persist.TokenScrub;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache directory on prod keeps visa-subs.json, the one-shot Auto visas of v1.36.0 and earlier, with the JIRA
 * token of each, encrypted, and the copies of it made before each build. No code reads the file any more, so its
 * tokens would stay on the disk for good. The start removes it and its copies, and leaves every other file alone.
 */
class RetiredVisaSubscriptionsTest {
    /** A subscription armed by alice, one JIRA refused the token of, and one waiting for its next try. */
    private static final String LATEST = """
        [{"pr":13655,"issue":"IGNITE-26512","token":"JbCL6gbCh0z6pN0XsthChF3oKlvFOYiKx4BUojmcz8n8wtoxzj3RZr3N",
          "username":"alice","armedAt":1759800000000,"retryChain":9389046,"retryChainSeenAt":1759800100000,
          "retryAt":1759800700000,"retryingSince":1759800100000},
         {"pr":13671,"issue":"IGNITE-26540","username":"frank","armedAt":1759811111000,"refusedAt":1759812222000}]
        """;

    @Test
    void theFileOfTheOneShotVisasGoesWithItsCopies(@TempDir Path dir) throws IOException {
        try (InputStream release = getClass().getResourceAsStream("/snapshots/v1.20.11/visa-subs.json")) {
            Files.copy(release, dir.resolve("visa-subs.json"));
        }
        Files.writeString(dir.resolve("visa-subs.json.before-1.36.0-built-20261017-101500"), LATEST);
        Files.writeString(dir.resolve("visa-subs.json.bad-20261001-120000"), "{");
        Files.writeString(dir.resolve("visa-subs.json.tmp"), LATEST);
        List<String> kept = List.of("standing-visas.json", "standing-visas.json.before-1.36.0-built-20261017-101500",
            "users.json", "visa-subs-notes.txt");
        for (String name : kept)
            Files.writeString(dir.resolve(name), "[]");

        new RetiredVisaSubscriptions(new PersistProperties(true, dir.toString(), null)).removeOnStart();

        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left.map(f -> f.getFileName().toString())).containsExactlyInAnyOrderElementsOf(kept);
        }
    }

    /**
     * A daily backup made before this release holds visa-subs.json. Two days old, it loses its tokens like the other
     * files of the backup. Restored into the cache directory, the file goes at the next start, and the rest stays.
     */
    @Test
    void aRestoredBackupBringsBackNoOneShotVisa(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("cache-2026-10-10.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : List.of("standing-visas.json", "visa-subs.json")) {
                out.putNextEntry(new ZipEntry(name));
                out.write((name.startsWith("visa") ? LATEST : "{\"enrollments\":[],\"retries\":{}}").getBytes(UTF_8));
                out.closeEntry();
            }
        }

        assertThat(TokenScrub.removeFrom(zip, System.currentTimeMillis())).isTrue();

        Path cache = Files.createDirectory(dir.resolve("cache"));
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry())
                Files.copy(in, cache.resolve(e.getName()));
        }
        assertThat(cache.resolve("visa-subs.json")).content().contains("\"tokenRemoved\":true")
            .doesNotContain("JbCL6gbCh0z6pN0Xsth");

        new RetiredVisaSubscriptions(new PersistProperties(true, cache.toString(), null)).removeOnStart();

        try (Stream<Path> left = Files.list(cache)) {
            assertThat(left.map(f -> f.getFileName().toString())).containsExactly("standing-visas.json");
        }
    }

    @Test
    void persistenceOffTouchesNoFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("visa-subs.json"), LATEST);

        new RetiredVisaSubscriptions(new PersistProperties(false, dir.toString(), null)).removeOnStart();

        assertThat(dir.resolve("visa-subs.json")).exists();
    }

    /** A cache directory the service cannot list is left as it is, and the start goes on. */
    @Test
    void aDirectoryThatCannotBeListedStopsNoStart(@TempDir Path dir) throws IOException {
        Path cache = Files.createDirectory(dir.resolve("cache"));
        Files.writeString(cache.resolve("visa-subs.json"), LATEST);
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("-wx------"));
        try {
            assertThatCode(() -> new RetiredVisaSubscriptions(new PersistProperties(true, cache.toString(), null))
                .removeOnStart()).doesNotThrowAnyException();
            assertThatCode(() -> new RetiredVisaSubscriptions(new PersistProperties(true,
                dir.resolve("absent").toString(), null)).removeOnStart()).doesNotThrowAnyException();
        }
        finally {
            Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
