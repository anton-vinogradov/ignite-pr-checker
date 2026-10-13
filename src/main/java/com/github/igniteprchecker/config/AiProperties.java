package com.github.igniteprchecker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Claude, through Anthropic's API, picks the test suite for a PR's test class that no suite runs. {@code apiKey} is
 * the operator's Anthropic API key; without it the option is not offered. {@code model} is the Claude model asked.
 * {@code readTimeout} bounds how long a call may wait for the answer: the model thinks before it answers, so it is
 * longer than the other services'.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(String apiKey, String model, Duration readTimeout) {
    public static final String MODEL = "claude-opus-5-5";

    static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    @ConstructorBinding
    public AiProperties {
        if (apiKey != null)
            apiKey = apiKey.strip();
        if (model == null || model.isBlank())
            model = MODEL;
        if (readTimeout == null)
            readTimeout = READ_TIMEOUT;
    }

    public AiProperties(String apiKey) {
        this(apiKey, null, null);
    }

    /** Whether the operator gave the checker an API key. */
    public boolean available() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /** How long to wait for Anthropic's API to take the connection, as for the other services. */
    public Duration connectTimeout() {
        return OutboundHttp.CONNECT_TIMEOUT;
    }
}
