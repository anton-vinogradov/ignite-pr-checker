package com.github.igniteprchecker.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

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
}
