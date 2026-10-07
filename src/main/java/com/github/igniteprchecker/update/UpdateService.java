package com.github.igniteprchecker.update;

import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * In-app half of the self-update: it knows the running version, checks the project's latest GitHub release, and, on
 * request, writes the release it means into {@code update/requested} beside the jar and exits. Before the next start
 * {@code update.sh}, run by systemd as root, installs exactly that release if its sha256 matches the one GitHub
 * records, keeping the old jar as {@code app.jar.prev}, so the service account can ask for an update but cannot
 * replace the code it runs. A failure keeps the running jar and leaves its reason in {@code update-failed}. An install
 * older than {@code update/} has {@code run.sh} fetch the jar, on the marker {@code .update-requested}.
 */
@Service
public class UpdateService {
    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final String LEGACY_MARKER = ".update-requested";

    private static final String REQUESTS = "update";

    private static final String REQUEST = "requested";

    private static final String FAILED = "update-failed";

    /**
     * EX_TEMPFAIL: the unit counts it as a success and restarts on it anyway, so a restart asked for from the
     * status page is not logged as a failure. An older unit restarts on it as on any failure.
     */
    public static final int RESTART_EXIT_CODE = 75;

    private final UpdateProperties props;
    private final GithubClient github;
    private final String currentVersion;

    /** The commit the running jar was built from; null when the build did not know it. */
    private final String commit;

    private final IntConsumer exit;

    @Autowired
    public UpdateService(UpdateProperties props, GithubClient github, ObjectProvider<BuildProperties> buildProps) {
        this(props, github, buildProps, System::exit);
    }

    UpdateService(UpdateProperties props, GithubClient github, ObjectProvider<BuildProperties> buildProps,
        IntConsumer exit) {
        this.props = props;
        this.github = github;
        BuildProperties bp = buildProps.getIfAvailable();
        this.currentVersion = bp != null && bp.getVersion() != null ? bp.getVersion() : "dev";
        this.commit = bp != null ? bp.get("commit") : null;
        this.exit = exit;
    }

    public Status status() {
        String latest = github.latestReleaseTag();
        boolean available = props.enabled()
            && latest != null && !latest.isBlank()
            && isNewer(latest, baseVersion());

        return new Status(currentVersion, commit, latest, available, notesUrl(latest, baseVersion()), lastFailure());
    }

    /**
     * The notes of {@code latest} when it is the patch release right after {@code running}; otherwise releases may lie
     * between them, each with its own notes (a change of the verdict rules among them), so the list of all releases.
     */
    static String notesUrl(String latest, String running) {
        if (latest == null || latest.isBlank())
            return null;

        String releases = "https://github.com/" + GithubClient.SELF_REPO + "/releases";

        return isNextPatch(latest, running) ? releases + "/tag/v" + latest : releases;
    }

    private static boolean isNextPatch(String latest, String running) {
        String[] pl = latest.split("\\."), pr = running.split("\\.");
        if (pl.length != 3 || pr.length < 3)
            return false;

        return numeric(pl[0]) == numeric(pr[0]) && numeric(pl[1]) == numeric(pr[1])
            && numeric(pl[2]) == numeric(pr[2]) + 1;
    }

    /**
     * Why the last requested update did not happen, as update.sh wrote it: the release, when (UTC) and the reason;
     * null when there is none or the release it names is the one running now.
     */
    private Failure lastFailure() {
        Path file = jarDir().resolve(FAILED);
        try {
            List<String> parts = List.of(Files.readString(file).strip().split("\t", 3));
            if (parts.size() < 3 || parts.get(0).equals(baseVersion()))
                return null;

            return new Failure(parts.get(0), Instant.parse(parts.get(1)).toEpochMilli(), parts.get(2));
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Path jarDir() {
        return Path.of(props.jarPath()).toAbsolutePath().getParent();
    }

    /** True if version {@code a} is strictly newer than {@code b} (dot-separated numeric compare). Avoids
     * offering an older release as an "update" when a dev build's version happens to differ from it. */
    static boolean isNewer(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? numeric(pa[i]) : 0;
            int nb = i < pb.length ? numeric(pb[i]) : 0;
            if (na != nb)
                return na > nb;
        }
        return false;
    }

    private static int numeric(String part) {
        String digits = part.replaceAll("\\D.*$", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /**
     * Asks for the latest release in {@code update/requested} (the legacy marker on an older install) and restarts;
     * update.sh installs it before the next start.
     */
    public synchronized void performUpdate() throws IOException {
        Status status = status();
        if (!status.updateAvailable())
            throw new IllegalStateException("no update available (current " + currentVersion + ", latest " + status.latest() + ")");

        Path requests = jarDir().resolve(REQUESTS);
        Files.writeString(Files.isDirectory(requests) ? requests.resolve(REQUEST) : jarDir().resolve(LEGACY_MARKER),
            status.latest());

        log.info("update to {} requested; restarting so it is installed before the next start", status.latest());
        scheduleRestart();
    }

    /** Plain restart without an update: exits so that systemd relaunches the current jar. */
    public void restart() {
        log.info("service restart requested from the status page");
        scheduleRestart();
    }

    private String baseVersion() {
        return currentVersion.replace("-SNAPSHOT", "");
    }

    private void scheduleRestart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000); // let the HTTP response flush to the client first
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exit.accept(RESTART_EXIT_CODE);
        }, "self-update-restart");
        t.setDaemon(false);
        t.start();
    }

    /**
     * {@code current} runs, built from {@code commit} (null if unknown); {@code latest} is the newest release and
     * {@code notesUrl} the notes that say what changes for users on the way to it; {@code updateFailed} says why the
     * last update to another release did not happen, or is null.
     */
    public record Status(String current, String commit, String latest, boolean updateAvailable, String notesUrl,
        Failure updateFailed) {
    }

    /** An update to {@code version} that did not happen at {@code at} (epoch ms), and why. */
    public record Failure(String version, long at, String reason) {
    }
}
