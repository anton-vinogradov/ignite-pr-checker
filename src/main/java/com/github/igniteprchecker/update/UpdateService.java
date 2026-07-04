package com.github.igniteprchecker.update;

import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * In-app self-update: checks the project's latest GitHub release and, on request, downloads the new
 * jar over the running one and restarts. The restart relies on systemd relaunching the service when
 * the process exits ({@code Restart=on-failure}) — no shell access or sudo is needed, only that the
 * running user owns the jar (it does under the standard install).
 */
@Service
public class UpdateService {
    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final long MIN_JAR_BYTES = 1_000_000;

    private final UpdateProperties props;
    private final GithubClient github;
    private final String currentVersion;

    public UpdateService(UpdateProperties props, GithubClient github, ObjectProvider<BuildProperties> buildProps) {
        this.props = props;
        this.github = github;
        BuildProperties bp = buildProps.getIfAvailable();
        this.currentVersion = bp != null && bp.getVersion() != null ? bp.getVersion() : "dev";
    }

    public Status status() {
        String latest = github.latestReleaseTag();
        boolean available = props.enabled()
            && latest != null && !latest.isBlank()
            && !latest.equals(baseVersion());

        return new Status(currentVersion, latest, available);
    }

    /** Downloads the latest release jar over the running one and schedules a restart. */
    public synchronized void performUpdate() throws IOException, InterruptedException {
        Status status = status();
        if (!status.updateAvailable())
            throw new IllegalStateException("no update available (current " + currentVersion + ", latest " + status.latest() + ")");

        Path jar = Path.of(props.jarPath());
        Path dir = jar.toAbsolutePath().getParent();
        Path tmp = dir.resolve("app.jar.new");

        download("https://github.com/" + props.repo() + "/releases/latest/download/ignite-pr-checker.jar", tmp);

        if (Files.size(tmp) < MIN_JAR_BYTES || !isJar(tmp)) {
            Files.deleteIfExists(tmp);
            throw new IOException("downloaded file is not a valid jar");
        }

        Files.copy(jar, dir.resolve("app.jar.bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);

        log.info("update to {} downloaded; restarting", status.latest());
        scheduleRestart();
    }

    private String baseVersion() {
        return currentVersion.replace("-SNAPSHOT", "");
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

        HttpResponse<Path> resp = client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(target));

        if (resp.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("download failed, HTTP " + resp.statusCode());
        }
    }

    /** A jar/zip starts with the local-file-header magic "PK\03\04". */
    private static boolean isJar(Path file) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.read(head) != 4)
                return false;
        }
        return head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
    }

    private static void scheduleRestart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000); // let the HTTP response flush to the client first
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Non-zero exit -> systemd (Restart=on-failure) relaunches the service with the new jar.
            System.exit(1);
        }, "self-update-restart");
        t.setDaemon(false);
        t.start();
    }

    public record Status(String current, String latest, boolean updateAvailable) {
    }
}
