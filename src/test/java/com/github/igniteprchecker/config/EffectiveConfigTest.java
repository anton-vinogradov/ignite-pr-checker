package com.github.igniteprchecker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * ANALYSIS_CONCURRENCY=0 quietly became 12 though application.yml says 8, and an unset cache TTL became 15 minutes
 * where the yml says 120. Nothing printed the settings in effect, so a typo in the env file went unnoticed, and a
 * fresh install with an empty APP_PUBLIC_URL or no SERVER_ADDRESS gave no sign of either.
 */
@ExtendWith(OutputCaptureExtension.class)
class EffectiveConfigTest {
    /** How install.sh's env file has it once the operator filled it in. */
    private static final Map<String, Object> INSTALLED = Map.of("SERVER_ADDRESS", "127.0.0.1",
        "APP_PUBLIC_URL", "https://prc.example.org", "SESSION_COOKIE_SECURE", "true",
        "SESSION_SECRET", "kq3v1n8Zp0", "GITHUB_TOKEN", "ghp_n0tReallyAToken");

    /** application.yml under the given environment variables, as Spring reads them; nothing from this machine. */
    private static ConfigurableEnvironment env(Map<String, Object> vars) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource("vars", vars));
        new YamlPropertySourceLoader().load("application.yml",
            new FileSystemResource("src/main/resources/application.yml")).forEach(sources::addLast);
        ConfigurationPropertySources.attach(env);

        return env;
    }

    private static EffectiveConfig config(Map<String, Object> vars) throws IOException {
        ConfigurableEnvironment env = env(vars);
        Binder binder = Binder.get(env);

        return new EffectiveConfig(env, binder.bindOrCreate("teamcity", TeamcityProperties.class),
            binder.bindOrCreate("github", GithubProperties.class),
            binder.bindOrCreate("analysis", AnalysisProperties.class),
            binder.bindOrCreate("persist", PersistProperties.class),
            binder.bindOrCreate("warm", WarmProperties.class),
            binder.bindOrCreate("update", UpdateProperties.class),
            binder.bindOrCreate("admin", AdminProperties.class),
            binder.bindOrCreate("session", SessionProperties.class));
    }

    @Test
    void aNumberOutOfRangeIsReportedNotQuietlyReplaced() throws IOException {
        EffectiveConfig config = config(Map.of("SERVER_ADDRESS", "127.0.0.1", "ANALYSIS_CONCURRENCY", "0",
            "WARM_COUNT", "-5"));

        assertThat(config.settings()).containsEntry("ANALYSIS_CONCURRENCY", "8").containsEntry("WARM_COUNT", "50");
        assertThat(config.problems()).containsExactly("ANALYSIS_CONCURRENCY=0 is out of range; using 8",
            "WARM_COUNT=-5 is out of range; using 50");
    }

    @Test
    void theFallbacksAreTheDefaultsOfApplicationYml() throws IOException {
        Binder yml = Binder.get(env(Map.of()));

        assertThat(yml.bindOrCreate("analysis", AnalysisProperties.class))
            .isEqualTo(new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null));
        assertThat(yml.bindOrCreate("warm", WarmProperties.class))
            .isEqualTo(new WarmProperties(null, null, null, null));
        assertThat(yml.bindOrCreate("persist", PersistProperties.class))
            .isEqualTo(new PersistProperties(null, null, null));
        assertThat(yml.bindOrCreate("github", GithubProperties.class))
            .isEqualTo(new GithubProperties(null, "", null));
    }

    @Test
    void aFreshInstallIsToldWhatItLeftOpen() throws IOException {
        EffectiveConfig config = config(Map.of("APP_PUBLIC_URL", ""));

        assertThat(config.settings()).containsEntry("SERVER_ADDRESS", "not set (all interfaces)")
            .containsEntry("APP_PUBLIC_URL", "empty");
        assertThat(config.problems()).containsExactly(
            "SERVER_ADDRESS is not set: port 8080 takes plain HTTP on every network interface — set it to 127.0.0.1"
                + " behind an HTTPS proxy, or to 0.0.0.0 if that is meant",
            "APP_PUBLIC_URL is empty: links this instance posts to PRs and JIRA lead nowhere — set it to the https"
                + " address users open");
        assertThat(config(INSTALLED).problems()).isEmpty();
    }

    @Test
    void theStartupLineNamesEverySettingButNoSecret(CapturedOutput output) throws IOException {
        EffectiveConfig config = config(INSTALLED);

        config.report();

        assertThat(config.settings()).containsEntry("APP_PUBLIC_URL", "https://prc.example.org")
            .containsEntry("TC_BASE_URL", "https://ci2.ignite.apache.org/")
            .containsEntry("JIRA_BASE_URL", "https://issues.apache.org/jira")
            .containsEntry("MASTER_HISTORY_DEPTH", "100")
            .containsEntry("SESSION_COOKIE_SECURE", "true")
            .containsEntry("GITHUB_TOKEN", "set")
            .containsEntry("SESSION_SECRET", "set")
            .containsEntry("PRC_LOG_FILE", "not set (console only)");
        assertThat(output.getOut()).contains("effective config: SERVER_ADDRESS=127.0.0.1; SERVER_PORT=8080; ")
            .doesNotContain("ghp_n0tReallyAToken").doesNotContain("kq3v1n8Zp0");
    }

    @Test
    void anEmptyRepoIsReportedWithWhatIsUsedInstead(CapturedOutput output) throws IOException {
        EffectiveConfig config = config(Map.of("SERVER_ADDRESS", "127.0.0.1", "GITHUB_REPO", " "));

        config.report();

        assertThat(config.problems()).containsExactly("GITHUB_REPO is empty; using apache/ignite");
        assertThat(output.getOut()).contains("WARN").contains("config: GITHUB_REPO is empty; using apache/ignite");
    }
}
