package com.github.igniteprchecker.config;

import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;

/**
 * The one place outbound HTTP clients (TeamCity, JIRA, GitHub) get their request factories, each bounded by a
 * connect and a read timeout. Without them a peer that accepts the connection and never answers holds the
 * calling thread for good: an analysis, the sweep, or one of the three scheduler threads.
 */
public final class OutboundHttp {
    /** The slowest TeamCity call in an hour of prod traffic took 3.3 s. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

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
            .build(settings(readTimeout).withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW));
    }

    private static ClientHttpRequestFactorySettings settings(Duration readTimeout) {
        return ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(CONNECT_TIMEOUT)
            .withReadTimeout(readTimeout);
    }

    /**
     * A failed call, with the service named when the call got no answer: the page tells the user which service
     * that was. Any other failure is returned as is.
     */
    public static RuntimeException naming(String service, RuntimeException e) {
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
