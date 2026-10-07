package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.update.UpdateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogFile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * install.sh writes the systemd unit and run.sh on every run. Without SuccessExitStatus every deploy left "Failed
 * with result 'exit-code'" in the journal, and so did each restart from the status page. The JVM flags were fixed in
 * run.sh, which the next install overwrites, so memory could not be tuned in the env file that installs keep. The
 * service logged to the journal only, which on prod keeps about 40 hours: a complaint from last week needed zgrep
 * through syslog. run.sh, app.jar and their directory belonged to the service account, so a hole in the service could
 * rewrite what starts next. Any java on the PATH was taken, and on Java 11 the service failed at start for ever.
 */
class InstallScriptTest {
    private static String heredoc(String opening, String end) throws IOException {
        return InstallScript.heredoc(opening, end);
    }

    @Test
    void stopsAndAskedForRestartsAreNotFailures() throws IOException {
        String unit = heredoc("cat > \"/etc/systemd/system/${SERVICE}.service\" <<UNIT\n", "UNIT");

        assertThat(unit).contains("Restart=on-failure\n")
            .contains("SuccessExitStatus=143 " + UpdateService.RESTART_EXIT_CODE + "\n")
            .contains("RestartForceExitStatus=" + UpdateService.RESTART_EXIT_CODE + "\n");
    }

    @Test
    void theServiceKeepsAMonthOfItsOwnLog() throws IOException {
        String unit = heredoc("cat > \"/etc/systemd/system/${SERVICE}.service\" <<UNIT\n", "UNIT");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("unit", Map.of("PRC_LOG_FILE",
            "/opt/ignite-pr-checker/logs/ignite-pr-checker.log")));
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application.yml",
            new FileSystemResource("src/main/resources/application.yml"));
        yml.forEach(env.getPropertySources()::addLast);

        assertThat(unit).contains("Environment=PRC_LOG_FILE=${APP_DIR}/logs/ignite-pr-checker.log\n");
        assertThat(Files.readString(Path.of("install.sh")))
            .contains("install -d -o prc  -g prc  -m 750 \"$APP_DIR/logs\"\n");
        assertThat(env.getProperty(LogFile.FILE_NAME_PROPERTY))
            .isEqualTo("/opt/ignite-pr-checker/logs/ignite-pr-checker.log");
        assertThat(env.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("30");
    }

    @Test
    void theServiceCannotChangeWhatItRuns() throws IOException {
        String install = Files.readString(Path.of("install.sh"));
        String unit = heredoc("cat > \"/etc/systemd/system/${SERVICE}.service\" <<UNIT\n", "UNIT");

        assertThat(install).contains("install -d -o root -g root -m 755 \"$APP_DIR\"\n")
            .contains("chown root:root \"$APP_DIR/run.sh\"\n").contains("chown root:root \"$APP_DIR/update.sh\"\n")
            .doesNotContain("chown prc");
        assertThat(unit).contains("User=prc\n").contains("ExecStartPre=+${APP_DIR}/update.sh\n")
            .contains("ExecStart=${APP_DIR}/run.sh\n");
    }

    /**
     * Run again over an install whose directory was the service account's, install.sh wrote update.sh through the link
     * a hole in the service could have left there, into a file in cache/ that the service account then rewrote and
     * systemd ran as root; a link in place of update/ had install -d give its target to prc. Runs the steps that set up
     * the directories, update.sh and run.sh in a scratch directory, with stubs for what needs root.
     */
    @Test
    void whatTheServiceAccountLeftIsReplacedNotFollowed(@TempDir Path dir) throws Exception {
        Path app = Files.createDirectories(dir.resolve("app"));
        Path cache = Files.createDirectories(app.resolve("cache"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(cache.resolve("u.sh"), "held by prc");
        Files.writeString(cache.resolve("r.sh"), "held by prc");
        Files.createSymbolicLink(app.resolve("update.sh"), cache.resolve("u.sh"));
        Files.createSymbolicLink(app.resolve("run.sh"), cache.resolve("r.sh"));
        Files.createSymbolicLink(app.resolve("update"), elsewhere);
        Files.writeString(app.resolve("app.jar.new"), "left by the old run.sh");

        Path bin = Files.createDirectories(dir.resolve("bin"));
        stub(bin, "id", "exit 0");
        stub(bin, "chown", "exit 0");
        stub(bin, "install", "for last; do :; done; mkdir -p \"$last\"");
        String steps = "set -euo pipefail\nAPP_DIR=" + app + "\nETC_DIR=" + dir.resolve("etc") + "\nlog() { :; }\n"
            + InstallScript.lines("# 2. ", "# 5. ") + InstallScript.lines("# 6. ", "# 7. ");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", steps).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + pb.environment().get("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);

        assertThat(p.waitFor()).as(out).isZero();
        assertThat(Files.isSymbolicLink(app.resolve("update.sh"))).isFalse();
        assertThat(Files.isSymbolicLink(app.resolve("run.sh"))).isFalse();
        assertThat(app.resolve("update.sh")).content().contains("REQUEST=\"$APP_DIR/update/requested\"");
        assertThat(app.resolve("run.sh")).content().contains("exec \"${PRC_JAVA:-java}\"");
        assertThat(cache.resolve("u.sh")).hasContent("held by prc");
        assertThat(cache.resolve("r.sh")).hasContent("held by prc");
        assertThat(Files.isSymbolicLink(app.resolve("update"))).isFalse();
        assertThat(app.resolve("update")).isDirectory();
        assertThat(app.resolve("app.jar.new")).doesNotExist();
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/usr/bin/env bash\n" + body + "\n");
        f.toFile().setExecutable(true);
    }

    @Test
    void onlyJava17OrNewerIsTaken(@TempDir Path dir) throws Exception {
        String install = Files.readString(Path.of("install.sh"));
        int from = install.indexOf("java_major() {");
        String functions = install.substring(from, install.indexOf("\n}\n", install.indexOf("java_ok() {")) + 3);

        Map<String, Boolean> taken = new LinkedHashMap<>();
        for (String version : List.of("openjdk version \"1.8.0_382\"", "openjdk version \"11.0.20\" 2023-07-18",
            "openjdk version \"17.0.8\" 2023-07-18", "openjdk version \"21\" 2023-09-19", "java version \"17-ea\"")) {
            Path java = dir.resolve("java");
            Files.writeString(java, "#!/bin/sh\necho '" + version + "' >&2\n");
            java.toFile().setExecutable(true);
            Process p = new ProcessBuilder("bash", "-c", functions + "java_ok " + java).start();
            taken.put(version, p.waitFor() == 0);
        }

        assertThat(taken).containsExactly(Map.entry("openjdk version \"1.8.0_382\"", false),
            Map.entry("openjdk version \"11.0.20\" 2023-07-18", false),
            Map.entry("openjdk version \"17.0.8\" 2023-07-18", true),
            Map.entry("openjdk version \"21\" 2023-09-19", true), Map.entry("java version \"17-ea\"", true));
        assertThat(heredoc("cat > \"/etc/systemd/system/${SERVICE}.service\" <<UNIT\n", "UNIT"))
            .contains("Environment=PRC_JAVA=${JAVA}\n");
    }

    /** run.sh as installed, but under {@code dir}; the unit's PRC_JAVA names a java that writes down its arguments. */
    private static Path runSh(Path dir) throws IOException {
        String run = heredoc("cat > \"$APP_DIR/run.sh\" <<'RUN'\n", "RUN")
            .replace("APP_DIR=/opt/ignite-pr-checker", "APP_DIR=" + dir);
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
        pb.environment().put("PRC_JAVA", dir.resolve("java").toString());
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
