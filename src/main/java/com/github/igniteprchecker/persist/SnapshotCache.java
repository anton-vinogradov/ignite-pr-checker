package com.github.igniteprchecker.persist;

import java.io.IOException;
import java.nio.file.Path;

/** A cache that can snapshot itself to (and restore itself from) a single file, to survive restarts. */
public interface SnapshotCache {
    /** File name (within the persistence dir) this cache reads and writes. */
    String fileName();

    /** Serialize the current contents to {@code file}, atomically. */
    void saveTo(Path file) throws IOException;

    /** Repopulate from {@code file}; a missing file is a no-op (the cache just starts cold). */
    void loadFrom(Path file) throws IOException;

    /**
     * State the service acts on and cannot rebuild from TeamCity or GitHub: who opted in, which commands ran,
     * which runs it watches. It reaches the disk within a second of a change instead of on the interval, so a
     * crash cannot make the service post the same visa or run the same command twice.
     */
    default boolean durable() {
        return false;
    }
}
