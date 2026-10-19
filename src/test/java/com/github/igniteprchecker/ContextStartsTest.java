package com.github.igniteprchecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.metrics.HeapDumpExpiry;
import com.github.igniteprchecker.persist.CacheStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
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

    @Autowired
    private ScheduledTaskHolder scheduled;

    @LocalServerPort
    private int port;

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

    /**
     * Under {@code script-src 'self'} and {@code nosniff} a browser runs a page's script only from this origin and
     * only when it is served as JavaScript; anything else leaves the page dead.
     */
    @Test
    void thePagesScriptsAreServedAsJavaScriptUnderAPolicyThatAllowsOnlyThem() {
        for (String page : List.of("/", "/flaky.html", "/status.html")) {
            ResponseEntity<String> html = http.getForEntity(page, String.class);
            assertThat(html.getHeaders().getFirst("Content-Security-Policy")).as(page).contains("script-src 'self'");
            List<String> scripts = Pattern.compile("<script src=\"(/[^\"]+)\"></script>").matcher(html.getBody())
                .results().map(m -> m.group(1)).toList();
            assertThat(scripts).as(page).contains("/theme.js", "/common.js");
            for (String script : scripts) {
                ResponseEntity<String> js = http.getForEntity(script, String.class);
                assertThat(js.getStatusCode().is2xxSuccessful()).as(script).isTrue();
                assertThat(js.getHeaders().getContentType()).as(script).hasToString("text/javascript");
            }
        }
    }

    /** A page of another site cannot change anything: the filter that refuses its requests is in place. */
    @Test
    void aPostFromAnotherSiteIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-Fetch-Site", "cross-site");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> res = http.exchange("/api/login", HttpMethod.POST,
            new HttpEntity<>("{\"token\":\"FAKEtokenFAKEtoken0123456789\"}", headers), String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    /**
     * Tomcat and Spring read {@code /api;x/login} and {@code /%61pi/login} as {@code /api/login}: a filter that looked
     * at the raw path let such a POST from another site reach the login, and the options with the visitor's cookie.
     */
    @Test
    void aPostFromAnotherSiteIsRefusedWhateverItsPathLooksLike() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<String> paths = List.of("/api;x/login", "/%61pi/login", "/api/login;x=1",
            "/api;x/auto-visa-all?visa=true");
        for (String path : paths) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Sec-Fetch-Site", "same-site").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"FAKEtokenFAKEtoken0123456789\"}")).build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).as(path).isEqualTo(403);
        }
    }

    /**
     * A heap dump and the tokens in old copies of the state go on time only while a job looks for them every 10
     * minutes: without the job they stay on disk, and nothing else fails.
     */
    @Test
    void oldDumpsAndTokensInCopiesAreLookedForEveryTenMinutes() {
        Map<String, Duration> every = new HashMap<>();
        for (ScheduledTask task : scheduled.getScheduledTasks()) {
            if (task.getTask() instanceof FixedDelayTask t)
                every.put(t.toString(), t.getIntervalDuration());
        }

        assertThat(every).containsEntry(HeapDumpExpiry.class.getName() + ".deleteExpired", Duration.ofMinutes(10))
            .containsEntry(CacheStore.class.getName() + ".removeOldTokens", Duration.ofMinutes(10));
    }

    /**
     * The status page counts down to the first standing sweep from the time /api/status gives, and says "never" in
     * amber a minute after it: a time off from the scheduler's would call a sweep late that is not.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFirstSweepIsDueWhenTheSchedulerRunsIt() {
        Map<String, Object> watcher = (Map<String, Object>)http.exchange("/api/status", HttpMethod.GET, null,
            new ParameterizedTypeReference<Map<String, Object>>() { }).getBody().get("watcher");
        ScheduledTask sweep = scheduled.getScheduledTasks().stream()
            .filter(t -> t.getTask().toString().equals(StandingVisas.class.getName() + ".sweep")).findFirst()
            .orElseThrow();

        assertThat(((Number)watcher.get("standingFirstSweepAt")).longValue())
            .isCloseTo(sweep.nextExecution().toEpochMilli(), within(1000L));
        assertThat((Map<String, Object>)watcher.get("suiteFix")).containsEntry("firstSweepAt", 0);
    }

    /** Warming is off here: a first cycle due would turn the warmer card amber for a cycle that never runs. */
    @Test
    @SuppressWarnings("unchecked")
    void noWarmCycleIsDueWithWarmingOff() {
        Map<String, Object> app = (Map<String, Object>)http.exchange("/api/status", HttpMethod.GET, null,
            new ParameterizedTypeReference<Map<String, Object>>() { }).getBody().get("app");

        assertThat(app).containsEntry("firstCycleAt", 0);
    }

    /** How long a user's tokens have left is theirs to see: without a login the answer is a 401. */
    @Test
    void theExpiryOfTokensNeedsALogin() {
        assertThat(http.getForEntity("/api/token-expiry?service=teamcity", String.class).getStatusCode().value())
            .isEqualTo(401);
    }

    /** The language is the account's: without a login, choosing one is a 401, not an error of a missing user. */
    @Test
    void choosingALanguageNeedsALogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThat(http.postForEntity("/api/language", new HttpEntity<>("{\"lang\":\"ru\"}", headers), String.class)
            .getStatusCode().value()).isEqualTo(401);
    }

    /** Misspelt where a job reads it, automation.enabled would leave a local run sweeping and polling prod's PRs. */
    @Test
    void automationOffReachesTheJobs() {
        assertThat(ReflectionTestUtils.getField(standing, "automation")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(commands, "automation")).isEqualTo(false);
    }
}
