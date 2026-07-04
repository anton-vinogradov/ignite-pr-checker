package com.github.igniteprchecker.persist;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.PersistProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheStoreTest {
    /** A fake cache that records how often it is asked to load/save and where. */
    private static final class RecordingCache implements SnapshotCache {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger saves = new AtomicInteger();
        volatile Path lastSaved;

        @Override public String fileName() {
            return "fake.json";
        }

        @Override public void saveTo(Path file) throws IOException {
            saves.incrementAndGet();
            lastSaved = file;
            Files.writeString(file, "{}");
        }

        @Override public void loadFrom(Path file) {
            loads.incrementAndGet();
        }
    }

    @Test
    void loadsOnStartupAndSavesOnSnapshotAndShutdown(@TempDir Path dir) {
        RecordingCache cache = new RecordingCache();
        CacheStore store = new CacheStore(List.of(cache), new PersistProperties(true, dir.toString(), 5));

        store.init();
        assertThat(cache.loads.get()).isEqualTo(1);
        assertThat(cache.lastSaved).isNull();

        store.snapshot();
        store.onShutdown();
        assertThat(cache.saves.get()).isEqualTo(2);
        assertThat(cache.lastSaved).isEqualTo(dir.resolve("fake.json"));
        assertThat(dir.resolve("fake.json")).exists();
    }

    @Test
    void disablesItselfWhenDirIsNotWritable(@TempDir Path dir) throws Exception {
        // A regular file where a directory is expected: createDirectories under it must fail.
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        Path unusable = blocker.resolve("cache");

        RecordingCache cache = new RecordingCache();
        CacheStore store = new CacheStore(List.of(cache), new PersistProperties(true, unusable.toString(), 5));

        store.init();      // must not throw
        store.snapshot();  // must be a no-op while inactive
        store.onShutdown();

        assertThat(cache.loads.get()).isZero();
        assertThat(cache.saves.get()).isZero();
    }

    @Test
    void doesNothingWhenDisabled(@TempDir Path dir) {
        RecordingCache cache = new RecordingCache();
        CacheStore store = new CacheStore(List.of(cache), new PersistProperties(false, dir.toString(), 5));

        store.init();
        store.snapshot();
        store.onShutdown();

        assertThat(cache.loads.get()).isZero();
        assertThat(cache.saves.get()).isZero();
    }
}
