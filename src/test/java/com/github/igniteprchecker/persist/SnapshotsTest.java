package com.github.igniteprchecker.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.igniteprchecker.Jackson2Defaults;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jackson 3 throws its errors unchecked, where those of Jackson 2 were IOExceptions. On an IOException the callers keep
 * the state they could not write: a merged PR's verdict stays in memory, and the status page names the file whose save
 * failed. An unchecked error went past them, and took the sweep of merged PRs down with it.
 */
class SnapshotsTest {
    /** A record whose JSON cannot be made: a property of it fails. */
    record Broken(String name) {
        public String getFailing() {
            throw new IllegalStateException("cannot say");
        }
    }

    @Test
    void aFileThatCannotBeWrittenIsAnIoException(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("state.json.tmp"));

        assertThatThrownBy(() -> Snapshots.writeAtomic(Jackson2Defaults.mapper(), dir.resolve("state.json"), "x"))
            .isInstanceOf(IOException.class);
        assertThat(dir.resolve("state.json")).doesNotExist();
    }

    @Test
    void aValueThatCannotBeWrittenIsAnIoException(@TempDir Path dir) {
        assertThatThrownBy(() -> Snapshots.writeAtomic(Jackson2Defaults.mapper(), dir.resolve("state.json"),
            new Broken("x"))).isInstanceOf(IOException.class).hasMessageContaining("cannot say");
        assertThat(dir.resolve("state.json")).doesNotExist();
    }
}
