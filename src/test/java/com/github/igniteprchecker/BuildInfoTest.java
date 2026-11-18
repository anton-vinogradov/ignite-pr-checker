package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Prod ran "1.20.10-dev": a local build named after the last tag plus "-dev", whose jar matched no release and whose
 * build-info had no commit, so nothing told which code was running.
 */
class BuildInfoTest {
    private static String git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8).trim();
        assertThat(p.waitFor()).as(out).isZero();

        return out;
    }

    private static Properties buildInfo() throws IOException {
        Properties info = new Properties();
        try (InputStream in = BuildInfoTest.class.getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(in).as("build-info.properties on the classpath").isNotNull();
            info.load(in);
        }

        return info;
    }

    @Test
    void theBuildNamesItsCommitAndWhetherTheTreeHadChanges() throws Exception {
        Properties info = buildInfo();
        boolean dirty = !git("status", "--porcelain", "--", "src", "build.gradle", "settings.gradle", "gradle")
            .isEmpty();

        assertThat(info.getProperty("build.commit")).isEqualTo(git("rev-parse", "HEAD"));
        assertThat(info.getProperty("build.dirty")).isEqualTo(String.valueOf(dirty));
    }

    /** Release-based versions ignore Deploy tags; a Deploy build adds its exact Actions run number. */
    @Test
    void aBuildThatIsNoReleaseSaysWhereItStands() throws Exception {
        String version = buildInfo().getProperty("build.version");
        String described = git("describe", "--tags", "--match", "v[0-9]*", "--always").replaceFirst("^v", "");
        boolean dirty = Boolean.parseBoolean(buildInfo().getProperty("build.dirty"));

        String deployNumber = System.getenv("PRC_DEPLOY_NUMBER");
        if (deployNumber != null && !deployNumber.isBlank()) {
            assertThat(deployNumber).matches("[1-9][0-9]*");
            String suffix = "-deploy." + deployNumber;
            assertThat(version).endsWith(suffix);
            version = version.substring(0, version.length() - suffix.length());
        }

        assertThat(version).satisfiesAnyOf(
            v -> assertThat(v).matches("\\d+\\.\\d+\\.\\d+"),
            v -> assertThat(v).isEqualTo(described + (dirty ? "-dirty" : "")));
        assertThat(version).doesNotEndWith("-dev").isNotEqualTo("0.0.0-SNAPSHOT");
    }
}
