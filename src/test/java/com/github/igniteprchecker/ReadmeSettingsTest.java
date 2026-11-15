package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.PersistProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import com.github.igniteprchecker.config.UpdateProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.ThinClients;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The README listed 9 of the settings an instance reads, without SERVER_ADDRESS, the one that keeps port 8080 off the
 * network, and called GITHUB_TOKEN an optional read token. Its settings table now names every variable the service
 * reports at startup and every one install.sh and run.sh read, in both languages.
 */
class ReadmeSettingsTest {
    private static final Path INSTALL = Path.of("install.sh");

    private static final Pattern TEMPLATE_LINE = Pattern.compile("(?m)^#?([A-Z][A-Z0-9_]+)=");

    private static final Pattern SCRIPT_VARIABLE = Pattern.compile("\\$\\{(PRC_JAVA|JAVA_OPTS):-");

    private static final Pattern CODE = Pattern.compile("`([A-Z][A-Z0-9_]+)`");

    @Test
    void bothReadmesListEverySetting() throws IOException {
        Set<String> settings = settings();
        assertThat(settings).as("what the service and its scripts read")
            .contains("SERVER_ADDRESS", "APP_PUBLIC_URL", "GITHUB_TOKEN", "PRC_ADMINS", "JAVA_OPTS", "PRC_JAVA")
            .contains("PYTHON_CLIENT_REPO", "PHP_CLIENT_REPO", "NODEJS_CLIENT_REPO")
            .contains("GIT_PROXY_ENABLED")
            .hasSizeGreaterThanOrEqualTo(35);

        assertThat(table(Path.of("README.md"), "## Configuration")).containsAll(settings);
        assertThat(table(Path.of("README.ru.md"), "## Конфигурация")).containsAll(settings);
    }

    /** GITHUB_TOKEN is the account the checker writes as, and an install must not leave port 8080 on the network. */
    @Test
    void bothReadmesSayWhoseGithubTokenAndWhereToListen() throws IOException {
        String en = Files.readString(Path.of("README.md"), UTF_8);
        String ru = Files.readString(Path.of("README.ru.md"), UTF_8);

        assertThat(rowOf(en, "GITHUB_TOKEN")).contains("app account", "no rights in `apache/*`", "`public_repo`")
            .doesNotContainIgnoringCase("optional");
        assertThat(rowOf(ru, "GITHUB_TOKEN")).contains("аккаунта приложения", "без прав в `apache/*`", "`public_repo`");
        assertThat(en.replaceAll("\\s+", " ")).contains("Bind the service to loopback (`SERVER_ADDRESS=127.0.0.1`");
        assertThat(ru.replaceAll("\\s+", " ")).contains("Привяжи сервис к loopback (`SERVER_ADDRESS=127.0.0.1`");
    }

    private static String rowOf(String readme, String variable) {
        return readme.lines().filter(l -> l.startsWith("| `" + variable + "` |")).findFirst().orElseThrow();
    }

    /** The variables EffectiveConfig reports, the env template of install.sh holds, and run.sh reads. */
    private static Set<String> settings() throws IOException {
        Set<String> out = new TreeSet<>(effectiveConfig().settings().keySet());

        String install = Files.readString(INSTALL, UTF_8);
        String template = install.substring(install.indexOf("<<'ENV'"), install.indexOf("\nENV\n"));
        TEMPLATE_LINE.matcher(template).results().forEach(m -> out.add(m.group(1)));
        SCRIPT_VARIABLE.matcher(install).results().forEach(m -> out.add(m.group(1)));

        return out;
    }

    /** Run the real catalog: client variable names are assembled from the selected project keys. */
    private static EffectiveConfig effectiveConfig() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("application.yml",
            new FileSystemResource("src/main/resources/application.yml")).forEach(env.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(env);
        Binder binder = Binder.get(env);

        return new EffectiveConfig(env, binder.bindOrCreate("teamcity", TeamcityProperties.class),
            binder.bindOrCreate("github", GithubProperties.class),
            binder.bindOrCreate("analysis", AnalysisProperties.class),
            binder.bindOrCreate("persist", PersistProperties.class),
            binder.bindOrCreate("warm", WarmProperties.class),
            binder.bindOrCreate("update", UpdateProperties.class),
            binder.bindOrCreate("admin", AdminProperties.class),
            binder.bindOrCreate("session", SessionProperties.class),
            new ThinClients(binder.bindOrCreate("python-client", PythonClientProperties.class),
                binder.bindOrCreate("thin-clients", ThinClientProperties.class)));
    }

    /** The variables quoted in the first column of the README's settings table. */
    private static Set<String> table(Path readme, String heading) throws IOException {
        String text = Files.readString(readme, UTF_8);
        int start = text.indexOf("\n" + heading + "\n");
        assertThat(start).as(readme + ": " + heading).isNotNegative();
        String section = text.substring(start + 1, text.indexOf("\n## ", start + 1));

        Set<String> out = new TreeSet<>();
        section.lines().filter(l -> l.startsWith("| `")).forEach(row -> {
            Matcher code = CODE.matcher(row.substring(0, row.indexOf(" |", 2)));
            while (code.find())
                out.add(code.group(1));
        });

        return out;
    }
}
