package com.github.igniteprchecker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * A local bootRun as the README described it warmed the 50 newest PRs on the shared ci2 under the developer's token,
 * ran the standing-option sweep and the /run-all poll next to the production instance, and kept its state nowhere
 * (the cache directory is /opt on the server), so with a random session key every restart meant logging in again.
 */
class DevProfileTest {
    private static Binder profiles(String... files) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (String file : files) {
            new YamlPropertySourceLoader().load(file, new FileSystemResource("src/main/resources/" + file))
                .forEach(sources::addLast);
        }

        return Binder.get(env);
    }

    @Test
    void aLocalRunLeavesTeamCityAndThePrsAlone() throws IOException {
        Binder dev = profiles("application-dev.yml", "application.yml");

        assertThat(dev.bindOrCreate("warm", WarmProperties.class).enabled()).isFalse();
        assertThat(dev.bind("automation.enabled", Boolean.class).get()).isFalse();
        assertThat(dev.bindOrCreate("update", UpdateProperties.class).enabled()).isFalse();
        assertThat(dev.bind("server.address", String.class).get()).isEqualTo("127.0.0.1");
    }

    @Test
    void aLocalRunKeepsItsStateAndLoginsAcrossRestarts() throws IOException {
        Binder dev = profiles("application-dev.yml", "application.yml");

        assertThat(dev.bindOrCreate("persist", PersistProperties.class).dir()).isEqualTo("./build/prc-cache");
        assertThat(dev.bindOrCreate("session", SessionProperties.class).secret()).isNotBlank();
    }

    @Test
    void bootRunIsTheLocalRunAndProductionActs() throws IOException {
        Binder prod = profiles("application.yml");

        assertThat(Files.readString(Path.of("build.gradle"))).contains("systemProperty 'spring.profiles.default', 'dev'");
        assertThat(prod.bind("automation.enabled", Boolean.class).get()).isTrue();
        assertThat(prod.bindOrCreate("warm", WarmProperties.class).enabled()).isTrue();
    }
}
