package com.github.igniteprchecker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * No test started the application, so a bean Spring could not wire (two constructors marked for injection, a missing
 * bean, a bad property) was first seen on prod, where the service failed at start and systemd restarted it over and
 * over. Runs the whole context with snapshots, warming, automation and self-update off and every outside service on a
 * closed local port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "persist.enabled=false",
    "warm.enabled=false",
    "automation.enabled=false",
    "update.enabled=false",
    "server.address=127.0.0.1",
    "session.secret=context-test",
    "teamcity.base-url=http://127.0.0.1:9/",
    "github.api-url=http://127.0.0.1:9",
    "jira.base-url=http://127.0.0.1:9",
})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class ContextStartsTest {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private BuildProperties build;

    @Autowired
    private StandingVisas standing;

    @Autowired
    private PrCommands commands;

    @Test
    void theServiceStartsAndAnswers() {
        Map<String, Object> status = http.exchange("/api/status", HttpMethod.GET, null,
            new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();

        assertThat(status).containsEntry("version", build.getVersion()).containsEntry("commit", build.get("commit"))
            .containsEntry("signedIn", false);
        assertThat(http.getForEntity("/api/version", String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(http.getForEntity("/", String.class).getBody()).contains("Ignite PR Checker");
    }

    @Test
    void theStartIsLoggedWithTheSettingsInEffect(CapturedOutput output) {
        assertThat(output.getAll()).contains("effective config: SERVER_ADDRESS=127.0.0.1; ")
            .contains("TC_BASE_URL=http://127.0.0.1:9/").contains("SESSION_SECRET=set")
            .doesNotContain("context-test");
    }

    /** Misspelt where a job reads it, automation.enabled would leave a local run sweeping and polling prod's PRs. */
    @Test
    void automationOffReachesTheJobs() {
        assertThat(ReflectionTestUtils.getField(standing, "automation")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(commands, "automation")).isEqualTo(false);
    }
}
