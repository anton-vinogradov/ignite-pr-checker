package com.github.igniteprchecker.persist;

import com.github.igniteprchecker.config.PersistProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists the in-memory caches to disk so a restart/redeploy starts warm. Loads once on startup,
 * then re-snapshots on a fixed interval and on graceful shutdown. If the configured directory isn't
 * writable (e.g. a local run without the server layout), persistence disables itself and the app
 * runs in-memory only — never fatal.
 */
@Component
public class CacheStore {
    private static final Logger log = LoggerFactory.getLogger(CacheStore.class);

    private final List<SnapshotCache> caches;
    private final PersistProperties props;
    private final Path dir;

    private volatile boolean active;

    public CacheStore(List<SnapshotCache> caches, PersistProperties props) {
        this.caches = caches;
        this.props = props;
        this.dir = Path.of(props.dir());
    }

    @PostConstruct
    void init() {
        if (!props.enabled()) {
            log.info("cache persistence disabled (persist.enabled=false)");
            return;
        }

        if (!ensureWritable()) {
            log.warn("cache persistence dir {} not writable; running in-memory only", dir);
            return;
        }

        active = true;
        loadAll();
        log.info("cache persistence active at {}", dir);
    }

    @Scheduled(fixedDelayString = "${persist.interval-minutes:5}", initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    void snapshot() {
        saveAll();
    }

    @PreDestroy
    void onShutdown() {
        saveAll();
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
                // A corrupt/incompatible snapshot must not block startup: drop it and start that cache cold.
                log.warn("could not load {} (starting cold): {}", f, e.toString());

                try {
                    Files.deleteIfExists(f);
                }
                catch (IOException ignore) {
                    // best effort
                }
            }
        }
    }

    private void saveAll() {
        if (!active)
            return;

        for (SnapshotCache c : caches) {
            Path f = dir.resolve(c.fileName());

            try {
                c.saveTo(f);
            }
            catch (Exception e) {
                log.warn("could not save {}: {}", f, e.toString());
            }
        }
    }
}
