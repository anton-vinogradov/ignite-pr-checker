package com.github.igniteprchecker.persist;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.PersistProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Subscriptions, stored tokens and handled commands were written once every 5 minutes, and an OOM exit skips the
 * shutdown save, so a crash could post a visa twice or rerun a /run-all. A snapshot that failed to load was deleted
 * and then overwritten with the empty state, so one bad release wiped everyone's options for good, with no backup.
 */
class CacheStoreTest {
    /** A fake cache that records how often it is asked to load/save and where. */
    private static class RecordingCache implements SnapshotCache {
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

        @Override public void loadFrom(Path file) throws IOException {
            loads.incrementAndGet();
        }
    }

    /** State that acts on the outside world, held as one string and written as the file's whole content. */
    private static class StateCache implements SnapshotCache {
        volatile String state = "";
        volatile String loaded;

        @Override public String fileName() {
            return "state.json";
        }

        @Override public boolean durable() {
            return true;
        }

        @Override public void saveTo(Path file) throws IOException {
            Files.writeString(file, state);
        }

        @Override public void loadFrom(Path file) throws IOException {
            if (!Files.exists(file))
                return;

            String text = Files.readString(file);
            if (!text.startsWith("{"))
                throw new IOException("Unexpected character ('x' (code 120))");

            loaded = text;
            state = text;
        }
    }

    private static CacheStore store(Path dir, SnapshotCache... caches) {
        return new CacheStore(List.of(caches), new PersistProperties(true, dir.toString(), 5));
    }

    private static void awaitContent(Path file, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.readString(file).equals(expected))
                return;

            Thread.sleep(50);
        }

        assertThat(file).hasContent(expected);
    }

    @Test
    void loadsOnStartupAndSavesOnSnapshotAndShutdown(@TempDir Path dir) {
        RecordingCache cache = new RecordingCache();
        CacheStore store = store(dir, cache);

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
        CacheStore store = store(unusable, cache);

        store.init();      // must not throw
        store.snapshot();  // must be a no-op while inactive
        store.onShutdown();

        assertThat(cache.loads.get()).isZero();
        assertThat(cache.saves.get()).isZero();
        assertThat(store.status().active()).isFalse();
        assertThat(store.status().off()).isEqualTo("directory " + unusable + " is not writable");
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
        assertThat(store.status().enabled()).isFalse();
    }

    @Test
    void aChangeToDurableStateReachesTheDiskWithinASecondOrTwo(@TempDir Path dir) throws Exception {
        StateCache state = new StateCache();
        CacheStore store = store(dir, state);
        store.init();
        try {
            state.state = "{\"posted\":{\"13655\":8123456}}";

            awaitContent(dir.resolve("state.json"), state.state);
        }
        finally {
            store.onShutdown();
        }
    }

    @Test
    void anUnreadableSnapshotIsKeptAsideAndReported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "xx-not-json");
        StateCache state = new StateCache();
        CacheStore store = store(dir, state);

        store.init();
        store.onShutdown();

        List<Path> kept;
        try (Stream<Path> files = Files.list(dir)) {
            kept = files.filter(p -> p.getFileName().toString().startsWith("state.json.bad-")).toList();
        }
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).hasContent("xx-not-json");
        assertThat(store.status().unreadable())
            .containsExactly(new CacheStore.Unreadable("state.json", kept.get(0).getFileName().toString()));
    }

    @Test
    void anUnreadableCacheIsKeptAsideButNotReported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fake.json"), "xx");
        RecordingCache cache = new RecordingCache() {
            @Override public void loadFrom(Path file) throws IOException {
                throw new IOException("Unexpected character");
            }
        };
        CacheStore store = store(dir, cache);

        store.init();
        store.onShutdown();

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("fake.json.bad-"));
        }
        assertThat(store.status().unreadable()).isEmpty();
    }

    @Test
    void theStateAsTheLastRunLeftItIsKeptAsPrev(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("state.json"), "{\"run\":1}");
        StateCache state = new StateCache();
        CacheStore store = store(dir, state);

        store.init();
        state.state = "{\"run\":2}";
        store.onShutdown();

        assertThat(state.loaded).isEqualTo("{\"run\":1}");
        assertThat(dir.resolve("state.json")).hasContent("{\"run\":2}");
        assertThat(dir.resolve("state.json.prev")).hasContent("{\"run\":1}");
    }

    @Test
    void aFailingSaveShowsOnTheStatusUntilItWorksAgain(@TempDir Path dir) {
        var cache = new RecordingCache() {
            volatile boolean diskFull = true;

            @Override public void saveTo(Path file) throws IOException {
                if (diskFull)
                    throw new IOException("No space left on device");

                super.saveTo(file);
            }
        };
        CacheStore store = store(dir, cache);
        store.init();

        store.snapshot();
        assertThat(store.status().failingSaves())
            .containsExactly(Map.entry("fake.json", "java.io.IOException: No space left on device"));

        cache.diskFull = false;
        store.snapshot();
        assertThat(store.status().failingSaves()).isEmpty();
        store.onShutdown();
    }

    @Test
    void aDayOfSnapshotsIsZippedAndAWeekKept(@TempDir Path dir) throws Exception {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int daysAgo = 1; daysAgo <= 8; daysAgo++)
            Files.writeString(backups.resolve("cache-" + today.minusDays(daysAgo) + ".zip"), "old");
        RecordingCache cache = new RecordingCache();
        CacheStore store = store(dir, cache);
        store.init();

        store.snapshot();
        store.onShutdown();

        List<String> names;
        try (Stream<Path> files = Files.list(backups)) {
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
        }
        List<String> expected = new ArrayList<>();
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--)
            expected.add("cache-" + today.minusDays(daysAgo) + ".zip");
        assertThat(names).containsExactlyElementsOf(expected);
        assertThat(store.status().lastBackup()).isEqualTo("cache-" + today + ".zip");

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backups.resolve("cache-" + today + ".zip")))) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry.getName()).isEqualTo("fake.json");
            assertThat(new String(zip.readAllBytes(), UTF_8)).isEqualTo("{}");
        }
    }

    @Test
    void anUnchangedSnapshotLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        ObjectMapper mapper = new ObjectMapper();
        Snapshots.writeAtomic(mapper, file, List.of("alice"));
        FileTime past = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, past);

        Snapshots.writeAtomic(mapper, file, List.of("alice"));
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(past);
        assertThat(dir.resolve("users.json.tmp")).doesNotExist();

        Snapshots.writeAtomic(mapper, file, List.of("alice", "bob"));
        assertThat(file).hasContent("[\"alice\",\"bob\"]");
    }
}
