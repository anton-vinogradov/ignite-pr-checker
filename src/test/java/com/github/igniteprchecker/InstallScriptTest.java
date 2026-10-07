package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.update.UpdateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * install.sh writes the systemd unit and run.sh on every run. Without SuccessExitStatus every deploy left "Failed
 * with result 'exit-code'" in the journal, and so did each restart from the status page. The JVM flags were fixed in
 * run.sh, which the next install overwrites, so memory could not be tuned in the env file that installs keep.
 */
class InstallScriptTest {
    private static String heredoc(String opening, String end) throws IOException {
        String script = Files.readString(Path.of("install.sh"));
        int from = script.indexOf(opening);
        assertThat(from).as(opening).isNotNegative();
        from += opening.length();

        return script.substring(from, script.indexOf("\n" + end + "\n", from) + 1);
    }

    @Test
    void stopsAndAskedForRestartsAreNotFailures() throws IOException {
        String unit = heredoc("cat > \"/etc/systemd/system/${SERVICE}.service\" <<UNIT\n", "UNIT");

        assertThat(unit).contains("Restart=on-failure\n")
            .contains("SuccessExitStatus=143 " + UpdateService.RESTART_EXIT_CODE + "\n")
            .contains("RestartForceExitStatus=" + UpdateService.RESTART_EXIT_CODE + "\n");
    }

    /** run.sh as installed, but under {@code dir}, launching a java that writes down its arguments. */
    private static Path runSh(Path dir) throws IOException {
        String run = heredoc("cat > \"$APP_DIR/run.sh\" <<'RUN'\n", "RUN")
            .replace("APP_DIR=/opt/ignite-pr-checker", "APP_DIR=" + dir)
            .replace("/usr/bin/java", dir.resolve("java").toString());
        Files.writeString(dir.resolve("java"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"" + dir.resolve("java-args")
            + "\"\n");
        dir.resolve("java").toFile().setExecutable(true);
        Files.writeString(dir.resolve("app.jar"), "PK");

        Path script = dir.resolve("run.sh");
        Files.writeString(script, run);

        return script;
    }

    private static List<String> launch(Path dir, String javaOpts) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", runSh(dir).toString()).redirectErrorStream(true);
        pb.environment().remove("JAVA_OPTS");
        if (javaOpts != null)
            pb.environment().put("JAVA_OPTS", javaOpts);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);

        assertThat(p.waitFor()).as(out).isZero();

        return Files.readAllLines(dir.resolve("java-args"));
    }

    @Test
    void javaOptsFromTheEnvFileComeAfterTheDefaults(@TempDir Path dir) throws Exception {
        String dumps = "-XX:HeapDumpPath=" + dir.resolve("dumps");

        assertThat(launch(dir, "-Xmx768m -XX:+UseSerialGC")).containsExactly("-Xmx512m",
            "-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError", dumps, "-Xmx768m", "-XX:+UseSerialGC",
            "-jar", dir.resolve("app.jar").toString());
        assertThat(launch(dir, null)).containsExactly("-Xmx512m", "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError", dumps, "-jar", dir.resolve("app.jar").toString());
    }

    @Test
    void onlyTheNewestHeapDumpIsKept(@TempDir Path dir) throws Exception {
        Path dumps = Files.createDirectories(dir.resolve("dumps"));
        Files.writeString(dumps.resolve("java_pid101.hprof"), "older");
        Files.setLastModifiedTime(dumps.resolve("java_pid101.hprof"), FileTime.fromMillis(1_700_000_000_000L));
        Files.writeString(dumps.resolve("java_pid202.hprof"), "newer");

        launch(dir, null);

        try (Stream<Path> left = Files.list(dumps)) {
            assertThat(left.map(f -> f.getFileName().toString())).containsExactly("java_pid202.hprof");
        }
    }
}
