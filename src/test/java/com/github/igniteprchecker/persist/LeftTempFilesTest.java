package com.github.igniteprchecker.persist;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.PersistProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The service stopped in the middle of the daily backup and left backups/cache-2026-10-10.zip.tmp with the stored
 * tokens in it. The scrub of tokens and the prune of the backups look only at finished zips, so 9 days later it still
 * held the TeamCity token. The start deletes such files, and the temp file of a save stopped before its rename. A
 * backup that fails while the service runs deletes its own.
 */
class LeftTempFilesTest {
    private static final String STANDING = "{\"enrollments\":[{\"username\":\"alice\",\"tcToken\":\"tc-cipher\","
        + "\"tcRejectedAt\":0}]}";

    @Test
    void theStartDeletesTheTempFilesAStopLeft(@TempDir Path dir) throws IOException {
        FileTime nineDaysAgo = FileTime.fromMillis(System.currentTimeMillis() - Duration.ofDays(9).toMillis());
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Path backup = backups.resolve("cache-2026-10-10.zip.tmp");
        try (OutputStream out = Files.newOutputStream(backup); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("standing-visas.json"));
            zip.write(STANDING.getBytes(UTF_8));
            zip.closeEntry();
        }
        Files.setLastModifiedTime(backup, nineDaysAgo);
        Path save = Files.writeString(dir.resolve("standing-visas.json.tmp"), STANDING);
        Files.setLastModifiedTime(save, nineDaysAgo);
        Path notTemp = Files.writeString(backups.resolve("notes.txt"), "kept");
        Files.setLastModifiedTime(notTemp, nineDaysAgo);
        Path fresh = Files.writeString(dir.resolve("being-written.json.tmp"), "{}");

        CacheStore store = new CacheStore(List.of(), new PersistProperties(true, dir.toString(), 5), "1.42.0");
        store.init();
        store.onShutdown();

        assertThat(backup).doesNotExist();
        assertThat(save).doesNotExist();
        assertThat(notTemp).exists();
        assertThat(fresh).as("written since the JVM started").exists();
    }

    /** The disk filled up in the middle of the daily backup, while the service ran on. */
    @Test
    void aBackupThatFailsLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("standing-visas.json"), STANDING);
        Files.createDirectories(dir.resolve("unreadable.json"));
        CacheStore store = new CacheStore(List.of(cache("standing-visas.json"), cache("unreadable.json")),
            new PersistProperties(true, dir.toString(), 5), "1.42.0");
        store.init();

        store.snapshot();
        store.onShutdown();

        try (Stream<Path> backups = Files.list(dir.resolve("backups"))) {
            assertThat(backups).isEmpty();
        }
        assertThat(store.status().failingSaves()).containsKey("backups");
    }

    /** A cache whose file stays as the test wrote it. */
    private static SnapshotCache cache(String file) {
        return new SnapshotCache() {
            @Override public String fileName() {
                return file;
            }

            @Override public void saveTo(Path f) {
            }

            @Override public void loadFrom(Path f) {
            }
        };
    }
}
