package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * deploy.sh replaced app.jar without a copy (the only backup on prod was app.jar.bak from 1.19.0, two months old),
 * built without the tests, from whatever the tree held, and named the jar after the last tag plus "-dev". Runs the
 * script from a scratch repository against a scratch server directory: ssh and scp run locally, systemctl, install,
 * curl and sleep are stubs.
 */
class DeployScriptTest {
    @TempDir
    Path repo;

    @TempDir
    Path server;

    @TempDir
    Path bin;

    /** What the stub curl says the service answers. */
    private String answering = "1.21.0-1-gabc1234";

    private static void exec(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    private void stub(String name, String body) throws IOException {
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/usr/bin/env bash\n" + body + "\n");
        f.toFile().setExecutable(true);
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.copy(Path.of("deploy.sh"), repo.resolve("deploy.sh"));
        Files.writeString(repo.resolve("gradlew"), """
            #!/bin/sh
            printf '%s\\n' "$@" > "$(dirname "$0")/gradlew-args"
            mkdir -p "$(dirname "$0")/build/resources/main/META-INF" "$(dirname "$0")/build/libs"
            printf 'build.version=1.21.0-1-gabc1234\\n' > "$(dirname "$0")/build/resources/main/META-INF/build-info.properties"
            printf 'new jar' > "$(dirname "$0")/build/libs/ignite-pr-checker.jar"
            """);
        repo.resolve("gradlew").toFile().setExecutable(true);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/App.java"), "class App {}\n");
        Files.writeString(repo.resolve(".gitignore"), "build/\ngradlew-args\n");
        exec(repo, "git", "init", "-q");
        exec(repo, "git", "add", "-A");
        exec(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");

        Files.writeString(server.resolve("app.jar"), "old jar");
        Files.writeString(server.resolve("update-failed"), "update to v1.20.12 failed: sha256 does not match");
        Files.writeString(server.resolve("env"), "SERVER_ADDRESS=127.0.0.1\n");

        String toServer = "sed -e 's|/opt/ignite-pr-checker|" + server + "|g' -e 's|/etc/ignite-pr-checker/env|"
            + server + "/env|g'";
        stub("ssh", toServer + " | bash -s -- \"${@:5}\"");
        stub("scp", "cp \"$1\" \"" + server + "/${2##*/}\"");
        stub("install", "while [ \"$1\" != \"${1#-}\" ]; do shift 2; done; cp \"$1\" \"$2\"");
        stub("systemctl", "echo \"$*\" >> \"" + server + "/systemctl\"");
        stub("sleep", ":");
    }

    private Result deploy(String... args) throws Exception {
        stub("curl", "printf '{\"version\":\"" + answering + "\",\"uptimeSeconds\":3}'");
        ProcessBuilder pb = new ProcessBuilder(concat("bash", repo.resolve("deploy.sh").toString(), args))
            .redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.get("PATH"));
        env.put("JAVA_HOME", "/nonexistent");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);

        return new Result(p.waitFor(), out);
    }

    private static String[] concat(String a, String b, String... rest) {
        String[] all = new String[rest.length + 2];
        all[0] = a;
        all[1] = b;
        System.arraycopy(rest, 0, all, 2, rest.length);

        return all;
    }

    @Test
    void theJarItReplacesStaysForARollback() throws Exception {
        Result r = deploy();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("1.21.0-1-gabc1234 is up");
        assertThat(server.resolve("app.jar")).hasContent("new jar");
        assertThat(server.resolve("app.jar.prev")).hasContent("old jar");
        assertThat(server.resolve("app.jar.new")).doesNotExist();
        assertThat(server.resolve("update-failed")).doesNotExist();
        assertThat(server.resolve("systemctl")).hasContent("restart ignite-pr-checker");
    }

    @Test
    void theJarIsBuiltWithTheTests() throws Exception {
        deploy();

        assertThat(Files.readAllLines(repo.resolve("gradlew-args"))).containsExactly("-p", repo.toString(), "build");
    }

    @Test
    void aTreeWithChangesToTheJarIsNotShippedUnlessForced() throws Exception {
        Files.writeString(repo.resolve("src/App.java"), "class App { int x; }\n");
        Files.writeString(repo.resolve("src/New.java"), "class New {}\n");

        Result refused = deploy();

        assertThat(refused.exit()).isNotZero();
        assertThat(refused.out()).contains("uncommitted changes in what goes into the jar", "src/App.java",
            "src/New.java", "--force");
        assertThat(repo.resolve("gradlew-args")).doesNotExist();
        assertThat(server.resolve("app.jar")).hasContent("old jar");

        assertThat(deploy("--force").exit()).isZero();
        assertThat(server.resolve("app.jar")).hasContent("new jar");
    }

    @Test
    void changesOutsideTheJarDoNotStopIt() throws Exception {
        Files.writeString(repo.resolve("notes.txt"), "todo\n");

        assertThat(deploy().exit()).isZero();
    }

    @Test
    void aVersionThatDoesNotComeUpIsReportedWithTheWayBack() throws Exception {
        answering = "1.20.10-dev";

        Result r = deploy();

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("1.21.0-1-gabc1234 did not answer within 60 s (answering: 1.20.10-dev)",
            "cp -p app.jar.prev app.jar && systemctl restart ignite-pr-checker");
        assertThat(server.resolve("app.jar.prev")).hasContent("old jar");
        assertThat(List.of(Files.readString(server.resolve("systemctl")).split("\n")))
            .startsWith("restart ignite-pr-checker");
    }

    private record Result(int exit, String out) {
    }
}
