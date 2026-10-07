package com.github.igniteprchecker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * TeamCity connection settings. Auth is per-user (token supplied at login), so no token lives here.
 * {@code readTimeout} bounds how long a call may wait for TeamCity to send anything.
 */
@ConfigurationProperties(prefix = "teamcity")
public record TeamcityProperties(String baseUrl, Duration readTimeout) {
    @ConstructorBinding
    public TeamcityProperties {
        if (readTimeout == null)
            readTimeout = OutboundHttp.READ_TIMEOUT;
    }

    public TeamcityProperties(String baseUrl) {
        this(baseUrl, null);
    }
}
