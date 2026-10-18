package com.github.igniteprchecker.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A heap dump the JVM wrote on running out of memory stayed on disk until the next OOM, with the decrypted tokens of
 * the pool and the session key in it, and nothing on the status page said the JVM had run out of memory. A dump now
 * goes two days after the JVM wrote it, while the service runs, and until then the page says when it was written.
 */
class HeapDumpsTest {
    private static final long NOW = 1_800_000_000_000L;

    private static Path dump(Path dir, String name, Duration age) throws IOException {
        Path f = Files.writeString(dir.resolve(name), "JAVA PROFILE 1.0.2");
        Files.setLastModifiedTime(f, FileTime.fromMillis(NOW - age.toMillis()));

        return f;
    }

    @Test
    void aDumpGoesTwoDaysAfterTheJvmWroteIt(@TempDir Path dir) throws IOException {
        Path fresh = dump(dir, "java_pid202.hprof", Duration.ofHours(47));
        Path old = dump(dir, "java_pid101.hprof", Duration.ofHours(49));
        Path other = dump(dir, "notes.txt", Duration.ofDays(30));

        new HeapDumps(dir).deleteExpired(NOW);

        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(other).exists();
    }

    @Test
    void theNewestDumpSaysWhenItWasWrittenAndWhenItGoes(@TempDir Path dir) throws IOException {
        dump(dir, "java_pid101.hprof", Duration.ofHours(30));
        dump(dir, "java_pid202.hprof", Duration.ofHours(5));

        HeapDumps.Dump newest = new HeapDumps(dir).newest();

        assertThat(newest.writtenAt()).isEqualTo(NOW - Duration.ofHours(5).toMillis());
        assertThat(newest.deletedAt()).isEqualTo(NOW + Duration.ofHours(43).toMillis());
    }

    @Test
    void noDumpNoOutOfMemory(@TempDir Path dir) throws IOException {
        dump(dir, "notes.txt", Duration.ofHours(1));

        assertThat(new HeapDumps(dir).newest()).isNull();
        assertThat(new HeapDumps(dir.resolve("missing")).newest()).isNull();
        assertThat(new HeapDumps(null).newest()).isNull();
    }

    /** run.sh names the dumps directory; without a path the JVM writes to its working directory. */
    @Test
    void theDumpsAreWhereTheJvmWritesThem() {
        assertThat(HeapDumps.dumpPath(true, "/opt/ignite-pr-checker/dumps"))
            .isEqualTo(Path.of("/opt/ignite-pr-checker/dumps"));
        assertThat(HeapDumps.dumpPath(true, "")).isEqualTo(Path.of(System.getProperty("user.dir")));
        assertThat(HeapDumps.dumpPath(false, "/opt/ignite-pr-checker/dumps")).isNull();
    }

    /** {@code -XX:HeapDumpPath} may name the dump file itself, in JAVA_OPTS of the env file. */
    @Test
    void aDumpPathThatNamesAFileIsThatDump(@TempDir Path dir) throws IOException {
        Path file = dump(dir, "prc.dump", Duration.ofDays(3));

        assertThat(new HeapDumps(file).newest().writtenAt()).isEqualTo(NOW - Duration.ofDays(3).toMillis());

        new HeapDumps(file).deleteExpired(NOW);

        assertThat(file).doesNotExist();
    }
}
