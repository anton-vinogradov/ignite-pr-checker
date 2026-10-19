package com.github.igniteprchecker;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** The JSON mapper as the service has it: Spring Boot's, with the settings of application.yml. */
public final class ServiceMapper {
    private static ObjectMapper mapper;

    private ServiceMapper() {
    }

    /** Built once a test run, in a context with nothing else in it. */
    public static synchronized ObjectMapper get() {
        if (mapper == null) {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(JsonOnly.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run()) {
                mapper = ctx.getBean(ObjectMapper.class);
            }
        }

        return mapper;
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class JsonOnly {
    }
}
