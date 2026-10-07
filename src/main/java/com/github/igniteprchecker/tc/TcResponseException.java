package com.github.igniteprchecker.tc;

import org.springframework.web.client.RestClientResponseException;

/**
 * An error status from TeamCity. GitHub and JIRA answer with the same Spring exceptions, and the web
 * layer must tell them apart: a TeamCity 401 sends the user back to the login form, another service's
 * 401 must not. Code that only checks the status keeps catching {@link RestClientResponseException}.
 */
public class TcResponseException extends RestClientResponseException {
    public TcResponseException(RestClientResponseException e) {
        super(e.getMessage(), e.getStatusCode(), e.getStatusText(), e.getResponseHeaders(),
            e.getResponseBodyAsByteArray(), null);
        initCause(e);
    }
}
