package com.github.igniteprchecker.persist;

import com.github.igniteprchecker.config.PersistProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists the in-memory caches to disk so a restart/redeploy starts warm. Loads once on startup; then
 * {@linkplain SnapshotCache#durable() durable} state is written within a second of a change, the rest on a
 * fixed interval, and everything on graceful shutdown. A file that cannot be read is kept aside as
 * {@code <file>.bad-<time>} and reported, never deleted; a durable file read at startup is copied to
 * {@code <file>.prev}, the state as the previous run left it. Once a day the snapshot files are zipped into
 * {@code backups/}, the last seven kept. If the configured directory isn't writable (e.g. a local run without
 * the server layout), persistence disables itself and the app runs in-memory only — never fatal.
 */
@Component
public class CacheStore {
    private static final Logger log = LoggerFactory.getLogger(CacheStore.class);

    private static final long FLUSH_EVERY_MS = 1000;

    private static final int BACKUPS_KEPT = 7;

    private static final String BACKUPS = "backups";

    private static final Pattern BACKUP_NAME = Pattern.compile("cache-\\d{4}-\\d{2}-\\d{2}\\.zip");

    private static final DateTimeFormatter BAD_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final List<SnapshotCache> caches;
    private final PersistProperties props;
    private final Path dir;
    private final List<Unreadable> unreadable = new CopyOnWriteArrayList<>();

    /** File name -> why its last save failed; a file leaves when a save works again. */
    private final Map<String, String> failing = new ConcurrentHashMap<>();

    private volatile boolean active;
    private volatile String off;
    private volatile String lastBackup;
    private ScheduledExecutorService flusher;

    public CacheStore(List<SnapshotCache> caches, PersistProperties props) {
        this.caches = caches;
        this.props = props;
        this.dir = Path.of(props.dir());
    }

    @PostConstruct
    void init() {
        if (!props.enabled()) {
            off = "disabled (persist.enabled=false)";
            log.info("cache persistence disabled (persist.enabled=false)");
            return;
        }

        if (!ensureWritable()) {
            off = "directory " + dir + " is not writable";
            log.warn("cache persistence dir {} not writable; running in-memory only", dir);
            return;
        }

        active = true;
        loadAll();
        lastBackup = newestBackup();
        startFlusher();
        log.info("cache persistence active at {}", dir);
    }

    @Scheduled(fixedDelayString = "${persist.interval-minutes:5}", initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    void snapshot() {
        if (!active)
            return;

        for (SnapshotCache c : caches) {
            if (!c.durable())
                save(c);
        }

        backUpOncePerDay();
    }

    /** Durable state is saved by the flusher alone while it runs, so two writers never share a temp file. */
    @PreDestroy
    void onShutdown() {
        if (flusher != null) {
            flusher.shutdown();
            try {
                flusher.awaitTermination(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (active)
            caches.forEach(this::save);
    }

    /** What the status page says about persistence. */
    public Status status() {
        return new Status(props.enabled(), active, off, List.copyOf(unreadable), new TreeMap<>(failing), lastBackup);
    }

    private void startFlusher() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flushDurable, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    private void flushDurable() {
        for (SnapshotCache c : caches) {
            if (c.durable())
                save(c);
        }
    }

    private boolean ensureWritable() {
        try {
            Files.createDirectories(dir);

            Path probe = dir.resolve(".writable");
            Files.writeString(probe, "");
            Files.deleteIfExists(probe);

            return true;
        }
        catch (IOException | RuntimeException e) {
            log.debug("persistence dir check failed: {}", e.toString());

            return false;
        }
    }

    private void loadAll() {
        for (SnapshotCache c : caches) {
            Path f = dir.resolve(c.fileName());

            try {
                c.loadFrom(f);
            }
            catch (Exception e) {
                setAside(c, f, e);
                continue;
            }

            if (c.durable())
                keepAsPrevious(f);
        }
    }

    /** A snapshot that fails to load must not block startup, nor be overwritten by the empty state that follows. */
    private void setAside(SnapshotCache c, Path f, Exception cause) {
        Path kept = f.resolveSibling(c.fileName() + ".bad-" + BAD_STAMP.format(Instant.now()));
        try {
            Files.move(f, kept);
        }
        catch (IOException e) {
            log.error("could not read {} ({}) nor move it aside ({}); it will be overwritten", f, cause.toString(),
                e.toString());
            return;
        }

        if (!c.durable()) {
            log.warn("could not read {} ({}); kept it as {}, starting cold", f, cause.toString(), kept.getFileName());
            return;
        }

        log.error("could not read {} ({}); kept it as {} — this state starts empty", f, cause.toString(),
            kept.getFileName());
        unreadable.add(new Unreadable(c.fileName(), kept.getFileName().toString()));
    }

    private static void keepAsPrevious(Path f) {
        if (!Files.exists(f))
            return;

        try {
            Files.copy(f, f.resolveSibling(f.getFileName() + ".prev"), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            log.warn("could not keep the previous {}: {}", f, e.toString());
        }
    }

    private void save(SnapshotCache c) {
        Path f = dir.resolve(c.fileName());

        try {
            c.saveTo(f);
            if (failing.remove(c.fileName()) != null)
                log.info("saving {} works again", f);
        }
        catch (Exception e) {
            if (failing.put(c.fileName(), e.toString()) == null)
                log.warn("could not save {}: {}", f, e.toString());
        }
    }

    private void backUpOncePerDay() {
        String name = "cache-" + LocalDate.now(ZoneOffset.UTC) + ".zip";
        Path backups = dir.resolve(BACKUPS);
        Path zip = backups.resolve(name);
        if (Files.exists(zip))
            return;

        try {
            Files.createDirectories(backups);
            Path tmp = backups.resolve(name + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream entries = new ZipOutputStream(out)) {
                for (SnapshotCache c : caches) {
                    Path f = dir.resolve(c.fileName());
                    if (!Files.exists(f))
                        continue;

                    entries.putNextEntry(new ZipEntry(c.fileName()));
                    Files.copy(f, entries);
                    entries.closeEntry();
                }
            }
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            lastBackup = name;
            pruneBackups(backups);
            if (failing.remove(BACKUPS) != null)
                log.info("backing up the snapshots works again");
        }
        catch (IOException e) {
            if (failing.put(BACKUPS, e.toString()) == null)
                log.warn("could not back up the snapshots to {}: {}", zip, e.toString());
        }
    }

    private static void pruneBackups(Path backups) throws IOException {
        for (Path old : backupsNewestFirst(backups).stream().skip(BACKUPS_KEPT).toList())
            Files.deleteIfExists(old);
    }

    private String newestBackup() {
        try {
            return backupsNewestFirst(dir.resolve(BACKUPS)).stream()
                .findFirst()
                .map(p -> p.getFileName().toString())
                .orElse(null);
        }
        catch (IOException e) {
            return null;
        }
    }

    private static List<Path> backupsNewestFirst(Path backups) throws IOException {
        if (!Files.isDirectory(backups))
            return List.of();

        try (Stream<Path> files = Files.list(backups)) {
            return files.filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        }
    }

    /**
     * {@code off} says why snapshots are off; {@code unreadable} lists durable files that failed to load at
     * startup; {@code failingSaves} maps a file (or {@code backups}) to why its last save failed.
     */
    public record Status(boolean enabled, boolean active, String off, List<Unreadable> unreadable,
        Map<String, String> failingSaves, String lastBackup) {
    }

    /** A durable snapshot that could not be read at startup, and the name it was kept under. */
    public record Unreadable(String file, String keptAs) {
    }
}
