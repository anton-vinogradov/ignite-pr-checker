package com.github.igniteprchecker.mirror;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.web.GitHttpController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GitHttpReadinessTest {
    @TempDir
    Path directory;

    /** HTTP readers cannot start a mirror bootstrap; disabled mirrors return 404 and initializing mirrors 503. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesUntilBackgroundSyncIsReady(boolean enabled) throws Exception {
        GitMirror mirror = new GitMirror(new GitMirrorProperties(enabled, directory.toString(), 60L, 30L),
            Map.of("ignite", new GitMirror.Sources("unused", "unused")));
        GitHttpController controller = new GitHttpController(mirror);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/git/ignite.git/info/refs");
        request.setQueryString("service=git-upload-pack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            controller.refs("ignite", request, response);

            assertThat(response.getStatus()).isEqualTo(enabled ? 503 : 404);
            if (enabled)
                assertThat(response.getHeader("Retry-After")).isEqualTo("10");
            try (var files = Files.list(directory)) {
                assertThat(files).isEmpty();
            }
        }
        finally {
            controller.close();
        }
    }
}
