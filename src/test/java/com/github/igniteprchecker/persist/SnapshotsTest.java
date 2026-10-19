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
 * Jackson 3 throws its errors unchecked, where those of Jackson 2 were IOExceptions. On an IOException a merged PR's
 * verdict that the disk refuses stays in memory. An unchecked error passes that catch and ends the sweep of merged PRs.
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
