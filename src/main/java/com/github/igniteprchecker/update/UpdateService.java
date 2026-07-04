package com.github.igniteprchecker.update;

import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * In-app half of the self-update: it knows the running version, checks the project's latest GitHub
 * release, and — on request — drops an {@code .update-requested} marker next to the jar and exits.
 * The startup wrapper ({@code run.sh}) is what actually downloads the new jar and launches it, so the
 * fragile part (replacing the jar) happens in a simple shell step at boot, not inside the running JVM.
 */
@Service
public class UpdateService {
    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final String MARKER = ".update-requested";

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

    /** Requests the update (marker for run.sh) and restarts; run.sh fetches the jar on the next boot. */
    public synchronized void performUpdate() throws IOException {
        Status status = status();
        if (!status.updateAvailable())
            throw new IllegalStateException("no update available (current " + currentVersion + ", latest " + status.latest() + ")");

        Files.writeString(Path.of(props.jarPath()).resolveSibling(MARKER), status.latest());

        log.info("update to {} requested; restarting so run.sh can fetch it", status.latest());
        scheduleRestart();
    }

    private String baseVersion() {
        return currentVersion.replace("-SNAPSHOT", "");
    }

    private static void scheduleRestart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000); // let the HTTP response flush to the client first
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Non-zero exit -> systemd (Restart=on-failure) reruns run.sh, which fetches + launches the new jar.
            System.exit(1);
        }, "self-update-restart");
        t.setDaemon(false);
        t.start();
    }

    public record Status(String current, String latest, boolean updateAvailable) {
    }
}
