package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.OutboundHttp;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns a call to TeamCity, JIRA or GitHub that got no answer (a DNS blip, a reset connection, a read timeout)
 * into a clean 502 naming that service, instead of a raw 500 with a full stack trace in the log and the UI.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<?> noAnswer(ResourceAccessException e) {
        String service = e instanceof OutboundHttp.NoAnswer named ? named.service() : "A service this page needs";
        log.warn("{} did not answer: {}", service, e.getMessage());

        return ResponseEntity.status(502).body(Map.of("error", service + " did not answer — try again in a moment"));
    }
}
