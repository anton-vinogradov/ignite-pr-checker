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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The service stopped in the middle of the daily backup and left backups/cache-2026-10-10.zip.tmp with the stored
 * tokens in it. The scrub of tokens and the prune of the backups look only at finished zips, so 9 days later it still
 * held the TeamCity token. The start deletes such files, and the temp file of a save stopped before its rename.
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
}
