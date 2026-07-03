package com.github.igniteprchecker.analysis;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** A tiny thread-safe get-or-load cache whose entries expire after a fixed TTL. */
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
        long now = System.currentTimeMillis();

        Entry<V> e = map.get(key);
        if (e != null && now < e.expiresAt())
            return e.value();

        V value = loader.get();
        map.put(key, new Entry<>(value, now + ttlMs));

        return value;
    }

    private record Entry<V>(V value, long expiresAt) {
    }
}
