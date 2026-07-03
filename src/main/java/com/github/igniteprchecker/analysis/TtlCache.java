package com.github.igniteprchecker.analysis;

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

    private record Entry<V>(V value, long expiresAt) {
    }
}
