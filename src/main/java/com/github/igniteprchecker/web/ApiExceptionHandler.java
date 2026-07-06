package com.github.igniteprchecker.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns transient TeamCity connectivity failures (DNS blips, connection resets) into a clean 502
 * with a human answer, instead of a raw 500 with a full stack trace in the log and the UI.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<?> teamCityUnreachable(ResourceAccessException e) {
        log.warn("TeamCity unreachable: {}", e.getMessage());

        return ResponseEntity.status(502)
            .body(Map.of("error", "TeamCity is unreachable (network or DNS hiccup) — try again in a moment"));
    }
}
