package com.github.igniteprchecker.config;

import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place outbound HTTP clients (TeamCity, JIRA, GitHub) get their request factories, each bounded by a
 * connect and a read timeout, and their JSON. Without the timeouts a peer that accepts the connection and never answers
 * holds the calling thread for good: an analysis, the sweep, or one of the three scheduler threads.
 */
public final class OutboundHttp {
    /** The slowest TeamCity call in an hour of prod traffic took 3.3 s. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How the JDK's HTTP client starts the error for a header it refuses, the header's value after it. */
    private static final String REFUSED_HEADER = "Illegal character(s) in message header";

    /**
     * The JSON of the calls, read and written by Jackson 2's rules and limits, as the service's own JSON is
     * (spring.jackson in application.yml). Jackson 3's own rules fail an answer that lacks a number field the record
     * has, or holds a null for it.
     */
    private static final JsonMapper JSON = Jackson2Json.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
        .build();

    private OutboundHttp() {
    }

    /**
     * Built on HttpURLConnection, which cannot send PATCH. The JDK HttpClient is no alternative for
     * TeamCity: it sends "Content-Length: 0" on GET, and the TeamCity WAF answers that with 403 "Access Blocked".
     */
    public static ClientHttpRequestFactory plain(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.simple().build(settings(readTimeout));
    }

    /** Built on Apache HttpClient, for GitHub, which edits comments and refs with PATCH. */
    public static ClientHttpRequestFactory withPatch(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.httpComponents().build(settings(readTimeout));
    }

    /**
     * The same, but a redirect comes back as the answer: GitHub sends a job's log from another host, and the caller,
     * not the client, decides what goes there with the request.
     */
    public static ClientHttpRequestFactory withPatchNoRedirects(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.httpComponents()
            .build(settings(readTimeout).withRedirects(HttpRedirects.DONT_FOLLOW));
    }

    /**
     * Makes a client read and write JSON by {@link #JSON}, with Spring's other converters as they are:
     * {@code .configureMessageConverters(OutboundHttp::json)}.
     */
    public static void json(HttpMessageConverters.ClientBuilder converters) {
        converters.registerDefaults().withJsonConverter(new JacksonJsonHttpMessageConverter(JSON));
    }

    private static HttpClientSettings settings(Duration readTimeout) {
        return HttpClientSettings.defaults()
            .withConnectTimeout(CONNECT_TIMEOUT)
            .withReadTimeout(readTimeout);
    }

    /**
     * A failed call, with the service named when the call got no answer: the page tells the user which service
     * that was. A header the JDK's client refused comes back without its value, which is {@code Bearer <token>} for
     * the token's header. Any other failure is returned as is.
     */
    public static RuntimeException naming(String service, RuntimeException e) {
        if (e instanceof IllegalArgumentException && String.valueOf(e.getMessage()).startsWith(REFUSED_HEADER)) {
            return new IllegalArgumentException(service + " was not called: a request header has a character HTTP "
                + "does not allow");
        }
        if (e instanceof NoAnswer || !(e instanceof ResourceAccessException failure))
            return e;

        return new NoAnswer(service, failure);
    }

    /** A call that got no answer: the connection failed, or nothing came within the read timeout. */
    public static final class NoAnswer extends ResourceAccessException {
        private final String service;

        private NoAnswer(String service, ResourceAccessException failure) {
            super(failure.getMessage(), failure.getCause() instanceof IOException io ? io : null);
            this.service = service;
        }

        /** "TeamCity", "JIRA" or "GitHub". */
        public String service() {
            return service;
        }
    }
}
