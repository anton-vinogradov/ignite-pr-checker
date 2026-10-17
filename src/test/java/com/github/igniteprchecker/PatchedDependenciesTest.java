package com.github.igniteprchecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.core.json.PackageVersion;
import java.util.Properties;
import org.apache.catalina.util.ServerInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http2.H2Error;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Prod ran Spring Boot 3.5.0: Tomcat 10.1.41 with 26 published vulnerabilities and jackson-core 2.19.0 with a
 * ReDoS in number parsing, which the anonymous /api/login endpoint reaches. Boot 3.5.16 alone still brings a
 * vulnerable Jackson and Tomcat, so both are pinned higher, and a later Boot bump must not drop below them.
 */
class PatchedDependenciesTest {
    @Test
    void jacksonHasTheParserFixes() {
        assertThat(PackageVersion.VERSION.getMajorVersion()).isEqualTo(2);
        assertThat(PackageVersion.VERSION.getMinorVersion() * 1000 + PackageVersion.VERSION.getPatchLevel())
            .isGreaterThanOrEqualTo(21_007);
    }

    @Test
    void tomcatHasTheSecurityConstraintFixes() {
        String[] v = ServerInfo.getServerNumber().split("\\.");

        assertThat(Integer.parseInt(v[0]) * 10_000 + Integer.parseInt(v[1]) * 1_000 + Integer.parseInt(v[2]))
            .isGreaterThanOrEqualTo(101_058);
    }

    /**
     * Boot 3.5.16 brings httpcore5 5.3.6, httpclient5 5.5.2, commons-lang3 3.17.0 and log4j-api 2.24.3, each with a
     * published vulnerability; the GitHub client runs on the first two.
     */
    @Test
    void httpComponentsCommonsLangAndLog4jHaveTheirFixes() {
        assertThat(version(HttpHost.class)).isGreaterThanOrEqualTo(5_004_003);
        assertThat(version(H2Error.class)).isGreaterThanOrEqualTo(5_004_003);
        assertThat(version(HttpClients.class)).isGreaterThanOrEqualTo(5_006_004);
        assertThat(version(StringUtils.class)).isGreaterThanOrEqualTo(3_018_000);
        assertThat(version(LogManager.class)).isGreaterThanOrEqualTo(2_025_005);
    }

    /** The version of the jar the class comes from, 5.4.3 as 5_004_003. */
    private static int version(Class<?> type) {
        String[] v = type.getPackage().getImplementationVersion().split("\\.");

        return Integer.parseInt(v[0]) * 1_000_000 + Integer.parseInt(v[1]) * 1_000 + Integer.parseInt(v[2]);
    }

    /** Checkstyle 12 brings slf4j-simple: whichever backend SLF4J finds first wins, and the status log needs Logback. */
    @Test
    void logbackStaysTheOnlyLoggingBackend() {
        assertThat(LoggerFactory.getILoggerFactory()).isInstanceOf(LoggerContext.class);
        assertThatThrownBy(() -> Class.forName("org.slf4j.simple.SimpleServiceProvider"))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void multipartParsingIsOff() {
        YamlPropertiesFactoryBean yml = new YamlPropertiesFactoryBean();
        yml.setResources(new ClassPathResource("application.yml"));
        Properties props = yml.getObject();

        assertThat(props).containsEntry("spring.servlet.multipart.enabled", false);
    }
}
