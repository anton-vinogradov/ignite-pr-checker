package com.github.igniteprchecker.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class UpdateServiceTest {
    @Test
    void offersStrictlyNewerReleases() {
        assertTrue(UpdateService.isNewer("0.1.1", "0.1.0"));
        assertTrue(UpdateService.isNewer("0.2.0", "0.1.9"));
        assertTrue(UpdateService.isNewer("1.0", "0.9.9"));
    }

    @Test
    void doesNotOfferSameOrOlderReleases() {
        assertFalse(UpdateService.isNewer("0.1.1", "0.1.1"));
        assertFalse(UpdateService.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateService.isNewer("0.1.1", "0.1.2")); // dev build already ahead of the last release
    }

    /**
     * A restart or update from the status page exited with 1, which systemd logs as "Failed with result
     * 'exit-code'". The unit counts this code as a success and restarts on it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRestartOrUpdateFromTheStatusPageExitsWithTheCodeTheUnitRestartsOn(@TempDir Path dir) throws Exception {
        GithubClient github = mock(GithubClient.class);
        when(github.latestReleaseTag()).thenReturn("1.20.12");
        BlockingQueue<Integer> exits = new LinkedBlockingQueue<>();
        UpdateService update = new UpdateService(new UpdateProperties(true, dir.resolve("app.jar").toString()),
            github, mock(ObjectProvider.class), exits::add);

        update.restart();
        assertThat(exits.poll(10, TimeUnit.SECONDS)).isEqualTo(UpdateService.RESTART_EXIT_CODE);

        update.performUpdate();
        assertThat(exits.poll(10, TimeUnit.SECONDS)).isEqualTo(UpdateService.RESTART_EXIT_CODE);
        assertThat(dir.resolve(".update-requested")).hasContent("1.20.12");
    }

    /** Whoever presses Update reads first what the release changes for users. */
    @Test
    void theOfferedReleaseComesWithItsNotes() {
        assertThat(notesOffered("1.21.0", "1.21.1"))
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases/tag/v1.21.1");
        assertThat(notesOffered("1.21.0-3-g5f2c9e1-dirty", "1.21.1"))
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases/tag/v1.21.1");
    }

    /**
     * v1.20.1 changed the verdict rules and v1.20.2 did not: on v1.20.0 the link led to the notes of v1.20.2 alone,
     * which say nothing of the rules.
     */
    @Test
    void releasesThatMayLieBetweenAreNotLeftOut() {
        assertThat(notesOffered("1.20.0", "1.20.2"))
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases");
        assertThat(notesOffered("1.20.9", "1.21.0"))
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases");
        assertThat(notesOffered(null, "1.21.1"))
            .isEqualTo("https://github.com/anton-vinogradov/ignite-pr-checker/releases");
    }

    /** The notes link the running {@code version} (null: a build without build-info) gets for {@code latest}. */
    @SuppressWarnings("unchecked")
    private static String notesOffered(String version, String latest) {
        GithubClient github = mock(GithubClient.class);
        when(github.latestReleaseTag()).thenReturn(latest);
        ObjectProvider<BuildProperties> build = mock(ObjectProvider.class);
        if (version != null) {
            Properties info = new Properties();
            info.setProperty("version", version);
            when(build.getIfAvailable()).thenReturn(new BuildProperties(info));
        }
        UpdateService update = new UpdateService(new UpdateProperties(true, "/opt/ignite-pr-checker/app.jar"), github,
            build, code -> { });

        return update.status().notesUrl();
    }
}
