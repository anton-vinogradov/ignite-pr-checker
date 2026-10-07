package com.github.igniteprchecker.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared JSON snapshot IO for {@link SnapshotCache} implementations. */
public final class Snapshots {
    private Snapshots() {
    }

    /**
     * Writes {@code value} as JSON to {@code file} via a temp file and an atomic rename, so a crash
     * mid-write can never leave a half-written snapshot in place. A snapshot equal to the file's content
     * leaves the file alone: durable state is offered for saving every second, changed or not.
     */
    public static void writeAtomic(ObjectMapper mapper, Path file, Object value) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), value);

        if (Files.exists(file) && Files.mismatch(tmp, file) == -1L) {
            Files.delete(tmp);

            return;
        }

        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
