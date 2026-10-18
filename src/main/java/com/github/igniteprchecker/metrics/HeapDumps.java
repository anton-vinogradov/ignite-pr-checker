package com.github.igniteprchecker.metrics;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The heap dumps the JVM writes when it runs out of memory ({@code -XX:+HeapDumpOnOutOfMemoryError}). A dump holds
 * whatever the heap held, decrypted tokens and the session key among them, so it is deleted {@link #KEPT} after the
 * JVM wrote it. Until then the status page says when the JVM ran out of memory and when the dump goes.
 */
public final class HeapDumps {
    private static final Logger log = LoggerFactory.getLogger(HeapDumps.class);

    /** How long a dump stays: long enough to fetch it after an OOM, short enough not to keep tokens on disk. */
    public static final Duration KEPT = Duration.ofDays(2);

    private static final String SUFFIX = ".hprof";

    /** Where the JVM writes its dumps: a directory, or the one file {@code -XX:HeapDumpPath} names; null for none. */
    private final Path where;

    HeapDumps(Path where) {
        this.where = where;
    }

    /** The dumps of the JVM that runs this code. */
    public static HeapDumps ofThisJvm() {
        return new HeapDumps(dumpPath());
    }

    private static Path dumpPath() {
        try {
            HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

            return vm == null ? null : dumpPath(Boolean.parseBoolean(vm.getVMOption("HeapDumpOnOutOfMemoryError")
                .getValue()), vm.getVMOption("HeapDumpPath").getValue());
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** Without {@code -XX:HeapDumpPath} the JVM writes the dump to its working directory. */
    static Path dumpPath(boolean dumpsOnOom, String heapDumpPath) {
        if (!dumpsOnOom)
            return null;

        return Path.of(heapDumpPath == null || heapDumpPath.isBlank() ? System.getProperty("user.dir") : heapDumpPath);
    }

    /** The newest dump; null when there is none. */
    public Dump newest() {
        return dumps().stream().max(Comparator.naturalOrder()).map(Dump::of).orElse(null);
    }

    /** Deletes the dumps written {@link #KEPT} or longer before {@code now}. */
    public void deleteExpired(long now) {
        for (Written w : dumps()) {
            Dump d = Dump.of(w);
            if (d.deletedAt() > now)
                continue;

            try {
                Files.deleteIfExists(w.file());
                log.info("deleted the heap dump {} the JVM wrote {}: a dump holds decrypted tokens and is kept {} days",
                    w.file(), Instant.ofEpochMilli(d.writtenAt()), KEPT.toDays());
            }
            catch (IOException e) {
                log.warn("could not delete the heap dump {}: {}", w.file(), e.toString());
            }
        }
    }

    private List<Written> dumps() {
        if (where == null)
            return List.of();

        if (!Files.isDirectory(where))
            return Files.isRegularFile(where) ? List.of(Written.of(where)) : List.of();

        try (Stream<Path> files = Files.list(where)) {
            return files.filter(f -> f.getFileName().toString().endsWith(SUFFIX) && Files.isRegularFile(f))
                .map(Written::of)
                .toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }

    /** A dump file and when the JVM finished writing it. */
    private record Written(Path file, long at) implements Comparable<Written> {
        static Written of(Path file) {
            try {
                return new Written(file, Files.getLastModifiedTime(file).toMillis());
            }
            catch (IOException e) {
                return new Written(file, 0);
            }
        }

        @Override
        public int compareTo(Written o) {
            return Long.compare(at, o.at);
        }
    }

    /** A heap dump: when the JVM wrote it, that is when it ran out of memory, and when it is deleted. */
    public record Dump(long writtenAt, long deletedAt) {
        static Dump of(Written w) {
            return new Dump(w.at(), w.at() + KEPT.toMillis());
        }
    }
}
