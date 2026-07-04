package com.github.igniteprchecker.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** A tiny thread-safe cache whose entries expire after a fixed TTL. */
final class TtlCache<K, V> {
    private final ConcurrentMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMs;

    TtlCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Returns the cached value if still fresh, otherwise loads it and caches it. A concurrent miss
     * may load more than once (the loader is expected to be idempotent), which avoids holding a lock
     * across the (network) load.
     */
    V get(K key, Supplier<V> loader) {
        return peek(key).orElseGet(() -> {
            V value = loader.get();
            put(key, value);
            return value;
        });
    }

    /** The cached value if present and still fresh; never loads. */
    Optional<V> peek(K key) {
        Entry<V> e = map.get(key);
        if (e != null && System.currentTimeMillis() < e.expiresAt())
            return Optional.of(e.value());

        return Optional.empty();
    }

    void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
    }

    int size() {
        return map.size();
    }

    /** Drops every entry; returns how many were removed. */
    int clear() {
        int n = map.size();
        map.clear();

        return n;
    }

    /** The still-fresh entries, with their expiry, for a disk snapshot. */
    List<Snapshot<K, V>> export() {
        long now = System.currentTimeMillis();
        List<Snapshot<K, V>> out = new ArrayList<>();

        map.forEach((k, e) -> {
            if (now < e.expiresAt())
                out.add(new Snapshot<>(k, e.value(), e.expiresAt()));
        });

        return out;
    }

    /** Repopulate from a snapshot, keeping each entry's original expiry and dropping any now expired. */
    void importAll(List<Snapshot<K, V>> entries) {
        long now = System.currentTimeMillis();

        for (Snapshot<K, V> s : entries)
            if (now < s.expiresAt())
                map.put(s.key(), new Entry<>(s.value(), s.expiresAt()));
    }

    private record Entry<V>(V value, long expiresAt) {
    }

    /** A persisted cache entry: key, value, and the absolute epoch-ms it expires at. */
    record Snapshot<K, V>(K key, V value, long expiresAt) {
    }
}
