package com.github.igniteprchecker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The files install.sh writes, read from install.sh itself, for tests that run or check them. */
public final class InstallScript {
    private InstallScript() {
    }

    /** The text of the here-document that starts after {@code opening} and ends at the line {@code end}. */
    public static String heredoc(String opening, String end) throws IOException {
        String script = Files.readString(Path.of("install.sh"));
        int from = script.indexOf(opening);
        assertThat(from).as(opening).isNotNegative();
        from += opening.length();

        return script.substring(from, script.indexOf("\n" + end + "\n", from) + 1);
    }

    /** The lines of install.sh from the one that starts with {@code from} up to the one that starts with {@code to}. */
    public static String lines(String from, String to) throws IOException {
        String script = Files.readString(Path.of("install.sh"));
        int start = script.indexOf("\n" + from);
        int end = script.indexOf("\n" + to, start + 1);
        assertThat(start).as(from).isNotNegative();
        assertThat(end).as(to).isNotNegative();

        return script.substring(start + 1, end + 1);
    }

    /** The settings file a fresh install writes, comments included. */
    public static String envTemplate() throws IOException {
        return heredoc("cat > \"$ETC_DIR/env\" <<'ENV'\n", "ENV");
    }

    /** update.sh as installed, but working in {@code appDir} instead of /opt/ignite-pr-checker. */
    public static Path updateSh(Path appDir) throws IOException {
        Path script = appDir.resolve("update.sh");
        Files.writeString(script, heredoc("cat > \"$APP_DIR/update.sh\" <<'UPDATE'\n", "UPDATE")
            .replace("APP_DIR=/opt/ignite-pr-checker", "APP_DIR=" + appDir));

        return script;
    }
}
